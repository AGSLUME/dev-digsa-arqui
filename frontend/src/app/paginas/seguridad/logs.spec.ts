import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, type TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { type FilaLog, type OpcionesLogs } from '../../api/seguridad';
import { hoyBogota, sumarDias } from '../../nucleo/formato';
import { interceptorApi } from '../../nucleo/interceptor-api';
import { proveerClientesApi } from '../../nucleo/proveedores';
import { Avisos } from '../../ui/avisos';
import { Sonido } from '../../ui/sonido';
import { Logs } from './logs';

// Datos ficticios
const OPCIONES: OpcionesLogs = {
  operaciones: ['insercion', 'actualizacion', 'eliminacion', 'consulta', 'ingreso', 'salida'],
  usuarios: [{ id: 1, nombre: 'Administradora Ficticia', usuario: 'admin.angular' }],
  modulos: ['auth', 'usuarios'],
  correoDefecto: 'evaluador@angular.prueba.local',
};
const fila = (id: number, extra: Partial<FilaLog> = {}): FilaLog => ({
  id,
  fecha: '2026-11-05 08:30:15',
  fechaLlave: '2026-11-05T08:30:15.000-05:00',
  usuario: 'admin.angular',
  operacion: 'ingreso',
  tipo: 'login',
  modulo: 'auth',
  descripcion: `Ingreso ${id}`,
  ip: '127.0.0.1',
  cambios: null,
  ...extra,
});

let ctrl: HttpTestingController;
let el: HTMLElement;

const lista = (r: { url: string; method: string }) => r.method === 'GET' && r.url === '/api/logs';
const pedido = (f: (r: { url: string; method: string }) => boolean): Promise<TestRequest> => vi.waitFor(() => ctrl.expectOne(f));
const boton = (raiz: ParentNode, nombre: string) =>
  [...raiz.querySelectorAll<HTMLButtonElement>('button')].find((b) => (b.getAttribute('aria-label') ?? b.textContent?.trim()) === nombre)!;
const campo = (etiqueta: string) => {
  const label = [...document.querySelectorAll('label')].find((l) => l.textContent?.replace('*', '').trim() === etiqueta)!;
  return document.getElementById(label.htmlFor) as HTMLInputElement;
};
const escribir = (input: HTMLInputElement, valor: string) => {
  input.value = valor;
  input.dispatchEvent(new Event('input'));
};

async function abrir(filas: FilaLog[] = [fila(1)]): Promise<void> {
  const f = TestBed.createComponent(Logs);
  el = f.nativeElement as HTMLElement;
  ctrl = TestBed.inject(HttpTestingController);
  (await pedido((r) => r.url === '/api/logs/opciones')).flush(OPCIONES);
  const primera = await pedido(lista);
  // La consulta inicial va con el periodo por omisión (los últimos 7 días)
  expect(primera.request.params.get('desde')).toBeNull();
  primera.flush({ filas, siguiente: null, desde: sumarDias(hoyBogota(), -7), hasta: hoyBogota() });
  await vi.waitFor(() => expect(el.querySelector('[data-testid="tabla-logs"] tbody')?.children.length).toBe(filas.length));
}

