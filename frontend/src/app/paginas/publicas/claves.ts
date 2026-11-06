import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal, viewChild } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { IngresoApi } from '../../api/seguridad';
import { FORMULARIO_LUME } from '../../ui';
import { tituloDocumento } from '../../ui/encabezado-pagina';
import { crearValidacion } from '../../ui/validacion';
import { Turnstile } from './turnstile';

const ENLACE_INVALIDO = 'El enlace no es válido o ya venció. Pida uno nuevo con «¿Olvidó su contraseña?».';

/**
 * «¿Olvidó su contraseña?» (port de OlvideClave en frontend/src/paginas/Claves.tsx): usuario o correo + captcha → `POST /api/auth/olvide`.
 * La respuesta es siempre la misma (exista o no el usuario); el enlace llega al correo y vence en 60 minutos.
 */
@Component({
  selector: 'lume-olvide-clave',
  imports: [...FORMULARIO_LUME, Turnstile, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="lume-publica">
      <div class="lume-card lume-publica__tarjeta lume-pila">
        <h1 class="lume-h3">¿Olvidó su contraseña?</h1>
        @if (listo(); as mensaje) {
          <lume-alerta color="green">{{ mensaje }}</lume-alerta>
        } @else {
          <form class="lume-pila" [lumeValidacion]="v" novalidate (submit)="$event.preventDefault(); enviar()">
            <lume-resumen-errores [validacion]="v" />
            <lume-campo etiqueta="Usuario o correo" obligatorio [error]="v.error('valor')">
              <input lumeEntrada autocomplete="username" maxlength="150" [value]="valor()" (input)="escribir($event)" />
            </lume-campo>
            <lume-turnstile (resuelto)="captcha = $event" />
            <button lumeBoton type="submit" [cargando]="enviando()">Enviar enlace</button>
          </form>
        }
        <a routerLink="/ingreso" class="lume-texto-pequeno">Volver al ingreso</a>
      </div>
    </main>
  `,
})
export class OlvideClave {
  private readonly api = inject(IngresoApi);
  protected readonly valor = signal('');
  protected readonly enviando = signal(false);
  protected readonly listo = signal<string | null>(null);
  protected captcha: string | null = null;
  private readonly turnstile = viewChild(Turnstile);
  protected readonly v = crearValidacion({ etiquetas: { valor: 'Usuario o correo' }, testid: 'error-olvide-clave' });

  constructor() {
    inject(Title).setTitle(tituloDocumento('Recuperar contraseña'));
  }

  protected escribir(e: Event): void {
    this.valor.set((e.target as HTMLInputElement).value);
    this.v.limpiar('valor');
  }

  protected async enviar(): Promise<void> {
    const valor = this.valor();
    if (!this.v.validar({ valor: valor.trim() ? null : 'Escriba su usuario o su correo.' })) return;
    this.enviando.set(true);
    try {
      const r = await firstValueFrom(this.api.olvide({ valor, captcha: this.captcha }));
      this.listo.set(r.mensaje);
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo enviar la solicitud.');
      this.turnstile()?.reiniciar();
    } finally {
      this.enviando.set(false);
    }
  }
}

/**
 * «Definir contraseña» con el enlace del correo (port de DefinirClave en Claves.tsx): valida el enlace (`POST /api/auth/clave/consultar`
 * con `{tkn}` en el cuerpo, contrato 0.4.0; el monolito usaba `GET /api/auth/clave?tkn=`) y
 * define la contraseña (`POST /api/auth/clave`). Sirve para la recuperación y para el primer ingreso.
 */
@Component({
  selector: 'lume-definir-clave',
  imports: [...FORMULARIO_LUME, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="lume-publica">
      <div class="lume-card lume-publica__tarjeta lume-pila">
        @if (hecho(); as mensaje) {
          <h1 class="lume-h3">Contraseña definida</h1>
          <lume-alerta color="green" data-testid="clave-ok">{{ mensaje }}</lume-alerta>
        } @else {
          <h1 class="lume-h3">Definir contraseña</h1>
          @if (valido() === false) {
            <lume-alerta color="red">{{ ENLACE_INVALIDO }}</lume-alerta>
          } @else if (valido() === true) {
            <p>Hola {{ nombre() }}. Defina su contraseña:</p>
            <ul class="lume-lista lume-texto-pequeno">
              <li>Mínimo 12 caracteres, con mayúsculas y minúsculas.</li>
              <li>Al menos un número y un carácter especial.</li>
              <li>Sin ñ ni tildes.</li>
            </ul>
            <form class="lume-pila" [lumeValidacion]="v" novalidate (submit)="$event.preventDefault(); guardar()">
              <lume-resumen-errores [validacion]="v" />
              <lume-campo etiqueta="Contraseña nueva" obligatorio clave [error]="v.error('clave')">
                <input lumeEntrada type="password" autocomplete="new-password" [value]="clave()" (input)="escribir('clave', $event)" />
              </lume-campo>
              <lume-campo etiqueta="Confirme la contraseña" obligatorio clave [error]="v.error('confirmacion')">
                <input lumeEntrada type="password" autocomplete="new-password" [value]="confirmacion()" (input)="escribir('confirmacion', $event)" />
              </lume-campo>
              <button lumeBoton type="submit" [cargando]="enviando()">Guardar contraseña</button>
            </form>
          }
        }
        <a routerLink="/ingreso" class="lume-texto-pequeno">Volver al ingreso</a>
      </div>
    </main>
  `,
})
export class DefinirClave implements OnInit {
  private readonly api = inject(IngresoApi);
  private readonly router = inject(Router);
  private readonly ruta = inject(ActivatedRoute);
  /** `?tkn=` del enlace del correo: se lee una vez y se borra de la dirección (no queda en el historial ni en el Referer). */
  readonly tkn = input<string | undefined>(undefined);
  /** El token, solo en memoria. */
  private token = '';
  protected readonly ENLACE_INVALIDO = ENLACE_INVALIDO;
  protected readonly valido = signal<boolean | null>(null);
  protected readonly nombre = signal('');
  protected readonly clave = signal('');
  protected readonly confirmacion = signal('');
  protected readonly enviando = signal(false);
  protected readonly hecho = signal<string | null>(null);
  protected readonly v = crearValidacion({ etiquetas: { clave: 'Contraseña nueva', confirmacion: 'Confirme la contraseña' }, testid: 'error-definir-clave' });

  constructor() {
    inject(Title).setTitle(tituloDocumento('Definir contraseña'));
  }

  async ngOnInit(): Promise<void> {
    this.token = this.tkn() ?? '';
    if (this.tkn() !== undefined) {
      void this.router.navigate([], { relativeTo: this.ruta, queryParams: { tkn: null }, queryParamsHandling: 'merge', replaceUrl: true });
    }
    try {
      const r = await firstValueFrom(this.api.consultarEnlace({ tkn: this.token }));
      this.nombre.set(r.nombre ?? '');
      this.valido.set(r.valido);
    } catch {
      this.valido.set(false);
    }
  }

  protected escribir(campo: 'clave' | 'confirmacion', e: Event): void {
    this[campo].set((e.target as HTMLInputElement).value);
    this.v.limpiar(campo);
  }

  protected async guardar(): Promise<void> {
    this.enviando.set(true);
    try {
      const r = await firstValueFrom(
        this.api.definirClave({ tkn: this.token, clave: this.clave(), confirmacion: this.confirmacion() }),
      );
      this.hecho.set(r.mensaje);
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo guardar la contraseña.');
    } finally {
      this.enviando.set(false);
    }
  }
}
