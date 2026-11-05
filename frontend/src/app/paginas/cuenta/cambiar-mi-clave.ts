import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CuentaApi } from '../../api/seguridad';
import { erroresClaveNueva } from '../../nucleo/validaciones';
import { Avisos } from '../../ui/avisos';
import { crearValidacion } from '../../ui/validacion';
import { FORMULARIO_LUME } from '../../ui';

/**
 * «Cambiar mi contraseña» desde la sesión, sin salir al correo (condición 12 de la prueba en sitio): `POST /api/cuenta/clave`. Va dentro
 * del modal «Cambiar mi contraseña» del shell, que lo crea al abrir. Port de frontend/src/shared/CambiarMiClave.tsx (textos y data-testid
 * iguales).
 */
@Component({
  selector: 'lume-cambiar-mi-clave',
  imports: [...FORMULARIO_LUME],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="lume-pila" [lumeValidacion]="v" novalidate (submit)="$event.preventDefault(); guardar()">
      <ul class="lume-lista lume-texto-pequeno">
        <li>Mínimo 12 caracteres, con mayúsculas y minúsculas.</li>
        <li>Al menos un número y un carácter especial.</li>
        <li>Sin ñ ni tildes.</li>
      </ul>
      <lume-resumen-errores [validacion]="v" />
      <lume-campo etiqueta="Contraseña actual" obligatorio clave [error]="v.error('actual')">
        <input lumeEntrada type="password" autocomplete="current-password" maxlength="200" [value]="actual()"
          (input)="actual.set($any($event.target).value); v.limpiar('actual')" />
      </lume-campo>
      <lume-campo etiqueta="Contraseña nueva" obligatorio clave [error]="v.error('clave')">
        <input lumeEntrada type="password" autocomplete="new-password" maxlength="64" [value]="clave()"
          (input)="clave.set($any($event.target).value); v.limpiar('clave')" />
      </lume-campo>
      <lume-campo etiqueta="Confirme la contraseña nueva" obligatorio clave [error]="v.error('confirmacion')">
        <input lumeEntrada type="password" autocomplete="new-password" maxlength="64" [value]="confirmacion()"
          (input)="confirmacion.set($any($event.target).value); v.limpiar('confirmacion')" />
      </lume-campo>
      <div class="lume-modal__acciones">
        <button lumeBoton="secundario" type="button" (click)="listo.emit()">Cancelar</button>
        <button lumeBoton type="submit" [cargando]="enviando()">Cambiar contraseña</button>
      </div>
    </form>
  `,
})
export class CambiarMiClave {
  private readonly api = inject(CuentaApi);
  private readonly avisos = inject(Avisos);
  /** Terminó (cambió la contraseña o canceló): el modal se cierra. */
  readonly listo = output<void>();

  protected readonly actual = signal('');
  protected readonly clave = signal('');
  protected readonly confirmacion = signal('');
  protected readonly enviando = signal(false);
  protected readonly v = crearValidacion({
    etiquetas: { actual: 'Contraseña actual', clave: 'Contraseña nueva', confirmacion: 'Confirme la contraseña nueva' },
    testid: 'error-mi-clave',
  });

  protected async guardar(): Promise<void> {
    const actual = this.actual();
    const clave = this.clave();
    const confirmacion = this.confirmacion();
    if (!this.v.validar({ actual: actual ? null : 'Escriba la contraseña actual.', ...erroresClaveNueva(clave, confirmacion) })) return;
    this.enviando.set(true);
    try {
      const r = await firstValueFrom(this.api.cambiarMiClave({ actual, clave, confirmacion }));
      // Primero se cierra el modal (el resto de la página deja de estar oculto para el lector) y luego va el aviso
      this.listo.emit();
      setTimeout(() => this.avisos.mostrar(r.mensaje));
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo cambiar la contraseña.');
    } finally {
      this.enviando.set(false);
    }
  }
}
