import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CLAVE_MOTIVO } from '../../nucleo/actividad';
import { interceptorApi } from '../../nucleo/interceptor-api';
import { NAVEGADOR, RUTAS_BFF } from '../../nucleo/sesion';
import { Sonido } from '../../ui/sonido';
import { destinoSeguro, Ingreso } from './ingreso';
import { proveerClientesApi } from '../../nucleo/proveedores';

const asignar = vi.fn();

async function montar() {
  document.cookie = 'XSRF-TOKEN=t; path=/';
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([interceptorApi])),
      provideHttpClientTesting(),
      ...proveerClientesApi(),
      provideRouter([]),
      { provide: NAVEGADOR, useValue: { asignar, recargar: vi.fn() } },
      { provide: Sonido, useValue: { sonarError: vi.fn(), activo: () => true, activar: vi.fn() } },
    ],
  });
  const f = TestBed.createComponent(Ingreso);
  document.body.appendChild(f.nativeElement);
  await f.whenStable();
  const ctrl = TestBed.inject(HttpTestingController);
  // El captcha pide la configuración del ingreso al aparecer
  (await vi.waitFor(() => ctrl.expectOne('/api/auth/inicio'))).flush({ captchaSitio: null, captchaDesactivado: true, csrf: 'X-XSRF-TOKEN' });
  await f.whenStable();
  return { f, el: f.nativeElement as HTMLElement, ctrl };
}

const campo = (el: HTMLElement, etiqueta: string) => {
  const l = [...el.querySelectorAll('label')].find((x) => x.textContent?.replace('*', '').trim() === etiqueta)!;
  return el.querySelector<HTMLInputElement>(`#${l.htmlFor}`)!;
};
const escribir = (c: HTMLInputElement, v: string) => {
  c.value = v;
  c.dispatchEvent(new Event('input'));
};
const boton = (el: HTMLElement, texto: string) => [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === texto)!;

beforeEach(() => {
  asignar.mockClear();
  sessionStorage.clear();
});
afterEach(() => document.querySelectorAll('lume-ingreso').forEach((n) => n.remove()));

