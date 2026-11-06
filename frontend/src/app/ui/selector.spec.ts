import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Campo } from './campo';
import { Selector, type OpcionSelector } from './selector';

@Component({
  imports: [Campo, Selector],
  template: `
    <lume-campo etiqueta="Fuerza (Sanidad)" [error]="error()">
      <lume-selector [opciones]="opciones" [(valor)]="valor" [buscable]="buscable()" limpiable />
    </lume-campo>
  `,
})
class Prueba {
  opciones: OpcionSelector[] = [
    { valor: 'EJC', etiqueta: 'Ejército' },
    { valor: 'ARC', etiqueta: 'Armada' },
    { valor: 'FAC', etiqueta: 'Fuerza Aérea' },
    { valor: 'PON', etiqueta: 'Policía' },
  ];
  valor = signal<string | null>(null);
  buscable = signal(false);
  error = signal<string | undefined>(undefined);
}

@Component({
  imports: [Campo, Selector],
  template: `<lume-campo etiqueta="Perfil base"><lume-selector [opciones]="opciones" deshabilitado /></lume-campo>`,
})
class Deshabilitado {
  opciones: OpcionSelector[] = [{ valor: '1', etiqueta: 'Auditor' }];
}

const montados: HTMLElement[] = [];
afterEach(() => {
  montados.splice(0).forEach((n) => n.remove());
  document.querySelectorAll('.cdk-overlay-container').forEach((n) => (n.innerHTML = ''));
});

async function montar(buscable = false) {
  const f = TestBed.createComponent(Prueba);
  f.componentInstance.buscable.set(buscable);
  document.body.appendChild(f.nativeElement);
  montados.push(f.nativeElement);
  await f.whenStable();
  const combo = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('[role="combobox"]')!;
  return { f, combo };
}

async function tecla(f: { whenStable(): Promise<unknown> }, el: HTMLElement, key: string, extra: KeyboardEventInit = {}) {
  el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...extra }));
  await f.whenStable();
}
const opciones = () => [...document.querySelectorAll<HTMLElement>('[role="option"]')];
const estado = () => (document.querySelector('.lume-selector [role="status"]')?.textContent ?? '').trim();

