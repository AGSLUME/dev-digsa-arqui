import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { type FilaUsuario, type ParametrosSeguridad, type PerfilAsignable, UsuariosApi } from '../../api/seguridad';
import { ErrorApi } from '../../nucleo/error-api';
import { Sesion } from '../../nucleo/sesion';
import { errorClave } from '../../nucleo/validaciones';
import { Avisos, crearValidacion, FORMULARIO_LUME, leerEntero, Numero } from '../../ui';
import { ListaDeUsuarios } from './lista-usuarios';

/** Modal «Asignar contraseña inicial» (U:630-671): la contraseña que el administrador entrega; se cambia al primer ingreso. */
@Component({
  selector: 'lume-asignar-clave-inicial',
  imports: [...FORMULARIO_LUME],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="lume-pila" [lumeValidacion]="v" novalidate (submit)="$event.preventDefault(); asignar()">
      <p>{{ usuario().nombre }} deberá cambiarla en su próximo ingreso. Si tiene una sesión abierta, se cierra.</p>
      <lume-resumen-errores [validacion]="v" />
      <lume-campo etiqueta="Contraseña inicial" descripcion="Mínimo 12 caracteres, sin tildes ni ñ." obligatorio clave [error]="v.error('clave')">
        <input lumeEntrada type="password" autocomplete="new-password" maxlength="64" [value]="clave()"
          (input)="clave.set($any($event.target).value); v.limpiar('clave')" />
      </lume-campo>
      <div class="lume-modal__acciones">
        <button lumeBoton="secundario" type="button" (click)="listo.emit(false)">Cancelar</button>
        <button lumeBoton type="submit" [cargando]="enviando()">Asignar</button>
      </div>
    </form>
  `,
})
export class AsignarClaveInicial {
  private readonly api = inject(UsuariosApi);
  private readonly avisos = inject(Avisos);
  private readonly listaUsuarios = inject(ListaDeUsuarios);
  readonly usuario = input.required<FilaUsuario>();
  /** Asignó o canceló: el modal se cierra (la lista se recarga por `ListaDeUsuarios`). */
  readonly listo = output<boolean>();

  protected readonly clave = signal('');
  protected readonly enviando = signal(false);
  protected readonly v = crearValidacion({ etiquetas: { clave: 'Contraseña inicial' }, testid: 'error-clave-inicial' });

  protected async asignar(): Promise<void> {
    if (!this.v.validar({ clave: errorClave(this.clave()) })) return;
    this.enviando.set(true);
    try {
      const r = await firstValueFrom(this.api.asignarClaveInicial(this.usuario().id, { clave: this.clave() }));
      this.listaUsuarios.recargar();
      this.listo.emit(true);
      setTimeout(() => this.avisos.mostrar(r.mensaje));
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo asignar la contraseña inicial.');
    } finally {
      this.enviando.set(false);
    }
  }
}

/** Modal «Seguridad» (U:673-715): el tiempo de inactividad (TIME OUT) de todas las sesiones, de 1 al máximo del servidor (480). */
@Component({
  selector: 'lume-parametros-seguridad',
  imports: [...FORMULARIO_LUME, Numero],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="lume-pila" [lumeValidacion]="v" novalidate (submit)="$event.preventDefault(); guardar()">
      <lume-resumen-errores [validacion]="v" />
      @if (errorCarga(); as e) {
        <lume-alerta color="red">{{ e }}</lume-alerta>
      }
      <lume-campo
        etiqueta="Tiempo de inactividad (TIME OUT), en minutos"
        descripcion="Anexo 5, 3.2: la sesión se cierra sola tras este tiempo sin actividad. Aplica desde la siguiente acción de cada usuario."
        obligatorio
        [error]="v.error('minutos')"
      >
        <input lumeEntrada lumeNumero data-testid="minutos-sesion" [value]="minutos()" (input)="minutos.set($any($event.target).value); v.limpiar('minutos')" />
      </lume-campo>
      <div class="lume-modal__acciones">
        <button lumeBoton="secundario" type="button" (click)="listo.emit()">Cancelar</button>
        <button lumeBoton type="submit" [cargando]="enviando()" [disabled]="!parametros()">Guardar</button>
      </div>
    </form>
  `,
})
export class ParametrosDeSeguridad implements OnInit {
  private readonly api = inject(UsuariosApi);
  private readonly avisos = inject(Avisos);
  private readonly sesion = inject(Sesion);
  readonly listo = output<void>();

  protected readonly parametros = signal<ParametrosSeguridad | null>(null);
  protected readonly errorCarga = signal<string | null>(null);
  protected readonly minutos = signal('');
  protected readonly enviando = signal(false);
  protected readonly v = crearValidacion({
    etiquetas: { minutos: 'Tiempo de inactividad (TIME OUT)' },
    campos: { minutosSesion: 'minutos' },
    testid: 'error-seguridad',
  });

