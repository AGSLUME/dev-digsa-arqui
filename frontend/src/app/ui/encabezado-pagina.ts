import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input } from '@angular/core';
import { Title } from '@angular/platform-browser';

/** Título de la pestaña del navegador (decisión UI-9 del monolito): «Suite DIGSA · <pantalla>». */
export function tituloDocumento(pantalla?: string): string {
  return pantalla ? `Suite DIGSA · ${pantalla}` : 'Suite DIGSA';
}

/**
 * Encabezado de cada página de la aplicación (las públicas, como el ingreso, ponen su propio h1): título (el único h1 de la pantalla),
 * descripción en gris y, a la derecha, la fuente normativa («Anexo 5 · 4.5») y las acciones (`<ng-container acciones>`). Lo demás que
 * se proyecta va junto al título (insignias de estado). Pone el título de la pestaña. Portado de frontend/src/shared/EncabezadoPagina.tsx.
 *
 * ```html
 * <lume-encabezado-pagina titulo="Usuarios" fuente="Anexo 5 · 3" descripcion="…">
 *   <button lumeBoton acciones type="button">Nuevo usuario</button>
 * </lume-encabezado-pagina>
 * ```
 */
@Component({
  selector: 'lume-encabezado-pagina',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-encabezado' },
  template: `
    <div class="lume-encabezado__texto">
      <div class="lume-encabezado__titulo">
        @if (orden() === 1) {
          <h1>{{ titulo() }}</h1>
        } @else {
          <h2 class="lume-h1">{{ titulo() }}</h2>
        }
        <ng-content />
      </div>
      @if (descripcion()) {
        <p class="lume-encabezado__descripcion">{{ descripcion() }}</p>
      }
    </div>
    <div class="lume-encabezado__acciones">
      @if (fuente()) {
        <span class="lume-fuente"><span class="lume-solo-lector">Fuente normativa: </span>{{ fuente() }}</span>
      }
      <ng-content select="[acciones]" />
    </div>
  `,
})
export class EncabezadoPagina {
  readonly titulo = input.required<string>();
  /** Nombre para la pestaña del navegador cuando difiere del título; por omisión, el título. */
  readonly documento = input<string | undefined>(undefined);
  readonly descripcion = input<string | undefined>(undefined);
  readonly fuente = input<string | undefined>(undefined);
  /** 2 cuando la página ya tiene su propio h1. */
  readonly orden = input<1 | 2>(1);

  constructor() {
    const titulo = inject(Title);
    effect(() => titulo.setTitle(tituloDocumento(this.documento() ?? this.titulo())));
    // Al salir de la pantalla la pestaña no conserva el nombre de la anterior
    inject(DestroyRef).onDestroy(() => titulo.setTitle(tituloDocumento()));
  }
}
