import { afterNextRender, ChangeDetectionStrategy, Component, computed, ElementRef, inject, Injector, signal, viewChild } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { IngresoApi, type Paso } from '../../api/seguridad';
import { tomarMotivo } from '../../nucleo/actividad';
import { ErrorApi } from '../../nucleo/error-api';
import { rutaInterna } from '../../nucleo/navegacion';
import { NAVEGADOR, RUTAS_BFF } from '../../nucleo/sesion';
import { erroresClaveNueva } from '../../nucleo/validaciones';
import { FORMULARIO_LUME } from '../../ui';
import { CodigoVerificacion } from '../../ui/codigo-verificacion';
import { InterruptorSonido } from '../../ui/interruptor-sonido';
import { tituloDocumento } from '../../ui/encabezado-pagina';
import { crearValidacion } from '../../ui/validacion';
import { Turnstile } from './turnstile';

/**
 * `continuar` nunca saca al usuario de LUME: solo una ruta del mismo origen (`rutaInterna`) y solo la autorización OIDC del emisor o una
 * ruta del gateway (ADR-014). Lo demás va a la autorización del gateway, que vuelve a empezar el ingreso.
 */
export function destinoSeguro(continuar: unknown): string {
  const ruta = rutaInterna(continuar);
  return ruta && (ruta.startsWith('/oauth2/authorize') || ruta.startsWith('/bff/')) ? ruta : RUTAS_BFF.autorizacion;
}

/**
 * Ingreso en dos pasos (Anexo 5, 3.1; ADR-004 y ADR-014), port de frontend/src/paginas/Ingreso.tsx con sus textos y data-testid: usuario,
 * contraseña y captcha → código de 6 dígitos que llega al correo (vence en 15 minutos). Si la contraseña es la inicial del administrador
 * o venció, se cambia aquí antes del código; si nunca se definió, se avisa que llegó un enlace al correo. Al terminar, la página va a
 * `continuar` (el emisor emite el código OIDC y el gateway abre la sesión). Muestra una vez el motivo del último cierre de sesión.
 */
