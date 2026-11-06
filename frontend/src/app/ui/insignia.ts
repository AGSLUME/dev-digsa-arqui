import { booleanAttribute, ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { variante } from './tema';

/**
 * Insignia de estado LUME: píldora con punto de 6 px del color de la variante (success, warning, info, neutral, danger). El color se
 * nombra como en el monolito (`green`, `orange`, `yellow`, `blue`, `gray`, `red`, `lume`) para portar las pantallas sin traducir; el
 * texto nunca se recorta. Equivale al `Badge` con el tema LUME de frontend/src/shared/tema.ts.
 *
 * ```html
 * <lume-insignia color="green">Activo</lume-insignia>
 * ```
 */
@Component({
  selector: 'lume-insignia',
  template: '<ng-content />',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': 'clases()',
  },
})
export class Insignia {
  readonly color = input<string | undefined>(undefined);
  /**
   * `suave` (por omisión; el `variant="light"` del monolito: fondo suave con punto), `relleno` (el `Badge` por omisión de Mantine: fondo
   * del color de la variante con texto blanco) o `borde` (`variant="outline"`: borde y texto del color, fondo blanco). Siempre con los
   * colores de la variante LUME (un color fuera de la paleta, como el `grape` del monolito, es «info»).
   */
  readonly estilo = input<'suave' | 'relleno' | 'borde'>('suave');
  /** Ocupa su línea (`display: flex`), como `Badge` con `display="block"`. */
  readonly bloque = input(false, { transform: booleanAttribute });
  protected readonly clases = computed(() => {
    const e = this.estilo();
    return `lume-insignia lume-insignia--${variante(this.color())}${e === 'suave' ? '' : ' lume-insignia--' + e}${this.bloque() ? ' lume-insignia--bloque' : ''}`;
  });
}

/**
 * Alerta LUME (el `Alert` del monolito con su tema): fondo, borde y texto de la variante, título en negrita opcional. Lleva
 * `role="alert"` como el de Mantine, para que el lector de pantalla la anuncie y las pruebas la encuentren con `getByRole('alert')`.
 *
 * ```html
 * <lume-alerta color="yellow" data-testid="motivo-cierre">{{ motivo }}</lume-alerta>
 * ```
 */
@Component({
  selector: 'lume-alerta',
  template: `
    @if (titulo()) {
      <div class="lume-alerta__titulo">{{ titulo() }}</div>
    }
    <div class="lume-alerta__mensaje"><ng-content /></div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.role]': "rol() === 'none' ? null : rol()",
    '[class]': "'lume-alerta lume-alerta--' + varianteActual()",
  },
})
export class Alerta {
  readonly color = input<string | undefined>(undefined);
  readonly titulo = input<string | undefined>(undefined);
  /** `alert` por omisión (como el de Mantine); `none` cuando otra región ya anuncia (los avisos). */
  readonly rol = input<'alert' | 'status' | 'none'>('alert');
  protected readonly varianteActual = computed(() => variante(this.color()));
}
