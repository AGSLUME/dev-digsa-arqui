import { ChangeDetectionStrategy, Component, DestroyRef, DOCUMENT, inject, Injectable, input, linkedSignal, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { SesionApi } from '../api/seguridad';
import { guardarMotivo, marcarActividad, ultimaActividadCompartida } from '../nucleo/actividad';
import { ErrorApi } from '../nucleo/error-api';
import { estadoAviso, mmss, ultimaActividadDelServidor } from '../nucleo/inactividad';
import { Sesion } from '../nucleo/sesion';
import { Boton } from '../ui/boton';
import { CuerpoModal, Modal } from '../ui/modal';

/**
 * Segundos que le quedan a la sesión antes del cierre por inactividad, para la cuenta regresiva de la cabecera. Los publica el aviso en
 * cada tic (es quien sabe la última actividad y el plazo del servidor); la cabecera solo los lee. Port de shared/relojSesion.ts.
 */
@Injectable({ providedIn: 'root' })
export class RelojSesion {
  readonly restantes = signal<number | null>(null);
}

export const MOTIVO_INACTIVIDAD = 'La sesión se cerró por inactividad. Vuelva a iniciar sesión.';

/**
 * Aviso de cierre por inactividad con cuenta regresiva, «Salir» y «Seguir conectado» (Anexo 5, 3.2). Port de
 * frontend/src/shared/AvisoInactividad.tsx del monolito, con sus tiempos y textos:
 * - la última actividad se comparte entre pestañas (`digsa.actividad`): si otra pestaña tuvo respuesta del servidor, este aviso se cierra;
 * - quien escribe o hace clic no está inactivo: se renueva la sesión (`GET /api/sesion/mantener`) como máximo cada 20 s;
 * - cada minuto, y al vencer el plazo local, se consulta `GET /api/sesion/estado` (NO renueva) y se adoptan los minutos y segundos del
 *   servidor (el administrador puede cambiar el TIME OUT con la sesión abierta);
 * - al vencer, si el servidor no responde o la sesión ya no existe, se vuelve al ingreso con el motivo: el aviso nunca se queda en 00:00.
 */
@Component({
  selector: 'lume-aviso-inactividad',
  imports: [Modal, CuerpoModal, Boton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <lume-modal [abierto]="restantes() !== null" titulo="Su sesión está por cerrarse" [conCerrar]="false" ancho="sm" (cerrar)="seguir()">
      <ng-template lumeCuerpoModal>
        <p data-aviso-inactividad>
          Por inactividad, la sesión se cerrará en <strong data-testid="cuenta-regresiva">{{ cuenta() }}</strong>.
        </p>
        <div class="lume-modal__acciones" data-aviso-inactividad>
          <button lumeBoton="secundario" type="button" (click)="salir()">Salir</button>
          <button lumeBoton type="button" data-autofocus (click)="seguir()">Seguir conectado</button>
        </div>
      </ng-template>
    </lume-modal>
  `,
})
export class AvisoInactividad {
  private readonly api = inject(SesionApi);
  private readonly sesion = inject(Sesion);
  private readonly reloj = inject(RelojSesion);
  private readonly documento = inject(DOCUMENT);

  /** Minutos de inactividad de la sesión (`minutosInactividad`). */
  readonly minutos = input.required<number>();

  protected readonly restantes = signal<number | null>(null);
  /** Minutos vigentes: los de la sesión, hasta que el servidor diga otros (el administrador puede cambiar el TIME OUT). */
  private readonly vigentes = linkedSignal(() => this.minutos());
  private local = Date.now();
  private verificando = false;
  private sincronizado = Date.now();
  private renovado = 0;

  protected cuenta(): string {
    return mmss(this.restantes() ?? 0);
  }

  constructor() {
    const destruir = inject(DestroyRef);
    // Quien está escribiendo o haciendo clic (p. ej. diligenciando un formulario sin guardar) no está inactivo
    const alInteractuar = (ev: Event) => {
      // Un clic en un botón o enlace ya hace su propia petición (y «Salir» no debe competir con una renovación)
      if (ev.type === 'pointerdown' && ev.target instanceof Element && ev.target.closest('button, a, [role="button"]')) return;
      // Dentro del propio aviso, moverse con el teclado (Tab hasta «Salir») no es actividad: si no, la primera tecla lo cerraría
      if (ev.target instanceof Element && ev.target.closest('[data-aviso-inactividad]')) return;
      const ahora = Date.now();
      if (ahora - this.renovado < 20_000 || this.verificando) return;
      this.renovado = ahora;
      this.mantener().catch(() => {
        // si la sesión ya se cerró, el interceptor lleva al ingreso
      });
    };
    const eventos = ['keydown', 'pointerdown', 'input'] as const;
    eventos.forEach((e) => this.documento.addEventListener(e, alInteractuar, { passive: true, capture: true }));

    marcarActividad();
    const tic = setInterval(() => this.tic(), 1000);
    destruir.onDestroy(() => {
      clearInterval(tic);
      eventos.forEach((e) => this.documento.removeEventListener(e, alInteractuar, { capture: true }));
      this.reloj.restantes.set(null);
    });
  }

  private tic(): void {
    const ultima = Math.max(this.local, ultimaActividadCompartida());
    const e = estadoAviso(ultima, Date.now(), this.vigentes());
    this.reloj.restantes.set(e.restantes); // cuenta regresiva de la cabecera
    if (e.vencida || Date.now() - this.sincronizado >= 60_000) {
      if (this.verificando) return;
      this.verificando = true;
      this.sincronizado = Date.now();
      firstValueFrom(this.api.estadoSesion())
        .then((r) => {
          // El servidor manda: su plazo vigente y lo que le queda (otra pestaña, relojes distintos o un TIME OUT nuevo)
          this.local = ultimaActividadDelServidor(Date.now(), r.minutos, r.restantes);
          this.vigentes.set(r.minutos);
          if (e.vencida) this.restantes.set(null);
        })
        .catch((x: unknown) => {
          // Un 401 con motivo ya llevó al ingreso con el mensaje del servidor (interceptor); lo demás (sin red, error, sesión que ya no
          // existe en el servidor) también vuelve al ingreso, con el motivo de inactividad
          if (x instanceof ErrorApi && x.estado === 401 && x.motivo !== undefined && x.motivo !== 'sin_sesion') return;
          if (!e.vencida) return; // la sincronización periódica no cierra por un error pasajero
          guardarMotivo(MOTIVO_INACTIVIDAD);
          void this.sesion.salir();
        })
        .finally(() => (this.verificando = false));
      return;
    }
    this.restantes.set(e.mostrar ? e.restantes : null);
  }

  private async mantener(): Promise<void> {
    const r = await firstValueFrom(this.api.mantenerSesion());
    this.local = Date.now();
    this.vigentes.set(r.minutos);
    this.restantes.set(null);
  }

  protected async seguir(): Promise<void> {
    try {
      await this.mantener();
    } catch (e) {
      if (e instanceof ErrorApi && e.estado === 401) void this.sesion.salir();
      else this.restantes.set(null);
    }
  }

  protected salir(): void {
    void this.sesion.salir();
  }
}
