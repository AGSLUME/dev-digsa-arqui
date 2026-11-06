import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { crearPaginacion, paginaDe, Paginacion, paginasVisibles } from './paginacion';

describe('crearPaginacion (usePaginacion del monolito)', () => {
  it('25 por página; si la lista se achica, queda en la última que existe; irA lleva a la página de una fila', () => {
    TestBed.runInInjectionContext(() => {
      const lista = signal(Array.from({ length: 60 }, (_, i) => i));
      const p = crearPaginacion(lista);
      expect(p.paginas()).toBe(3);
      expect(p.visibles()).toHaveLength(25);
      p.irAPagina(3);
      expect(p.visibles()).toEqual([50, 51, 52, 53, 54, 55, 56, 57, 58, 59]);
      lista.set(lista().slice(0, 30));
      expect(p.pagina()).toBe(2);
      p.irA(3);
      expect(p.pagina()).toBe(1);
      expect(paginaDe(25)).toBe(2);
      // Una fila que no está (findIndex = -1) o una página 0 no dejan la tabla vacía
      p.irA(-1);
      expect(p.pagina()).toBe(1);
      p.irAPagina(0);
      expect(p.pagina()).toBe(1);
      expect(p.visibles()).toHaveLength(25);
    });
  });

  it('páginas visibles con «…» como Mantine (siblings 1, boundaries 1)', () => {
    expect(paginasVisibles(1, 1)).toEqual([1]);
    expect(paginasVisibles(1, 3)).toEqual([1, 2, 3]);
    expect(paginasVisibles(5, 10)).toEqual([1, '…', 4, 5, 6, '…', 10]);
    expect(paginasVisibles(3, 10)).toEqual([1, 2, 3, 4, '…', 10]);
  });
});

@Component({
  imports: [Paginacion],
  template: `<lume-paginacion nombre="usuarios" [total]="total()" [(pagina)]="pagina" />`,
})
class Prueba {
  total = signal(60);
  pagina = signal(1);
}

describe('lume-paginacion', () => {
  it('«Mostrando X–Y de Z usuarios», botones con nombre, aria-current y cambio de página', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="total-usuarios"]')!.textContent).toBe('Mostrando 1–25 de 60 usuarios');
    expect(el.querySelector('nav')!.getAttribute('aria-label')).toBe('Páginas de usuarios');
    const anterior = el.querySelector<HTMLButtonElement>('button[aria-label="Página anterior"]')!;
    // aria-disabled (no disabled): el foco no se pierde al llegar al extremo; y no hace nada
    expect(anterior.getAttribute('aria-disabled')).toBe('true');
    anterior.click();
    expect(f.componentInstance.pagina()).toBe(1);
    const actual = el.querySelector('[aria-current="page"]')!;
    expect(actual.textContent!.trim()).toBe('1');
    expect(actual.getAttribute('aria-label')).toBe('Página 1');
    expect(el.querySelector('[data-testid="total-usuarios"]')!.getAttribute('role')).toBe('status');
    el.querySelector<HTMLButtonElement>('button[aria-label="Página siguiente"]')!.click();
    await f.whenStable();
    expect(f.componentInstance.pagina()).toBe(2);
    expect(el.querySelector('[data-testid="total-usuarios"]')!.textContent).toBe('Mostrando 26–50 de 60 usuarios');
  });

  it('sin filas: «0 usuarios» y sin botones; con una página tampoco hay botones', async () => {
    const f = TestBed.createComponent(Prueba);
    f.componentInstance.total.set(0);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="total-usuarios"]')!.textContent).toBe('0 usuarios');
    expect(el.querySelector('nav')).toBeNull();
    f.componentInstance.total.set(7);
    await f.whenStable();
    expect(el.querySelector('[data-testid="total-usuarios"]')!.textContent).toBe('Mostrando 1–7 de 7 usuarios');
    expect(el.querySelector('nav')).toBeNull();
  });
});
