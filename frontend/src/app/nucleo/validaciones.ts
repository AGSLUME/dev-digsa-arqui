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

const CORREO = /^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

/** ¿Es un correo que el servidor acepta? (el patrón y el máximo de 150 de reglas/Correos.java). */
export function correoValido(correo: string): boolean {
  const c = correo.trim();
  return c.length <= 150 && CORREO.test(c);
}

/** Error de un correo: vacío → `vacio`; con formato inválido → `invalido`; bien → null. */
export function errorCorreo(correo: string, invalido = 'Escriba un correo válido.', vacio = 'Escriba el correo.'): string | null {
  if (!correo.trim()) return vacio;
  return correoValido(correo) ? null : invalido;
}

/** Móvil colombiano en E.164 (+57 y 10 dígitos que empiezan por 3), como reglas/Alertas.normalizarTelefono; null si no lo es. */
export function normalizarTelefono(telefono: string): string | null {
  let t = telefono.trim().replace(/[\s().-]+/g, '');
  if (t.startsWith('+57')) t = t.slice(3);
  else if (t.startsWith('0057')) t = t.slice(4);
  else if (t.startsWith('57') && t.length === 12) t = t.slice(2);
  return /^3\d{9}$/.test(t) ? '+57' + t : null;
}

export const ERROR_TELEFONO = 'El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000).';

/**
 * Error del teléfono móvil (alertas.ts del monolito): vacío o igual al guardado es válido (los usuarios registrados antes de la validación
 * se pueden editar sin cambiarlo); si no, debe ser un móvil colombiano.
 */
export function errorTelefono(escrito: string, guardado: string | null = null): string | null {
  const t = escrito.trim();
  if (!t || t === guardado) return null;
  return normalizarTelefono(t) ? null : ERROR_TELEFONO;
}
