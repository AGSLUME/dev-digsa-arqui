import { Directive, ElementRef, inject } from '@angular/core';

/** El número de un texto de solo dígitos (null si está vacío o no es un entero), para no repetir la conversión en cada pantalla. */
export function leerEntero(texto: string): number | null {
  const t = texto.trim();
  return /^\d+$/.test(t) ? Number(t) : null;
}

/** Mensaje de un entero escrito fuera de su rango (null si está bien o vacío y no es obligatorio). */
export function errorEntero(
  texto: string,
  { min, max, obligatorio = false, nombre = 'un número' }: { min?: number; max?: number; obligatorio?: boolean; nombre?: string } = {},
): string | null {
  const t = texto.trim();
  if (!t) return obligatorio ? `Escriba ${nombre}.` : null;
  if (!/^\d+$/.test(t)) return 'Escriba solo números enteros.';
  const n = Number(t);
  if (min !== undefined && max !== undefined && (n < min || n > max)) return `Escriba un número entre ${min} y ${max}.`;
  if (min !== undefined && n < min) return `Escriba un número mayor o igual a ${min}.`;
  if (max !== undefined && n > max) return `Escriba un número menor o igual a ${max}.`;
  return null;
}

/**
 * Campo de número entero no negativo (el `NumberInput` del monolito con `allowDecimal={false}` y `allowNegative={false}`): va sobre un
 * `<input lumeEntrada>` de texto con teclado numérico; solo deja escribir dígitos (lo demás se quita al escribir o al pegar, sin mover el
 * cursor). El rango va como texto en la descripción del campo (`aria-valuemin/max` no valen en un textbox) y se valida al enviar con
 * `errorEntero` (mensaje junto al campo), sin bloquear la escritura.
 *
 * ```html
 * <lume-campo etiqueta="Tiempo de inactividad (TIME OUT), en minutos" descripcion="Solo números enteros, entre 1 y 480." obligatorio [error]="v.error('minutos')">
 *   <input lumeEntrada lumeNumero data-testid="minutos-sesion" [value]="minutos()" (input)="…" />
 * </lume-campo>
 * ```
 */
@Directive({
  selector: 'input[lumeNumero]',
  host: {
    type: 'text',
    inputmode: 'numeric',
    autocomplete: 'off',
    pattern: '[0-9]*',
    // Antes que el (input) de la pantalla: lo que no es dígito ya no está cuando la pantalla lee el valor
    '(input)': 'limpiar()',
  },
})
export class Numero {
  private readonly el = inject<ElementRef<HTMLInputElement>>(ElementRef).nativeElement;

  protected limpiar(): void {
    const v = this.el.value;
    const limpio = v.replace(/\D/g, '');
    if (limpio === v) return;
    // El cursor queda donde estaba, menos lo quitado antes de él
    const cursor = this.el.selectionStart ?? v.length;
    const quitados = cursor - v.slice(0, cursor).replace(/\D/g, '').length;
    this.el.value = limpio;
    this.el.setSelectionRange(cursor - quitados, cursor - quitados);
  }
}
