import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  Directive,
  ElementRef,
  inject,
  Injector,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { ErrorApi } from '../nucleo/error-api';
import { Alerta } from './insignia';
import { Sonido } from './sonido';

/**
 * Aviso estándar ante el mal diligenciamiento de campos (Anexo 5 num. 2.4), igual en todos los formularios. Portado de
 * frontend/src/shared/validacion.tsx (`useValidacion`) del monolito:
 * 1. la validación del formulario pone el mensaje junto al campo (`lume-campo [error]`: borde y fondo de error, `aria-invalid` y
 *    `aria-describedby`);
 * 2. un resumen con `role="alert"` (`lume-resumen-errores`) dice cuáles campos revisar, o trae el error del servidor;
 * 3. el foco va al primer campo con error (o al resumen si el error no es de un campo);
 * 4. suena el aviso corto (si el usuario no lo apagó).
 * Cada mensaje se muestra una sola vez: el de la validación junto al campo (el resumen solo nombra los campos) y el del servidor en el
 * resumen (el campo que el servidor señala queda marcado, sin repetir el texto).
 *
 * ```ts
 * protected readonly v = crearValidacion({ etiquetas: { usuario: 'Usuario', clave: 'Contraseña' }, testid: 'error-ingreso' });
 * ```
 * ```html
 * <form [lumeValidacion]="v" novalidate (ngSubmit)="ingresar()">
 *   <lume-resumen-errores [validacion]="v" />
 *   <lume-campo etiqueta="Usuario" [error]="v.error('usuario')">…</lume-campo>
 * ```
 */

/** Mensaje por campo; un valor vacío o null es «sin error». */
export type Errores = Record<string, string | null | undefined>;

export interface OpcionesValidacion {
  /**
   * Nombre visible de cada campo para el resumen («Teléfono»); sin etiqueta se usa el nombre del campo. Una función si depende de los
   * datos (p. ej. el nombre vigente de cada perfil).
   */
  etiquetas?: Record<string, string> | (() => Record<string, string>);
  /** Campo del servidor (nombre en el cuerpo de la petición) → campo del formulario, cuando difieren; una función si depende del paso. */
  campos?: Record<string, string> | (() => Record<string, string>);
  /** data-testid del resumen; cada pantalla conserva el que ya usan sus pruebas (p. ej. «error-ingreso»). */
  testid?: string;
}

const ENFOCABLE =
  'input:not([type="hidden"]):not(:disabled), textarea:not(:disabled), select:not(:disabled), button:not(:disabled), [tabindex]:not([tabindex="-1"])';

function aLaVista(el: HTMLElement): boolean {
  // Un campo dentro de un panel plegado (inert) o de la vista que no se muestra (tabla o tarjetas) no puede recibir el foco
  if (el.closest('[inert], [aria-hidden="true"]')) return false;
  return (el as HTMLElement & { checkVisibility?: () => boolean }).checkVisibility?.() !== false;
}

/**
 * Primer campo con error que se puede enfocar, en el orden de la pantalla: un campo marcado (`aria-invalid`) o el primer control del
 * grupo cuyo mensaje de error se muestra (un grupo de radios o casillas no lleva `aria-invalid`; su error tiene id «…-error»).
 */
export function primerCampoConError(raiz: HTMLElement): HTMLElement | null {
  for (const marca of raiz.querySelectorAll<HTMLElement>('[aria-invalid="true"], [id$="-error"]')) {
    // Un campo marcado con tabindex=-1 (el lienzo de la firma) se enfoca él mismo, no el botón vecino
    const directo = marca.matches(ENFOCABLE) || (marca.getAttribute('aria-invalid') === 'true' && marca.getAttribute('tabindex') === '-1');
    const objetivo = directo ? marca : (marca.parentElement?.querySelector<HTMLElement>(ENFOCABLE) ?? null);
    if (objetivo && aLaVista(objetivo)) return objetivo;
  }
  return null;
}

function soloConMensaje(e: Errores): Record<string, string> {
  return Object.fromEntries(Object.entries(e).filter((x): x is [string, string] => typeof x[1] === 'string' && x[1].trim() !== ''));
}

export class Validacion {
  private readonly errores = signal<Record<string, string>>({});
  private readonly marcado = signal<string | null>(null);
  /** Error del servidor o aviso que no es de un campo. */
  readonly general = signal<string | null>(null);
  /**
   * Título y campos del resumen al momento del aviso: no cambian mientras el usuario corrige (el lector de pantalla no vuelve a
   * anunciar el resumen con cada tecla). El resumen se va cuando ya no queda ningún error.
   */
  readonly fijo = signal<{ titulo: string; nombres: string[] }>({ titulo: '', nombres: [] });
  readonly hayErrores = computed(() => Object.keys(this.errores()).length > 0 || this.general() !== null);
  readonly testid: string;

  /** Contenedor del formulario (`form[lumeValidacion]`) y elemento del resumen (`lume-resumen-errores`). */
  contenedor: HTMLElement | null = null;
  resumen: (() => HTMLElement | null) | null = null;

  constructor(
    private readonly opciones: Required<OpcionesValidacion>,
    private readonly sonido: Sonido,
    private readonly injector: Injector,
  ) {
    this.testid = opciones.testid;
  }

  /** Valor para `[error]` del campo: el mensaje, true si solo se marca (error del servidor) o undefined. */
  error(campo: string): string | true | undefined {
    return this.errores()[campo] ?? (this.marcado() === campo ? true : undefined);
  }

