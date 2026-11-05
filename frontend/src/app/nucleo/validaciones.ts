/**
 * Validaciones del cliente con las mismas reglas y mensajes del servidor (Anexo 5 num. 2.4). Portado de frontend/src/shared/validaciones.ts
 * del monolito: por ahora lo de las contraseñas; las sesiones de módulo agregan aquí lo que porten (correo, textos de 10 a 800...).
 */

const NI_N_NI_TILDES = /[ñÑáéíóúÁÉÍÓÚüÜ]/;

/**
 * Revisión en el cliente de una contraseña nueva: solo lo que dicen los textos de ayuda (mínimo 12 caracteres, sin ñ ni tildes), con los
 * mismos mensajes del servidor (reglas/Claves.java). El servidor sigue decidiendo el resto (mayúsculas, número, carácter especial…).
 */
export function errorClave(clave: string): string | null {
  if (NI_N_NI_TILDES.test(clave)) return 'La contraseña no puede tener ñ ni tildes.';
  if ([...clave].length < 12) return 'La contraseña debe tener mínimo 12 caracteres.';
  return null;
}

/** La contraseña nueva y su confirmación (vacía: «Confirme…»; distinta: «no coinciden»). */
export function erroresClaveNueva(clave: string, confirmacion: string): { clave: string | null; confirmacion: string | null } {
  let errorConfirmacion: string | null = null;
  if (!confirmacion) errorConfirmacion = 'Confirme la contraseña nueva.';
  else if (confirmacion !== clave) errorConfirmacion = 'Las contraseñas no coinciden.';
  return { clave: errorClave(clave), confirmacion: errorConfirmacion };
}

/**
 * Error para un control que solo se colorea con `error=true` (casillas, grupos): el campo que marca el servidor lleva un texto que remite
 * al aviso, para que el error no dependa solo del color.
 */
export function conTexto(error: string | true | undefined): string | undefined {
  return error === true ? 'Revise este campo: el motivo está en el aviso de arriba.' : error;
}
