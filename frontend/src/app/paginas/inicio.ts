import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { agrupar } from '../nucleo/menu';
import { Sesion } from '../nucleo/sesion';
import { EncabezadoPagina } from '../ui/encabezado-pagina';
import { Icono } from '../ui/icono';

/**
 * Inicio como el «Panel de interventoría» del mockup (port de frontend/src/paginas/Inicio.tsx): una tarjeta por grupo del menú con las
 * pantallas que el perfil puede abrir (las mismas del menú, que filtra el servidor: Anexo 5 num. 3.7). No pide datos nuevos. Son botones
 * y no enlaces: el menú lateral conserva los únicos enlaces con el nombre de cada pantalla. El resumen de presupuesto del monolito no
 * está: presupuesto sigue en el monolito (ADR-008).
 */
@Component({
  selector: 'lume-inicio',
  imports: [EncabezadoPagina, Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="lume-pila">
      <lume-encabezado-pagina titulo="Panel de interventoría" [descripcion]="descripcion()" />
      @if (grupos().length > 0) {
        <div class="lume-rejilla-modulos" data-testid="modulos-inicio">
          @for (g of grupos(); track g.grupo) {
            <section class="lume-card lume-card--modulo">
              <div class="lume-card__seccion">
                <h2 class="lume-titulo-card">{{ g.grupo }}</h2>
              </div>
              <div class="lume-modulo__entradas">
                @for (e of g.entradas; track e.ruta) {
                  <button type="button" class="lume-boton lume-boton--sutil lume-modulo__entrada" (click)="abrir(e.ruta)">
                    <lume-icono [nombre]="e.icono" [tamano]="18" />
                    <span class="lume-modulo__titulo">{{ e.titulo }}</span>
                    @if (e.numeral) {
                      <span class="lume-modulo__numeral" aria-hidden="true">{{ e.numeral }}</span>
                    }
                  </button>
                }
              </div>
            </section>
          }
        </div>
      }
    </div>
  `,
})
export class Inicio {
  private readonly router = inject(Router);
  private readonly sesion = inject(Sesion);
  protected readonly grupos = computed(() => agrupar(this.sesion.datos()?.menu ?? []));
  protected readonly descripcion = computed(() => {
    const d = this.sesion.datos();
    return d ? `Bienvenido, ${d.nombre}. Perfiles: ${d.perfiles.join(', ')}.` : '';
  });

  protected abrir(ruta: string): void {
    void this.router.navigateByUrl('/' + ruta);
  }
}
