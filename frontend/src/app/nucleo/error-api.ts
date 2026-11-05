/**
 * Error de una petición, como `ErrorApi` de frontend/src/shared/api.ts del monolito: el estado HTTP, el `mensaje` del servidor (o uno
 * de respaldo) y el resto del cuerpo (`motivo`, `campo`...). El interceptor (`interceptor-api.ts`) convierte cada error HTTP en este.
 */
export class ErrorApi extends Error {
  constructor(
    public readonly estado: number,
    mensaje: string,
    public readonly datos: Record<string, unknown> = {},
  ) {
    super(mensaje);
    this.name = 'ErrorApi';
  }

  /** `motivo` del cuerpo (401 de sesión, 409 de sesión abierta), si viene como texto. */
  get motivo(): string | undefined {
    const m = this.datos['motivo'];
    return typeof m === 'string' ? m : undefined;
  }

  /** `campo` del cuerpo (422 de validación), si viene como texto. */
  get campo(): string | undefined {
    const c = this.datos['campo'];
    return typeof c === 'string' ? c : undefined;
  }
}

export const SIN_CONEXION = 'No hay conexión con el servidor. Revise su conexión e intente de nuevo.';
