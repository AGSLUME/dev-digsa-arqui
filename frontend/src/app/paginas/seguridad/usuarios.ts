import { afterNextRender, ChangeDetectionStrategy, Component, computed, DOCUMENT, inject, Injector, OnInit, signal } from '@angular/core';
import { firstValueFrom, type Observable } from 'rxjs';
import { type FilaUsuario, type ListaUsuarios, type Mensaje, UsuariosApi } from '../../api/seguridad';
import { ErrorApi } from '../../nucleo/error-api';
import { fechaHora } from '../../nucleo/formato';
import {
  Avisos,
  crearPaginacion,
  crearValidacion,
  CuerpoModal,
  EncabezadoPagina,
  FORMULARIO_LUME,
  Insignia,
  Modal,
  Paginacion,
  TablaAdaptable,
} from '../../ui';
import { FormularioUsuario } from './formulario-usuario';
import { ListaDeUsuarios } from './lista-usuarios';
import { AsignarClaveInicial, NombresPerfiles, ParametrosDeSeguridad } from './modales-usuarios';
import { RolesAdicionales } from './roles-adicionales';

/** Sin tildes y en minúsculas, para que «jose» encuentre a «José». */
const normal = (t: string) =>
  t
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLocaleLowerCase('es');

/**
 * Usuarios (Anexo 5 · 1.10–1.12, 3.1–3.7; Usuarios.tsx del monolito): solo el Administrador. Lista con búsqueda y paginación, acciones por
 * fila (activar, restablecer la contraseña, contraseña inicial, desbloquear, cerrar la sesión) y los modales de usuario, Seguridad
 * (TIME OUT), Nombres de perfiles y Roles adicionales. Textos, aria-label y data-testid del monolito: las e2e los usan.
 */
@Component({
  selector: 'lume-usuarios',
  imports: [
    ...FORMULARIO_LUME,
    EncabezadoPagina,
    Insignia,
    TablaAdaptable,
    Paginacion,
    Modal,
    CuerpoModal,
    FormularioUsuario,
    AsignarClaveInicial,
    ParametrosDeSeguridad,
    NombresPerfiles,
    RolesAdicionales,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ListaDeUsuarios],
  templateUrl: './usuarios.html',
})
export class Usuarios implements OnInit {
  private readonly api = inject(UsuariosApi);
  private readonly avisos = inject(Avisos);
  private readonly documento = inject(DOCUMENT);
  private readonly ventana = this.documento.defaultView!;
  private readonly injector = inject(Injector);
  /** Cada carga lleva su número: si dos se cruzan, solo la última pinta (una respuesta lenta no pisa a una más nueva). */
  private version = 0;

  protected readonly fechaHora = fechaHora;
  protected readonly datos = signal<ListaUsuarios | null>(null);
  protected readonly errorCarga = signal<string | null>(null);
  protected readonly buscar = signal('');
  /** Una sola acción de fila a la vez: mientras corre, los botones de todas las filas quedan deshabilitados (como el monolito). */
  protected readonly enAccion = signal(false);
  protected readonly vAccion = crearValidacion({ testid: 'error-accion-usuario' });

  /** Modales: el formulario lleva el usuario que se edita (null: uno nuevo); `undefined` es cerrado. */
  protected readonly formulario = signal<FilaUsuario | null | undefined>(undefined);
  protected readonly claveInicialDe = signal<FilaUsuario | null>(null);
  protected readonly seguridadAbierta = signal(false);
  protected readonly perfilesAbierto = signal(false);
  protected readonly rolesAbierto = signal(false);

  protected readonly filtrados = computed(() => {
    const usuarios = this.datos()?.usuarios ?? [];
    const q = normal(this.buscar().trim());
    return q ? usuarios.filter((u) => [u.nombre, u.usuario, u.correo].some((t) => normal(t).includes(q))) : usuarios;
  });
  protected readonly pag = crearPaginacion(this.filtrados);

  constructor() {
    inject(ListaDeUsuarios).registrar((usuario) => void (usuario === null ? this.cargar() : this.irAUsuario(usuario)));
  }

