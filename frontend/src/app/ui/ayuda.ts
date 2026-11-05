import { Overlay, OverlayRef } from '@angular/cdk/overlay';
import { ComponentPortal } from '@angular/cdk/portal';
import { ChangeDetectionStrategy, Component, DestroyRef, Directive, DOCUMENT, effect, ElementRef, inject, input, signal } from '@angular/core';

@Component({
  selector: 'lume-ayuda-burbuja',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-ayuda', 'aria-hidden': 'true' },
  template: '{{ texto() }}',
})
export class BurbujaAyuda {
  readonly texto = signal('');
}

/**
 * Ayuda al pasar o al enfocar (el `Tooltip` de Mantine del monolito), sobre el Overlay del CDK para que no la recorte el sidebar con
 * desplazamiento. Es solo visual (`aria-hidden`): el nombre accesible lo lleva el elemento (`aria-label`). WCAG 1.4.13: se cierra con
 * Escape en cualquier momento (también abierta por el puntero, sin foco), al salir o al perder el foco; con el dedo no se abre (en
 * pantallas táctiles no hay «pasar»). El texto sigue al estado (el interruptor de sonido). Vacía, no se muestra.
 *
 * ```html
 * <a [lumeAyuda]="colapsado() ? 'Usuarios' : ''" posicion="derecha" aria-label="Usuarios">…</a>
 * ```
 */
@Directive({
  selector: '[lumeAyuda]',
  host: {
    '(pointerenter)': 'alEntrar($event)',
    '(pointerleave)': 'ocultar()',
    '(focusin)': 'mostrar()',
    '(focusout)': 'ocultar()',
    '(click)': 'ocultar()',
  },
})
export class Ayuda {
  private readonly overlay = inject(Overlay);
  private readonly documento = inject(DOCUMENT);
  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly texto = input('', { alias: 'lumeAyuda' });
  readonly posicion = input<'derecha' | 'abajo'>('abajo');
  private ref: OverlayRef | null = null;
  private burbuja: BurbujaAyuda | null = null;
  private readonly alTeclear = (e: KeyboardEvent) => {
    if (e.key === 'Escape') this.ocultar();
  };

  constructor() {
    effect(() => {
      const t = this.texto();
      if (!t) this.ocultar();
      else this.burbuja?.texto.set(t);
    });
    inject(DestroyRef).onDestroy(() => this.ocultar());
  }

  protected alEntrar(e: PointerEvent): void {
    if (e.pointerType !== 'touch') this.mostrar();
  }

  protected mostrar(): void {
    const texto = this.texto();
    if (!texto || this.ref) return;
    const derecha = this.posicion() === 'derecha';
    const posicion = this.overlay
      .position()
      .flexibleConnectedTo(this.el)
      .withPositions(
        derecha
          ? [{ originX: 'end', originY: 'center', overlayX: 'start', overlayY: 'center', offsetX: 8 }]
          : [
              { originX: 'center', originY: 'bottom', overlayX: 'center', overlayY: 'top', offsetY: 6 },
              { originX: 'center', originY: 'top', overlayX: 'center', overlayY: 'bottom', offsetY: -6 },
            ],
      );
    this.ref = this.overlay.create({ positionStrategy: posicion, scrollStrategy: this.overlay.scrollStrategies.close() });
    this.burbuja = this.ref.attach(new ComponentPortal(BurbujaAyuda)).instance;
    this.burbuja.texto.set(texto);
    this.documento.addEventListener('keydown', this.alTeclear, true);
  }

  protected ocultar(): void {
    if (!this.ref) return;
    this.documento.removeEventListener('keydown', this.alTeclear, true);
    this.ref.dispose();
    this.ref = null;
    this.burbuja = null;
  }
}
