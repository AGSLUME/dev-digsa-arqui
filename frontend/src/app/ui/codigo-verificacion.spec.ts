import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CodigoVerificacion } from './codigo-verificacion';

@Component({
  imports: [CodigoVerificacion],
  template: `<lume-codigo-verificacion data-testid="codigo" [(valor)]="codigo" [error]="error()" />`,
})
class Prueba {
  codigo = signal('');
  error = signal<string | true | undefined>(undefined);
}

const montados: HTMLElement[] = [];
afterEach(() => montados.splice(0).forEach((n) => n.remove()));

async function montar() {
  const f = TestBed.createComponent(Prueba);
  document.body.appendChild(f.nativeElement);
  montados.push(f.nativeElement);
  await f.whenStable();
  const casillas = [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>('[data-testid="codigo"] input')];
  return { f, casillas };
}

function escribir(c: HTMLInputElement, v: string) {
  c.value = v;
  c.dispatchEvent(new Event('input'));
}


describe('lume-codigo-verificacion (PinInput del monolito)', () => {
  it('6 casillas con nombre accesible, en un grupo «Código de verificación»; el código del correo se propone en la primera', async () => {
    const { f, casillas } = await montar();
    const grupo = (f.nativeElement as HTMLElement).querySelector('[data-testid="codigo"]')!;
    expect(grupo.getAttribute('role')).toBe('group');
    expect(grupo.getAttribute('aria-label')).toBe('Código de verificación');
    expect(casillas).toHaveLength(6);
    expect(casillas.map((c) => c.getAttribute('aria-label'))).toEqual([1, 2, 3, 4, 5, 6].map((i) => `Dígito ${i} de 6 del código`));
    expect(casillas[0]!.getAttribute('autocomplete')).toBe('one-time-code');
    expect(casillas[0]!.inputMode).toBe('numeric');
  });

  it('cada dígito avanza a la siguiente casilla; lo que no es dígito no entra', async () => {
    const { f, casillas } = await montar();
    escribir(casillas[0]!, '4');
    expect(document.activeElement).toBe(casillas[1]);
    escribir(casillas[1]!, 'x');
    expect(casillas[1]!.value).toBe('');
    '53870'.split('').forEach((d, i) => escribir(casillas[i + 1]!, d));
    await f.whenStable();
    expect(f.componentInstance.codigo()).toBe('453870');
  });

  it('pegar el código completo llena las 6 casillas; vaciar el valor desde la pantalla las vacía', async () => {
    const { f, casillas } = await montar();
    const pegar = new Event('paste', { cancelable: true }) as ClipboardEvent;
    Object.defineProperty(pegar, 'clipboardData', { value: { getData: () => ' 453 870 ' } });
    casillas[0]!.dispatchEvent(pegar);
    await f.whenStable();
    expect(f.componentInstance.codigo()).toBe('453870');
    expect(casillas.map((c) => c.value).join('')).toBe('453870');
    f.componentInstance.codigo.set('');
    await f.whenStable();
    expect(casillas.map((c) => c.value).join('')).toBe('');
  });

  it('con error las casillas quedan marcadas y unidas al mensaje', async () => {
    const { f, casillas } = await montar();
    f.componentInstance.error.set('Escriba los 6 dígitos del código.');
    await f.whenStable();
    expect(casillas.every((c) => c.getAttribute('aria-invalid') === 'true')).toBe(true);
    expect(casillas[0]!.getAttribute('aria-describedby')).toBe('codigo-error');
  });
});