describe('Logs del sistema (Logs.tsx del monolito)', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([interceptorApi])),
        provideHttpClientTesting(),
        ...proveerClientesApi(),
        { provide: Sonido, useValue: { sonarError: vi.fn() } },
      ],
    });
  });

  afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((c) => (c.innerHTML = '')));

  it('fila: fecha con segundos, operación por su nombre y «Ver (N)» solo con cambios', async () => {
    await abrir([fila(1), fila(2, { operacion: 'actualizacion', tipo: 'usuario_editado', cambios: [{ campo: 'usuario', antes: 'a', despues: 'b', oculto: false }] })]);
    const filas = el.querySelectorAll('[data-testid="tabla-logs"] tbody tr');
    expect(filas[0]!.textContent).toContain('05/11/2026 08:30:15');
    expect(filas[0]!.textContent).toContain('Ingreso');
    expect(filas[0]!.querySelector('button')).toBeNull();
    expect(filas[1]!.textContent).toContain('Actualización');
    // El nombre empieza por el texto visible «Ver (1)» (2.5.3)
    expect(filas[1]!.querySelector('button')?.textContent?.trim()).toBe('Ver (1) cambios del registro 2');
  });

  it('«Buscar» aplica los filtros (tipo en el servidor, texto recortado); sin filas dice «No hay registros con estos filtros.»', async () => {
    await abrir();
    escribir(campo('Tipo'), ' usuario_editado ');
    boton(el, 'Buscar').click();
    const r = await pedido(lista);
    expect(r.request.params.get('tipo')).toBe('usuario_editado');
    expect(r.request.params.get('desde')).toBe(sumarDias(hoyBogota(), -7));
    expect(r.request.params.get('hasta')).toBe(hoyBogota());
    expect(r.request.params.has('texto')).toBe(false);
    r.flush({ filas: [], siguiente: null, desde: '', hasta: '' });
    await vi.waitFor(() => expect(el.textContent).toContain('No hay registros con estos filtros.'));
  });

  it('texto con un periodo de más de 92 días: error en el campo, sin consultar', async () => {
    await abrir();
    const desde = campo('Desde');
    escribir(desde, '01/01/2026');
    escribir(campo('Texto'), 'admin');
    boton(el, 'Buscar').click();
    await vi.waitFor(() => expect(el.querySelector('[data-testid="error-filtros-logs"]')?.textContent).toContain('Revise el campo marcado: Texto'));
    expect(el.textContent).toContain('Para buscar por texto, use un periodo de máximo 92 días.');
    ctrl.expectNone(lista);
  });

  it('«Ver más» pide la página siguiente con el cursor y la suma a la anterior', async () => {
    const f = TestBed.createComponent(Logs);
    el = f.nativeElement as HTMLElement;
    ctrl = TestBed.inject(HttpTestingController);
    (await pedido((r) => r.url === '/api/logs/opciones')).flush(OPCIONES);
    (await pedido(lista)).flush({ filas: [fila(3)], siguiente: { fecha: '2026-11-05T08:30:15.000-05:00', id: 3 }, desde: '', hasta: '' });
    await vi.waitFor(() => expect(boton(el, 'Ver más')).toBeTruthy());
    boton(el, 'Ver más').click();
    const mas = await pedido(lista);
    // En la URL, codificado una sola vez (el cliente generado codifica y HttpParams no vuelve a hacerlo)
    expect(mas.request.urlWithParams).toContain('despuesFecha=' + encodeURIComponent('2026-11-05T08:30:15.000-05:00'));
    expect(mas.request.urlWithParams).toContain('despuesId=3');
    // Una fila repetida entre páginas no se duplica
    mas.flush({ filas: [fila(3), fila(2)], siguiente: null, desde: '', hasta: '' });
    await vi.waitFor(() => expect(el.querySelectorAll('[data-testid="tabla-logs"] tbody tr')).toHaveLength(2));
    expect(boton(el, 'Ver más')).toBeUndefined();
    // Se anuncia lo que llegó y, como «Ver más» se fue, el foco pasa al primer registro nuevo
    expect(el.querySelector('p.lume-solo-lector[role="status"]')?.textContent).toBe('1 registros más; 2 en total.');
    await vi.waitFor(() => expect(document.activeElement).toBe(el.querySelectorAll('[data-testid="tabla-logs"] tbody tr')[1]));
  });

  it('«Buscar» mientras carga «Ver más»: la página vieja se descarta y «Ver más» vuelve a servir', async () => {
    const f = TestBed.createComponent(Logs);
    el = f.nativeElement as HTMLElement;
    ctrl = TestBed.inject(HttpTestingController);
    const cursor = { fecha: '2026-11-05T08:30:15.000-05:00', id: 3 };
    (await pedido((r) => r.url === '/api/logs/opciones')).flush(OPCIONES);
    (await pedido(lista)).flush({ filas: [fila(3)], siguiente: cursor, desde: '', hasta: '' });
    await vi.waitFor(() => expect(boton(el, 'Ver más')).toBeTruthy());
    boton(el, 'Ver más').click();
    const vieja = await pedido((r) => lista(r) && r.url === '/api/logs');
    boton(el, 'Buscar').click();
    // La búsqueda nueva deja la tabla vacía mientras llega (lo que se ve es lo aplicado)
    await vi.waitFor(() => expect(el.querySelectorAll('[data-testid="tabla-logs"] tbody tr')).toHaveLength(0));
    const nueva = await vi.waitFor(() => {
      const ps = ctrl.match(lista).filter((p) => p !== vieja);
      expect(ps).toHaveLength(1);
      return ps[0]!;
    });
    vieja.flush({ filas: [fila(1)], siguiente: null, desde: '', hasta: '' });
    nueva.flush({ filas: [fila(9)], siguiente: cursor, desde: '', hasta: '' });
    await vi.waitFor(() => expect(el.querySelectorAll('[data-testid="tabla-logs"] tbody tr')).toHaveLength(1));
    const verMas = boton(el, 'Ver más');
    expect(verMas.getAttribute('aria-busy')).toBeNull();
    verMas.click();
    await pedido(lista);
  });

  it('«Antes y después»: el dato personal solo dice «Modificado»', async () => {
    await abrir([
      fila(5, {
        tipo: 'usuario_editado',
        cambios: [
          { campo: 'nombre', antes: null, despues: null, oculto: true },
          { campo: 'usuario', antes: 'logcam1', despues: 'logcam1b', oculto: false },
        ],
      }),
    ]);
    boton(el, 'Ver (2) cambios del registro 5').click();
    const tabla = await vi.waitFor(() => {
      const t = document.querySelector('[data-testid="tabla-cambios"]');
      expect(t).not.toBeNull();
      return t!;
    });
    const [nombre, usuario] = tabla.querySelectorAll('tbody tr');
    expect(nombre!.textContent).toContain('Modificado (dato personal: no se guarda el valor, Ley 1581)');
    expect([...usuario!.querySelectorAll('td')].map((c) => c.textContent?.trim())).toEqual(['usuario', 'logcam1', 'logcam1b']);
  });

  it('«Enviar por correo»: el correo sugerido, la validación y el cuerpo con los filtros aplicados', async () => {
    await abrir();
    boton(el, 'Enviar por correo').click();
    await vi.waitFor(() => expect(campo('Correo de destino').value).toBe('evaluador@angular.prueba.local'));
    escribir(campo('Correo de destino'), 'mal');
    boton(document, 'Enviar').click();
    await vi.waitFor(() => expect(document.querySelector('[data-testid="error-envio-logs"]')).not.toBeNull());
    expect(document.body.textContent).toContain('Escriba un correo válido para enviar el reporte.');
    escribir(campo('Correo de destino'), 'evaluador@angular.prueba.local');
    boton(document, 'Enviar').click();
    const r = await pedido((x) => x.method === 'POST' && x.url === '/api/logs/enviar');
    expect(r.request.body).toMatchObject({ formato: 'xlsx', para: 'evaluador@angular.prueba.local', tipo: null });
    r.flush({ mensaje: 'Reporte enviado a evaluador@angular.prueba.local.' });
    await vi.waitFor(() => expect(TestBed.inject(Avisos).lista().at(-1)?.mensaje).toBe('Reporte enviado a evaluador@angular.prueba.local.'));
  });

  it('descarga: un 422 (cuerpo Blob) muestra el mensaje del servidor en su resumen', async () => {
    await abrir();
    boton(el, 'Descargar Excel').click();
    const r = await pedido((x) => x.url === '/api/logs/exportar');
    expect(r.request.params.get('formato')).toBe('xlsx');
    const mensaje = 'El periodo tiene más de 200000 registros. Acótelo (fechas, usuario u operación) y exporte de nuevo.';
    r.flush(new Blob([JSON.stringify({ status: 422, mensaje })], { type: 'application/problem+json' }), { status: 422, statusText: 'Unprocessable Entity' });
    await vi.waitFor(() => expect(el.querySelector('[data-testid="error-descarga-logs"]')?.textContent).toContain(mensaje));
  });
});
