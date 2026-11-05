import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { interceptorApi } from '../nucleo/interceptor-api';
import { proveerClientesApi } from '../nucleo/proveedores';
import { NAVEGADOR, Sesion } from '../nucleo/sesion';
import { SinAcceso, SinConexion } from './estados';

@Component({ template: 'inicio' })
class Inicio {}

function configurar() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([interceptorApi])),
      provideHttpClientTesting(),
      ...proveerClientesApi(),
      { provide: NAVEGADOR, useValue: { asignar: vi.fn(), recargar: vi.fn() } },
      provideRouter([
        { path: 'sin-conexion', component: SinConexion },
        { path: '', pathMatch: 'full', component: Inicio },
        { path: '**', component: SinAcceso },
      ]),
    ],
  });
}

describe('Sesion.cargar', () => {
  it('llamadas simultáneas hacen una sola petición y comparten el resultado', async () => {
    configurar();
    const s = TestBed.inject(Sesion);
    const [a, b] = [s.cargar(), s.cargar()];
    TestBed.inject(HttpTestingController)
      .expectOne('/api/sesion')
      .flush({ id: 1, usuario: 'u', nombre: 'U', perfiles: [], menu: [], pantallas: [], minutosInactividad: 10 });
    expect((await a).id).toBe(1);
    expect(await b).toBe(await a);
  });
});

describe('Sin conexión', () => {
  it.each([
    ['/agenda?x=1', '/agenda?x=1'],
    ['/\\otro.example', '/'],
    ['//otro.example', '/'],
    ['https://otro.example', '/'],
  ])('«Reintentar» con volver=%j va a %j, siempre dentro de la aplicación', async (volver, destino) => {
    configurar();
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/sin-conexion?volver=${encodeURIComponent(volver)}`);
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    [...harness.routeNativeElement!.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Reintentar')!.click();
    expect(navegar).toHaveBeenCalledWith(destino);
  });
});

describe('comodín del shell', () => {
  it('una pantalla del menú que aún no está portada muestra su título; una dirección desconocida vuelve al inicio', async () => {
    configurar();
    const s = TestBed.inject(Sesion);
    const c = s.cargar();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/sesion')
      .flush({ id: 1, usuario: 'u', nombre: 'U', perfiles: [], menu: [{ ruta: 'agenda', titulo: 'Agenda de visitas', grupo: 'Visitas' }], pantallas: ['agenda'], minutosInactividad: 10 });
    await c;
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/agenda');
    expect(harness.routeNativeElement!.querySelector('h1')!.textContent).toBe('Agenda de visitas');
    expect(harness.routeNativeElement!.textContent).toContain('Esta pantalla todavía no está disponible en la versión nueva.');
    await harness.navigateByUrl('/no-existe');
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/'));
  });
});
