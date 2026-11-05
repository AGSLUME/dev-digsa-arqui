import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Alerta, Insignia } from './insignia';

@Component({
  imports: [Insignia, Alerta],
  template: `
    <lume-insignia color="green" data-testid="i1">Activo</lume-insignia>
    <lume-insignia color="orange" bloque data-testid="i2">Bloqueado</lume-insignia>
    <lume-insignia data-testid="i3">Sin color</lume-insignia>
    <lume-alerta color="red" titulo="Revise el campo marcado: Usuario" data-testid="a1">Escriba el usuario.</lume-alerta>
    <lume-alerta color="yellow" data-testid="a2">La sesión se cerró por inactividad.</lume-alerta>
  `,
})
class Prueba {}

describe('insignias y alertas LUME', () => {
  it('la insignia usa la variante LUME del color del monolito y conserva el texto completo', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="i1"]')!.className).toBe('lume-insignia lume-insignia--success');
    expect([...el.querySelector('[data-testid="i2"]')!.classList].sort()).toEqual(['lume-insignia', 'lume-insignia--bloque', 'lume-insignia--warning']);
    expect(el.querySelector('[data-testid="i3"]')!.className).toBe('lume-insignia lume-insignia--info');
    expect(el.querySelector('[data-testid="i1"]')!.textContent).toBe('Activo');
  });

  it('la alerta es role=alert, con su variante, título y mensaje', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    const a1 = el.querySelector('[data-testid="a1"]')!;
    expect(a1.getAttribute('role')).toBe('alert');
    expect(a1.classList).toContain('lume-alerta--danger');
    expect(a1.querySelector('.lume-alerta__titulo')!.textContent).toBe('Revise el campo marcado: Usuario');
    expect(a1.querySelector('.lume-alerta__mensaje')!.textContent).toBe('Escriba el usuario.');
    const a2 = el.querySelector('[data-testid="a2"]')!;
    expect(a2.classList).toContain('lume-alerta--warning');
    expect(a2.querySelector('.lume-alerta__titulo')).toBeNull();
  });
});
