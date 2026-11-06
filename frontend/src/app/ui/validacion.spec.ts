import { Component, input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ErrorApi } from '../nucleo/error-api';
import { Boton } from './boton';
import { Campo, Entrada } from './campo';
import { Sonido } from './sonido';
import { crearValidacion, FormularioValidado, primerCampoConError, ResumenErrores } from './validacion';

// Portado de frontend/src/shared/validacion.test.tsx del monolito.

@Component({
  imports: [Campo, Entrada, ResumenErrores, FormularioValidado, Boton],
  template: `
    <form #f [lumeValidacion]="v" novalidate (submit)="$event.preventDefault(); enviar(f)">
      <lume-resumen-errores [validacion]="v" />
      <lume-campo etiqueta="Nombre" [error]="v.error('nombre')">
        <input lumeEntrada name="nombre" (input)="v.limpiar('nombre')" />
      </lume-campo>
      <lume-campo etiqueta="Teléfono" [error]="v.error('telefono')">
        <input lumeEntrada name="telefono" />
      </lume-campo>
      <button lumeBoton type="submit">Guardar</button>
    </form>
  `,
})
class Formulario {
  readonly fallo = input<unknown>(undefined);
  readonly v = crearValidacion({ etiquetas: { nombre: 'Nombre', telefono: 'Teléfono' }, campos: { celular: 'telefono' }, testid: 'error-usuario' });
  enviar(f: HTMLFormElement) {
    const datos = new FormData(f);
    const ok = this.v.validar({
      nombre: datos.get('nombre') ? null : 'Escriba el nombre.',
      telefono: /^\d{10}$/.test(String(datos.get('telefono'))) ? null : 'El teléfono tiene 10 dígitos.',
    });
    if (ok && this.fallo()) this.v.errorServidor(this.fallo(), 'No se pudo guardar.');
  }
}

const sonarError = vi.fn();
let fixture: ComponentFixture<Formulario>;

