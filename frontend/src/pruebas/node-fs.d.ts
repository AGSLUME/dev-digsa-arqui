/** Lo mínimo de node:fs que usan las pruebas (corren en Node con Vitest) para leer fuentes como texto, sin depender de @types/node. */
declare module 'node:fs' {
  export function readFileSync(ruta: string, codificacion: 'utf8'): string;
  export function readdirSync(ruta: string, opciones: { recursive: true }): string[];
}
