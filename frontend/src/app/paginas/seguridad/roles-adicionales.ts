import { afterNextRender, ChangeDetectionStrategy, Component, computed, DOCUMENT, ElementRef, inject, Injector, OnInit, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { type CatalogoRoles, type DatosRol, type RolAdicional, UsuariosApi } from '../../api/seguridad';
import { ErrorApi } from '../../nucleo/error-api';
import { conteo } from '../../nucleo/formato';
import { conTexto } from '../../nucleo/validaciones';
import { ListaDeUsuarios } from './lista-usuarios';
import { Avisos, crearValidacion, FORMULARIO_LUME, GrupoCasillas, Insignia, type OpcionCasilla, type OpcionSelector, Selector, TablaAdaptable } from '../../ui';

interface FormularioRol {
  id: number;
  nombre: string;
  descripcion: string;
  baseId: string | null;
  pantallas: string[];
}

/**
 * Roles adicionales (Anexo 5, 1.12; RolesAdicionales.tsx del monolito): va dentro del modal «Roles adicionales» de Usuarios, que lo crea
 * al abrir. Tabla de roles con su base, pantallas y usuarios activos; el formulario de crear o editar va en línea, debajo.
 */
@Component({
  selector: 'lume-roles-adicionales',
  imports: [...FORMULARIO_LUME, Selector, GrupoCasillas, Insignia, TablaAdaptable],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="lume-pila">
      <p class="lume-texto-pequeno lume-apagado">
        Anexo 5, 1.12: roles que pida la DIGSA durante el contrato. Cada rol toma el alcance y las reglas de su perfil base y ve solo las
        pantallas marcadas. Se asignan en el formulario de cada usuario.
      </p>
      @if (errorCarga(); as e) {
        <lume-alerta color="red">{{ e }}</lume-alerta>
      }
      <lume-resumen-errores [validacion]="vEstado" />
      <div class="lume-fila lume-fila--fin">
        <button lumeBoton type="button" data-nuevo-rol (click)="nuevo()">Nuevo rol</button>
      </div>
      <!-- El monolito usaba ScrollArea; con TablaAdaptable es una tarjeta por fila en celular y solo es parada de Tab si desborda -->
      <div lumeTablaAdaptable="Tabla de roles adicionales">
        <table class="lume-tabla lume-tabla--min-640" data-testid="tabla-roles">
          <thead>
            <tr><th>Rol</th><th>Perfil base</th><th>Pantallas</th><th>Usuarios</th><th>Acciones</th></tr>
          </thead>
          <tbody>
            @if (catalogo()?.roles?.length === 0) {
              <tr><td colspan="5" class="lume-texto-pequeno lume-apagado">Aún no hay roles adicionales.</td></tr>
            }
            @for (r of catalogo()?.roles ?? []; track r.id) {
              <tr [attr.data-testid]="'rol-' + r.nombre">
                <td>
                  <div class="lume-negrita">{{ r.nombre }}</div>
                  @if (r.descripcion) {
                    <div class="lume-texto-pequeno lume-apagado">{{ r.descripcion }}</div>
                  }
                </td>
                <td>{{ nombreBase(r.baseId) }}</td>
                <td>
                  <div class="lume-insignias">
                    @for (c of r.pantallas; track c) {
                      <lume-insignia>{{ tituloPantalla(r.baseId, c) }}</lume-insignia>
                    }
                  </div>
                </td>
                <td>{{ conteo(r.usuarios) }}</td>
                <td>
                  <div class="lume-acciones-fila">
                    <button lumeBoton="secundario" tamano="pequeno" type="button" [attr.aria-label]="'Editar el rol ' + r.nombre" (click)="editar(r)">Editar</button>
                    <button lumeBoton="secundario" tamano="pequeno" type="button" [disabled]="cambiando()"
                      [attr.aria-label]="(r.activo ? 'Inactivar el rol ' : 'Activar el rol ') + r.nombre" (click)="cambiarEstado(r)">
                      {{ r.activo ? 'Inactivar' : 'Activar' }}
                    </button>
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>

      @if (form(); as f) {
        <form class="lume-pila lume-card" [lumeValidacion]="v" novalidate data-testid="formulario-rol" (submit)="$event.preventDefault(); guardar()">
          <h3 class="lume-negrita">{{ f.id ? 'Editar rol' : 'Nuevo rol' }}</h3>
          <lume-resumen-errores [validacion]="v" />
          <lume-campo etiqueta="Nombre del rol" obligatorio [error]="v.error('nombre')">
            <input lumeEntrada maxlength="60" autocomplete="off" [value]="f.nombre" (input)="cambiar({ nombre: $any($event.target).value }); v.limpiar('nombre')" />
          </lume-campo>
          <lume-campo etiqueta="Descripción" [error]="v.error('descripcion')">
            <input lumeEntrada maxlength="200" autocomplete="off" [value]="f.descripcion" (input)="cambiar({ descripcion: $any($event.target).value })" />
          </lume-campo>
          <lume-campo etiqueta="Perfil base" descripcion="Define el alcance de los datos y las reglas del rol. No se cambia después de crearlo." obligatorio
            [error]="v.error('baseId')">
            <lume-selector [opciones]="bases()" [valor]="f.baseId" [deshabilitado]="f.id > 0" (valorChange)="elegirBase($event)" />
          </lume-campo>
          @if (f.baseId) {
            <lume-grupo-casillas leyenda="Pantallas del rol" obligatorio [opciones]="pantallasDelForm()" [valores]="f.pantallas"
              (valoresChange)="cambiar({ pantallas: $event }); v.limpiar('pantallas')" [error]="conTexto(v.error('pantallas'))" />
          }
          <div class="lume-fila lume-fila--fin">
            <button lumeBoton="secundario" type="button" (click)="cerrarFormulario()">Cancelar</button>
            <button lumeBoton type="submit" [cargando]="guardando()">Guardar rol</button>
          </div>
        </form>
      }
    </div>
  `,
})
export class RolesAdicionales implements OnInit {
  private readonly api = inject(UsuariosApi);
  private readonly avisos = inject(Avisos);
  private readonly documento = inject(DOCUMENT);
  private readonly ventana = this.documento.defaultView!;
  private readonly injector = inject(Injector);
  private readonly raiz = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  /** Botón que abrió el formulario en línea: al cerrarlo, el foco vuelve a él. */
  private abridor: HTMLElement | null = null;
  /** Cambió un rol: la lista de Usuarios se recarga (los roles son perfiles asignables). */
  private readonly listaUsuarios = inject(ListaDeUsuarios);

  protected readonly conteo = conteo;
  protected readonly conTexto = conTexto;
  protected readonly catalogo = signal<CatalogoRoles | null>(null);
  protected readonly errorCarga = signal<string | null>(null);
  protected readonly form = signal<FormularioRol | null>(null);
  protected readonly guardando = signal(false);
  protected readonly cambiando = signal(false);
  protected readonly bases = computed<OpcionSelector[]>(() => (this.catalogo()?.bases ?? []).map((b) => ({ valor: String(b.id), etiqueta: b.nombre })));

  protected readonly v = crearValidacion({
    etiquetas: { nombre: 'Nombre del rol', descripcion: 'Descripción', baseId: 'Perfil base', pantallas: 'Pantallas del rol' },
    testid: 'error-rol',
  });
  protected readonly vEstado = crearValidacion({ testid: 'error-estado-rol' });

  ngOnInit(): void {
    void this.cargar();
  }

  private async cargar(): Promise<void> {
    try {
      this.catalogo.set(await firstValueFrom(this.api.catalogoRoles()));
      this.errorCarga.set(null);
    } catch (x) {
      this.errorCarga.set(x instanceof ErrorApi ? x.message : 'No se pudieron cargar los roles.');
    }
  }

  protected nombreBase(id: number): string {
    return this.catalogo()?.bases.find((b) => b.id === id)?.nombre ?? String(id);
  }

  protected tituloPantalla(baseId: number, codigo: string): string {
    return this.catalogo()?.bases.find((b) => b.id === baseId)?.pantallas.find((p) => p.codigo === codigo)?.titulo ?? codigo;
  }

  /** Pantallas elegibles de la base elegida (computed: el grupo de casillas no recibe una lista nueva en cada ciclo). */
  protected readonly pantallasDelForm = computed<OpcionCasilla[]>(() => {
    const baseId = this.form()?.baseId;
    if (!baseId) return [];
    return (this.catalogo()?.bases.find((b) => b.id === Number(baseId))?.pantallas ?? []).map((p) => ({ valor: p.codigo, etiqueta: p.titulo }));
  });

  protected nuevo(): void {
    this.abrir({ id: 0, nombre: '', descripcion: '', baseId: null, pantallas: [] });
  }

  protected editar(r: RolAdicional): void {
    this.abrir({ id: r.id, nombre: r.nombre, descripcion: r.descripcion ?? '', baseId: String(r.baseId), pantallas: [...r.pantallas] });
  }

  /** El formulario aparece debajo de la tabla: el foco va a su primer campo (el lector se entera y queda a la vista). */
  private abrir(f: FormularioRol): void {
    this.abridor = this.documento.activeElement instanceof HTMLElement ? this.documento.activeElement : null;
    this.v.reiniciar();
    this.form.set(f);
    afterNextRender(() => this.raiz.querySelector<HTMLElement>('[data-testid="formulario-rol"] input')?.focus(), { injector: this.injector });
  }

  protected cambiar(parte: Partial<FormularioRol>): void {
    this.form.update((f) => (f ? { ...f, ...parte } : f));
  }

  /** Al cambiar la base se vacían las pantallas marcadas (son de la base anterior). */
  protected elegirBase(baseId: string | null): void {
    this.cambiar({ baseId, pantallas: [] });
    this.v.limpiar('baseId');
  }

  /** Al cerrar el formulario (Cancelar o guardado) el foco vuelve al botón que lo abrió, o a «Nuevo rol» si ese ya no está. */
  protected cerrarFormulario(): void {
    this.form.set(null);
    this.v.reiniciar();
    const abridor = this.abridor;
    this.abridor = null;
    afterNextRender(
      () => (abridor?.isConnected ? abridor : this.raiz.querySelector<HTMLElement>('[data-nuevo-rol]'))?.focus(),
      { injector: this.injector },
    );
  }

  protected async guardar(): Promise<void> {
    const f = this.form();
    if (!f) return;
    const ok = this.v.validar({
      nombre: f.nombre.trim() ? null : 'Escriba el nombre del rol.',
      baseId: f.baseId ? null : 'Elija el perfil base.',
      pantallas: f.baseId && f.pantallas.length === 0 ? 'Marque al menos una pantalla.' : null,
    });
    if (!ok) return;
    const cuerpo: DatosRol = { nombre: f.nombre, descripcion: f.descripcion || null, baseId: Number(f.baseId), pantallas: f.pantallas };
    this.guardando.set(true);
    try {
      const r = await firstValueFrom(f.id ? this.api.editarRol(f.id, cuerpo) : this.api.crearRol(cuerpo));
      this.avisos.mostrar(r.mensaje);
      this.cerrarFormulario();
      this.listaUsuarios.recargar();
      await this.cargar();
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo guardar el rol.');
    } finally {
      this.guardando.set(false);
    }
  }

  protected async cambiarEstado(r: RolAdicional): Promise<void> {
    if (r.activo) {
      const aviso = r.usuarios > 0 ? ` Sus ${conteo(r.usuarios)} usuarios dejarán de ver sus pantallas.` : '';
      if (!this.ventana.confirm(`¿Inactivar el rol ${r.nombre}?${aviso}`)) return;
    }
    this.cambiando.set(true);
    try {
      const x = await firstValueFrom(this.api.cambiarEstadoRol(r.id, { activo: !r.activo }));
      this.vEstado.reiniciar();
      this.avisos.mostrar(x.mensaje);
      this.listaUsuarios.recargar();
      await this.cargar();
    } catch (e) {
      this.vEstado.errorServidor(e, 'No se pudo cambiar el estado.');
    } finally {
      this.cambiando.set(false);
    }
  }
}