@Component({
  selector: 'lume-ingreso',
  imports: [...FORMULARIO_LUME, CodigoVerificacion, InterruptorSonido, Turnstile, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ingreso.html',
})
export class Ingreso {
  private readonly api = inject(IngresoApi);
  private readonly navegador = inject(NAVEGADOR);
  private readonly injector = inject(Injector);
  private readonly tarjeta = viewChild<ElementRef<HTMLElement>>('tarjeta');

  protected readonly usuario = signal('');
  protected readonly clave = signal('');
  protected readonly codigo = signal('');
  protected readonly nueva = signal('');
  protected readonly confirmacion = signal('');
  protected readonly paso = signal<Paso | null>(null);
  protected readonly enviando = signal(false);
  /** Motivo del último cierre de sesión (inactividad, otro equipo...): se muestra una sola vez. */
  protected readonly aviso = signal<string | null>(tomarMotivo());
  private captcha: string | null = null;
  private readonly turnstile = viewChild(Turnstile);

  protected readonly v = crearValidacion({
    etiquetas: {
      usuario: 'Usuario',
      clave: 'Contraseña',
      nueva: 'Contraseña nueva',
      confirmacion: 'Confirme la contraseña nueva',
      codigo: 'Código de verificación',
    },
    // En el cambio de la contraseña inicial el cuerpo lleva «clave» para la contraseña nueva: se marca el campo «nueva»
    campos: (): Record<string, string> => (this.paso()?.paso === 'cambiar_clave' ? { clave: 'nueva' } : {}),
    testid: 'error-ingreso',
  });
  protected readonly errorCodigo = computed(() => this.v.error('codigo'));

  constructor() {
    inject(Title).setTitle(tituloDocumento('Ingreso'));
  }

  protected alResolverCaptcha(t: string | null): void {
    this.captcha = t;
  }

  /**
   * Cambia de paso y, ya pintado, lleva el foco a su primer control (el botón pulsado desaparece con el formulario anterior): la primera
   * casilla del código o el primer campo. Con un error del servidor el foco ya lo puso la validación en el resumen.
   */
  private irAlPaso(p: Paso | null): void {
    this.paso.set(p);
    afterNextRender(
      () => {
        if (this.v.hayErrores()) return;
        const destino = this.tarjeta()?.nativeElement.querySelector<HTMLElement>('form input:not([type="hidden"])');
        destino?.focus();
      },
      { injector: this.injector },
    );
  }

  private volverAlPaso1(): void {
    this.irAlPaso(null);
    this.captcha = null; // el captcha ya se usó: hay que resolverlo de nuevo
    this.turnstile()?.reiniciar();
  }

  protected async ingresar(): Promise<void> {
    const usuario = this.usuario();
    const clave = this.clave();
    // En el paso 1 el campo del servidor «clave» es la contraseña (no la nueva)
    if (!this.v.validar({ usuario: usuario.trim() ? null : 'Escriba el usuario.', clave: clave ? null : 'Escriba la contraseña.' })) return;
    this.enviando.set(true);
    try {
      const p = await firstValueFrom(this.api.ingresar({ usuario, clave, captcha: this.captcha }));
      this.v.reiniciar();
      this.irAlPaso(p);
      this.clave.set('');
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo ingresar.');
      this.turnstile()?.reiniciar();
    } finally {
      this.enviando.set(false);
    }
  }

  protected async verificar(): Promise<void> {
    const codigo = this.codigo();
    if (!this.v.validar({ codigo: /^\d{6}$/.test(codigo) ? null : 'Escriba los 6 dígitos del código.' })) return;
    this.enviando.set(true);
    try {
      const r = await firstValueFrom(this.api.verificarCodigo({ codigo }));
      // ADR-014: el emisor emite el código OIDC y el gateway abre la sesión; se queda «enviando» mientras la página cambia
      this.navegador.asignar(destinoSeguro(r.continuar));
      return;
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo verificar el código.');
      if (x instanceof ErrorApi && x.estado === 401) this.volverAlPaso1();
    }
    this.enviando.set(false);
  }

  /** Contraseña inicial asignada por el administrador (o vencida): se cambia aquí, sin salir al correo; después sigue el código. */
  protected async cambiarClave(): Promise<void> {
    const nueva = this.nueva();
    const confirmacion = this.confirmacion();
    const errores = erroresClaveNueva(nueva, confirmacion);
    if (!this.v.validar({ nueva: errores.clave, confirmacion: errores.confirmacion })) return;
    this.enviando.set(true);
    try {
      const p = await firstValueFrom(this.api.cambiarClaveEnIngreso({ clave: nueva, confirmacion }));
      this.v.reiniciar();
      this.irAlPaso(p);
      this.nueva.set('');
      this.confirmacion.set('');
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo cambiar la contraseña.');
      if (x instanceof ErrorApi && x.estado === 401) this.volverAlPaso1();
    } finally {
      this.enviando.set(false);
    }
  }

  protected async reenviar(): Promise<void> {
    if (this.enviando()) return; // un doble clic no manda dos correos
    this.v.reiniciar();
    this.enviando.set(true);
    try {
      const p = await firstValueFrom(this.api.reenviarCodigo());
      this.irAlPaso(p);
      this.codigo.set('');
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo reenviar el código.');
    } finally {
      this.enviando.set(false);
    }
  }

  protected escribir(campo: 'usuario' | 'clave' | 'nueva' | 'confirmacion', e: Event): void {
    this[campo].set((e.target as HTMLInputElement).value);
    this.v.limpiar(campo);
  }

  protected alCambiarCodigo(c: string): void {
    this.codigo.set(c);
    this.v.limpiar('codigo');
  }
}
