import {
  afterNextRender,
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  Directive,
  ElementRef,
  inject,
  input,
  signal,
} from '@angular/core';

let siguiente = 0;

/**
 * Campo de formulario LUME (el `TextInput`/`PasswordInput` del monolito con su tema): etiqueta en MAYÚSCULAS apagada AA, asterisco
 * de obligatorio, descripción, el control nativo que se proyecta (`<input lumeEntrada>`, `<select lumeEntrada>`, `<textarea
 * lumeEntrada>`) y el mensaje de error con id `<id>-error`. Con error, el control queda con borde y fondo de error, `aria-invalid` y
 * `aria-describedby`, y conserva el foco visible (componentes.css). `error` es el valor de `Validacion.error(campo)`: un mensaje, o
 * `true` si solo se marca (el error del servidor ya está en el resumen).
 *
 * ```html
 * <lume-campo etiqueta="Usuario" [error]="v.error('usuario')">
 *   <input lumeEntrada name="usuario" autocomplete="username" maxlength="50" [(ngModel)]="usuario" />
 * </lume-campo>
 * <lume-campo etiqueta="Contraseña" clave [error]="v.error('clave')">
 *   <input lumeEntrada type="password" name="clave" autocomplete="current-password" />
 * </lume-campo>
 * ```
 */
@Component({
  selector: 'lume-campo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'lume-campo' },
  template: `
    <label class="lume-campo__etiqueta" [attr.for]="id">
      {{ etiqueta() }}
      @if (obligatorio()) {
        <span class="lume-campo__obligatorio" aria-hidden="true"> *</span>
      }
    </label>
    @if (descripcion()) {
      <p class="lume-campo__descripcion" [id]="id + '-descripcion'">{{ descripcion() }}</p>
    }
    <div class="lume-campo__control" [class.lume-campo__control--clave]="clave()">
      <ng-content />
      @if (clave()) {
        <!-- Nombre accesible del botón interno (U4 del monolito): sin la palabra «Contraseña», que las e2e buscan con getByLabel -->
        <button
          type="button"
          class="lume-campo__ver"
          aria-label="Mostrar u ocultar lo escrito"
          [attr.aria-pressed]="visible()"
          [attr.aria-controls]="id"
          (click)="alternarVisible()"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false">
            @if (visible()) {
              <path d="M10.585 10.587a2 2 0 0 0 2.829 2.828M16.681 16.673a8.717 8.717 0 0 1-4.681 1.327c-3.6 0-6.6-2-9-6c1.272-2.12 2.712-3.678 4.32-4.674m2.86-1.146a9.055 9.055 0 0 1 1.82-.18c3.6 0 6.6 2 9 6c-.666 1.11-1.379 2.067-2.138 2.87M3 3l18 18" />
            } @else {
              <path d="M10 12a2 2 0 1 0 4 0a2 2 0 0 0-4 0M21 12c-2.4 4-5.4 6-9 6c-3.6 0-6.6-2-9-6c2.4-4 5.4-6 9-6c3.6 0 6.6 2 9 6" />
            }
          </svg>
        </button>
      }
    </div>
    @if (mensaje()) {
      <p class="lume-campo__error" [id]="id + '-error'">{{ mensaje() }}</p>
    }
  `,
})
export class Campo {
  readonly etiqueta = input.required<string>();
  readonly error = input<string | true | null | undefined>(undefined);
  readonly descripcion = input<string | undefined>(undefined);
  readonly obligatorio = input(false, { transform: booleanAttribute });
  /** Contraseña: agrega el botón «Mostrar u ocultar lo escrito». */
  readonly clave = input(false, { transform: booleanAttribute });
  /** id del control; por omisión uno único. */
  readonly idCampo = input<string | undefined>(undefined, { alias: 'id' });

  private readonly idAuto = `campo-${++siguiente}`;
  get id(): string {
    return this.idCampo() ?? this.idAuto;
  }

  readonly invalido = computed(() => {
    const e = this.error();
    return e === true || (typeof e === 'string' && e.trim() !== '');
  });
  protected readonly mensaje = computed(() => {
    const e = this.error();
    return typeof e === 'string' && e.trim() !== '' ? e : null;
  });
  readonly describePor = computed(() => {
    // El error se lee antes que la descripción (3.3.1)
    const ids = [this.mensaje() ? this.id + '-error' : null, this.descripcion() ? this.id + '-descripcion' : null].filter(Boolean);
    return ids.length ? ids.join(' ') : null;
  });

  protected readonly visible = signal(false);
  private entrada: Entrada | null = null;

  registrar(e: Entrada): void {
    this.entrada = e;
  }

  protected alternarVisible(): void {
    this.visible.update((v) => !v);
    this.entrada?.mostrar(this.visible());
  }
}

/**
 * El control nativo de un `lume-campo`: recibe del campo el id (para la etiqueta), `aria-invalid`, `aria-describedby` y
 * `aria-required`. Fuera de un `lume-campo` solo pone la clase del control LUME. Si la pantalla necesita un id fijo para el control,
 * lo pone en el `lume-campo` (`<lume-campo id="usuario">`): un `id` escrito en el `<input>` lo reemplaza el del campo.
 */
@Directive({
  selector: 'input[lumeEntrada], select[lumeEntrada], textarea[lumeEntrada]',
  host: {
    class: 'lume-entrada',
    '[id]': 'campo?.id ?? null',
    '[attr.aria-invalid]': "campo?.invalido() ? 'true' : null",
    '[attr.aria-describedby]': 'campo?.describePor() ?? null',
    '[attr.aria-required]': "campo?.obligatorio() ? 'true' : null",
  },
})
export class Entrada {
  protected readonly campo = inject(Campo, { optional: true });
  private readonly el = inject<ElementRef<HTMLInputElement>>(ElementRef).nativeElement;

  constructor() {
    this.campo?.registrar(this);
    // Un campo de contraseña empieza oculto aunque la plantilla no diga type="password"
    afterNextRender(() => {
      if (this.campo?.clave()) this.mostrar(false);
    });
  }

  /** Muestra u oculta lo escrito en un campo de contraseña. */
  mostrar(visible: boolean): void {
    this.el.type = visible ? 'text' : 'password';
  }
}
