import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FORMULARIO_LUME } from './index';

@Component({
  imports: [...FORMULARIO_LUME],
  template: `
    <lume-campo idControl="usuario" etiqueta="Usuario" descripcion="Como lo asignó el administrador." [error]="error()">
      <input lumeEntrada name="usuario" />
    </lume-campo>
    <lume-campo etiqueta="Contraseña" clave><input lumeEntrada name="clave" /></lume-campo>
    <button lumeBoton type="button" [cargando]="cargando()" (click)="clics = clics + 1">Ingresar</button>
    <button lumeBoton="secundario" type="button" disabled [cargando]="cargando()">Cancelar</button>
    <a lumeBoton="outline" bloque href="/olvide-clave">¿Olvidó su contraseña?</a>
  `,
})
class Prueba {
  error = signal<string | true | undefined>(undefined);
  cargando = signal(false);
  clics = 0;
}

async function montar() {
  const f = TestBed.createComponent(Prueba);
  await f.whenStable();
  return { f, el: f.nativeElement as HTMLElement };
}

describe('lume-campo y lumeEntrada', () => {
  it('id fijo del campo, descripción y error juntos en aria-describedby', async () => {
    const { f, el } = await montar();
    const usuario = el.querySelector<HTMLInputElement>('input[name="usuario"]')!;
    expect(usuario.id).toBe('usuario');
    // El id es solo del control: si quedara también en <lume-campo> habría dos elementos con el mismo id
    expect(el.querySelectorAll('#usuario')).toHaveLength(1);
    expect(el.querySelector('lume-campo')!.hasAttribute('id')).toBe(false);
    expect(usuario.getAttribute('aria-describedby')).toBe('usuario-descripcion');
    f.componentInstance.error.set('Escriba el usuario.');
    await f.whenStable();
    expect(usuario.getAttribute('aria-invalid')).toBe('true');
    expect(usuario.getAttribute('aria-describedby')).toBe('usuario-error usuario-descripcion');
    expect(el.querySelector('#usuario-error')!.textContent).toBe('Escriba el usuario.');
  });

  it('error=true solo marca el campo (el mensaje del servidor ya está en el resumen)', async () => {
    const { f, el } = await montar();
    f.componentInstance.error.set(true);
    await f.whenStable();
    const usuario = el.querySelector<HTMLInputElement>('input[name="usuario"]')!;
    expect(usuario.getAttribute('aria-invalid')).toBe('true');
    expect(el.querySelector('#usuario-error')).toBeNull();
    expect(usuario.getAttribute('aria-describedby')).toBe('usuario-descripcion');
  });

  it('un campo de contraseña empieza oculto aunque la plantilla no diga type=password', async () => {
    const { el } = await montar();
    expect(el.querySelector<HTMLInputElement>('input[name="clave"]')!.type).toBe('password');
  });
});

describe('lumeBoton: cargando, bloque y enlace', () => {
  it('cargando no actúa (sin doble envío) ni pierde el foco: aria-disabled y aria-busy, sin disabled; al terminar vuelve como estaba', async () => {
    const { f, el } = await montar();
    const [ingresar, cancelar] = el.querySelectorAll<HTMLButtonElement>('button.lume-boton');
    ingresar!.focus();
    f.componentInstance.cargando.set(true);
    await f.whenStable();
    expect(ingresar!.disabled).toBe(false);
    expect(document.activeElement).toBe(ingresar);
    expect(ingresar!.getAttribute('aria-disabled')).toBe('true');
    expect(ingresar!.getAttribute('aria-busy')).toBe('true');
    expect(ingresar!.hasAttribute('data-loading')).toBe(true);
    expect(ingresar!.textContent).toBe('Ingresar'); // sigue siendo su nombre accesible
    ingresar!.click();
    expect(f.componentInstance.clics).toBe(0);
    f.componentInstance.cargando.set(false);
    await f.whenStable();
    expect(ingresar!.hasAttribute('aria-disabled')).toBe(false);
    expect(ingresar!.hasAttribute('aria-busy')).toBe(false);
    expect(cancelar!.disabled).toBe(true); // ya estaba deshabilitado
    ingresar!.click();
    expect(f.componentInstance.clics).toBe(1);
  });

  it('cargando en el botón de envío: el clic (también el implícito de Enter) no vuelve a enviar el formulario', async () => {
    @Component({
      imports: [...FORMULARIO_LUME],
      template: `<form (submit)="$event.preventDefault(); envios = envios + 1"><input name="x" /><button lumeBoton type="submit" [cargando]="cargando()">Guardar</button></form>`,
    })
    class ConEnvio {
      cargando = signal(true);
      envios = 0;
    }
    const f = TestBed.createComponent(ConEnvio);
    await f.whenStable();
    const boton = (f.nativeElement as HTMLElement).querySelector('button')!;
    boton.click();
    expect(f.componentInstance.envios).toBe(0);
    f.componentInstance.cargando.set(false);
    await f.whenStable();
    boton.click();
    expect(f.componentInstance.envios).toBe(1);
  });

  it('sobre un enlace conserva el elemento y su href; bloque ocupa el ancho', async () => {
    const { el } = await montar();
    const a = el.querySelector<HTMLAnchorElement>('a.lume-boton')!;
    expect(a.getAttribute('href')).toBe('/olvide-clave');
    expect(a.classList).toContain('lume-boton--outline');
    expect(a.classList).toContain('lume-boton--bloque');
  });
});
