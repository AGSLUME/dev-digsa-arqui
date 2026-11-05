import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { conSesion, pantalla } from './guardas';
import { interceptorApi } from './interceptor-api';
import { NAVEGADOR, RUTAS_BFF, Sesion, type DatosSesion } from './sesion';
import { proveerClientesApi } from './proveedores';

@Component({ template: 'pantalla' })
class Pantalla {}
@Component({ template: 'Acceso restringido.' })
class Restringida {}

const asignar = vi.fn();
const datos: DatosSesion = {
  id: 7,
  usuario: 'auditor.uno',
  nombre: 'Auditor Uno',
  perfiles: ['Auditor'],
  menu: [{ ruta: 'mis-visitas', titulo: 'Mis visitas', grupo: 'Visitas' }],
  pantallas: ['inicio', 'mis-visitas', 'visita'],
  minutosInactividad: 10,
};

beforeEach(() => {
  asignar.mockClear();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([interceptorApi])),
      provideHttpClientTesting(),
      ...proveerClientesApi(),
      { provide: NAVEGADOR, useValue: { asignar, recargar: vi.fn() } },
      provideRouter([
        { path: 'sin-conexion', component: Pantalla },
        {
          path: '',
          canMatch: [conSesion],
          children: [
            { path: 'usuarios', canMatch: [pantalla('usuarios')], component: Pantalla },
            { path: 'visita/:id', canMatch: [pantalla('visita')], component: Pantalla },
            { path: '**', component: Restringida },
          ],
        },
        { path: '**', component: Pantalla },
      ]),
    ],
  });
});

const ctrl = () => TestBed.inject(HttpTestingController);
/** La navegación llega a la guarda en otra vuelta: se espera a que salga la petición. */
const sesionPedida = () => vi.waitFor(() => ctrl().expectOne('/api/sesion'));

describe('guardas', () => {
  it('carga directa (F5) de una pantalla permitida: espera la sesión y la muestra, no «Acceso restringido.»', async () => {
    const harness = await RouterTestingHarness.create();
    const nav = harness.navigateByUrl('/usuarios');
    (await sesionPedida()).flush({ ...datos, pantallas: [...datos.pantallas, 'usuarios'] });
    await nav;
    expect(harness.routeNativeElement?.textContent).toBe('pantalla');
    ctrl().expectNone('/api/sesion'); // una sola petición: la del canMatch del shell
  });

  it('carga directa de una pantalla que el perfil no tiene: «Acceso restringido.»', async () => {
    const harness = await RouterTestingHarness.create();
    const nav = harness.navigateByUrl('/usuarios');
    (await sesionPedida()).flush(datos);
    await nav;
    expect(harness.routeNativeElement?.textContent).toBe('Acceso restringido.');
  });

  it('sin sesión (401) va al ingreso por el gateway con página completa', async () => {
    const router = TestBed.inject(Router);
    const nav = router.navigateByUrl('/usuarios');
    (await sesionPedida())
      .flush({ mensaje: 'La sesión expiró. Vuelva a iniciar sesión.', motivo: 'sin_sesion' }, { status: 401, statusText: 'x' });
    await nav;
    expect(asignar).toHaveBeenCalledWith(RUTAS_BFF.autorizacion);
    ctrl().expectNone('/api/sesion'); // no se repite la petición
  });

  it('sin red: a «sin conexión» recordando adónde iba', async () => {
    const router = TestBed.inject(Router);
    const nav = router.navigateByUrl('/usuarios');
    (await sesionPedida()).error(new ProgressEvent('error'));
    await nav;
    expect(router.url).toBe('/sin-conexion?volver=%2Fusuarios');
    expect(asignar).not.toHaveBeenCalled();
  });

  it('con sesión: entra a lo permitido (también a una pantalla oculta del menú) y lo demás cae en el comodín', async () => {
    const router = TestBed.inject(Router);
    const nav = router.navigateByUrl('/visita/15');
    (await sesionPedida()).flush(datos);
    expect(await nav).toBe(true);
    expect(router.url).toBe('/visita/15');
    expect(TestBed.inject(Sesion).puedeEntrar('usuarios')).toBe(false);
    await router.navigateByUrl('/usuarios');
    expect(router.url).toBe('/usuarios'); // la dirección se conserva, como en el monolito
    ctrl().expectNone('/api/sesion'); // la sesión se pidió una sola vez
  });
});
