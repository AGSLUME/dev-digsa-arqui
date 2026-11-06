import { booleanAttribute, ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

let siguiente = 0;

/** Una opción de un grupo de casillas: valor, etiqueta (el nombre accesible, exacto) y descripción opcional (aparte). */
export interface OpcionCasilla {
  valor: string;
  etiqueta: string;
  descripcion?: string;
}

/**
 * Casilla de verificación LUME (el `Checkbox` del monolito): `<input type="checkbox">` nativo con su `<label>`; la etiqueta es el nombre
 * accesible exacto (las e2e lo buscan con `getByLabel('Auditor', { exact: true })`) y la descripción va aparte, unida con
 * `aria-describedby`. Borde 3:1 sin marcar, foco visible.
 *
 * ```html
 * <lume-casilla etiqueta="Activo" [(marcada)]="activo" />
 * ```
 */
@Component({
  selector: 'lume-casilla',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-casilla' },
  template: `
    <input
      type="checkbox"
      class="lume-casilla__control"
      [id]="id"
      [checked]="marcada()"
      [disabled]="deshabilitada()"
      [attr.aria-describedby]="describePor()"
      [attr.aria-invalid]="invalida() ? 'true' : null"
      (change)="marcada.set($any($event.target).checked)"
    />
    <span class="lume-casilla__texto">
      <label class="lume-casilla__etiqueta" [attr.for]="id">{{ etiqueta() }}</label>
      @if (descripcion()) {
        <span class="lume-casilla__descripcion" [id]="id + '-descripcion'">{{ descripcion() }}</span>
      }
    </span>
  `,
})
export class Casilla {
  readonly etiqueta = input.required<string>();
  readonly descripcion = input<string | undefined>(undefined);
  readonly marcada = model(false);
  readonly deshabilitada = input(false, { transform: booleanAttribute });
  readonly invalida = input(false, { transform: booleanAttribute });
  /** id del mensaje de error del grupo: se lee también al llegar con el foco a la casilla. */
  readonly idError = input<string | null>(null);
  protected readonly id = `casilla-${++siguiente}`;
  protected readonly describePor = computed(() => [this.descripcion() ? this.id + '-descripcion' : null, this.idError()].filter(Boolean).join(' ') || null);
}

/**
 * Grupo de casillas (el `Checkbox.Group` del monolito): `<fieldset>` con `<legend>` (el nombre del grupo), asterisco de obligatorio, las
 * casillas y el mensaje de error con id «…-error» debajo (la validación lleva el foco a la primera casilla del grupo con ese mensaje).
 * `error = true` (el error del servidor ya está en el resumen) se muestra con `conTexto` de nucleo/validaciones: un texto que remite al
 * aviso, para que el error no dependa solo del color.
 *
 * ```html
 * <lume-grupo-casillas leyenda="Perfiles" obligatorio [opciones]="perfiles()" [(valores)]="form.perfiles" [error]="v.error('perfiles')" />
 * ```
 */
@Component({
  selector: 'lume-grupo-casillas',
  imports: [Casilla],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-grupo-casillas' },
  template: `
    <fieldset class="lume-grupo-casillas__fieldset" [attr.aria-describedby]="mensaje() ? id + '-error' : null">
      <legend class="lume-campo__etiqueta">
        {{ leyenda() }}
        @if (obligatorio()) {
          <span class="lume-campo__obligatorio" aria-hidden="true"> *</span>
          <!-- Un fieldset no admite aria-required: el lector lo oye en la leyenda -->
          <span class="lume-solo-lector"> (obligatorio)</span>
        }
      </legend>
      <div class="lume-grupo-casillas__lista">
        @for (o of opciones(); track o.valor) {
          <lume-casilla
            [etiqueta]="o.etiqueta"
            [descripcion]="o.descripcion"
            [marcada]="valores().includes(o.valor)"
            [invalida]="!!error()"
            [idError]="mensaje() ? id + '-error' : null"
            [deshabilitada]="deshabilitado()"
            (marcadaChange)="alternar(o.valor, $event)"
          />
        }
      </div>
      @if (mensaje(); as m) {
        <p class="lume-campo__error" [id]="id + '-error'">{{ m }}</p>
      }
    </fieldset>
  `,
})
export class GrupoCasillas {
  readonly leyenda = input.required<string>();
  readonly opciones = input.required<OpcionCasilla[]>();
  readonly valores = model<string[]>([]);
  readonly error = input<string | true | null | undefined>(undefined);
  readonly obligatorio = input(false, { transform: booleanAttribute });
  readonly deshabilitado = input(false, { transform: booleanAttribute });
  protected readonly id = `grupo-casillas-${++siguiente}`;

  protected readonly mensaje = computed(() => {
    const e = this.error();
    return e === true ? 'Revise este campo: el motivo está en el aviso de arriba.' : e || null;
  });

  protected alternar(valor: string, marcada: boolean): void {
    // En el orden de las opciones, como Mantine
    const elegidos = new Set(this.valores());
    if (marcada) elegidos.add(valor);
    else elegidos.delete(valor);
    this.valores.set(this.opciones().map((o) => o.valor).filter((v) => elegidos.has(v)));
  }
}

/**
 * Interruptor (el `Switch` del monolito): una casilla nativa con `role="switch"` (el lector dice «activado/desactivado») y su etiqueta, que
 * también lo cambia al pulsarla. La pista se dibuja por CSS; el control sigue siendo el nativo (teclado con Espacio).
 *
 * ```html
 * <lume-interruptor etiqueta="Asignar contraseña inicial (se cambia al primer ingreso)" [(activo)]="conClaveInicial" />
 * ```
 */
@Component({
  selector: 'lume-interruptor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-interruptor' },
  template: `
    <input
      type="checkbox"
      role="switch"
      class="lume-interruptor__control"
      [id]="id"
      [checked]="activo()"
      [disabled]="deshabilitado()"
      [attr.aria-describedby]="descripcion() ? id + '-descripcion' : null"
      (change)="activo.set($any($event.target).checked)"
    />
    <span class="lume-interruptor__pista" aria-hidden="true"></span>
    <span class="lume-casilla__texto">
      <label class="lume-casilla__etiqueta" [attr.for]="id">{{ etiqueta() }}</label>
      @if (descripcion()) {
        <span class="lume-casilla__descripcion" [id]="id + '-descripcion'">{{ descripcion() }}</span>
      }
    </span>
  `,
})
export class Interruptor {
  readonly etiqueta = input.required<string>();
  readonly descripcion = input<string | undefined>(undefined);
  readonly activo = model(false);
  readonly deshabilitado = input(false, { transform: booleanAttribute });
  protected readonly id = `interruptor-${++siguiente}`;
}
