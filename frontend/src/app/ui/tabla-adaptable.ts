import { afterNextRender, DestroyRef, Directive, ElementRef, inject, input, signal } from '@angular/core';

/**
 * Pone a cada celda del cuerpo el nombre de su columna (`data-etiqueta`, del encabezado) y deja explícitos los roles de tabla, fila y
 * celda: en celular la tabla se apila como tarjetas por CSS (`display: block`) y algunos navegadores pierden la semántica de tabla con
 * ese cambio. Los títulos salen de la última fila del encabezado. Portado de frontend/src/shared/TablaAdaptable.tsx del monolito.
 */
export function etiquetarCeldas(raiz: HTMLElement): void {
  for (const tabla of raiz.querySelectorAll('table')) {
    if (!tabla.hasAttribute('role')) tabla.setAttribute('role', 'table');
    const titulos = Array.from(tabla.querySelectorAll<HTMLTableCellElement>(':scope > thead > tr:last-child > th')).flatMap((th) =>
      Array<string>(th.colSpan || 1).fill((th.textContent ?? '').trim()),
    );
    for (const tr of tabla.querySelectorAll(':scope > thead > tr, :scope > tbody > tr, :scope > tfoot > tr')) {
      if (!tr.hasAttribute('role')) tr.setAttribute('role', 'row');
    }
    for (const th of tabla.querySelectorAll(':scope > thead > tr > th')) {
      if (!th.hasAttribute('role')) th.setAttribute('role', 'columnheader');
    }
    for (const tr of tabla.querySelectorAll(':scope > tbody > tr, :scope > tfoot > tr')) {
      let columna = 0;
      for (const celda of Array.from(tr.children) as HTMLTableCellElement[]) {
        if (!celda.hasAttribute('role')) celda.setAttribute('role', celda.tagName === 'TH' ? 'rowheader' : 'cell');
        const span = celda.colSpan || 1;
        // Una celda que ocupa varias columnas lleva sus títulos unidos («Antes / Después»); la que ocupa todas (el «Sin datos» de la
        // tabla vacía), ninguno
        const etiqueta =
          span > 1 && span >= titulos.length ? '' : Array.from(new Set(titulos.slice(columna, columna + span).filter(Boolean))).join(' / ');
        if (celda.getAttribute('data-etiqueta') !== etiqueta) celda.setAttribute('data-etiqueta', etiqueta);
        columna += span;
      }
    }
  }
}

/**
 * Tabla que en escritorio se desplaza a lo ancho dentro de su caja y en celular y tableta vertical (< 48em) se muestra como una tarjeta
 * por fila, con el nombre de cada columna sobre su valor (Anexo 5 num. 1.18; decisión UI-10). Va en el contenedor de una `<table>`
 * normal sin cambiar su marcado: filas, celdas y `data-testid` siguen iguales para las pruebas. Se vuelve a etiquetar cuando cambian
 * las filas (paginación, filtros, datos nuevos).
 *
 * Cuando la tabla es más ancha que su caja (escritorio), la caja se puede enfocar y desplazar con el teclado (2.1.1): `tabindex="0"`,
 * `role="region"` y el nombre de `lumeTablaAdaptable` («Usuarios»).
 *
 * ```html
 * <div lumeTablaAdaptable="Usuarios"><table class="lume-tabla">…</table></div>
 * ```
 */
@Directive({
  selector: '[lumeTablaAdaptable]',
  host: {
    class: 'tabla-adaptable',
    '[attr.tabindex]': "desplazable() ? '0' : null",
    '[attr.role]': "desplazable() ? 'region' : null",
    '[attr.aria-label]': 'desplazable() ? nombre() || null : null',
  },
})
export class TablaAdaptable {
  /** Nombre de la región cuando se desplaza (el título de la tabla). */
  readonly nombre = input('', { alias: 'lumeTablaAdaptable' });
  protected readonly desplazable = signal(false);

  constructor() {
    const el = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    let observador: MutationObserver | undefined;
    let medidor: ResizeObserver | undefined;
    const medir = () => this.desplazable.set(el.scrollWidth > el.clientWidth);
    afterNextRender(() => {
      etiquetarCeldas(el);
      // El observador ya entrega las mutaciones por lotes (una llamada por microtarea): una sola pasada por lote
      observador = new MutationObserver(() => {
        etiquetarCeldas(el);
        medir();
      });
      observador.observe(el, { childList: true, subtree: true, characterData: true });
      if (typeof ResizeObserver !== 'undefined') {
        medidor = new ResizeObserver(medir);
        medidor.observe(el);
      }
      medir();
    });
    inject(DestroyRef).onDestroy(() => {
      observador?.disconnect();
      medidor?.disconnect();
    });
  }
}
