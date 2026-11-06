import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { interceptorApi } from '../../nucleo/interceptor-api';
import { proveerClientesApi } from '../../nucleo/proveedores';
import { Sonido } from '../../ui/sonido';
import { DefinirClave } from './claves';

const TKN = 'a'.repeat(64);

describe('Definir contraseña (DefinirClave de Claves.tsx)', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([interceptorApi])),
        provideHttpClientTesting(),
        ...proveerClientesApi(),
        provideRouter([{ path: 'definir-clave', component: DefinirClave }], withComponentInputBinding()),
        { provide: Sonido, useValue: { sonarError: vi.fn() } },
      ],
    });
  });

  it('el token sale de la dirección (solo queda en memoria) y se usa para validar y para guardar', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/definir-clave?tkn=${TKN}`);
    const ctrl = TestBed.inject(HttpTestingController);
    // Contrato 0.4.0: POST con el token en el cuerpo (nunca en la URL de la petición)
    const consulta = await vi.waitFor(() => ctrl.expectOne((r) => r.url === '/api/auth/clave/consultar' && r.method === 'POST'));
    expect(consulta.request.body).toEqual({ tkn: TKN });
    expect(consulta.request.urlWithParams).not.toContain(TKN);
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/definir-clave'));
    consulta.flush({ valido: true, nombre: 'Usuario Ficticio' });
    const el = harness.routeNativeElement!;
    await vi.waitFor(() => expect(el.textContent).toContain('Hola Usuario Ficticio. Defina su contraseña:'));

    const [clave, confirmacion] = el.querySelectorAll<HTMLInputElement>('input');
    for (const c of [clave!, confirmacion!]) {
      c.value = 'Propia.Del.Usuario.2026';
      c.dispatchEvent(new Event('input'));
    }
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Guardar contraseña')!.click();
    const guardar = await vi.waitFor(() => ctrl.expectOne((r) => r.url === '/api/auth/clave' && r.method === 'POST'));
    expect(guardar.request.body).toEqual({ tkn: TKN, clave: 'Propia.Del.Usuario.2026', confirmacion: 'Propia.Del.Usuario.2026' });
    guardar.flush({ mensaje: 'La contraseña se cambió correctamente. Inicie sesión con su nueva contraseña.' });
    await vi.waitFor(() => expect(el.querySelector('[data-testid="clave-ok"]')?.textContent).toContain('La contraseña se cambió correctamente'));
  });
});
