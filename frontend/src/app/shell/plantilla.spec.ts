import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { interceptorApi } from '../nucleo/interceptor-api';
import { NAVEGADOR, RUTAS_BFF, Sesion, type DatosSesion } from '../nucleo/sesion';
import { Plantilla } from './plantilla';
import { proveerClientesApi } from '../nucleo/proveedores';

@Component({ template: '<h1>Usuarios</h1>' })
class Pantalla {}

const asignar = vi.fn();
const admin: DatosSesion = {
  id: 1,
  usuario: 'admin.angular',
  nombre: 'Administradora Ficticia',
  perfiles: ['Administrador'],
  menu: [
    { ruta: 'usuarios', titulo: 'Usuarios', grupo: 'Configuración' },
    { ruta: 'logs', titulo: 'Logs del sistema', grupo: 'Configuración' },
    { ruta: 'alertas', titulo: 'Alertas', grupo: 'Configuración' },
    { ruta: 'agenda', titulo: 'Agenda de visitas', grupo: 'Visitas' },
  ],
  pantallas: ['inicio', 'usuarios', 'logs', 'alertas', 'agenda'],
  minutosInactividad: 10,
};

async function montar(datos: DatosSesion = admin, url = '/usuarios') {
  localStorage.clear();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([interceptorApi])),
      provideHttpClientTesting(),
      ...proveerClientesApi(),
      { provide: NAVEGADOR, useValue: { asignar, recargar: vi.fn() } },
      // La prueba crea el shell; el router solo pinta la pantalla dentro de él
      provideRouter([{ path: '**', component: Pantalla }]),
    ],
  });
  const sesion = TestBed.inject(Sesion);
  const c = sesion.cargar();
  TestBed.inject(HttpTestingController).expectOne('/api/sesion').flush(datos);
  await c;
  await TestBed.inject(Router).navigateByUrl(url);
  const f = TestBed.createComponent(Plantilla);
  document.body.appendChild(f.nativeElement);
  await f.whenStable();
  return { f, el: f.nativeElement as HTMLElement };
}

afterEach(() => {
  document.querySelectorAll('lume-plantilla').forEach((n) => n.remove());
  asignar.mockClear();
});

const texto = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim();

describe('shell LUME (Plantilla)', () => {
  it('menú del servidor agrupado por módulos, con Inicio arriba y «Mi cuenta › Mi contraseña» al pie', async () => {
    const { el } = await montar();
    const nav = el.querySelector('nav[aria-label="Módulos"]')!;
    expect(nav.id).toBe('menu-lateral');
    expect([...nav.querySelectorAll('.lume-seccion')].map(texto)).toEqual(['Visitas', 'Seguridad', 'Administración', 'Mi cuenta']);
    const enlaces = [...nav.querySelectorAll('a.lume-nav')].map((a) => [texto(a.querySelector('.lume-nav__titulo')), a.getAttribute('href')]);
    expect(enlaces).toEqual([
      ['Inicio', '/'],
      ['Agenda de visitas', '/agenda'],
      ['Usuarios', '/usuarios'],
      ['Logs del sistema', '/logs'],
      ['Alertas', '/alertas'],
    ]);
    // La entrada activa lleva aria-current; el numeral del Anexo 5 está oculto para el lector
    const activa = nav.querySelector('a[aria-current="page"]')!;
    expect(texto(activa.querySelector('.lume-nav__titulo'))).toBe('Usuarios');
    expect(activa.querySelector('.lume-nav-numeral')!.getAttribute('aria-hidden')).toBe('true');
    expect(texto(nav.querySelector('button.lume-nav'))).toBe('Mi contraseña');
  });

  it('cabecera: migas con aria-current, usuario y perfiles, campana solo con Alertas, sonido y Salir', async () => {
    const { el } = await montar();
    const migas = el.querySelector('nav[aria-label="Ruta"]')!;
    expect([...migas.querySelectorAll('li')].map(texto)).toEqual(['Seguridad', 'Usuarios']);
    expect(migas.querySelector('li:last-child')!.getAttribute('aria-current')).toBe('page');
    expect(texto(el.querySelector('[data-testid="nombre-usuario"]'))).toBe('Administradora Ficticia');
    expect(texto(el.querySelector('.lume-avatar'))).toBe('AF');
    expect(el.querySelector('a[aria-label="Ir a Alertas"]')!.getAttribute('href')).toBe('/alertas');
    expect(texto(el.querySelector('[data-testid="reloj-cabecera"]'))).toMatch(/^\d{2}\/\d{2}\/\d{4} \d{2}:\d{2} h$/);
    const sonido = el.querySelector<HTMLButtonElement>('[data-testid="interruptor-sonido"]')!;
    expect(sonido.getAttribute('aria-label')).toBe('Sonido de las alertas');
    expect(sonido.getAttribute('aria-pressed')).toBe('true');
    // El primer encabezado de la página es el h1 de la pantalla: la marca no es un encabezado
    expect(el.querySelector('h1, h2, h3')!.textContent).toBe('Usuarios');
  });

  it('sin la pantalla Alertas no hay campana', async () => {
    const { el } = await montar({ ...admin, menu: admin.menu.filter((m) => m.ruta !== 'alertas'), pantallas: ['inicio', 'usuarios'] });
    expect(el.querySelector('a[aria-label="Ir a Alertas"]')).toBeNull();
  });

  it('«Menú lateral» colapsa a solo íconos (nombre en aria-label) y el navegador lo recuerda', async () => {
    const { f, el } = await montar();
    const boton = el.querySelector<HTMLButtonElement>('button[aria-label="Menú lateral"]')!;
    expect(boton.getAttribute('aria-expanded')).toBe('true');
    boton.click();
    await f.whenStable();
    expect(boton.getAttribute('aria-expanded')).toBe('false');
    expect(el.querySelector('.lume-nav__titulo')).toBeNull();
    expect(el.querySelector('a.lume-nav[href="/usuarios"]')!.getAttribute('aria-label')).toBe('Usuarios');
    expect(localStorage.getItem('lume.sidebar.colapsado')).toBe('si');
  });

  it('«Mi contraseña» abre el modal «Cambiar mi contraseña»', async () => {
    const { f, el } = await montar();
    el.querySelector<HTMLButtonElement>('button.lume-nav')!.click();
    await f.whenStable();
    const d = document.querySelector('[role="dialog"]')!;
    expect(document.getElementById(d.getAttribute('aria-labelledby')!)!.textContent).toBe('Cambiar mi contraseña');
    expect(d.querySelector('input[autocomplete="current-password"]')).not.toBeNull();
  });

  it('«Salir» llama a /bff/salir y va al ingreso por el gateway', async () => {
    const { el } = await montar();
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => texto(b) === 'Salir')!.click();
    const ctrl = TestBed.inject(HttpTestingController);
    // Sin cookie CSRF, primero /api/auth/inicio (la deja lista)
    (await vi.waitFor(() => ctrl.expectOne('/api/auth/inicio'))).flush({});
    const r = await vi.waitFor(() => ctrl.expectOne(RUTAS_BFF.salir));
    expect(r.request.method).toBe('POST');
    r.flush(null, { status: 204, statusText: 'x' });
    await vi.waitFor(() => expect(asignar).toHaveBeenCalledWith(RUTAS_BFF.autorizacion));
  });
});
