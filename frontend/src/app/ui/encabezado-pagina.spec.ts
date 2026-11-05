import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { EncabezadoPagina, tituloDocumento } from './encabezado-pagina';

@Component({
  imports: [EncabezadoPagina],
  template: `
    @if (visible()) {
      <lume-encabezado-pagina titulo="Usuarios" fuente="Anexo 5 · 3" descripcion="Cuentas, perfiles y bloqueos.">
        <span data-testid="insignia">3 activos</span>
        <button acciones type="button">Nuevo usuario</button>
      </lume-encabezado-pagina>
    }
  `,
})
class Prueba {
  visible = signal(true);
}

// Portado de frontend/src/shared/EncabezadoPagina.test.tsx del monolito
describe('lume-encabezado-pagina', () => {
  it('un h1 con el título, descripción, fuente normativa con texto para lector y acciones a la derecha', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelectorAll('h1')).toHaveLength(1);
    expect(el.querySelector('h1')!.textContent).toBe('Usuarios');
    expect(el.querySelector('.lume-encabezado__titulo [data-testid="insignia"]')).not.toBeNull();
    expect(el.querySelector('.lume-encabezado__descripcion')!.textContent).toBe('Cuentas, perfiles y bloqueos.');
    expect(el.querySelector('.lume-fuente')!.textContent).toBe('Fuente normativa: Anexo 5 · 3');
    expect(el.querySelector('.lume-encabezado__acciones button')!.textContent).toBe('Nuevo usuario');
  });

  it('pone el título de la pestaña y lo quita al salir de la pantalla', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const titulo = TestBed.inject(Title);
    expect(titulo.getTitle()).toBe('Suite DIGSA · Usuarios');
    f.componentInstance.visible.set(false);
    await f.whenStable();
    expect(titulo.getTitle()).toBe('Suite DIGSA');
    expect(tituloDocumento()).toBe('Suite DIGSA');
  });
});
