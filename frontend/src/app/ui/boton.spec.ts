import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Boton, BotonConMotivo } from './boton';
import { Sonido } from './sonido';

@Component({
  imports: [Boton, BotonConMotivo],
  template: `
    <button lumeBoton type="button" data-testid="primario">Ingresar</button>
    <button lumeBoton="secundario" tamano="pequeno" type="button" data-testid="secundario">Cancelar</button>
    <form (submit)="enviado = enviado + 1; $event.preventDefault()">
      <button lumeBoton type="submit" [lumeConMotivo]="motivo()" idMotivo="ayuda" (alPulsar)="pulsado = pulsado + 1">Guardar</button>
    </form>
    <p id="ayuda">Cambie un valor para guardar.</p>
  `,
})
class Prueba {
  motivo = signal<string | null>('Cambie un valor para guardar.');
  pulsado = 0;
  enviado = 0;
}

describe('lumeBoton', () => {
  it('pone las clases de la variante y el tamaño sin cambiar el elemento', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    const p = el.querySelector('[data-testid="primario"]')!;
    expect(p.tagName).toBe('BUTTON');
    expect([...p.classList]).toEqual(['lume-boton', 'lume-boton--primario']);
    expect([...el.querySelector('[data-testid="secundario"]')!.classList]).toEqual(
      expect.arrayContaining(['lume-boton--secundario', 'lume-boton--pequeno']),
    );
  });
});

describe('lumeConMotivo (UI-24)', () => {
  const sonarError = vi.fn();
  beforeEach(() => {
    sonarError.mockClear();
    TestBed.configureTestingModule({ providers: [{ provide: Sonido, useValue: { sonarError } }] });
  });

  // Portado de frontend/src/shared/BotonConMotivo.test.tsx del monolito
  it('con motivo: queda aria-disabled, no actúa ni envía el formulario, suena y lleva el foco a la explicación', async () => {
    const f = TestBed.createComponent(Prueba);
    document.body.appendChild(f.nativeElement);
    await f.whenStable();
    const boton = (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(boton.getAttribute('aria-disabled')).toBe('true');
    expect(boton.getAttribute('aria-describedby')).toBe('ayuda');
    expect(boton.hasAttribute('disabled')).toBe(false);
    boton.click();
    expect(f.componentInstance.pulsado).toBe(0);
    expect(f.componentInstance.enviado).toBe(0);
    expect(sonarError).toHaveBeenCalledOnce();
    expect(document.activeElement?.id).toBe('ayuda');
    f.nativeElement.remove();
  });

  it('sin motivo: funciona como un botón normal', async () => {
    const f = TestBed.createComponent(Prueba);
    f.componentInstance.motivo.set(null);
    await f.whenStable();
    const boton = (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(boton.hasAttribute('aria-disabled')).toBe(false);
    expect(boton.hasAttribute('aria-describedby')).toBe(false);
    boton.click();
    expect(f.componentInstance.pulsado).toBe(1);
    expect(f.componentInstance.enviado).toBe(1);
    expect(sonarError).not.toHaveBeenCalled();
  });
});
