import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { etiquetarCeldas, TablaAdaptable } from './tabla-adaptable';

describe('etiquetarCeldas (tablas en tarjetas en celular)', () => {
  // Portado de frontend/src/shared/TablaAdaptable.test.ts del monolito
  it('pone a cada celda el nombre de su columna y los roles de tabla', () => {
    const raiz = document.createElement('div');
    raiz.innerHTML = `<table>
      <thead><tr><th>Visita</th><th colspan="2">Fechas</th><th>Estado</th></tr></thead>
      <tbody>
        <tr><td>VS-1</td><td>01/10</td><td>02/10</td><td>Cerrada</td></tr>
        <tr><td colspan="4">Sin visitas</td></tr>
        <tr><td>VS-2</td><td colspan="3">Modificado (dato personal)</td></tr>
      </tbody></table>`;
    etiquetarCeldas(raiz);
    const filas = raiz.querySelectorAll('tbody tr');
    expect([...filas[0]!.children].map((c) => c.getAttribute('data-etiqueta'))).toEqual(['Visita', 'Fechas', 'Fechas', 'Estado']);
    expect(filas[1]!.children[0]!.getAttribute('data-etiqueta')).toBe('');
    expect(filas[2]!.children[1]!.getAttribute('data-etiqueta')).toBe('Fechas / Estado');
    expect(raiz.querySelector('table')!.getAttribute('role')).toBe('table');
    expect(filas[0]!.getAttribute('role')).toBe('row');
    expect(filas[0]!.children[0]!.getAttribute('role')).toBe('cell');
    expect(raiz.querySelector('th')!.getAttribute('role')).toBe('columnheader');
  });
});

@Component({
  imports: [TablaAdaptable],
  template: `<div lumeTablaAdaptable>
    <table>
      <thead><tr><th>Usuario</th><th>Estado</th></tr></thead>
      <tbody>
        @for (f of filas(); track f) {
          <tr data-testid="fila"><td>{{ f }}</td><td>Activo</td></tr>
        }
      </tbody>
    </table>
  </div>`,
})
class Prueba {
  filas = signal(['ana']);
}

describe('lumeTablaAdaptable', () => {
  it('etiqueta las celdas al pintar y otra vez cuando llegan filas nuevas', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[lumeTablaAdaptable]')!.classList).toContain('tabla-adaptable');
    expect(el.querySelector('td')!.getAttribute('data-etiqueta')).toBe('Usuario');
    f.componentInstance.filas.set(['ana', 'beto']);
    await f.whenStable();
    await new Promise((r) => setTimeout(r)); // el MutationObserver avisa en una microtarea
    const celdas = el.querySelectorAll('[data-testid="fila"]')[1]!.children;
    expect([...celdas].map((c) => c.getAttribute('data-etiqueta'))).toEqual(['Usuario', 'Estado']);
  });
});
