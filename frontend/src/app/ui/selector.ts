import { CdkConnectedOverlay, CdkOverlayOrigin, ConnectedPosition } from '@angular/cdk/overlay';
import {
  afterNextRender,
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  ElementRef,
  inject,
  Injector,
  input,
  model,
  signal,
  viewChild,
} from '@angular/core';
import { Campo, Entrada } from './campo';

/** Una opción del selector: el valor (lo que se guarda) y la etiqueta (lo que se ve y el nombre accesible de la opción). */
export interface OpcionSelector {
  valor: string;
  etiqueta: string;
}

let siguiente = 0;

/** Sin tildes y en minúsculas, para buscar «Bogota» y encontrar «Bogotá». */
function normal(t: string): string {
  return t
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase();
}

const POSICIONES: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
];

/**
 * Selector LUME (el `Select` de Mantine del monolito, con `searchable` y `clearable`) con el patrón **combobox** de ARIA 1.2: el campo
 * es `role="combobox"` con `aria-expanded`, `aria-controls` (la lista), `aria-activedescendant` (la opción resaltada; el foco nunca sale
 * del campo) y, si se puede buscar, `aria-autocomplete="list"`. La lista (`role="listbox"`, opciones `role="option"` con
 * `aria-selected`) va en un Overlay del CDK, para que no la recorte un modal. Va dentro de un `lume-campo` (etiqueta, error, foco).
 *
 * Teclado (APG): ↓/↑ abren y recorren (↑ abre en la última), Alt+↓ abre, Alt+↑ elige y cierra, Inicio/Fin y RePág/AvPág (si no se
 * busca), Enter elige, Escape cierra (y no cierra el modal de atrás), Tab cierra sin elegir; sin búsqueda, Espacio abre y elige, y lo
 * tecleado en 500 ms salta a la primera opción que empieza así. Al abrir y al filtrar se anuncia cuántos resultados hay en una región
 * viva («3 resultados», «Sin resultados»).
 *
 * ```html
 * <lume-campo etiqueta="Fuerza (Sanidad)" obligatorio [error]="v.error('alcanceFuerza')">
 *   <lume-selector [opciones]="fuerzas()" [(valor)]="alcanceFuerza" buscable limpiable />
 * </lume-campo>
 * ```
 */
@Component({
  selector: 'lume-selector',
  imports: [Entrada, CdkConnectedOverlay, CdkOverlayOrigin],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-selector' },
  template: `
    <div class="lume-selector__control" cdkOverlayOrigin #origen="cdkOverlayOrigin">
      <input
        lumeEntrada
        #campo
        type="text"
        role="combobox"
        autocomplete="off"
        aria-haspopup="listbox"
        [attr.aria-expanded]="abierto()"
        [attr.aria-controls]="idLista"
        [attr.aria-autocomplete]="buscable() ? 'list' : null"
        [attr.aria-activedescendant]="abierto() && activa() >= 0 ? idOpcion(activa()) : null"
        [attr.placeholder]="placeholder() ?? null"
        [readOnly]="!buscable()"
        [disabled]="deshabilitado()"
        [value]="textoVisible()"
        (input)="alEscribir($event)"
        (keydown)="alTeclear($event)"
        (click)="alPulsar()"
        (blur)="alSalir()"
      />
      @if (limpiable() && valor() !== null && !deshabilitado()) {
        <!-- Con el nombre del campo: con varios selectores no hay varios «Borrar la selección» iguales (2.4.6) -->
        <button type="button" class="lume-selector__borrar" [attr.aria-label]="nombreBorrar()" (click)="borrar()">
          <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" focusable="false"><path d="M18 6L6 18M6 6l12 12" /></svg>
        </button>
      } @else {
        <svg class="lume-selector__flecha" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false"><path d="M6 9l6 6l6 -6" /></svg>
      }
    </div>
    <!-- Cuántos resultados hay: el lector lo dice al abrir y al filtrar -->
    <div class="lume-solo-lector" role="status" aria-live="polite">{{ anuncio() }}</div>

    <ng-template
      cdkConnectedOverlay
      [cdkConnectedOverlayOrigin]="origen"
      [cdkConnectedOverlayOpen]="abierto()"
      [cdkConnectedOverlayPositions]="posiciones"
      [cdkConnectedOverlayMinWidth]="ancho()"
      (overlayOutsideClick)="alClicFuera($event)"
      (detach)="cerrar()"
    >
      <!-- mousedown sin efecto en todo el panel (opciones, relleno, barra de desplazamiento): el campo no pierde el foco ni se cierra -->
      <div class="lume-selector__panel" (mousedown)="$event.preventDefault()">
        <ul role="listbox" class="lume-selector__lista" [id]="idLista" [attr.aria-label]="nombre()">
          @for (o of filtradas(); track o.valor; let i = $index) {
            <!-- Patrón combobox con aria-activedescendant (ARIA APG): las opciones NO reciben foco; el teclado lo atiende el campo
                 (alTeclear). El clic es solo para el puntero. Ver docs/sesiones/DESVIACIONES_A11Y.md, «Lint de accesibilidad». -->
            <!-- eslint-disable-next-line @angular-eslint/template/click-events-have-key-events, @angular-eslint/template/interactive-supports-focus -->
            <li
              role="option"
              class="lume-selector__opcion"
              [id]="idOpcion(i)"
              [class.lume-selector__opcion--activa]="i === activa()"
              [attr.aria-selected]="o.valor === valor()"
              (mousemove)="activa() !== i && activa.set(i)"
              (click)="elegir(o)"
            >
              {{ o.etiqueta }}
            </li>
          }
        </ul>
        @if (filtradas().length === 0) {
          <p class="lume-selector__vacio">Sin resultados</p>
        }
      </div>
    </ng-template>
  `,
})
export class Selector {
  private readonly campoLume = inject(Campo, { optional: true });
  private readonly documento = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  readonly opciones = input.required<OpcionSelector[]>();
  /** El valor elegido o null. */
  readonly valor = model<string | null>(null);
  /** Se puede escribir para filtrar (`searchable`). */
  readonly buscable = input(false, { transform: booleanAttribute });
  /** Con «Borrar la selección» (`clearable`). */
  readonly limpiable = input(false, { transform: booleanAttribute });
  readonly deshabilitado = input(false, { transform: booleanAttribute });
  readonly placeholder = input<string | undefined>(undefined);
  /** Nombre de la lista cuando el selector no va dentro de un `lume-campo` (si va, se usa la etiqueta del campo). */
  readonly etiqueta = input<string | undefined>(undefined);

