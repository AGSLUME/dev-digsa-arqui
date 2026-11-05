import { Dialog } from '@angular/cdk/dialog';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Boton } from './boton';
import { CuerpoModal, Modal } from './modal';

let creados = 0;

@Component({
  selector: 'lume-prueba-cuerpo',
  template: `<input aria-label="Contraseña actual" id="actual" /><button lumeBoton type="button">Cambiar contraseña</button>`,
  imports: [Boton],
})
class Cuerpo {
  constructor() {
    creados++;
  }
}

@Component({
  imports: [Modal, CuerpoModal, Cuerpo],
  template: `
    <button type="button" id="abrir" (click)="abierto.set(true)">Mi contraseña</button>
    <lume-modal [abierto]="abierto()" titulo="Cambiar mi contraseña" (cerrar)="cerradas = cerradas + 1; abierto.set(false)">
      <ng-template lumeCuerpoModal><lume-prueba-cuerpo /></ng-template>
    </lume-modal>
  `,
})
class Prueba {
  abierto = signal(false);
  cerradas = 0;
}

const dialogo = () => document.querySelector<HTMLElement>('[role="dialog"]');

async function montar() {
  const f = TestBed.createComponent(Prueba);
  document.body.appendChild(f.nativeElement);
  await f.whenStable();
  return f;
}

beforeEach(() => (creados = 0));
afterEach(() => document.querySelectorAll('lume-prueba-cuerpo, [ng-version]').forEach(() => undefined));

describe('lume-modal (CDK Dialog)', () => {
  it('abre con role=dialog y el título como nombre, foco en el primer control; Escape avisa y el foco vuelve', async () => {
    const f = await montar();
    expect(dialogo()).toBeNull();
    expect(creados).toBe(0); // el cuerpo no existe con el modal cerrado

    const abrir = document.getElementById('abrir') as HTMLButtonElement;
    abrir.focus();
    abrir.click();
    await f.whenStable();
    const d = dialogo()!;
    expect(d.getAttribute('aria-modal')).toBe('true');
    const titulo = document.getElementById(d.getAttribute('aria-labelledby')!)!;
    expect(titulo.tagName).toBe('H2');
    expect(titulo.textContent).toBe('Cambiar mi contraseña');
    expect(d.closest('.lume-modal-panel')).not.toBeNull();
    await vi.waitFor(() => expect(document.activeElement?.id).toBe('actual'));

    d.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await f.whenStable();
    expect(f.componentInstance.cerradas).toBe(1);
    await vi.waitFor(() => expect(dialogo()).toBeNull());
    await vi.waitFor(() => expect(document.activeElement).toBe(abrir));
    f.nativeElement.remove();
  });

  it('la «×» tiene nombre accesible y avisa el cierre', async () => {
    const f = await montar();
    f.componentInstance.abierto.set(true);
    await f.whenStable();
    dialogo()!.querySelector<HTMLButtonElement>('button[aria-label="Cerrar"]')!.click();
    await f.whenStable();
    expect(f.componentInstance.cerradas).toBe(1);
    expect(f.componentInstance.abierto()).toBe(false);
    f.nativeElement.remove();
  });

  it('el cuerpo se crea de nuevo en cada apertura (el formulario empieza limpio)', async () => {
    const f = await montar();
    for (let i = 0; i < 2; i++) {
      f.componentInstance.abierto.set(true);
      await f.whenStable();
      f.componentInstance.abierto.set(false);
      await f.whenStable();
    }
    expect(creados).toBe(2);
    expect(f.componentInstance.cerradas).toBe(0); // la pantalla lo cerró: no hay aviso
    f.nativeElement.remove();
  });

  it('si el CDK lo cierra por su cuenta, avisa y se puede volver a abrir', async () => {
    const f = await montar();
    f.componentInstance.abierto.set(true);
    await f.whenStable();
    TestBed.inject(Dialog).closeAll();
    await f.whenStable();
    expect(f.componentInstance.cerradas).toBe(1);
    expect(f.componentInstance.abierto()).toBe(false);
    f.componentInstance.abierto.set(true);
    await f.whenStable();
    expect(dialogo()).not.toBeNull();
    f.nativeElement.remove();
  });

  it('al destruirse la pantalla con el modal abierto no queda el diálogo', async () => {
    const f = await montar();
    f.componentInstance.abierto.set(true);
    await f.whenStable();
    expect(dialogo()).not.toBeNull();
    f.destroy();
    await vi.waitFor(() => expect(dialogo()).toBeNull());
  });
});
