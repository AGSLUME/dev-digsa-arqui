import { TestBed } from '@angular/core/testing';
import { Avisos, RegionAvisos } from './avisos';

describe('avisos', () => {
  afterEach(() => vi.useRealTimers());

  it('el verde se cierra solo a los 4 s; el naranja persistente (ms = null) se queda hasta cerrarlo', () => {
    vi.useFakeTimers();
    const avisos = TestBed.inject(Avisos);
    avisos.mostrar('Usuario creado.');
    avisos.mostrar('Usuario creado, pero el correo de bienvenida no salió.', 'orange', null);
    expect(avisos.lista()).toHaveLength(2);
    vi.advanceTimersByTime(4000);
    expect(avisos.lista().map((a) => a.color)).toEqual(['orange']);
    vi.advanceTimersByTime(60_000);
    expect(avisos.lista()).toHaveLength(1);
    avisos.quitar(avisos.lista()[0]!.id);
    expect(avisos.lista()).toHaveLength(0);
  });

  it('la región es role=status con aria-live y cada aviso trae «Cerrar el aviso»', async () => {
    const f = TestBed.createComponent(RegionAvisos);
    TestBed.inject(Avisos).mostrar('Aviso naranja.', 'orange', null);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.getAttribute('role')).toBe('status');
    expect(el.getAttribute('aria-live')).toBe('polite');
    expect(el.querySelector('.lume-alerta--warning')!.textContent).toContain('Aviso naranja.');
    el.querySelector<HTMLButtonElement>('button[aria-label="Cerrar el aviso"]')!.click();
    await f.whenStable();
    expect(el.querySelector('.lume-alerta')).toBeNull();
  });
});
