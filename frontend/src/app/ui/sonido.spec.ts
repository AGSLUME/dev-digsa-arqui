import { TestBed } from '@angular/core/testing';
import { CLAVE_SONIDO, Sonido } from './sonido';

// Portado de frontend/src/shared/alertaSonora.test.ts del monolito.

/** AudioContext de mentira: cuenta los osciladores que se crean. */
function audioFalso() {
  const osciladores: unknown[] = [];
  class Contexto {
    state = 'running';
    currentTime = 0;
    destination = {};
    resume = () => Promise.resolve();
    createOscillator() {
      const o = { type: '', frequency: { value: 0 }, connect: (g: unknown) => g, start: vi.fn(), stop: vi.fn() };
      osciladores.push(o);
      return o;
    }
    createGain() {
      return { gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() }, connect: (d: unknown) => d };
    }
  }
  return { Contexto, osciladores };
}

/** Un servicio nuevo, como si la página se hubiera recargado. */
function nuevo(): Sonido {
  TestBed.resetTestingModule();
  return TestBed.inject(Sonido);
}

describe('alerta sonora (Anexo 5 num. 2.4, decisión UI-8)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('activada por omisión: suena dos notas cortas', () => {
    const { Contexto, osciladores } = audioFalso();
    vi.stubGlobal('AudioContext', Contexto);
    const s = nuevo();
    expect(s.activo()).toBe(true);
    s.sonarError();
    expect(osciladores).toHaveLength(2);
  });

  it('no repite el sonido en menos de 400 ms (varios campos a la vez)', () => {
    const { Contexto, osciladores } = audioFalso();
    vi.stubGlobal('AudioContext', Contexto);
    const s = nuevo();
    s.sonarError();
    s.sonarError();
    expect(osciladores).toHaveLength(2);
    vi.advanceTimersByTime(500);
    s.sonarError();
    expect(osciladores).toHaveLength(4);
  });

  it('el interruptor lo apaga y el navegador lo recuerda', () => {
    const { Contexto, osciladores } = audioFalso();
    vi.stubGlobal('AudioContext', Contexto);
    const s1 = nuevo();
    s1.activar(false);
    s1.sonarError();
    expect(osciladores).toHaveLength(0);
    expect(localStorage.getItem(CLAVE_SONIDO)).toBe('no');
    expect(nuevo().activo()).toBe(false);
  });

  it('otra pestaña cambia el interruptor: esta lo sigue sin recargar', () => {
    const s = nuevo();
    localStorage.setItem(CLAVE_SONIDO, 'no');
    window.dispatchEvent(new StorageEvent('storage', { key: CLAVE_SONIDO }));
    expect(s.activo()).toBe(false);
  });

  it('sin almacenamiento ni audio no falla (navegación privada, equipo sin sonido)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.stubGlobal('AudioContext', undefined);
    const s = nuevo();
    expect(s.activo()).toBe(true);
    expect(() => s.activar(false)).not.toThrow();
    expect(s.activo()).toBe(false);
    s.activar(true);
    expect(() => s.sonarError()).not.toThrow();
  });

  it('la validación nativa del navegador (evento invalid) también suena', () => {
    const { Contexto, osciladores } = audioFalso();
    vi.stubGlobal('AudioContext', Contexto);
    const s = nuevo();
    const dejar = s.sonarEnValidacionNativa();
    const campo = document.createElement('input');
    campo.required = true;
    document.body.appendChild(campo);
    campo.checkValidity();
    expect(osciladores).toHaveLength(2);
    dejar();
    campo.remove();
  });
});