async function montar(fallo?: unknown): Promise<HTMLElement> {
  TestBed.configureTestingModule({ providers: [{ provide: Sonido, useValue: { sonarError } }] });
  fixture = TestBed.createComponent(Formulario);
  if (fallo !== undefined) fixture.componentRef.setInput('fallo', fallo);
  document.body.appendChild(fixture.nativeElement);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

const porEtiqueta = (raiz: HTMLElement, texto: string) => {
  const label = [...raiz.querySelectorAll('label')].find((l) => l.textContent?.trim() === texto)!;
  return raiz.ownerDocument.getElementById(label.htmlFor) as HTMLInputElement;
};
const boton = (raiz: HTMLElement) => raiz.querySelector<HTMLButtonElement>('button[type="submit"]')!;
const escribir = (el: HTMLInputElement, valor: string) => {
  el.value = valor;
  el.dispatchEvent(new Event('input'));
};
const pulsar = async (raiz: HTMLElement) => {
  boton(raiz).click();
  await fixture.whenStable();
};

afterEach(() => {
  fixture?.nativeElement.remove();
  sonarError.mockClear();
});

describe('crearValidacion (Anexo 5 num. 2.4)', () => {
  it('marca cada campo, resume cuáles revisar, lleva el foco al primero y suena', async () => {
    const raiz = await montar();
    await pulsar(raiz);
    const nombre = porEtiqueta(raiz, 'Nombre');
    const telefono = porEtiqueta(raiz, 'Teléfono');
    expect(nombre.getAttribute('aria-invalid')).toBe('true');
    expect(telefono.getAttribute('aria-invalid')).toBe('true');
    expect(nombre.getAttribute('aria-describedby')).toBe(`${nombre.id}-error`);
    expect(raiz.ownerDocument.getElementById(`${nombre.id}-error`)!.textContent).toBe('Escriba el nombre.');
    // El resumen nombra los campos sin repetir el mensaje (cada mensaje aparece una sola vez)
    const resumen = raiz.querySelector('[role="alert"]')!;
    expect(resumen.textContent).toContain('Revise los 2 campos marcados');
    expect(resumen.textContent).toContain('Teléfono');
    expect(raiz.textContent!.split('Escriba el nombre.')).toHaveLength(2);
    expect(document.activeElement).toBe(nombre);
    expect(sonarError).toHaveBeenCalledTimes(1);
  });

  it('al corregir un campo se quita su error; con todo bien no hay resumen ni sonido', async () => {
    const raiz = await montar();
    await pulsar(raiz);
    escribir(porEtiqueta(raiz, 'Nombre'), 'Ana');
    await fixture.whenStable();
    expect(porEtiqueta(raiz, 'Nombre').getAttribute('aria-invalid')).not.toBe('true');
    escribir(porEtiqueta(raiz, 'Teléfono'), '3001234567');
    sonarError.mockClear();
    await pulsar(raiz);
    expect(raiz.querySelector('[role="alert"]')).toBeNull();
    expect(sonarError).not.toHaveBeenCalled();
  });

  it('un 422 con «campo»: el mensaje queda una vez en el resumen (con su testid) y el campo traducido queda marcado; el foco va al resumen', async () => {
    const raiz = await montar(new ErrorApi(422, 'Ese teléfono ya está registrado.', { campo: 'celular' }));
    escribir(porEtiqueta(raiz, 'Nombre'), 'Ana');
    escribir(porEtiqueta(raiz, 'Teléfono'), '3001234567');
    await pulsar(raiz);
    expect(porEtiqueta(raiz, 'Teléfono').getAttribute('aria-invalid')).toBe('true');
    expect(raiz.textContent!.split('Ese teléfono ya está registrado.')).toHaveLength(2);
    const resumen = raiz.querySelector<HTMLElement>('[data-testid="error-usuario"]')!;
    expect(resumen.textContent).toContain('Ese teléfono ya está registrado.');
    expect(resumen.textContent).toContain('Revise el campo marcado: Teléfono');
    expect(document.activeElement).toBe(resumen);
    expect(sonarError).toHaveBeenCalledTimes(1);
  });

  it('un error del servidor sin campo queda en el resumen, visible y anunciado, con el foco en él', async () => {
    const raiz = await montar(new ErrorApi(409, 'La visita cambió de estado.'));
    escribir(porEtiqueta(raiz, 'Nombre'), 'Ana');
    escribir(porEtiqueta(raiz, 'Teléfono'), '3001234567');
    await pulsar(raiz);
    const resumen = raiz.querySelector('[role="alert"]')!;
    expect(resumen.textContent).toContain('No se pudo completar la acción');
    expect(resumen.textContent).toContain('La visita cambió de estado.');
    expect(document.activeElement).toBe(resumen);
  });

  it('un error que no es de la API muestra el texto de respaldo', async () => {
    const raiz = await montar(new Error('red'));
    escribir(porEtiqueta(raiz, 'Nombre'), 'Ana');
    escribir(porEtiqueta(raiz, 'Teléfono'), '3001234567');
    await pulsar(raiz);
    expect(raiz.querySelector('[role="alert"]')!.textContent).toContain('No se pudo guardar.');
  });

  it('el resumen no cambia mientras el usuario corrige (no se vuelve a anunciar)', async () => {
    const raiz = await montar();
    await pulsar(raiz);
    const titulo = raiz.querySelector('[role="alert"]')!.textContent;
    escribir(porEtiqueta(raiz, 'Nombre'), 'Ana');
    await fixture.whenStable();
    expect(raiz.querySelector('[role="alert"]')!.textContent).toBe(titulo);
  });
});

describe('primerCampoConError', () => {
  it('salta el campo de un panel plegado (inert) y llega al primer control de un grupo con mensaje de error', () => {
    const raiz = document.createElement('div');
    raiz.innerHTML = `<div inert><input aria-invalid="true" /></div>
      <fieldset><legend>Respuesta</legend><input type="radio" id="acepta" /><input type="radio" /><p id="decision-error">Elija.</p></fieldset>`;
    document.body.appendChild(raiz);
    expect(primerCampoConError(raiz)?.id).toBe('acepta');
    raiz.remove();
  });

  it('enfoca el lienzo de la firma marcado (tabindex=-1) y no el botón vecino', () => {
    const raiz = document.createElement('div');
    raiz.innerHTML = '<div><canvas aria-invalid="true" tabindex="-1"></canvas><button>Borrar</button></div>';
    document.body.appendChild(raiz);
    expect(primerCampoConError(raiz)?.tagName).toBe('CANVAS');
    raiz.remove();
  });
});

@Component({
  imports: [Campo, Entrada],
  template: `<lume-campo etiqueta="Contraseña" clave obligatorio descripcion="Mínimo 12 caracteres.">
    <input lumeEntrada type="password" />
  </lume-campo>`,
})
class ConClave {}

describe('lume-campo', () => {
  it('etiqueta unida al control, obligatorio, descripción y «Mostrar u ocultar lo escrito»', async () => {
    const f = TestBed.createComponent(ConClave);
    await f.whenStable();
    const raiz = f.nativeElement as HTMLElement;
    const entrada = raiz.querySelector('input')!;
    expect(raiz.querySelector('label')!.htmlFor).toBe(entrada.id);
    expect(entrada.getAttribute('aria-required')).toBe('true');
    expect(entrada.getAttribute('aria-describedby')).toBe(`${entrada.id}-descripcion`);
    expect(entrada.hasAttribute('aria-invalid')).toBe(false);
    const ver = raiz.querySelector<HTMLButtonElement>('button')!;
    expect(ver.getAttribute('aria-label')).toBe('Mostrar u ocultar lo escrito');
    expect(ver.type).toBe('button');
    ver.click();
    await f.whenStable();
    expect(entrada.type).toBe('text');
    expect(ver.getAttribute('aria-pressed')).toBe('true');
    ver.click();
    expect(entrada.type).toBe('password');
  });
});