describe('lume-selector (patrón combobox de ARIA 1.2)', () => {
  it('cerrado: combobox con nombre de la etiqueta, aria-expanded=false, aria-controls a la lista, sin activedescendant', async () => {
    const { combo } = await montar();
    const label = document.querySelector<HTMLLabelElement>('label')!;
    expect(label.htmlFor).toBe(combo.id);
    expect(label.textContent!.trim()).toBe('Fuerza (Sanidad)');
    expect(combo.getAttribute('aria-expanded')).toBe('false');
    expect(combo.getAttribute('aria-haspopup')).toBe('listbox');
    expect(combo.getAttribute('aria-controls')).toMatch(/^selector-lista-/);
    expect(combo.hasAttribute('aria-activedescendant')).toBe(false);
    expect(combo.readOnly).toBe(true); // sin búsqueda: select-only
  });

  it('↓ abre con la primera resaltada (activedescendant), recorre con ↓/↑ y Fin; Enter elige y cierra; se anuncian los resultados', async () => {
    const { f, combo } = await montar();
    await tecla(f, combo, 'ArrowDown');
    expect(combo.getAttribute('aria-expanded')).toBe('true');
    const lista = document.getElementById(combo.getAttribute('aria-controls')!)!;
    expect(lista.getAttribute('role')).toBe('listbox');
    expect(lista.getAttribute('aria-label')).toBe('Fuerza (Sanidad)');
    expect(opciones().map((o) => o.textContent!.trim())).toEqual(['Ejército', 'Armada', 'Fuerza Aérea', 'Policía']);
    expect(combo.getAttribute('aria-activedescendant')).toBe(opciones()[0]!.id);
    expect(estado()).toBe('4 resultados');
    await tecla(f, combo, 'ArrowDown');
    await tecla(f, combo, 'ArrowDown');
    await tecla(f, combo, 'ArrowUp');
    expect(combo.getAttribute('aria-activedescendant')).toBe(opciones()[1]!.id);
    await tecla(f, combo, 'End');
    expect(combo.getAttribute('aria-activedescendant')).toBe(opciones()[3]!.id);
    await tecla(f, combo, 'Enter');
    expect(f.componentInstance.valor()).toBe('PON');
    expect(combo.value).toBe('Policía');
    expect(combo.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement === combo || !document.hasFocus()).toBe(true);
  });

  it('al reabrir, la opción elegida está resaltada y marcada aria-selected; ↑ cerrado abre en la última', async () => {
    const { f, combo } = await montar();
    f.componentInstance.valor.set('ARC');
    await f.whenStable();
    await tecla(f, combo, 'ArrowDown');
    expect(combo.getAttribute('aria-activedescendant')).toBe(opciones()[1]!.id);
    expect(opciones()[1]!.getAttribute('aria-selected')).toBe('true');
    expect(opciones()[0]!.getAttribute('aria-selected')).toBe('false');
    await tecla(f, combo, 'Escape');
    f.componentInstance.valor.set(null);
    await f.whenStable();
    await tecla(f, combo, 'ArrowUp');
    expect(combo.getAttribute('aria-activedescendant')).toBe(opciones()[3]!.id);
  });

  it('Escape cierra la lista sin elegir y no deja pasar el evento (no cierra el modal de atrás)', async () => {
    const { f, combo } = await montar();
    await tecla(f, combo, 'ArrowDown');
    const fuera = vi.fn();
    document.addEventListener('keydown', fuera);
    await tecla(f, combo, 'Escape');
    document.removeEventListener('keydown', fuera);
    expect(fuera).not.toHaveBeenCalled();
    expect(combo.getAttribute('aria-expanded')).toBe('false');
    expect(f.componentInstance.valor()).toBeNull();
  });

  it('sin búsqueda, una letra salta a la primera opción que empieza por ella', async () => {
    const { f, combo } = await montar();
    await tecla(f, combo, 'p');
    expect(combo.getAttribute('aria-expanded')).toBe('true');
    expect(combo.getAttribute('aria-activedescendant')).toBe(opciones()[3]!.id);
  });

  it('con búsqueda: filtra sin tildes ni mayúsculas, anuncia los resultados y «Sin resultados»', async () => {
    const { f, combo } = await montar(true);
    expect(combo.readOnly).toBe(false);
    expect(combo.getAttribute('aria-autocomplete')).toBe('list');
    combo.value = 'aer';
    combo.dispatchEvent(new Event('input'));
    await f.whenStable();
    expect(opciones().map((o) => o.textContent!.trim())).toEqual(['Fuerza Aérea']);
    expect(estado()).toBe('1 resultado');
    expect(combo.getAttribute('aria-activedescendant')).toBe(opciones()[0]!.id);
    combo.value = 'zzz';
    combo.dispatchEvent(new Event('input'));
    await f.whenStable();
    expect(opciones()).toHaveLength(0);
    expect(estado()).toBe('Sin resultados');
    expect(combo.hasAttribute('aria-activedescendant')).toBe(false);
    await tecla(f, combo, 'Escape');
    combo.dispatchEvent(new Event('blur'));
    await f.whenStable();
    expect(combo.value).toBe(''); // lo escrito sin elegir se descarta
  });

  it('clic en una opción la elige; «Borrar la selección» la quita y deja el foco en el campo', async () => {
    const { f, combo } = await montar();
    combo.click();
    await f.whenStable();
    opciones()[2]!.click();
    await f.whenStable();
    expect(f.componentInstance.valor()).toBe('FAC');
    const borrar = document.querySelector<HTMLButtonElement>('button[aria-label="Borrar la selección de Fuerza (Sanidad)"]')!;
    borrar.click();
    await f.whenStable();
    expect(f.componentInstance.valor()).toBeNull();
    expect(document.activeElement).toBe(combo);
  });

  it('con error del campo: aria-invalid y aria-describedby al mensaje', async () => {
    const { f, combo } = await montar();
    f.componentInstance.error.set('Elija la fuerza.');
    await f.whenStable();
    expect(combo.getAttribute('aria-invalid')).toBe('true');
    expect(document.getElementById(combo.getAttribute('aria-describedby')!)!.textContent).toBe('Elija la fuerza.');
  });

  it('APG select-only: Espacio abre y elige; Inicio con la lista cerrada abre en la primera; Alt+↑ elige y cierra', async () => {
    const { f, combo } = await montar();
    await tecla(f, combo, ' ');
    expect(combo.getAttribute('aria-expanded')).toBe('true');
    await tecla(f, combo, 'ArrowDown');
    await tecla(f, combo, ' ');
    expect(f.componentInstance.valor()).toBe('ARC');
    expect(combo.getAttribute('aria-expanded')).toBe('false');
    await tecla(f, combo, 'Home');
    expect(combo.getAttribute('aria-activedescendant')).toBe(opciones()[0]!.id);
    await tecla(f, combo, 'ArrowDown');
    await tecla(f, combo, 'ArrowDown');
    await tecla(f, combo, 'ArrowUp', { altKey: true });
    expect(f.componentInstance.valor()).toBe('FAC');
    expect(combo.getAttribute('aria-expanded')).toBe('false');
  });

  it('búsqueda por letras: lo tecleado en 500 ms se acumula; repetir la letra recorre las que empiezan por ella', async () => {
    const { f, combo } = await montar();
    f.componentInstance.opciones = [
      { valor: 'a1', etiqueta: 'Amazonas' },
      { valor: 'a2', etiqueta: 'Antioquia' },
      { valor: 'a3', etiqueta: 'Arauca' },
    ];
    f.componentRef.changeDetectorRef.markForCheck();
    await f.whenStable();
    await tecla(f, combo, 'a');
    await tecla(f, combo, 'r');
    expect(document.getElementById(combo.getAttribute('aria-activedescendant')!)!.textContent!.trim()).toBe('Arauca');
    await tecla(f, combo, 'Escape');
    await new Promise((r) => setTimeout(r, 550));
    await tecla(f, combo, 'a');
    await tecla(f, combo, 'a');
    expect(document.getElementById(combo.getAttribute('aria-activedescendant')!)!.textContent!.trim()).toBe('Antioquia');
  });

  it('con búsqueda, el clic en el campo con la lista abierta no la cierra ni borra lo escrito', async () => {
    const { f, combo } = await montar(true);
    combo.value = 'ar';
    combo.dispatchEvent(new Event('input'));
    await f.whenStable();
    combo.click();
    await f.whenStable();
    expect(combo.getAttribute('aria-expanded')).toBe('true');
    expect(combo.value).toBe('ar');
  });

  it('deshabilitado: no abre', async () => {
    const f = TestBed.createComponent(Deshabilitado);
    await f.whenStable();
    const combo = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('[role="combobox"]')!;
    expect(combo.disabled).toBe(true);
    combo.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await f.whenStable();
    expect(combo.getAttribute('aria-expanded')).toBe('false');
  });
});