describe('Ingreso (Ingreso.tsx del monolito, ADR-014)', () => {
  it('muestra una vez el motivo del último cierre de sesión', async () => {
    sessionStorage.setItem(CLAVE_MOTIVO, 'La sesión se cerró por inactividad. Vuelva a iniciar sesión.');
    const { el } = await montar();
    expect(el.querySelector('[data-testid="motivo-cierre"]')!.textContent).toContain('La sesión se cerró por inactividad');
    expect(sessionStorage.getItem(CLAVE_MOTIVO)).toBeNull();
    expect(el.querySelector('h1')!.textContent).toBe('Interventoría Integral DIGSA');
    expect(el.querySelector('[data-testid="aviso-codigo"]')!.textContent).toContain('le enviaremos un código de verificación');
  });

  it('sin usuario ni contraseña: los marca sin llamar al servidor', async () => {
    const { f, el, ctrl } = await montar();
    boton(el, 'Ingresar').click();
    await f.whenStable();
    expect(el.querySelector('[data-testid="error-ingreso"]')!.textContent).toContain('Revise los 2 campos marcados');
    expect(campo(el, 'Usuario').getAttribute('aria-invalid')).toBe('true');
    ctrl.expectNone('/api/auth/ingreso');
  });

  it('paso 1 → código → navega a `continuar` (página completa)', async () => {
    const { f, el, ctrl } = await montar();
    escribir(campo(el, 'Usuario'), 'admin.angular');
    escribir(campo(el, 'Contraseña'), 'Clave.De.Prueba.2026');
    boton(el, 'Ingresar').click();
    const r1 = await vi.waitFor(() => ctrl.expectOne('/api/auth/ingreso'));
    expect(r1.request.body).toEqual({ usuario: 'admin.angular', clave: 'Clave.De.Prueba.2026', captcha: null });
    r1.flush({ paso: 'codigo', mensaje: 'Le enviamos un código de 6 dígitos a su correo.', correo: 'a***r@angular.prueba.local', enlaceLocal: null });
    await f.whenStable();
    expect(el.textContent).toContain('Le enviamos un código de 6 dígitos a su correo. Lo enviamos a a***r@angular.prueba.local. Vence en 15 minutos.');
    const casillas = el.querySelectorAll<HTMLInputElement>('[data-testid="codigo"] input');
    '453870'.split('').forEach((d, i) => escribir(casillas[i]!, d));
    await f.whenStable();
    boton(el, 'Verificar e ingresar').click();
    const r2 = await vi.waitFor(() => ctrl.expectOne('/api/auth/codigo'));
    expect(r2.request.body).toEqual({ codigo: '453870' });
    r2.flush({ usuario: 'admin.angular', nombre: 'A', continuar: '/oauth2/authorize?response_type=code&state=x' });
    await vi.waitFor(() => expect(asignar).toHaveBeenCalledWith('/oauth2/authorize?response_type=code&state=x'));
  });

  it('401 al verificar (la verificación venció): vuelve al paso 1 con el mensaje', async () => {
    const { f, el, ctrl } = await montar();
    escribir(campo(el, 'Usuario'), 'admin.angular');
    escribir(campo(el, 'Contraseña'), 'x');
    boton(el, 'Ingresar').click();
    (await vi.waitFor(() => ctrl.expectOne('/api/auth/ingreso'))).flush({ paso: 'codigo', mensaje: 'm', correo: 'c' });
    await f.whenStable();
    const casillas = el.querySelectorAll<HTMLInputElement>('[data-testid="codigo"] input');
    '123456'.split('').forEach((d, i) => escribir(casillas[i]!, d));
    boton(el, 'Verificar e ingresar').click();
    const m = 'La verificación venció. Ingrese de nuevo con su usuario y contraseña.';
    (await vi.waitFor(() => ctrl.expectOne('/api/auth/codigo'))).flush({ mensaje: m, motivo: 'mfa_vencido' }, { status: 401, statusText: 'x' });
    await f.whenStable();
    expect(el.querySelector('[data-testid="error-ingreso"]')!.textContent).toContain(m);
    expect(boton(el, 'Ingresar')).toBeTruthy();
    expect(asignar).not.toHaveBeenCalled();
  });

  it('contraseña inicial: el 422 de «clave» marca «Contraseña nueva»', async () => {
    const { f, el, ctrl } = await montar();
    escribir(campo(el, 'Usuario'), 'inicial.angular');
    escribir(campo(el, 'Contraseña'), 'x');
    boton(el, 'Ingresar').click();
    (await vi.waitFor(() => ctrl.expectOne('/api/auth/ingreso'))).flush({ paso: 'cambiar_clave', mensaje: 'Es su primer ingreso…', correo: 'c' });
    await f.whenStable();
    escribir(campo(el, 'Contraseña nueva'), 'Propia.Del.Usuario.2026');
    escribir(campo(el, 'Confirme la contraseña nueva'), 'Propia.Del.Usuario.2026');
    boton(el, 'Cambiar contraseña y continuar').click();
    const r = await vi.waitFor(() => ctrl.expectOne('/api/auth/cambiar-clave'));
    expect(r.request.body).toEqual({ clave: 'Propia.Del.Usuario.2026', confirmacion: 'Propia.Del.Usuario.2026' });
    r.flush({ mensaje: 'La contraseña debe tener al menos un número.', campo: 'clave' }, { status: 422, statusText: 'x' });
    await f.whenStable();
    expect(campo(el, 'Contraseña nueva').getAttribute('aria-invalid')).toBe('true');
    expect(el.querySelector('[data-testid="error-ingreso"]')!.textContent).toContain('Revise el campo marcado: Contraseña nueva');
  });
});

describe('destinoSeguro (continuar nunca saca de LUME)', () => {
  it('solo rutas del mismo origen; lo demás va a la autorización del gateway', () => {
    expect(destinoSeguro('/oauth2/authorize?x=1')).toBe('/oauth2/authorize?x=1');
    expect(destinoSeguro('/bff/oauth2/authorization/lume')).toBe('/bff/oauth2/authorization/lume');
    // Otra ruta del mismo origen tampoco: continuar solo termina la autorización
    expect(destinoSeguro('/usuarios')).toBe(RUTAS_BFF.autorizacion);
    for (const malo of ['https://otro.example/x', '//otro.example', '/\\otro.example', 'javascript:alert(1)', '', null, 3]) {
      expect(destinoSeguro(malo)).toBe(RUTAS_BFF.autorizacion);
    }
  });
});
