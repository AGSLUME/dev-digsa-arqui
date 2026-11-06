import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, type TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { type CatalogoRoles, type FilaUsuario, type ListaUsuarios } from '../../api/seguridad';
import { interceptorApi } from '../../nucleo/interceptor-api';
import { proveerClientesApi } from '../../nucleo/proveedores';
import { Sesion } from '../../nucleo/sesion';
import { Avisos } from '../../ui/avisos';
import { Sonido } from '../../ui/sonido';
import { Usuarios } from './usuarios';

// Datos ficticios (@angular.prueba.local)
const fila = (p: Partial<FilaUsuario> & Pick<FilaUsuario, 'id' | 'nombre' | 'usuario'>): FilaUsuario => ({
  correo: `${p.usuario}@angular.prueba.local`,
  activo: true,
  bloqueado: false,
  sesionAbierta: false,
  claveInicial: false,
  perfiles: [3],
  ultimoIngreso: null,
  documento: null,
  telefono: null,
  ...p,
});

const LISTA: ListaUsuarios = {
  usuarios: [
    fila({ id: 1, nombre: 'Administradora Ficticia', usuario: 'admin.angular', perfiles: [1], ultimoIngreso: '2026-11-05 08:30', sesionAbierta: true }),
    fila({ id: 2, nombre: 'José Bloqueado Ficticio', usuario: 'jose.angular', bloqueado: true, claveInicial: true }),
    fila({ id: 3, nombre: 'Inactiva Ficticia', usuario: 'inactiva.angular', activo: false, bloqueado: true, perfiles: [101] }),
  ],
  perfiles: [
    { id: 1, nombre: 'Administrador', descripcion: null, baseId: null },
    { id: 3, nombre: 'Auditor', descripcion: 'Hace las visitas.', baseId: null },
    { id: 7, nombre: 'ESM', descripcion: null, baseId: null },
    { id: 101, nombre: 'Gestor del riesgo', descripcion: null, baseId: 7 },
  ],
  puntos: [{ id: 501, nombre: 'Establecimiento Ficticio Norte', sigla: 'EFN', fuerza: 'ARC' }],
  regionales: [{ id: 31, nombre: 'Regional Ficticia Andina' }],
  fuerzas: [{ codigo: 'EJC', nombre: 'DISAN EJC (Ejército)' }],
};

let ctrl: HttpTestingController;
let el: HTMLElement;
const confirmar = vi.fn(() => true);

async function abrir(lista = LISTA): Promise<void> {
  const f = TestBed.createComponent(Usuarios);
  el = f.nativeElement as HTMLElement;
  ctrl = TestBed.inject(HttpTestingController);
  const r = await vi.waitFor(() => ctrl.expectOne('/api/usuarios'));
  r.flush(lista);
  await vi.waitFor(() => expect(el.querySelector('[data-testid="usuario-admin.angular"]')).not.toBeNull());
}

const fila$ = (usuario: string) => el.querySelector<HTMLElement>(`[data-testid="usuario-${usuario}"]`)!;
const boton = (raiz: ParentNode, nombre: string) =>
  [...raiz.querySelectorAll<HTMLButtonElement>('button')].find((b) => (b.getAttribute('aria-label') ?? b.textContent?.trim()) === nombre)!;
const escribir = (input: HTMLInputElement, valor: string) => {
  input.value = valor;
  input.dispatchEvent(new Event('input'));
};
/** El control de un campo por el texto de su etiqueta (dentro de un modal del CDK, que va en el body). */
const campo = (etiqueta: string) => {
  const label = [...document.querySelectorAll('label')].find((l) => l.textContent?.replace('*', '').replace('(obligatorio)', '').trim() === etiqueta)!;
  return document.getElementById(label.htmlFor) as HTMLInputElement;
};
const dialogo = () => document.querySelector<HTMLElement>('[role="dialog"]')!;

async function pedido(metodo: string, url: string): Promise<TestRequest> {
  return vi.waitFor(() => ctrl.expectOne((r) => r.method === metodo && r.url === url));
}

