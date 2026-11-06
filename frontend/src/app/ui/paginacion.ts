import { ChangeDetectionStrategy, Component, computed, input, model, signal, Signal } from '@angular/core';
import { conteo } from '../nucleo/formato';

/** Filas por página de las tablas que se paginan en el navegador (paginacion.tsx del monolito). */
export const POR_PAGINA = 25;

/** Página (desde 1) en la que queda la fila `i` (desde 0). */
export function paginaDe(i: number, porPagina = POR_PAGINA): number {
  return Math.floor(i / porPagina) + 1;
}

/**
 * Paginación en el navegador de una lista ya filtrada (`usePaginacion` del monolito). Si la lista se achica (otro filtro, una fila menos),
 * la página se ajusta a la última que existe. `irA(i)` lleva a la página de la fila `i` (p. ej. la que se acaba de crear o editar).
 */
export function crearPaginacion<T>(lista: Signal<T[]>, porPagina = POR_PAGINA) {
  const elegida = signal(1);
  const paginas = computed(() => Math.max(1, Math.ceil(lista().length / porPagina)));
  const pagina = computed(() => Math.max(1, Math.min(elegida(), paginas())));
  const visibles = computed(() => lista().slice((pagina() - 1) * porPagina, pagina() * porPagina));
  return {
    pagina,
    paginas,
    visibles,
    total: computed(() => lista().length),
    irAPagina: (p: number) => elegida.set(Math.max(1, p)),
    // Una fila que no está (findIndex = -1) no mueve la página
    irA: (i: number) => i >= 0 && elegida.set(paginaDe(i, porPagina)),
  };
}

/** Las páginas que se muestran: la primera, la última, la actual y una a cada lado; «…» donde se saltan (Mantine: siblings 1, boundaries 1). */
export function paginasVisibles(pagina: number, paginas: number): (number | '…')[] {
  const s = new Set([1, paginas, pagina - 1, pagina, pagina + 1].filter((p) => p >= 1 && p <= paginas));
  const orden = [...s].sort((a, b) => a - b);
  const salida: (number | '…')[] = [];
  orden.forEach((p, i) => {
    if (i > 0 && p - orden[i - 1]! > 1) salida.push(p - orden[i - 1]! === 2 ? p - 1 : '…');
    salida.push(p);
  });
  return salida;
}

/**
 * Paginación con el total de filas debajo de una tabla (`Paginacion` del monolito): «Mostrando X–Y de Z usuarios» a la izquierda
 * (`data-testid="total-<nombre>"`) y los botones a la derecha, que solo salen con más de una página. Los controles se llaman «Página
 * anterior», «Página siguiente» y «Página N»; la actual lleva `aria-current="page"`. En los extremos los botones quedan con
 * `aria-disabled` (no `disabled`: el foco no se pierde al llegar a la última página) y el total es `role="status"`.
 *
 * ```html
 * <lume-paginacion nombre="usuarios" [total]="p.total()" [pagina]="p.pagina()" (paginaChange)="p.irAPagina($event)" />
 * ```
 */
@Component({
  selector: 'lume-paginacion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-paginacion' },
  template: `
    <!-- role=status: al cambiar de página o de filtro el lector dice el nuevo rango -->
    <p class="lume-paginacion__total" role="status" [attr.data-testid]="'total-' + nombre()">{{ texto() }}</p>
    @if (paginas() > 1) {
      <nav class="lume-paginacion__botones" [attr.aria-label]="'Páginas de ' + nombre()">
        <button type="button" class="lume-paginacion__boton" aria-label="Página anterior" [attr.aria-disabled]="pagina() <= 1 || null" (click)="ir(pagina() - 1)">
          <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" focusable="false"><path d="M15 6l-6 6l6 6" /></svg>
        </button>
        @for (p of numeros(); track p === '…' ? 'salto-' + $index : p) {
          @if (p === '…') {
            <span class="lume-paginacion__salto" aria-hidden="true">…</span>
          } @else {
            <button
              type="button"
              class="lume-paginacion__boton"
              [class.lume-paginacion__boton--actual]="p === pagina()"
              [attr.aria-current]="p === pagina() ? 'page' : null"
              [attr.aria-label]="'Página ' + p"
              (click)="ir(p)"
            >
              {{ p }}
            </button>
          }
        }
        <button type="button" class="lume-paginacion__boton" aria-label="Página siguiente" [attr.aria-disabled]="pagina() >= paginas() || null" (click)="ir(pagina() + 1)">
          <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" focusable="false"><path d="M9 6l6 6l-6 6" /></svg>
        </button>
      </nav>
    }
  `,
})
export class Paginacion {
  readonly total = input.required<number>();
  readonly pagina = model(1);
  /** «usuarios», «registros»…: para el texto del total, su data-testid y el nombre de la navegación. */
  readonly nombre = input.required<string>();
  readonly porPagina = input(POR_PAGINA);

  protected readonly paginas = computed(() => Math.max(1, Math.ceil(this.total() / this.porPagina())));
  protected readonly numeros = computed(() => paginasVisibles(this.pagina(), this.paginas()));
  protected readonly texto = computed(() => {
    const total = this.total();
    if (total === 0) return `0 ${this.nombre()}`;
    const desde = (this.pagina() - 1) * this.porPagina() + 1;
    const hasta = Math.min(total, this.pagina() * this.porPagina());
    return `Mostrando ${conteo(desde)}–${conteo(hasta)} de ${conteo(total)} ${this.nombre()}`;
  });

  protected ir(p: number): void {
    if (p >= 1 && p <= this.paginas()) this.pagina.set(p);
  }
}
