import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TRAZOS, type NombreIcono } from './trazos-iconos';

/**
 * Ícono SVG en línea (ADR-013), decorativo: siempre `aria-hidden`. El nombre accesible lo lleva el botón o el enlace que lo contiene
 * (`aria-label` en los botones de solo ícono).
 *
 * ```html
 * <lume-icono nombre="usuarios" [tamano]="16" />
 * ```
 */
@Component({
  selector: 'lume-icono',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-icono' },
  template: `
    <svg viewBox="0 0 24 24" [attr.width]="tamano()" [attr.height]="tamano()" aria-hidden="true" focusable="false">
      @for (d of trazos(); track $index) {
        <path [attr.d]="d" />
      }
    </svg>
  `,
})
export class Icono {
  readonly nombre = input.required<NombreIcono>();
  readonly tamano = input(16);
  protected readonly trazos = computed(() => TRAZOS[this.nombre()]);
}