  async ngOnInit(): Promise<void> {
    try {
      const p = await firstValueFrom(this.api.parametrosSeguridad());
      this.parametros.set(p);
      this.minutos.set(String(p.minutosSesion));
    } catch (x) {
      this.errorCarga.set(x instanceof ErrorApi ? x.message : 'No se pudo cargar el tiempo de inactividad.');
    }
  }

  protected async guardar(): Promise<void> {
    const maximo = this.parametros()?.minutosMaximo ?? 480;
    const n = leerEntero(this.minutos());
    const valido = n !== null && n >= 1 && n <= maximo;
    if (!this.v.validar({ minutos: valido ? null : `Escriba un número entero de minutos, de 1 a ${maximo}.` })) return;
    this.enviando.set(true);
    try {
      const r = await firstValueFrom(this.api.cambiarParametrosSeguridad({ minutosSesion: n }));
      this.listo.emit();
      setTimeout(() => this.avisos.mostrar(r.mensaje));
      // El shell toma los minutos nuevos (aviso de inactividad); si la recarga falla, el aviso ya salió y la sesión sigue igual
      void this.sesion.cargar().catch(() => undefined);
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo guardar.');
    } finally {
      this.enviando.set(false);
    }
  }
}

/** Modal «Nombres de perfiles» (U:717-767): el nombre visible de cada perfil base, mientras llega la adenda con el catálogo final. */
@Component({
  selector: 'lume-nombres-perfiles',
  imports: [...FORMULARIO_LUME],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="lume-pila" [lumeValidacion]="v">
      <p>Los nombres son configurables mientras llega la adenda con el catálogo final de perfiles. Los permisos no cambian.</p>
      <lume-resumen-errores [validacion]="v" />
      @for (p of bases(); track p.id) {
        <form class="lume-fila lume-fila--abajo" novalidate (submit)="$event.preventDefault(); guardar(p)">
          <lume-campo class="lume-crece" [etiqueta]="p.nombre" [error]="v.error('perfil' + p.id)">
            <input lumeEntrada maxlength="60" [value]="escrito(p)" (input)="escribir(p, $any($event.target).value)" />
          </lume-campo>
          <!-- aria-disabled (no disabled): al guardar, el foco no se pierde; el nombre dice de qué perfil es cada «Guardar» -->
          <button lumeBoton="secundario" type="submit" [attr.aria-disabled]="enviando() || escrito(p) === p.nombre || null">
            Guardar<span class="lume-solo-lector"> el nombre de {{ p.nombre }}</span>
          </button>
        </form>
      }
    </div>
  `,
})
export class NombresPerfiles {
  private readonly api = inject(UsuariosApi);
  private readonly avisos = inject(Avisos);
  private readonly listaUsuarios = inject(ListaDeUsuarios);
  /** Los perfiles de GET /api/usuarios: aquí solo van los base (`baseId` null). */
  readonly perfiles = input.required<PerfilAsignable[]>();

  protected readonly bases = computed(() => this.perfiles().filter((p) => p.baseId == null));
  private readonly textos = signal<Record<number, string>>({});
  protected readonly enviando = signal(false);
  /** El perfil que se está guardando: un 422 con `campo: nombre` marca su campo (`conCampo` del monolito). */
  private guardando: number | null = null;
  protected readonly v = crearValidacion({
    etiquetas: () => Object.fromEntries(this.bases().map((b) => ['perfil' + b.id, b.nombre])),
    campos: () => ({ nombre: 'perfil' + this.guardando }),
    testid: 'error-nombres-perfiles',
  });

  protected escrito(p: PerfilAsignable): string {
    return this.textos()[p.id] ?? p.nombre;
  }

  protected escribir(p: PerfilAsignable, valor: string): void {
    this.textos.update((t) => ({ ...t, [p.id]: valor }));
    this.v.limpiar('perfil' + p.id);
  }

  protected async guardar(p: PerfilAsignable): Promise<void> {
    const nombre = this.escrito(p);
    if (this.enviando() || nombre === p.nombre) return;
    if (!this.v.validar({ ['perfil' + p.id]: nombre.trim() ? null : 'Escriba el nombre del perfil.' })) return;
    this.enviando.set(true);
    this.guardando = p.id;
    try {
      const r = await firstValueFrom(this.api.renombrarPerfil(p.id, { nombre, descripcion: p.descripcion ?? null }));
      this.v.reiniciar();
      // El texto escrito se queda: sin parpadeo al nombre anterior mientras llega la lista nueva (que trae ese mismo nombre)
      this.listaUsuarios.recargar();
      this.avisos.mostrar(r.mensaje);
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo guardar el nombre.');
    } finally {
      this.enviando.set(false);
    }
  }
}