  protected readonly idLista = `selector-lista-${++siguiente}`;
  protected readonly posiciones = POSICIONES;
  protected readonly abierto = signal(false);
  /** Lo que se escribe para buscar; null mientras no se busca (se ve la opción elegida). */
  protected readonly consulta = signal<string | null>(null);
  /** Índice resaltado en la lista filtrada (-1: ninguno). */
  protected readonly activa = signal(-1);
  protected readonly ancho = signal(0);
  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  private readonly origen = viewChild.required(CdkOverlayOrigin);
  /** Búsqueda por letras sin búsqueda (APG): lo tecleado en los últimos 500 ms. */
  private prefijo = '';
  private prefijoHasta = 0;

  protected readonly nombre = computed(() => this.etiqueta() ?? this.campoLume?.etiqueta() ?? null);
  protected readonly nombreBorrar = computed(() => (this.nombre() ? `Borrar la selección de ${this.nombre()}` : 'Borrar la selección'));
  protected readonly elegida = computed(() => this.opciones().find((o) => o.valor === this.valor()) ?? null);
  protected readonly textoVisible = computed(() => this.consulta() ?? this.elegida()?.etiqueta ?? '');
  /** Las etiquetas sin tildes, una sola vez por lista (no en cada tecla). */
  private readonly normalizadas = computed(() => this.opciones().map((o) => ({ o, n: normal(o.etiqueta) })));
  protected readonly filtradas = computed(() => {
    const q = this.consulta();
    if (!this.buscable() || !q) return this.opciones();
    const n = normal(q.trim());
    return this.normalizadas()
      .filter((x) => x.n.includes(n))
      .map((x) => x.o);
  });
  protected readonly anuncio = computed(() => {
    if (!this.abierto()) return '';
    const n = this.filtradas().length;
    return n === 0 ? 'Sin resultados' : n === 1 ? '1 resultado' : `${n} resultados`;
  });

  protected idOpcion(i: number): string {
    return `${this.idLista}-${i}`;
  }

  abrir(donde: 'elegida' | 'primera' | 'ultima' = 'elegida'): void {
    if (this.deshabilitado() || this.abierto()) return;
    this.ancho.set((this.origen().elementRef.nativeElement as HTMLElement).getBoundingClientRect().width);
    this.abierto.set(true);
    const lista = this.filtradas();
    const elegida = lista.findIndex((o) => o.valor === this.valor());
    const i = donde === 'primera' ? 0 : donde === 'ultima' ? lista.length - 1 : elegida >= 0 ? elegida : 0;
    this.activa.set(lista.length ? i : -1);
    this.mostrarActiva();
  }

  cerrar(): void {
    this.abierto.set(false);
    this.consulta.set(null);
    this.activa.set(-1);
  }

  /** Clic en el campo: alterna; con búsqueda solo abre (el clic para mover el cursor no debe cerrar ni borrar lo escrito). */
  protected alPulsar(): void {
    if (!this.abierto()) this.abrir();
    else if (!this.buscable()) this.cerrar();
  }

  protected elegir(o: OpcionSelector): void {
    this.valor.set(o.valor);
    this.cerrar();
    this.campo().nativeElement.focus();
  }