  ngOnInit(): void {
    void this.cargar();
  }

  /** Carga la lista; true si quedó la de esta carga (no falló ni la reemplazó otra más nueva). */
  protected async cargar(): Promise<boolean> {
    const n = ++this.version;
    try {
      const datos = await firstValueFrom(this.api.listarUsuarios());
      if (n !== this.version) return false;
      this.datos.set(datos);
      this.errorCarga.set(null);
      return true;
    } catch (x) {
      if (n === this.version) this.errorCarga.set(x instanceof ErrorApi ? x.message : 'No se pudo cargar la lista de usuarios.');
      return false;
    }
  }

  protected escribirBusqueda(texto: string): void {
    this.buscar.set(texto);
    this.pag.irAPagina(1);
  }

  /** Nombre del perfil o rol (el del catálogo; el id si ya no está). */
  protected nombrePerfil(id: number): string {
    return this.datos()?.perfiles.find((p) => p.id === id)?.nombre ?? String(id);
  }

  /** Tras crear o editar: la lista nueva y la página del usuario; si la búsqueda lo oculta, se quita la búsqueda (U:127-132). */
  private async irAUsuario(usuario: string): Promise<void> {
    if (!(await this.cargar())) return;
    if (!this.filtrados().some((u) => u.usuario === usuario)) this.buscar.set('');
    this.pag.irA(this.filtrados().findIndex((u) => u.usuario === usuario));
  }

  protected cambiarEstado(u: FilaUsuario): void {
    if (this.enAccion()) return;
    const verbo = u.activo ? 'Inactivar' : 'Activar';
    if (!this.ventana.confirm(`¿${verbo} a ${u.nombre}?`)) return;
    void this.accion(this.api.cambiarEstadoUsuario(u.id, { activo: !u.activo }));
  }

  protected restablecer(u: FilaUsuario): void {
    if (this.enAccion()) return;
    if (!this.ventana.confirm(`¿Enviar a ${u.nombre} un enlace para definir una contraseña nueva?`)) return;
    void this.accion(this.api.restablecerClave(u.id));
  }

  protected desbloquear(u: FilaUsuario): void {
    if (this.enAccion()) return;
    void this.accion(this.api.desbloquearUsuario(u.id));
  }

  protected cerrarSesion(u: FilaUsuario): void {
    if (this.enAccion()) return;
    if (!this.ventana.confirm(`¿Cerrar la sesión abierta de ${u.nombre}?`)) return;
    void this.accion(this.api.cerrarSesionUsuario(u.id));
  }

  protected abrirClaveInicial(u: FilaUsuario): void {
    if (!this.enAccion()) this.claveInicialDe.set(u);
  }

  /**
   * Acción de una fila (`accionTabla` del monolito): aviso con el mensaje del servidor y la lista de nuevo; el error, en el resumen. Los
   * botones quedan con `aria-disabled` mientras corre (no `disabled`: el foco no se pierde); si el botón pulsado ya no está al terminar
   * («Desbloquear» y «Cerrar la sesión» se van con el estado), el foco pasa al primer botón de su fila.
   */
  private async accion(peticion: Observable<Mensaje>): Promise<void> {
    const origen = this.documento.activeElement instanceof HTMLElement ? this.documento.activeElement : null;
    const fila = origen?.closest('tr')?.getAttribute('data-testid') ?? null;
    this.enAccion.set(true);
    try {
      const r = await firstValueFrom(peticion);
      this.vAccion.reiniciar();
      this.avisos.mostrar(r.mensaje);
      await this.cargar();
    } catch (x) {
      this.vAccion.errorServidor(x, 'No se pudo completar la acción.');
    } finally {
      this.enAccion.set(false);
      afterNextRender(
        () => {
          if (!origen || origen.isConnected || !fila || this.documento.activeElement !== this.documento.body) return;
          this.documento.querySelector<HTMLElement>(`[data-testid="${fila}"] button`)?.focus();
        },
        { injector: this.injector },
      );
    }
  }


}
