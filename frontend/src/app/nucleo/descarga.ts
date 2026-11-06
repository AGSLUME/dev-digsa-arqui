import { HttpResponse } from '@angular/common/http';

/**
 * Nombre del archivo de un `Content-Disposition` (`filename*=UTF-8''…` o `filename="…"`), como `descargar` de frontend/src/shared/api.ts
 * del monolito; sin él, `porOmision`. Solo el nombre: sin rutas ni caracteres que el sistema de archivos no admite.
 */
export function nombreDeArchivo(disposicion: string | null, porOmision: string): string {
  if (!disposicion) return porOmision;
  const utf8 = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposicion)?.[1];
  let nombre: string | undefined;
  if (utf8) {
    try {
      nombre = decodeURIComponent(utf8.trim());
    } catch {
      nombre = undefined;
    }
  }
  nombre ??= /filename\s*=\s*"([^"]+)"/i.exec(disposicion)?.[1] ?? /filename\s*=\s*([^;]+)/i.exec(disposicion)?.[1]?.trim();
  // Sin ruta, sin caracteres que no admite el sistema de archivos, sin puntos ni espacios en los extremos, y solo letras, números y
  // pocos signos (un nombre raro, «..» o un reservado de Windows como CON queda en el de omisión)
  const limpio = nombre
    ?.replace(/^.*[\\/]/, '')
    .replace(/[<>:"|?*\u0000-\u001f]/g, '_')
    .replace(/^[.\s]+|[.\s]+$/g, '');
  if (!limpio || limpio.length > 120 || !/^[\p{L}\p{N}._\-() ]+$/u.test(limpio) || /^(con|prn|aux|nul|com\d|lpt\d)(\.|$)/i.test(limpio)) return porOmision;
  return limpio;
}

/**
 * Guarda en el equipo el archivo de una respuesta (un `<a download>` con la URL del blob). Con `extension`, el nombre debe terminar en
 * ella (si no, va el de omisión). Sin cuerpo lanza un error, para que la pantalla diga que no se pudo descargar.
 */
export function guardarArchivo(respuesta: HttpResponse<Blob>, documento: Document, porOmision = 'archivo', extension?: string): void {
  const blob = respuesta.body;
  if (!blob) throw new Error('La respuesta no trae el archivo.');
  let nombre = nombreDeArchivo(respuesta.headers.get('Content-Disposition'), porOmision);
  if (extension && !nombre.toLowerCase().endsWith('.' + extension)) nombre = porOmision;
  const url = URL.createObjectURL(blob);
  const a = documento.createElement('a');
  a.href = url;
  a.download = nombre;
  a.hidden = true;
  documento.body.appendChild(a);
  a.click();
  a.remove();
  // Se libera después: hacerlo enseguida puede cortar la descarga de un archivo grande en algunos navegadores
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