  protected borrar(): void {
    this.valor.set(null);
    this.cerrar();
    this.campo().nativeElement.focus();
  }

  protected alEscribir(e: Event): void {
    this.consulta.set((e.target as HTMLInputElement).value);
    if (!this.abierto()) this.abrir();
    this.activa.set(this.filtradas().length ? 0 : -1);
  }

  protected alSalir(): void {
    // Al salir sin elegir, lo escrito se descarta y vuelve a verse la opción elegida
    if (this.abierto()) this.cerrar();
    else this.consulta.set(null);
  }

  protected alClicFuera(e: MouseEvent): void {
    const destino = e.target as Element | null;
    const campo = this.campo().nativeElement;
    // Ni el campo ni su etiqueta (el clic en la etiqueta lleva al campo: cerraría y volvería a abrir)
    if (campo.contains(destino) || destino?.closest?.(`label[for="${campo.id}"]`)) return;
    this.cerrar();
  }

  protected alTeclear(e: KeyboardEvent): void {
    const n = this.filtradas().length;
    const abierto = this.abierto();
    const ultima = n - 1;
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        if (!abierto) return this.abrir();
        if (n) this.mover(Math.min(ultima, this.activa() + 1));
        return;
      case 'ArrowUp': {
        e.preventDefault();
        if (!abierto) return this.abrir('ultima');
        if (e.altKey) {
          // Alt+↑ (APG): elige la resaltada y cierra
          const o = this.filtradas()[this.activa()];
          return o ? this.elegir(o) : this.cerrar();
        }
        if (n) this.mover(Math.max(0, this.activa() - 1));
        return;
      }
      case 'Home':
      case 'End':
        // Con búsqueda, Inicio y Fin mueven el cursor del texto
        if (this.buscable()) return;
        e.preventDefault();
        if (!abierto) return this.abrir(e.key === 'Home' ? 'primera' : 'ultima');
        if (n) this.mover(e.key === 'Home' ? 0 : ultima);
        return;
      case 'PageDown':
      case 'PageUp':
        if (!abierto || !n) return;
        e.preventDefault();
        this.mover(e.key === 'PageDown' ? Math.min(ultima, this.activa() + 10) : Math.max(0, this.activa() - 10));
        return;
      case 'Enter': {
        if (!abierto) return; // sin la lista abierta, Enter envía el formulario como en cualquier campo
        e.preventDefault();
        const o = this.filtradas()[this.activa()];
        if (o) this.elegir(o);
        return;
      }
      case ' ': {
        // Sin búsqueda (select-only, APG): Espacio abre y, abierta, elige la resaltada. Con búsqueda es un espacio en el texto
        if (this.buscable()) return;
        e.preventDefault();
        if (!abierto) return this.abrir();
        if (this.prefijo && Date.now() < this.prefijoHasta) return this.buscarPorLetras(' ');
        const o = this.filtradas()[this.activa()];
        if (o) this.elegir(o);
        return;
      }
      case 'Escape':
        if (abierto) {
          // Cierra la lista, no el modal de atrás
          e.preventDefault();
          e.stopPropagation();
          this.cerrar();
        } else if (this.buscable() && this.consulta() !== null) {
          e.stopPropagation();
          this.consulta.set(null);
        }
        return;
      case 'Tab':
        if (abierto) this.cerrar();
        return;
      default:
        if (!this.buscable() && e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) this.buscarPorLetras(e.key);
    }
  }

  /** Sin búsqueda: lo tecleado en los últimos 500 ms lleva a la primera opción que empieza así («ar» → «Armada»). */
  private buscarPorLetras(letra: string): void {
    const ahora = Date.now();
    this.prefijo = ahora < this.prefijoHasta ? this.prefijo + letra : letra;
    this.prefijoHasta = ahora + 500;
    const p = normal(this.prefijo);
    const lista = this.normalizadas().map((x) => x.n);
    let i = lista.findIndex((n) => n.startsWith(p));
    // Repetir la misma letra recorre las opciones que empiezan por ella
    if (this.prefijo.length > 1 && new Set(this.prefijo).size === 1) {
      const l = normal(letra);
      const conLetra = lista.map((n, k) => (n.startsWith(l) ? k : -1)).filter((k) => k >= 0);
      if (conLetra.length) i = conLetra.find((k) => k > this.activa()) ?? conLetra[0]!;
    }
    if (i < 0) return;
    if (!this.abierto()) this.abrir();
    this.mover(i);
  }

  private mover(i: number): void {
    this.activa.set(i);
    this.mostrarActiva();
  }

  /** La opción resaltada queda a la vista dentro de la lista con desplazamiento. */
  private mostrarActiva(): void {
    afterNextRender(() => this.documento.getElementById(this.idOpcion(this.activa()))?.scrollIntoView?.({ block: 'nearest' }), {
      injector: this.injector,
    });
  }
}