describe('Usuarios (Usuarios.tsx del monolito)', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    confirmar.mockReset().mockReturnValue(true);
    vi.spyOn(window, 'confirm').mockImplementation(confirmar);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([interceptorApi])),
        provideHttpClientTesting(),
        ...proveerClientesApi(),
        { provide: Sonido, useValue: { sonarError: vi.fn() } },
        { provide: Sesion, useValue: { cargar: vi.fn(() => Promise.resolve({})) } },
      ],
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.querySelectorAll('.cdk-overlay-container').forEach((c) => (c.innerHTML = ''));
  });

  it('tabla: perfiles por nombre, último ingreso en dd/mm/aaaa HH:mm, insignias de estado y botones según el estado', async () => {
    await abrir();
    const admin = fila$('admin.angular');
    expect(admin.textContent).toContain('Administrador');
    expect(admin.textContent).toContain('05/11/2026 08:30');
    expect(admin.textContent).toContain('Activo');
    expect(admin.textContent).toContain('Sesión abierta');
    expect(boton(admin, 'Cerrar la sesión de Administradora Ficticia')).toBeTruthy();
    expect(boton(admin, 'Desbloquear a Administradora Ficticia')).toBeUndefined();

    const jose = fila$('jose.angular');
    expect(jose.querySelector('.lume-insignia--warning.lume-insignia--relleno')?.textContent?.trim()).toBe('Bloqueado');
    expect(jose.textContent).toContain('Contraseña inicial');
    expect(jose.textContent).toContain('—');
    expect(boton(jose, 'Desbloquear a José Bloqueado Ficticio')).toBeTruthy();

    // Inactivo gana aunque esté bloqueado; el rol adicional sale con su nombre
    const inactiva = fila$('inactiva.angular');
    expect(inactiva.querySelector('.lume-insignia--relleno')?.textContent?.trim()).toBe('Inactivo');
    expect(inactiva.textContent).toContain('Gestor del riesgo');
    expect(boton(inactiva, 'Activar a Inactiva Ficticia').textContent?.trim()).toBe('Activar');
    expect(el.querySelector('[data-testid="total-usuarios"]')?.textContent).toBe('Mostrando 1–3 de 3 usuarios');
  });

  it('«Buscar» filtra por nombre, usuario o correo sin tildes ni mayúsculas', async () => {
    await abrir();
    escribir(campo('Buscar'), 'JOSE');
    await vi.waitFor(() => expect(el.querySelectorAll('tbody tr')).toHaveLength(1));
    expect(fila$('jose.angular')).not.toBeNull();
    escribir(campo('Buscar'), 'inactiva.angular@');
    await vi.waitFor(() => expect(fila$('inactiva.angular')).not.toBeNull());
    escribir(campo('Buscar'), 'nadie');
    await vi.waitFor(() => expect(el.querySelector('[data-testid="total-usuarios"]')?.textContent).toBe('0 usuarios'));
  });

  it('acciones: desbloquear sin confirmar; inactivar y cerrar la sesión piden confirmación; el aviso y la lista de nuevo', async () => {
    await abrir();
    const avisos = TestBed.inject(Avisos);
    const desbloquear = boton(fila$('jose.angular'), 'Desbloquear a José Bloqueado Ficticio');
    desbloquear.focus();
    desbloquear.click();
    expect(confirmar).not.toHaveBeenCalled();
    // Mientras corre, las demás acciones no hacen nada (aria-disabled: el foco no se pierde)
    await vi.waitFor(() => expect(boton(fila$('admin.angular'), 'Inactivar a Administradora Ficticia').getAttribute('aria-disabled')).toBe('true'));
    boton(fila$('admin.angular'), 'Inactivar a Administradora Ficticia').click();
    expect(confirmar).not.toHaveBeenCalled();
    (await pedido('POST', '/api/usuarios/2/desbloquear')).flush({ mensaje: 'Usuario desbloqueado.' });
    // Ya no está bloqueado: «Desbloquear» se va de la fila y el foco pasa al primer botón de esa fila
    (await pedido('GET', '/api/usuarios')).flush({ ...LISTA, usuarios: LISTA.usuarios.map((u) => (u.id === 2 ? { ...u, bloqueado: false } : u)) });
    await vi.waitFor(() => expect(avisos.lista().map((a) => a.mensaje)).toContain('Usuario desbloqueado.'));
    await vi.waitFor(() => expect(document.activeElement).toBe(boton(fila$('jose.angular'), 'Editar a José Bloqueado Ficticio')));
    // Mientras corre una acción, los botones de todas las filas quedan deshabilitados
    await vi.waitFor(() => expect(boton(fila$('admin.angular'), 'Inactivar a Administradora Ficticia').getAttribute('aria-disabled')).toBeNull());

    confirmar.mockReturnValueOnce(false);
    boton(fila$('admin.angular'), 'Inactivar a Administradora Ficticia').click();
    expect(confirmar).toHaveBeenLastCalledWith('¿Inactivar a Administradora Ficticia?');
    ctrl.expectNone((r) => r.url.endsWith('/estado'));

    boton(fila$('admin.angular'), 'Cerrar la sesión de Administradora Ficticia').click();
    expect(confirmar).toHaveBeenLastCalledWith('¿Cerrar la sesión abierta de Administradora Ficticia?');
    (await pedido('POST', '/api/usuarios/1/cerrar-sesion')).flush({ mensaje: 'Sesión cerrada.' });
    (await pedido('GET', '/api/usuarios')).flush(LISTA);
    await vi.waitFor(() => expect(boton(fila$('jose.angular'), 'Restablecer la contraseña de José Bloqueado Ficticio').getAttribute('aria-disabled')).toBeNull());

    boton(fila$('jose.angular'), 'Restablecer la contraseña de José Bloqueado Ficticio').click();
    expect(confirmar).toHaveBeenLastCalledWith('¿Enviar a José Bloqueado Ficticio un enlace para definir una contraseña nueva?');
    (await pedido('POST', '/api/usuarios/2/restablecer')).flush({ type: 'x', status: 502, mensaje: 'No se pudo enviar el correo: SMTP caído' }, { status: 502, statusText: 'Bad Gateway' });
    await vi.waitFor(() => expect(el.querySelector('[data-testid="error-accion-usuario"]')?.textContent).toContain('No se pudo enviar el correo: SMTP caído'));
  });

  it('formulario nuevo: validación en el orden del monolito, alcance según el perfil (o la base del rol) y el cuerpo del contrato', async () => {
    await abrir();
    boton(el, 'Nuevo usuario').click();
    await vi.waitFor(() => expect(dialogo()?.textContent).toContain('Nuevo usuario'));
    const enviar = () => boton(dialogo(), 'Guardar').click();
    enviar();
    await vi.waitFor(() => expect(document.querySelector('[data-testid="error-usuario"]')?.textContent).toContain('Revise los 3 campos marcados'));
    expect(dialogo().textContent).toContain('Escriba el nombre completo.');
    expect(dialogo().textContent).toContain('Escriba el usuario.');
    expect(dialogo().textContent).toContain('Escriba el correo.');

    escribir(campo('Nombre completo'), 'Auditor Uno de Prueba');
    escribir(campo('Usuario'), 'Auditor.Uno');
    expect(campo('Usuario').value).toBe('auditor.uno');
    escribir(campo('Correo'), 'auditor.uno@angular.prueba.local');
    escribir(campo('Documento'), '80123456');
    // El rol con base ESM pide el establecimiento, como el perfil ESM
    const rol = [...dialogo().querySelectorAll('label')].find((l) => l.textContent?.trim() === 'Gestor del riesgo (rol adicional · base ESM)')!;
    (document.getElementById(rol.htmlFor) as HTMLInputElement).click();
    await vi.waitFor(() => expect(dialogo().textContent).toContain('Establecimiento (ESM)'));
    enviar();
    await vi.waitFor(() => expect(dialogo().textContent).toContain('Elija el establecimiento.'));

    (document.getElementById(rol.htmlFor) as HTMLInputElement).click();
    const auditor = [...dialogo().querySelectorAll('label')].find((l) => l.textContent?.trim() === 'Auditor')!;
    (document.getElementById(auditor.htmlFor) as HTMLInputElement).click();
    enviar();
    const crear = await pedido('POST', '/api/usuarios');
    expect(crear.request.body).toEqual({
      nombre: 'Auditor Uno de Prueba',
      usuario: 'auditor.uno',
      correo: 'auditor.uno@angular.prueba.local',
      perfiles: [3],
      alcanceFuerza: null,
      alcancePunto: null,
      alcanceRegional: null,
      documento: '80123456',
      telefono: null,
      claveInicial: null,
    });
    crear.flush({ id: 9, creado: true, correoOk: false, mensaje: 'Usuario creado, pero NO se pudo enviar el correo (x).', enlaceLocal: null }, { status: 201, statusText: 'Created' });
    (await pedido('GET', '/api/usuarios')).flush(LISTA);
    // Correo fallido: aviso naranja que no se cierra solo
    const avisos = TestBed.inject(Avisos);
    await vi.waitFor(() => expect(avisos.lista().at(-1)).toMatchObject({ color: 'orange', mensaje: 'Usuario creado, pero NO se pudo enviar el correo (x).' }));
  });

  it('formulario: contraseña inicial solo al crear, con la política local; el teléfono no detiene el envío y el 422 del servidor lo marca', async () => {
    await abrir();
    boton(el, 'Nuevo usuario').click();
    await vi.waitFor(() => expect(dialogo()).not.toBeNull());
    escribir(campo('Nombre completo'), 'Usuario Teléfono de Prueba');
    escribir(campo('Usuario'), 'telefono.prueba');
    escribir(campo('Correo'), 'telefono.prueba@angular.prueba.local');
    escribir(campo('Teléfono móvil'), '601 000 0000');
    campo('Teléfono móvil').dispatchEvent(new Event('blur'));
    await vi.waitFor(() => expect(dialogo().textContent).toContain('El teléfono debe ser un móvil colombiano'));
    campo('Asignar contraseña inicial (se cambia al primer ingreso)').click();
    await vi.waitFor(() => expect(campo('Contraseña inicial')).toBeTruthy());
    escribir(campo('Contraseña inicial'), 'corta');
    boton(dialogo(), 'Guardar').click();
    await vi.waitFor(() => expect(dialogo().textContent).toContain('La contraseña debe tener mínimo 12 caracteres.'));
    ctrl.expectNone((r) => r.method === 'POST');

    escribir(campo('Contraseña inicial'), 'Inicial.Asignada.2026');
    boton(dialogo(), 'Guardar').click();
    const crear = await pedido('POST', '/api/usuarios');
    expect(crear.request.body).toMatchObject({ telefono: '601 000 0000', claveInicial: 'Inicial.Asignada.2026' });
    crear.flush(
      { status: 422, mensaje: 'El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000). Se usa para los SMS de las alertas críticas.', campo: 'telefono' },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    const resumen = await vi.waitFor(() => {
      const r = document.querySelector('[data-testid="error-usuario"]');
      expect(r?.textContent).toContain('alertas críticas');
      return r!;
    });
    expect(resumen.textContent).toContain('Revise el campo marcado: Teléfono móvil');
  });

  it('si el modal se cierra mientras guarda, al llegar la respuesta la lista se recarga y va a la fila del usuario', async () => {
    await abrir();
    boton(el, 'Nuevo usuario').click();
    await vi.waitFor(() => expect(dialogo()).not.toBeNull());
    escribir(campo('Nombre completo'), 'Coordinador Uno de Prueba');
    escribir(campo('Usuario'), 'coordinador.uno');
    escribir(campo('Correo'), 'coordinador.uno@angular.prueba.local');
    escribir(campo('Buscar'), 'jose');
    boton(dialogo(), 'Guardar').click();
    const crear = await pedido('POST', '/api/usuarios');
    boton(dialogo(), 'Cancelar').click();
    await vi.waitFor(() => expect(document.querySelector('[role="dialog"]')).toBeNull());
    crear.flush({ id: 9, creado: true, correoOk: true, mensaje: 'Usuario creado. Se le envió el enlace para definir su contraseña.', enlaceLocal: null });
    const nuevo = fila({ id: 9, nombre: 'Coordinador Uno de Prueba', usuario: 'coordinador.uno', perfiles: [] });
    (await pedido('GET', '/api/usuarios')).flush({ ...LISTA, usuarios: [...LISTA.usuarios, nuevo] });
    // La búsqueda lo ocultaba: se quita y la fila queda a la vista
    await vi.waitFor(() => expect(fila$('coordinador.uno')).not.toBeNull());
    expect(campo('Buscar').value).toBe('');
  });

  it('editar: los datos del usuario, sin contraseña inicial; PUT al id', async () => {
    await abrir();
    boton(fila$('jose.angular'), 'Editar a José Bloqueado Ficticio').click();
    await vi.waitFor(() => expect(dialogo()?.textContent).toContain('Editar usuario'));
    expect(campo('Usuario').value).toBe('jose.angular');
    expect(dialogo().textContent).not.toContain('Asignar contraseña inicial');
    boton(dialogo(), 'Guardar').click();
    const editar = await pedido('PUT', '/api/usuarios/2');
    expect(editar.request.body).toMatchObject({ usuario: 'jose.angular', perfiles: [3], claveInicial: null });
  });

  it('Seguridad: carga los minutos, valida de 1 al máximo, guarda y recarga la sesión', async () => {
    await abrir();
    boton(el, 'Seguridad').click();
    (await pedido('GET', '/api/usuarios/seguridad')).flush({ minutosSesion: 10, minutosMaximo: 480 });
    const minutos = await vi.waitFor(() => {
      const m = document.querySelector<HTMLInputElement>('[data-testid="minutos-sesion"]');
      expect(m?.value).toBe('10');
      return m!;
    });
    escribir(minutos, '481');
    boton(dialogo(), 'Guardar').click();
    await vi.waitFor(() => expect(dialogo().textContent).toContain('Escriba un número entero de minutos, de 1 a 480.'));
    escribir(minutos, '3');
    boton(dialogo(), 'Guardar').click();
    const put = await pedido('PUT', '/api/usuarios/seguridad');
    expect(put.request.body).toEqual({ minutosSesion: 3 });
    put.flush({ mensaje: 'Tiempo de inactividad actualizado: 3 minutos. Aplica desde la siguiente acción de cada usuario.' });
    await vi.waitFor(() => expect(TestBed.inject(Sesion).cargar).toHaveBeenCalled());
  });

  it('Nombres de perfiles: solo los base; «Guardar» deshabilitado sin cambios; el 422 con campo nombre marca ese perfil', async () => {
    await abrir();
    boton(el, 'Nombres de perfiles').click();
    await vi.waitFor(() => expect(dialogo()?.textContent).toContain('Los nombres son configurables'));
    expect(dialogo().textContent).not.toContain('Gestor del riesgo');
    const auditor = campo('Auditor');
    const guardar = auditor.closest('form')!.querySelector('button')!;
    expect(guardar.getAttribute('aria-disabled')).toBe('true');
    expect(guardar.textContent?.replace(/s+/g, ' ').trim()).toBe('Guardar el nombre de Auditor');
    escribir(auditor, 'Administrador');
    await vi.waitFor(() => expect(guardar.getAttribute('aria-disabled')).toBeNull());
    guardar.click();
    const put = await pedido('PUT', '/api/usuarios/perfiles/3');
    expect(put.request.body).toEqual({ nombre: 'Administrador', descripcion: 'Hace las visitas.' });
    put.flush({ status: 422, mensaje: 'Ya existe otro perfil con ese nombre.', campo: 'nombre' }, { status: 422, statusText: 'Unprocessable Entity' });
    await vi.waitFor(() => expect(document.querySelector('[data-testid="error-nombres-perfiles"]')?.textContent).toContain('Revise el campo marcado: Auditor'));
    expect(auditor.getAttribute('aria-invalid')).toBe('true');
  });

  it('Roles adicionales: tabla con base y pantallas por título, inactivar confirma con los usuarios y el formulario valida nombre y base', async () => {
    await abrir();
    boton(el, 'Roles adicionales').click();
    const catalogo: CatalogoRoles = {
      bases: [
        { id: 2, nombre: 'Coordinador', pantallas: [{ codigo: 'RIESGO', titulo: 'Dashboard de riesgo' }, { codigo: 'DASHBOARD', titulo: 'Dashboard' }] },
        { id: 5, nombre: 'DIGSA', pantallas: [{ codigo: 'CALENDARIO', titulo: 'Calendario de visitas' }] },
      ],
      roles: [{ id: 101, nombre: 'Gestor del riesgo', descripcion: 'Solo riesgo', baseId: 2, activo: true, pantallas: ['RIESGO'], usuarios: 2 }],
    };
    (await pedido('GET', '/api/usuarios/roles')).flush(catalogo);
    const fila = await vi.waitFor(() => {
      const f = document.querySelector<HTMLElement>('[data-testid="rol-Gestor del riesgo"]');
      expect(f?.textContent).toContain('Dashboard de riesgo');
      return f!;
    });
    expect(fila.textContent).toContain('Coordinador');

    confirmar.mockReturnValueOnce(false);
    boton(fila, 'Inactivar el rol Gestor del riesgo').click();
    expect(confirmar).toHaveBeenLastCalledWith('¿Inactivar el rol Gestor del riesgo? Sus 2 usuarios dejarán de ver sus pantallas.');

    boton(dialogo(), 'Nuevo rol').click();
    const form = await vi.waitFor(() => {
      const f = document.querySelector<HTMLElement>('[data-testid="formulario-rol"]');
      expect(f).not.toBeNull();
      return f!;
    });
    boton(form, 'Guardar rol').click();
    await vi.waitFor(() => expect(form.textContent).toContain('Escriba el nombre del rol.'));
    expect(form.textContent).toContain('Elija el perfil base.');
  });
});
