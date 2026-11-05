import { ChangeDetectionStrategy, Component, computed, ElementRef, input, linkedSignal, model, viewChildren } from '@angular/core';

/**
 * Código de verificación en 6 casillas (el `PinInput` de Mantine del monolito): solo dígitos, avanza solo al escribir, retrocede con
 * Retroceso, acepta pegar el código completo y propone el código del correo (`autocomplete="one-time-code"`). El grupo se llama «Código
 * de verificación» y cada casilla «Dígito N de 6 del código»; con error, las casillas quedan marcadas y unidas al mensaje (`idError`).
 *
 * ```html
 * <lume-codigo-verificacion data-testid="codigo" [(valor)]="codigo" [error]="v.error('codigo')" idError="codigo-error" />
 * ```
 */
@Component({
  selector: 'lume-codigo-verificacion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-codigo', role: 'group', 'aria-label': 'Código de verificación', '[attr.aria-describedby]': 'describe() ?? null' },
  template: `
    @for (i of posiciones; track i) {
      <input
        #casilla
        class="lume-entrada lume-codigo__casilla"
        type="text"
        inputmode="numeric"
        pattern="[0-9]*"
        [attr.maxlength]="i === 0 ? 6 : 1"
        [attr.autocomplete]="i === 0 ? 'one-time-code' : 'off'"
        [attr.aria-label]="'Dígito ' + (i + 1) + ' de 6 del código'"
        [attr.aria-invalid]="error() ? 'true' : null"
        [attr.aria-describedby]="mensaje() ? idError() : null"
        [value]="digitos()[i]"
        (input)="alEscribir(i, $event)"
        (keydown)="alTeclear(i, $event)"
        (paste)="alPegar($event)"
        (focus)="$any($event.target).select()"
      />
    }
  `,
})
export class CodigoVerificacion {
  readonly valor = model('');
  readonly error = input<string | true | null | undefined>(undefined);
  /** id del mensaje de error que va debajo (el de la pantalla). */
  readonly idError = input('codigo-error');
  /** id del texto que explica el código (a qué correo llegó y cuándo vence): describe el grupo. */
  readonly describe = input<string | undefined>(undefined);

  protected readonly posiciones = [0, 1, 2, 3, 4, 5];
  /**
   * Lo escrito en cada casilla (puede haber huecos mientras se escribe); `valor` es su unión. Si la pantalla cambia el valor (lo vacía
   * tras reenviar), las casillas lo siguen; si es el mismo que ya muestran, conservan sus huecos.
   */
  protected readonly digitos = linkedSignal<string, string[]>({
    source: this.valor,
    computation: (v, previo) => (previo && previo.value.join('') === v ? previo.value : [0, 1, 2, 3, 4, 5].map((i) => v[i] ?? '')),
  });
  protected readonly mensaje = computed(() => typeof this.error() === 'string');
  private readonly casillas = viewChildren<ElementRef<HTMLInputElement>>('casilla');

  /** Lleva el foco a la primera casilla vacía (o a la primera). */
  enfocar(): void {
    const i = Math.max(0, this.digitos().findIndex((d) => !d));
    this.casillas()[i]?.nativeElement.focus();
  }

  private poner(i: number, d: string): void {
    const nuevos = [...this.digitos()];
    nuevos[i] = d;
    this.digitos.set(nuevos);
    this.valor.set(nuevos.join(''));
  }

  protected alEscribir(i: number, e: Event): void {
    const campo = e.target as HTMLInputElement;
    const solo = campo.value.replace(/\D/g, '');
    if (solo.length > 1) {
      this.repartir(solo, i);
      return;
    }
    campo.value = solo;
    this.poner(i, solo);
    if (solo && i < 5) this.casillas()[i + 1]?.nativeElement.focus();
  }

  protected alTeclear(i: number, e: KeyboardEvent): void {
    const campo = e.target as HTMLInputElement;
    if (e.key === 'Backspace' && !campo.value && i > 0) {
      e.preventDefault();
      this.poner(i - 1, '');
      this.casillas()[i - 1]?.nativeElement.focus();
    } else if (e.key === 'ArrowLeft' && i > 0) {
      this.casillas()[i - 1]?.nativeElement.focus();
    } else if (e.key === 'ArrowRight' && i < 5) {
      this.casillas()[i + 1]?.nativeElement.focus();
    }
  }

  protected alPegar(e: ClipboardEvent): void {
    const texto = (e.clipboardData?.getData('text') ?? '').replace(/\D/g, '');
    if (!texto) return;
    e.preventDefault();
    this.repartir(texto, 0);
  }

  private repartir(texto: string, desde: number): void {
    const nuevos = [...this.digitos()];
    let i = desde;
    for (const d of texto) {
      if (i > 5) break;
      nuevos[i++] = d;
    }
    this.digitos.set(nuevos);
    this.valor.set(nuevos.join(''));
    this.casillas()[Math.min(i, 5)]?.nativeElement.focus();
  }
}
