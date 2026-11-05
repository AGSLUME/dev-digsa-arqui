import { DestroyRef, DOCUMENT, Injectable, inject, signal } from '@angular/core';

/**
 * Aviso sonoro ante el mal diligenciamiento de campos (Anexo 5 num. 2.4; decisión UI-8 del monolito): un tono corto y suave generado
 * con Web Audio (sin archivos). Activado por omisión; el interruptor de la cabecera lo apaga y el navegador lo recuerda (misma clave que
 * el monolito, `digsa.sonido`). Siempre acompaña a un aviso visual: nunca es el único. Portado de frontend/src/shared/alertaSonora.ts.
 */
export const CLAVE_SONIDO = 'digsa.sonido';

type ConstructorAudio = typeof AudioContext;

@Injectable({ providedIn: 'root' })
export class Sonido {
  private readonly documento = inject(DOCUMENT);
  private readonly ventana = this.documento.defaultView;
  private readonly _activo = signal(this.leer());
  private contexto: AudioContext | null = null;
  private ultimo = 0;

  /** Estado del interruptor (para la cabecera). */
  readonly activo = this._activo.asReadonly();

  constructor() {
    // Otra pestaña cambió el interruptor: esta lo sigue sin recargar
    const alCambiarAlmacen = (e: StorageEvent) => {
      if (e.key === CLAVE_SONIDO || e.key === null) this._activo.set(this.leer());
    };
    this.ventana?.addEventListener('storage', alCambiarAlmacen);
    inject(DestroyRef).onDestroy(() => this.ventana?.removeEventListener('storage', alCambiarAlmacen));
  }

  activar(valor: boolean): void {
    this._activo.set(valor);
    try {
      this.ventana?.localStorage.setItem(CLAVE_SONIDO, valor ? 'si' : 'no');
    } catch {
      // sin almacenamiento: vale para esta pestaña
    }
  }

  /** Dos notas descendentes de 110 ms a volumen bajo. No suena dos veces seguidas en menos de 400 ms (varios campos inválidos a la vez). */
  sonarError(): void {
    if (!this._activo()) return;
    const ahora = Date.now();
    if (ahora - this.ultimo < 400) return;
    this.ultimo = ahora;
    try {
      const ctx = this.contextoAudio();
      if (!ctx) return;
      const t = ctx.currentTime;
      [523.25, 392].forEach((frecuencia, i) => {
        const oscilador = ctx.createOscillator();
        const volumen = ctx.createGain();
        oscilador.type = 'sine';
        oscilador.frequency.value = frecuencia;
        const inicio = t + i * 0.12;
        volumen.gain.setValueAtTime(0.0001, inicio);
        volumen.gain.exponentialRampToValueAtTime(0.08, inicio + 0.02);
        volumen.gain.exponentialRampToValueAtTime(0.0001, inicio + 0.11);
        oscilador.connect(volumen).connect(ctx.destination);
        oscilador.start(inicio);
        oscilador.stop(inicio + 0.12);
      });
    } catch {
      // sin audio en el equipo: el aviso visual basta
    }
  }

  /**
   * La validación nativa del navegador (`required`, `type=email`...) también suena: el evento `invalid` no burbujea, así que se
   * escucha en captura sobre el documento. Devuelve la función para dejar de escuchar.
   */
  sonarEnValidacionNativa(): () => void {
    const alInvalido = () => this.sonarError();
    this.documento.addEventListener('invalid', alInvalido, true);
    return () => this.documento.removeEventListener('invalid', alInvalido, true);
  }

  /**
   * Los navegadores solo dejan sonar un AudioContext creado o reanudado durante un gesto del usuario. Un error del servidor llega
   * después (asíncrono): el contexto se prepara en los gestos de la sesión hasta que quede sonando. En pantallas táctiles el navegador
   * solo cuenta `touchend` y `click` como gesto, no `pointerdown`.
   */
  prepararEnPrimerGesto(): () => void {
    const GESTOS = ['pointerdown', 'keydown', 'touchend', 'click'] as const;
    const quitar = () => GESTOS.forEach((g) => this.documento.removeEventListener(g, preparar, true));
    const preparar = () => {
      try {
        const ctx = this.contextoAudio();
        if (ctx && ctx.state !== 'running') return;
      } catch {
        // sin audio en el equipo
      }
      quitar();
    };
    GESTOS.forEach((g) => this.documento.addEventListener(g, preparar, true));
    return quitar;
  }

  private leer(): boolean {
    try {
      return this.ventana?.localStorage.getItem(CLAVE_SONIDO) !== 'no';
    } catch {
      return true; // navegación privada o almacenamiento bloqueado: se queda el valor por omisión
    }
  }

  /** El contexto de audio del navegador (se crea una vez), reanudado; null si el equipo no tiene Web Audio. */
  private contextoAudio(): AudioContext | null {
    const v = this.ventana as unknown as { AudioContext?: ConstructorAudio; webkitAudioContext?: ConstructorAudio } | null;
    const Constructor = v?.AudioContext ?? v?.webkitAudioContext;
    if (!Constructor) return null;
    const ctx = (this.contexto ??= new Constructor());
    if (ctx.state === 'suspended') void ctx.resume().catch(() => undefined);
    return ctx;
  }
}