  /** Revisa los errores: si hay alguno los muestra, suena y lleva el foco; devuelve true si no hay errores. */
  validar(e: Errores): boolean {
    const conMensaje = soloConMensaje(e);
    this.errores.set(conMensaje);
    this.marcado.set(null);
    this.general.set(null);
    const etiquetas = this.etiquetas();
    const nombres = Object.keys(conMensaje).map((c) => etiquetas[c] ?? c);
    if (nombres.length === 0) return true;
    this.fijo.set({ titulo: nombres.length === 1 ? `Revise el campo marcado: ${nombres[0]}` : `Revise los ${nombres.length} campos marcados`, nombres });
    this.avisar('campo');
    return false;
  }

  /** Error de una petición: el mensaje va al resumen y, si el servidor dice el campo (`campo` del 422), el campo queda marcado. */
  errorServidor(e: unknown, texto: string): void {
    const delServidor = e instanceof ErrorApi ? (e.campo ?? null) : null;
    const etiquetas = this.etiquetas();
    const campos = typeof this.opciones.campos === 'function' ? this.opciones.campos() : this.opciones.campos;
    const campo = delServidor === null ? null : (campos[delServidor] ?? delServidor);
    // Solo se marca un campo que el formulario conoce
    const conocido = campo !== null && campo in etiquetas ? campo : null;
    this.errores.set({});
    this.marcado.set(conocido);
    this.general.set(e instanceof ErrorApi ? e.message : texto);
    this.fijo.set({ titulo: conocido !== null ? `Revise el campo marcado: ${etiquetas[conocido]}` : 'No se pudo completar la acción', nombres: [] });
    this.avisar('resumen');
  }

  /** Aviso que no es de un campo (p. ej. «falta la firma»): va al resumen, suena y lleva el foco al resumen. */
  alerta(mensaje: string): void {
    this.errores.set({});
    this.marcado.set(null);
    this.general.set(mensaje);
    this.fijo.set({ titulo: 'No se pudo completar la acción', nombres: [] });
    this.avisar('resumen');
  }

  /** Quita el error de un campo (al corregirlo). */
  limpiar(campo: string): void {
    this.errores.update((x) => {
      if (!(campo in x)) return x;
      const { [campo]: _quitado, ...resto } = x;
      return resto;
    });
    if (this.marcado() === campo) this.marcado.set(null);
  }

  /** Quita todos los errores (al cerrar o reiniciar el formulario o al guardar bien). */
  reiniciar(): void {
    this.errores.set({});
    this.marcado.set(null);
    this.general.set(null);
  }

  /** Las etiquetas vigentes (una función si dependen de los datos, p. ej. el nombre de cada perfil). */
  private etiquetas(): Record<string, string> {
    const e = this.opciones.etiquetas;
    return typeof e === 'function' ? e() : e;
  }

  private avisar(destino: 'campo' | 'resumen'): void {
    this.sonido.sonarError();
    // Adónde va el foco: al primer campo con error (validación del formulario) o al resumen (error del servidor o aviso general: así
    // el lector lee el motivo antes de llegar al campo). Después de pintar, cuando ya están las marcas y el resumen.
    afterNextRender(
      () => {
        const campo = destino === 'campo' && this.contenedor ? primerCampoConError(this.contenedor) : null;
        (campo ?? this.resumen?.() ?? null)?.focus();
      },
      { injector: this.injector },
    );
  }
}

/** Crea la validación de un formulario. Se llama en el contexto de inyección del componente (inicializador de un campo). */
export function crearValidacion({ etiquetas = {}, campos = {}, testid = 'resumen-errores' }: OpcionesValidacion = {}): Validacion {
  return new Validacion({ etiquetas, campos, testid }, inject(Sonido), inject(Injector));
}

/** Une el formulario con su validación: el foco busca dentro de él el primer campo con error. */
@Directive({ selector: '[lumeValidacion]' })
export class FormularioValidado {
  readonly validacion = input.required<Validacion>({ alias: 'lumeValidacion' });
  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  constructor() {
    afterNextRender(() => (this.validacion().contenedor = this.el));
    // La validación puede vivir más que el formulario (dentro de un @if): no se queda con un nodo muerto
    inject(DestroyRef).onDestroy(() => {
      const v = this.validacion();
      if (v.contenedor === this.el) v.contenedor = null;
    });
  }
}

/** Resumen de la validación (al inicio del formulario o junto al botón de enviar), con el data-testid de la pantalla. */
@Component({
  selector: 'lume-resumen-errores',
  imports: [Alerta],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let v = validacion();
    @if (v.hayErrores()) {
      <lume-alerta #alerta color="red" tabindex="-1" [titulo]="v.fijo().titulo" [attr.data-testid]="v.testid">
        @if (v.general(); as general) {
          {{ general }}
        } @else if (v.fijo().nombres.length > 1) {
          <ul class="lume-lista">
            @for (n of v.fijo().nombres; track n) {
              <li>{{ n }}</li>
            }
          </ul>
        }
      </lume-alerta>
    }
  `,
})
export class ResumenErrores {
  readonly validacion = input.required<Validacion>();
  private readonly alerta = viewChild('alerta', { read: ElementRef });

  constructor() {
    const buscar = () => (this.alerta()?.nativeElement as HTMLElement | undefined) ?? null;
    afterNextRender(() => (this.validacion().resumen = buscar));
    inject(DestroyRef).onDestroy(() => {
      const v = this.validacion();
      if (v.resumen === buscar) v.resumen = null;
    });
  }
}
