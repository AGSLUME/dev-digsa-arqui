import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MENSAJE_NO_EXISTE, errorFecha } from '../nucleo/fechas';
import { Campo, Entrada } from './campo';
import { Fecha } from './fecha';
import { errorEntero, leerEntero, Numero } from './numero';

@Component({
  imports: [Campo, Entrada, Numero, Fecha],
  template: `
    <lume-campo etiqueta="Tiempo de inactividad (TIME OUT), en minutos" idControl="minutos" descripcion="Solo números enteros, entre 1 y 480.">
      <input lumeEntrada lumeNumero data-testid="minutos-sesion" (input)="minutos.set($any($event.target).value)" />
    </lume-campo>
    <lume-campo etiqueta="Desde" idControl="desde">
      <lume-fecha [(valor)]="desde" />
    </lume-campo>
  `,
})
class Prueba {
  minutos = signal('');
  desde = signal<string | null>('2026-10-29');
  fecha = viewChild.required(Fecha);
}

function escribir(c: HTMLInputElement, v: string) {
  c.value = v;
  c.dispatchEvent(new Event('input'));
}

describe('lumeNumero (NumberInput del monolito: enteros, sin negativos)', () => {
  it('teclado numérico, rango en la descripción (no aria-valuemin/max, inválidos en un textbox); lo que no es dígito no entra', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const c = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('[data-testid="minutos-sesion"]')!;
    expect(c.getAttribute('inputmode')).toBe('numeric');
    expect(c.hasAttribute('aria-valuemin')).toBe(false);
    expect(c.hasAttribute('aria-valuemax')).toBe(false);
    expect(c.id).toBe('minutos');
    expect((f.nativeElement as HTMLElement).querySelector(`#${c.getAttribute('aria-describedby')}`)!.textContent).toBe(
      'Solo números enteros, entre 1 y 480.',
    );
    escribir(c, '-1,5a0');
    expect(c.value).toBe('150');
    expect(f.componentInstance.minutos()).toBe('150');
  });

  it('errorEntero: vacío, rango y obligatorio', () => {
    expect(errorEntero('30', { min: 1, max: 480 })).toBeNull();
    expect(errorEntero('0', { min: 1, max: 480 })).toBe('Escriba un número entre 1 y 480.');
    expect(errorEntero('', { obligatorio: true, nombre: 'los minutos' })).toBe('Escriba los minutos.');
    expect(errorEntero('2.5')).toBe('Escriba solo números enteros.');
    expect(leerEntero(' 480 ')).toBe(480);
    expect(leerEntero('')).toBeNull();
    expect(leerEntero('4,5')).toBeNull();
  });
});

describe('lume-fecha (DatePickerInput DD/MM/AAAA del monolito)', () => {
  it('muestra el valor con formato.ts y la etiqueta queda unida al campo', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const c = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#desde')!;
    expect(c.value).toBe('29/10/2026');
    expect(c.getAttribute('placeholder')).toBe('DD/MM/AAAA');
    expect(c.getAttribute('inputmode')).toBe('numeric');
  });

  it('al escribir pone las barras y, con una fecha válida, el valor es «aaaa-mm-dd»', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const c = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#desde')!;
    escribir(c, '01112026');
    expect(c.value).toBe('01/11/2026');
    expect(f.componentInstance.desde()).toBe('2026-11-01');
  });

  it('una fecha imposible o a medias deja el valor vacío en cuanto se escribe (no queda la anterior), y lo escrito queda para validarlo', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const c = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#desde')!;
    expect(f.componentInstance.desde()).toBe('2026-10-29');
    escribir(c, '2910');
    expect(f.componentInstance.desde()).toBeNull();
    escribir(c, '31022026');
    await f.whenStable();
    expect(f.componentInstance.desde()).toBeNull();
    expect(c.value).toBe('31/02/2026');
    expect(errorFecha(f.componentInstance.fecha().texto())).toBe(MENSAJE_NO_EXISTE);
  });
});

describe('lume-fecha: reinicio desde fuera', () => {
  it('reiniciar() limpia también lo escrito a medias (el linkedSignal solo no lo haría si el valor ya era null)', async () => {
    const f = TestBed.createComponent(Prueba);
    await f.whenStable();
    const c = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#desde')!;
    escribir(c, '231');
    expect(f.componentInstance.desde()).toBeNull();
    f.componentInstance.fecha().reiniciar();
    await f.whenStable();
    expect(c.value).toBe('');
    expect(f.componentInstance.fecha().texto()).toBe('');
    f.componentInstance.fecha().reiniciar('2026-11-01');
    await f.whenStable();
    expect(c.value).toBe('01/11/2026');
    expect(f.componentInstance.desde()).toBe('2026-11-01');
  });
});
