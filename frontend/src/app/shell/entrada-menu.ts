import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Ayuda } from '../ui/ayuda';
import { Icono } from '../ui/icono';
import type { NombreIcono } from '../ui/trazos-iconos';

/**
 * Entrada del menú lateral: ícono, nombre y numeral del Anexo 5 (oculto para el lector). Con el sidebar colapsado solo el ícono, con el
 * nombre en el nombre accesible y en una ayuda al pasar o enfocar. La activa lleva `aria-current="page"`.
 */
@Component({
  selector: 'lume-entrada-menu',
  imports: [RouterLink, Icono, Ayuda],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: 'display: contents' },
  template: `
    <a
      class="lume-nav"
      [routerLink]="ruta()"
      [class.lume-nav--activa]="activa()"
      [attr.aria-current]="activa() ? 'page' : null"
      [attr.aria-label]="colapsado() ? titulo() : null"
      [lumeAyuda]="colapsado() ? titulo() : ''"
      posicion="derecha"
    >
      <lume-icono [nombre]="icono()" />
      @if (!colapsado()) {
        <span class="lume-nav__titulo">{{ titulo() }}</span>
        @if (numeral()) {
          <span class="lume-nav-numeral" aria-hidden="true">{{ numeral() }}</span>
        }
      }
    </a>
  `,
})
export class EntradaMenu {
  readonly ruta = input.required<string>();
  readonly titulo = input.required<string>();
  readonly icono = input.required<NombreIcono>();
  readonly numeral = input<string | undefined>(undefined);
  readonly activa = input(false);
  readonly colapsado = input(false);
}
