import { booleanAttribute, Directive, effect, ElementRef, inject, input, output } from '@angular/core';
import { Sonido } from './sonido';

/** Variantes de botón de LUME (LUME_design_system.md): las mismas que el monolito dibuja con Mantine y `tema.ts`. */
export type VarianteBoton = 'primario' | 'secundario' | 'outline' | 'peligro' | 'peligro-relleno' | 'sutil';

/**
 * Botón LUME sobre un `<button>` o un `<a>` nativo (el marcado y el rol no cambian):
 * - `primario` (por omisión): relleno #2563EB, hover #1D4ED8, deshabilitado #93C5FD con texto blanco;
 * - `secundario`: blanco con borde, hover --lume-bg; `outline`: borde azul, hover --lume-info-bg con texto --lume-blue (AA);
 * - `peligro`: blanco con texto --lume-danger; `peligro-relleno`: rojo relleno (el «Eliminar» de un diálogo); `sutil`: sin borde.
 * `tamano="pequeno"` es el `size="xs"` del monolito.
 *
 * ```html
 * <button lumeBoton type="submit">Ingresar</button>
 * <button lumeBoton="secundario" tamano="pequeno" type="button">Cancelar</button>
 * ```
 */
@Directive({
  selector: 'button[lumeBoton], a[lumeBoton]',
  host: {
    class: 'lume-boton',
    '[class.lume-boton--primario]': "variante() === 'primario'",
    '[class.lume-boton--secundario]': "variante() === 'secundario'",
    '[class.lume-boton--outline]': "variante() === 'outline'",
    '[class.lume-boton--peligro]': "variante() === 'peligro'",
    '[class.lume-boton--peligro-relleno]': "variante() === 'peligro-relleno'",
    '[class.lume-boton--sutil]': "variante() === 'sutil'",
    '[class.lume-boton--pequeno]': "tamano() === 'pequeno'",
    '[class.lume-boton--bloque]': 'bloque()',
    '[attr.aria-busy]': "cargando() ? 'true' : null",
    '[attr.data-loading]': "cargando() ? '' : null",
  },
})
export class Boton {
  private readonly el = inject<ElementRef<HTMLButtonElement | HTMLAnchorElement>>(ElementRef).nativeElement;

  /** El atributo sin valor (`lumeBoton`) es el primario. */
  readonly variante = input<VarianteBoton, VarianteBoton | ''>('primario', {
    alias: 'lumeBoton',
    transform: (v) => v || 'primario',
  });
  readonly tamano = input<'normal' | 'pequeno'>('normal');
  /** Ocupa todo el ancho (`fullWidth` del monolito). */
  readonly bloque = input(false, { transform: booleanAttribute });
  /**
   * Petición en curso (`loading` del monolito): el botón no actúa (no hay doble envío, tampoco con Enter: el envío implícito del formulario
   * es un clic en su botón y se cancela), con `aria-busy`, `aria-disabled` y un indicador en lugar del texto, que sigue siendo su nombre
   * accesible. No usa `disabled` a propósito: un botón deshabilitado pierde el foco y quien usa el teclado o el lector queda en el body
   * (2.4.3). Al terminar vuelve el `aria-disabled` que tenía.
   */
  readonly cargando = input(false, { transform: booleanAttribute });

  constructor() {
    // En la fase de captura, antes que los (click) de la pantalla (que se registran en burbuja)
    this.el.addEventListener(
      'click',
      (e) => {
        if (!this.cargando()) return;
        e.preventDefault();
        e.stopImmediatePropagation();
      },
      { capture: true },
    );
    let previo: string | null | undefined;
    effect(() => {
      const cargando = this.cargando();
      if (cargando && previo === undefined) {
        previo = this.el.getAttribute('aria-disabled');
        this.el.setAttribute('aria-disabled', 'true');
      } else if (!cargando && previo !== undefined) {
        if (previo === null) this.el.removeAttribute('aria-disabled');
        else this.el.setAttribute('aria-disabled', previo);
        previo = undefined;
      }
    });
  }
}

/**
 * Botón que todavía no se puede usar y dice por qué (Anexo 5 num. 2.4; decisión UI-24 del monolito): en lugar de `disabled`, que no
 * deja pulsarlo ni avisa, queda con `aria-disabled` y el aspecto de deshabilitado; al pulsarlo suena el aviso y el foco va al texto que
 * explica qué falta (unido con `aria-describedby`). Playwright lo ve deshabilitado igual (`toBeDisabled` respeta `aria-disabled`).
 * La acción va en `(alPulsar)`, no en `(click)`: así no se ejecuta mientras el botón está bloqueado. Un `type="submit"` bloqueado
 * tampoco envía el formulario. Portado de frontend/src/shared/BotonConMotivo.tsx.
 *
 * ```html
 * <button lumeBoton [lumeConMotivo]="motivo()" idMotivo="ayuda-guardar" (alPulsar)="guardar()">Guardar</button>
 * <p id="ayuda-guardar">{{ motivo() }}</p>
 * ```
 */
@Directive({
  selector: 'button[lumeConMotivo]',
  host: {
    '[attr.aria-disabled]': 'bloqueado() || null',
    '[attr.data-disabled]': "bloqueado() ? '' : null",
    '[attr.aria-describedby]': 'describePor()',
    '(click)': 'alHacerClic($event)',
  },
})
export class BotonConMotivo {
  private readonly sonido = inject(Sonido);
  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  /** El `aria-describedby` que escribió la pantalla: se conserva (bloqueado, va junto al motivo), como en el monolito. */
  private readonly describePropio = this.el.getAttribute('aria-describedby');

  protected describePor(): string | null {
    return this.bloqueado() ? [this.describePropio, this.idMotivo()].filter(Boolean).join(' ') : this.describePropio;
  }

  /** Por qué no se puede todavía (null: el botón funciona). */
  readonly motivo = input.required<string | null>({ alias: 'lumeConMotivo' });
  /** id del texto junto al botón que explica el motivo: recibe el foco al intentar. */
  readonly idMotivo = input.required<string>();
  /** Al pulsarlo bloqueado: p. ej. marcar los campos y llevar el foco al primero con error; si devuelve true, el foco ya se movió. */
  readonly alBloquear = input<(() => boolean | void) | undefined>(undefined);
  // Nota: `alBloquear` recibe una función ya ligada (un campo `= () => …` del componente); un método pasado tal cual pierde `this`.
  /** El botón se pulsó y no estaba bloqueado. */
  readonly alPulsar = output<MouseEvent>();

  protected bloqueado(): boolean {
    return this.motivo() !== null;
  }

  protected alHacerClic(e: MouseEvent): void {
    if (!this.bloqueado()) {
      this.alPulsar.emit(e);
      return;
    }
    e.preventDefault(); // tampoco envía el formulario si es de tipo submit
    this.sonido.sonarError();
    if (this.alBloquear()?.() === true) return;
    const ayuda = this.el.ownerDocument.getElementById(this.idMotivo());
    if (ayuda) {
      if (!ayuda.hasAttribute('tabindex')) ayuda.setAttribute('tabindex', '-1');
      ayuda.setAttribute('data-motivo', ''); // foco visible también tras un clic (componentes.css)
      ayuda.focus();
    }
  }
}
