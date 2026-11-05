import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Ayuda } from './ayuda';
import { Icono } from './icono';
import { Sonido } from './sonido';

/** Interruptor del aviso sonoro de validación (decisión UI-8): activado por omisión, el navegador recuerda la elección. */
@Component({
  selector: 'lume-interruptor-sonido',
  imports: [Icono, Ayuda],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: 'display: contents' },
  template: `
    <button
      type="button"
      class="lume-icono-boton"
      aria-label="Sonido de las alertas"
      data-testid="interruptor-sonido"
      [attr.aria-pressed]="sonido.activo()"
      [lumeAyuda]="sonido.activo() ? 'Sonido de las alertas activado' : 'Sonido de las alertas desactivado'"
      (click)="sonido.activar(!sonido.activo())"
    >
      <lume-icono [nombre]="sonido.activo() ? 'sonido' : 'sin-sonido'" [tamano]="20" />
    </button>
  `,
})
export class InterruptorSonido {
  protected readonly sonido = inject(Sonido);
}
