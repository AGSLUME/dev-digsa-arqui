import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { GrupoCasillas, Interruptor, type OpcionCasilla } from './casillas';

@Component({
  imports: [GrupoCasillas, Interruptor],
  template: `
    <lume-grupo-casillas leyenda="Perfiles" obligatorio [opciones]="perfiles" [(valores)]="elegidos" [error]="error()" />
    <lume-interruptor etiqueta="Asignar contraseña inicial (se cambia al primer ingreso)" [(activo)]="clave" />
  `,
})
class Prueba {
  perfiles: OpcionCasilla[] = [
    { valor: '1', etiqueta: 'Administrador' },
    { valor: '3', etiqueta: 'Auditor', descripcion: 'Hace las visitas de campo.' },
    { valor: '2', etiqueta: 'Coordinador' },
  ];
  elegidos = signal<string[]>([]);
  error = signal<string | true | undefined>(undefined);
  clave = signal(false);
}

async function montar() {
  const f = TestBed.createComponent(Prueba);
  await f.whenStable();
  return { f, el: f.nativeElement as HTMLElement };
}
const casilla = (el: HTMLElement, etiqueta: string) => {
  const l = [...el.querySelectorAll('label')].find((x) => x.textContent?.trim() === etiqueta)!;
  return el.querySelector<HTMLInputElement>(`#${l.htmlFor}`)!;
};

describe('lume-grupo-casillas (Checkbox.Group del monolito)', () => {
  it('fieldset con legend; cada casilla se llama exactamente como su etiqueta y la descripción va aparte', async () => {
    const { el } = await montar();
    // El asterisco es visual; el lector oye «(obligatorio)» (un fieldset no admite aria-required)
    expect(el.querySelector('legend')!.textContent!.replace('*', '').replace(/\s+/g, ' ').trim()).toBe('Perfiles (obligatorio)');
    expect(el.querySelector('legend .lume-solo-lector')!.textContent!.trim()).toBe('(obligatorio)');
    const auditor = casilla(el, 'Auditor');
    expect(auditor.type).toBe('checkbox');
    expect(el.ownerDocument.getElementById(auditor.getAttribute('aria-describedby')!)?.textContent ?? el.querySelector(`#${auditor.getAttribute('aria-describedby')}`)!.textContent).toBe(
      'Hace las visitas de campo.',
    );
  });

  it('marcar y desmarcar actualiza los valores en el orden de las opciones', async () => {
    const { f, el } = await montar();
    casilla(el, 'Coordinador').click();
    casilla(el, 'Administrador').click();
    await f.whenStable();
    expect(f.componentInstance.elegidos()).toEqual(['1', '2']);
    casilla(el, 'Coordinador').click();
    await f.whenStable();
    expect(f.componentInstance.elegidos()).toEqual(['1']);
  });

  it('el error va debajo con id «…-error» (la validación lleva el foco a la primera casilla); true remite al aviso', async () => {
    const { f, el } = await montar();
    f.componentInstance.error.set('Elija al menos un perfil.');
    await f.whenStable();
    const error = el.querySelector('[id$="-error"]')!;
    expect(error.textContent).toBe('Elija al menos un perfil.');
    expect(el.querySelector('fieldset')!.getAttribute('aria-describedby')).toBe(error.id);
    expect(casilla(el, 'Auditor').getAttribute('aria-invalid')).toBe('true');
    // Cada casilla también lleva el error en su aria-describedby (se oye al llegar a ella con el foco)
    expect(casilla(el, 'Administrador').getAttribute('aria-describedby')).toBe(error.id);
    expect(casilla(el, 'Auditor').getAttribute('aria-describedby')!.split(' ')).toContain(error.id);
    f.componentInstance.error.set(true);
    await f.whenStable();
    expect(el.querySelector('[id$="-error"]')!.textContent).toBe('Revise este campo: el motivo está en el aviso de arriba.');
  });
});

describe('lume-interruptor (Switch del monolito)', () => {
  it('casilla nativa con role=switch; pulsar el texto lo cambia', async () => {
    const { f, el } = await montar();
    const control = el.querySelector<HTMLInputElement>('input[role="switch"]')!;
    // Sin aria-checked: el estado lo da el checkbox nativo (ARIA in HTML lo desaconseja en un input checkbox)
    expect(control.hasAttribute('aria-checked')).toBe(false);
    expect(control.checked).toBe(false);
    [...el.querySelectorAll('label')].find((l) => l.textContent?.startsWith('Asignar contraseña inicial'))!.click();
    await f.whenStable();
    expect(f.componentInstance.clave()).toBe(true);
    expect(control.checked).toBe(true);
  });
});
