import { ChangeDetectionStrategy, Component, computed, ElementRef, inject, input, linkedSignal, model, viewChild } from '@angular/core';
import { aTextoFecha, leerFecha } from '../nucleo/fechas';
import { Campo, Entrada } from './campo';

/** «23102026» (o lo escrito con barras) → «23/10/2026»: solo dígitos, las barras se ponen solas. */
export function conBarras(texto: string): string {
  const d = texto.replace(/\D/g, '').slice(0, 8);
  return d.length > 4 ? `${d.slice(0, 2)}/${d.slice(2, 4)}/${d.slice(4)}` : d.length > 2 ? `${d.slice(0, 2)}/${d.slice(2)}` : d;
}

/** Posición del cursor en el texto con barras que corresponde a la que tenía entre `digitosAntes` dígitos (al editar en medio). */
function posicionTras(texto: string, digitosAntes: number): number {
  if (digitosAntes === 0) return 0;
  let vistos = 0;
  for (let i = 0; i < texto.length; i++) {
    if (/\d/.test(texto[i]!) && ++vistos === digitosAntes) return i + 1;
  }
  return texto.length;
}

/**
 * Fecha DD/MM/AAAA (el `DatePickerInput` del monolito, `valueFormat="DD/MM/YYYY"`): va dentro de un `lume-campo`, que debe llevar la
 * descripción del formato (`descripcion="Formato DD/MM/AAAA (día, mes y año)."`: el placeholder solo no basta, 3.3.2). Se puede escribir
 * (teclado numérico; las barras se ponen solas y el cursor se queda donde se edita) o elegir en el calendario del navegador (botón
 * «Abrir el calendario de …», donde el navegador lo ofrece). El valor es «aaaa-mm-dd» (lo que viaja a la API) o null: mientras lo escrito
 * no sea una fecha válida, el valor es null (nunca queda una fecha vieja). Lo que se ve sale de nucleo/formato.ts. La pantalla valida al
 * enviar con `errorFecha(fecha.texto(), { min, max })` (nucleo/fechas.ts) y la reinicia con `reiniciar()` (p. ej. «Limpiar filtros»).
 *
 * ```html
 * <lume-campo etiqueta="Desde" descripcion="Formato DD/MM/AAAA (día, mes y año)." [error]="v.error('desde')">
 *   <lume-fecha #desde [(valor)]="fechaDesde" />
 * </lume-campo>
 * ```
 */
@Component({
  selector: 'lume-fecha',
  imports: [Entrada],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-fecha' },
  template: `
    <input
      lumeEntrada
      #campo
      type="text"
      inputmode="numeric"
      autocomplete="off"
      maxlength="10"
      placeholder="DD/MM/AAAA"
      [value]="texto()"
      [disabled]="deshabilitada()"
      (input)="alEscribir($event)"
    />
    @if (conCalendario && !deshabilitada()) {
      <button type="button" class="lume-fecha__calendario" [attr.aria-label]="nombreCalendario()" (click)="abrirCalendario()">
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false">
          <path d="M4 7a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12" />
          <path d="M16 3v4" /><path d="M8 3v4" /><path d="M4 11h16" />
        </svg>
      </button>
      <!-- Solo para el calendario del navegador: fuera del orden de tabulación y del árbol de accesibilidad -->
      <input
        #nativo
        type="date"
        class="lume-fecha__nativo"
        tabindex="-1"
        aria-hidden="true"
        [value]="valor() ?? ''"
        [attr.min]="min() ?? null"
        [attr.max]="max() ?? null"
        (change)="alElegir($any($event.target).value)"
      />
    }
  `,
})
export class Fecha {
  private readonly campoLume = inject(Campo, { optional: true });

  /** «aaaa-mm-dd» o null. */
  readonly valor = model<string | null>(null);
  /** Límites del calendario («aaaa-mm-dd»); lo escrito se valida contra ellos con `errorFecha(…, { min, max })`. */
  readonly min = input<string | undefined>(undefined);
  readonly max = input<string | undefined>(undefined);
  readonly deshabilitada = input(false);

  /** Lo escrito (para validarlo al enviar): sigue al valor cuando la pantalla lo cambia. */
  readonly texto = linkedSignal(() => aTextoFecha(this.valor()));
  protected readonly conCalendario = typeof HTMLInputElement !== 'undefined' && 'showPicker' in HTMLInputElement.prototype;
  protected readonly nombreCalendario = computed(() => {
    const e = this.campoLume?.etiqueta();
    return e ? `Abrir el calendario de ${e}` : 'Abrir el calendario';
  });
  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  private readonly nativo = viewChild<ElementRef<HTMLInputElement>>('nativo');

  /** Deja la fecha en `iso` (o vacía) aunque haya algo escrito a medias: el texto también se reinicia. */
  reiniciar(iso: string | null = null): void {
    this.valor.set(iso);
    this.texto.set(aTextoFecha(iso));
  }

  protected alEscribir(e: Event): void {
    const el = e.target as HTMLInputElement;
    const cursor = el.selectionStart ?? el.value.length;
    const digitosAntes = el.value.slice(0, cursor).replace(/\D/g, '').length;
    const t = conBarras(el.value);
    if (t !== el.value) {
      el.value = t;
      const p = posicionTras(t, digitosAntes);
      el.setSelectionRange(p, p);
    }
    // Mientras no sea una fecha válida, el valor es null (no queda la fecha anterior si se envía sin salir del campo)
    this.valor.set(leerFecha(t));
    // Después del valor: cambiarlo recalcula el texto, y lo escrito (aunque esté a medias) es lo que se ve y se valida
    this.texto.set(t);
  }

  protected abrirCalendario(): void {
    try {
      this.nativo()?.nativeElement.showPicker();
    } catch {
      this.campo().nativeElement.focus();
    }
  }

  protected alElegir(iso: string): void {
    this.reiniciar(iso || null);
    this.campo().nativeElement.focus();
  }
}
