import { Dialog, DialogRef } from '@angular/cdk/dialog';
import { NgTemplateOutlet } from '@angular/common';
import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  contentChild,
  DestroyRef,
  Directive,
  effect,
  inject,
  input,
  output,
  TemplateRef,
  untracked,
  viewChild,
} from '@angular/core';

let siguiente = 0;

/** Adónde va el foco al abrir: lo marcado con `data-autofocus` o, si no hay, el primer control del cuerpo. */
const CONTROL_INICIAL = [
  '.lume-modal__cuerpo [data-autofocus]',
  '.lume-modal__cuerpo:not(:has([data-autofocus])) :is(input:not([type="hidden"]), select, textarea, button, a[href]):not(:disabled)',
].join(', ');

/** El cuerpo del modal: una plantilla que se crea al abrir y se destruye al cerrar. */
@Directive({ selector: 'ng-template[lumeCuerpoModal]' })
export class CuerpoModal {
  readonly plantilla = inject<TemplateRef<unknown>>(TemplateRef);
}

/**
 * Modal LUME sobre el `Dialog` del CDK (ADR-013): trampa de foco, foco al primer control, Escape, `role="dialog"` con `aria-modal` y
 * nombre accesible del título, y el foco vuelve a donde estaba al cerrar. Se usa de forma declarativa, como el `Modal` de Mantine del
 * monolito: la pantalla decide `abierto` y escucha `(cerrar)` (Escape, clic en el fondo, la «×» o el diálogo cerrado por el CDK). En
 * celular (< 48em) ocupa la pantalla completa (componentes.css), para que el teclado no tape los botones.
 *
 * El cuerpo va en `<ng-template lumeCuerpoModal>`: como en Mantine, se crea al abrir y se destruye al cerrar, así que un formulario
 * vuelve a empezar limpio (valores, errores, `crearValidacion`) cada vez que se abre.
 *
 * ```html
 * <lume-modal [abierto]="claveAbierta()" titulo="Cambiar mi contraseña" (cerrar)="claveAbierta.set(false)">
 *   <ng-template lumeCuerpoModal><lume-cambiar-mi-clave (listo)="claveAbierta.set(false)" /></ng-template>
 * </lume-modal>
 * ```
 */
@Component({
  selector: 'lume-modal',
  imports: [NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ng-template #contenido>
      <div class="lume-modal" [class.lume-modal--centrado]="centrado()" [attr.data-ancho]="ancho()">
        <div class="lume-modal__cabecera">
          <h2 class="lume-modal__titulo" [id]="idTitulo">{{ titulo() }}</h2>
          @if (conCerrar()) {
            <button type="button" class="lume-modal__cerrar" aria-label="Cerrar" (click)="cerrar.emit()">
              <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false"><path d="M18 6L6 18M6 6l12 12" /></svg>
            </button>
          }
        </div>
        <div class="lume-modal__cuerpo">
          @if (cuerpo(); as c) {
            <ng-container [ngTemplateOutlet]="c.plantilla" />
          }
        </div>
      </div>
    </ng-template>
  `,
})
export class Modal {
  private readonly dialogo = inject(Dialog);
  readonly abierto = input.required<boolean>();
  readonly titulo = input.required<string>();
  /** La «×» de la cabecera (`withCloseButton` del monolito). */
  readonly conCerrar = input(true, { transform: booleanAttribute });
  readonly centrado = input(false, { transform: booleanAttribute });
  readonly ancho = input<'sm' | 'md' | 'lg'>('md');
  /** Escape, clic en el fondo, la «×» o el diálogo cerrado por el CDK: la pantalla decide (y pone `abierto` en false). */
  readonly cerrar = output<void>();

  protected readonly idTitulo = `modal-titulo-${++siguiente}`;
  protected readonly cuerpo = contentChild(CuerpoModal);
  private readonly contenido = viewChild.required<TemplateRef<unknown>>('contenido');
  private ref: DialogRef | null = null;

  constructor() {
    effect(() => {
      const abierto = this.abierto();
      untracked(() => (abierto ? this.abrir() : this.quitar()));
    });
    inject(DestroyRef).onDestroy(() => this.quitar());
  }

  private abrir(): void {
    if (this.ref) return;
    const ref = this.dialogo.open(this.contenido(), {
      ariaLabelledBy: this.idTitulo,
      ariaModal: true,
      // El cierre lo decide la pantalla: Escape y el fondo solo avisan
      disableClose: true,
      // Al cambiar de ruta el componente se destruye y cierra el diálogo (DestroyRef); el CDK no lo cierra por su cuenta
      closeOnNavigation: false,
      panelClass: 'lume-modal-panel',
      backdropClass: 'lume-modal-fondo',
      // El primer control del cuerpo (no la «×» de la cabecera); sin controles, el diálogo mismo
      autoFocus: CONTROL_INICIAL,
      restoreFocus: true,
    });
    this.ref = ref;
    ref.keydownEvents.subscribe((e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        this.cerrar.emit();
      }
    });
    ref.backdropClick.subscribe(() => this.cerrar.emit());
    // Si el CDK lo cerró por su cuenta (closeAll, overlay destruido), la pantalla se entera y el modal se puede volver a abrir
    ref.closed.subscribe(() => {
      if (this.ref !== ref) return;
      this.ref = null;
      this.cerrar.emit();
    });
  }

  private quitar(): void {
    const ref = this.ref;
    this.ref = null; // el cierre que pide la pantalla no vuelve a avisar `cerrar`
    ref?.close();
  }
}
