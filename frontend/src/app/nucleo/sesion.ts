import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { BffApi, SesionApi, type EntradaMenu, type Sesion as SesionContrato } from '../api/seguridad';

/**
 * Sesión de la SPA (ADR-003, ADR-014): los datos de `GET /api/sesion` (contrato `seguridad.yaml`, esquema `Sesion`, con el cliente
 * generado de ADR-007) y la navegación al ingreso y a la salida, que pasan por el gateway con página completa. El navegador nunca ve
 * tokens: la cookie del BFF viaja sola.
 */

/** `EntradaMenu` del contrato. */
export type ItemMenu = EntradaMenu;

/** `Sesion` del contrato. */
export type DatosSesion = SesionContrato;

/** Rutas del gateway (BFF) que la SPA abre con página completa. */
export const RUTAS_BFF = {
  /** Sin sesión: el gateway inicia la autorización OIDC y el emisor manda a `/ingreso` (ADR-014, paso 1). */
  autorizacion: '/bff/oauth2/authorization/lume',
  /** Cierra la sesión del gateway y la del emisor; responde 204 (ADR-014, paso 5). */
  salir: '/bff/salir',
} as const;

/** Navegación de página completa (no la del router): `window.location`. Token para reemplazarla en las pruebas. */
export interface Navegador {
  asignar(url: string): void;
  recargar(): void;
}
export const NAVEGADOR = new InjectionToken<Navegador>('Navegador', {
  providedIn: 'root',
  factory: () => ({
    asignar: (url: string) => window.location.assign(url),
    recargar: () => window.location.reload(),
  }),
});

/** Regla de acceso del monolito (`puedeEntrar` de shared/sesion.tsx): Inicio siempre; si no, la pantalla debe estar permitida. */
export function puedeEntrar(s: Pick<DatosSesion, 'menu' | 'pantallas'>, ruta: string): boolean {
  return ruta === 'inicio' || (s.pantallas ?? []).includes(ruta) || s.menu.some((m) => m.ruta === ruta);
}

@Injectable({ providedIn: 'root' })
export class Sesion {
  private readonly api = inject(SesionApi);
  private readonly bff = inject(BffApi);
  private readonly navegador = inject(NAVEGADOR);
  private readonly _datos = signal<DatosSesion | null>(null);
  private cargando: Promise<DatosSesion> | null = null;

  /** Los datos de la sesión abierta (null mientras no hay). */
  readonly datos = this._datos.asReadonly();

  /** Datos de la sesión abierta; los pide una sola vez (también con llamadas simultáneas). Rechaza con `ErrorApi`. */
  cargar(): Promise<DatosSesion> {
    const actuales = this._datos();
    if (actuales) return Promise.resolve(actuales);
    this.cargando ??= firstValueFrom(this.api.datosSesion())
      .then((d) => {
        this._datos.set(d);
        return d;
      })
      .finally(() => (this.cargando = null));
    return this.cargando;
  }

  puedeEntrar(ruta: string): boolean {
    const d = this._datos();
    return d !== null && puedeEntrar(d, ruta);
  }

  /**
   * Lleva al ingreso por el gateway (página completa): el emisor muestra `/ingreso` y, al terminar, vuelve a la aplicación. Los datos de
   * la sesión no se borran aquí: la página se descarga, y el shell que sigue montado hasta entonces los necesita.
   */
  irAlIngreso(): void {
    this.navegador.asignar(RUTAS_BFF.autorizacion);
  }

  /** «Salir»: `POST /bff/salir` (204) y al ingreso. Sin red o con la sesión ya cerrada, igual se va al ingreso (como el monolito). */
  async salir(): Promise<void> {
    try {
      await firstValueFrom(this.bff.salir());
    } catch {
      // sin red o sesión ya cerrada: igual se vuelve al ingreso
    }
    this.irAlIngreso();
  }
}
