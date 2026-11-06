import { ChangeDetectionStrategy, Component, computed, DOCUMENT, inject, input, OnInit, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { type DatosUsuario, type FilaUsuario, type ListaUsuarios, UsuariosApi } from '../../api/seguridad';
import { conTexto, errorClave, errorCorreo, errorTelefono } from '../../nucleo/validaciones';
import { ListaDeUsuarios } from './lista-usuarios';
import { Avisos, crearValidacion, FORMULARIO_LUME, GrupoCasillas, Interruptor, type OpcionCasilla, type OpcionSelector, Selector } from '../../ui';

/** Ids fijos de reglas/Perfil.java de los perfiles que piden un dato de alcance. */
const SANIDAD = 6;
const ESM = 7;
const OPL_REGIONAL = 9;

const USUARIO = /^[a-z0-9._-]{3,50}$/;

/** Nombre de cada campo en el resumen de errores (ETIQUETAS_USUARIO del monolito). */
export const ETIQUETAS_USUARIO: Record<string, string> = {
  nombre: 'Nombre completo',
  usuario: 'Usuario',
  correo: 'Correo',
  documento: 'Documento',
  telefono: 'Teléfono móvil',
  perfiles: 'Perfiles',
  alcancePunto: 'Establecimiento (ESM)',
  alcanceRegional: 'Regional (OPL regional)',
  alcanceFuerza: 'Fuerza (Sanidad)',
  claveInicial: 'Contraseña inicial',
};

/**
 * Formulario «Nuevo usuario» / «Editar usuario» de la pantalla Usuarios (U:447-628 del monolito), dentro de su modal: datos, perfiles y
 * roles adicionales, el alcance que pide cada perfil y, solo al crear, la contraseña inicial. Textos y data-testid del monolito.
 */
@Component({
  selector: 'lume-formulario-usuario',
  imports: [...FORMULARIO_LUME, Selector, GrupoCasillas, Interruptor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Un puntero soltado (o cancelado) en cualquier parte ya no difiere el error del teléfono
  host: { '(document:pointerup)': 'soltarPuntero()', '(document:pointercancel)': 'soltarPuntero()' },
  template: `
    <form class="lume-pila" [lumeValidacion]="v" novalidate (pointerdown)="punteroAbajo = true" (submit)="$event.preventDefault(); guardar()">
      <lume-resumen-errores [validacion]="v" />
      <lume-campo etiqueta="Nombre completo" obligatorio [error]="v.error('nombre')">
        <input lumeEntrada maxlength="150" autocomplete="off" [value]="nombre()" (input)="nombre.set(valor($event)); v.limpiar('nombre')" />
      </lume-campo>
      <lume-campo etiqueta="Usuario" descripcion="Minúsculas sin tildes, números, punto, guion o guion bajo (3 a 50)." obligatorio [error]="v.error('usuario')">
        <input lumeEntrada maxlength="50" autocomplete="off" autocapitalize="none" spellcheck="false" [value]="usuario()" (input)="minusculas($event); v.limpiar('usuario')" />
      </lume-campo>
      <lume-campo etiqueta="Correo" obligatorio [error]="v.error('correo')">
        <input lumeEntrada type="email" maxlength="150" autocomplete="off" [value]="correo()" (input)="correo.set(valor($event)); v.limpiar('correo')" />
      </lume-campo>
      <div class="lume-rejilla-2">
        <lume-campo etiqueta="Documento" [error]="v.error('documento')">
          <input lumeEntrada autocomplete="off" [value]="documento()" (input)="documento.set(valor($event))" />
        </lume-campo>
        <lume-campo
          etiqueta="Teléfono móvil"
          descripcion="Móvil colombiano (+57 y 10 dígitos que empiezan por 3). Recibe por SMS las alertas críticas si su perfil es destinatario."
          [error]="errorDelTelefono()"
        >
          <input lumeEntrada type="tel" inputmode="tel" autocomplete="off" placeholder="+57 3xx xxx xxxx" data-testid="campo-telefono" [value]="telefono()"
            (input)="telefono.set(valor($event)); v.limpiar('telefono')" (blur)="salirDelTelefono()" />
        </lume-campo>
      </div>
      <lume-grupo-casillas leyenda="Perfiles" [opciones]="opcionesPerfiles()" [(valores)]="perfiles" [error]="conTexto(v.error('perfiles'))" />
      @if (pide(ESM)) {
        <lume-campo etiqueta="Establecimiento (ESM)" obligatorio [error]="v.error('alcancePunto')">
          <lume-selector [opciones]="puntos()" [(valor)]="alcancePunto" buscable (valorChange)="v.limpiar('alcancePunto')" />
        </lume-campo>
      }
      @if (pide(OPL_REGIONAL)) {
        <lume-campo etiqueta="Regional (OPL regional)" obligatorio [error]="v.error('alcanceRegional')">
          <lume-selector [opciones]="regionales()" [(valor)]="alcanceRegional" (valorChange)="v.limpiar('alcanceRegional')" />
        </lume-campo>
      }
      @if (pide(SANIDAD)) {
        <lume-campo etiqueta="Fuerza (Sanidad)" obligatorio [error]="v.error('alcanceFuerza')">
          <lume-selector [opciones]="fuerzas()" [(valor)]="alcanceFuerza" (valorChange)="v.limpiar('alcanceFuerza')" />
        </lume-campo>
      }
      @if (!editado()) {
        <lume-interruptor etiqueta="Asignar contraseña inicial (se cambia al primer ingreso)" [(activo)]="conClaveInicial" />
        @if (conClaveInicial()) {
          <lume-campo etiqueta="Contraseña inicial" descripcion="Mínimo 12 caracteres, sin tildes ni ñ. El usuario deberá cambiarla en su primer ingreso." obligatorio clave
            [error]="v.error('claveInicial')">
            <input lumeEntrada type="password" autocomplete="new-password" maxlength="64" [value]="claveInicial()"
              (input)="claveInicial.set(valor($event)); v.limpiar('claveInicial')" />
          </lume-campo>
        }
      }
      <p class="lume-texto-pequeno lume-apagado">
        @if (!editado() && conClaveInicial()) {
          Entregue la contraseña inicial al usuario por un canal seguro. No se envía correo.
        } @else {
          Al crear el usuario se le envía un enlace de un solo uso (60 minutos) para definir su contraseña. Nadie más la conoce.
        }
      </p>
      <div class="lume-modal__acciones">
        <button lumeBoton="secundario" type="button" (click)="cancelar.emit()">Cancelar</button>
        <button lumeBoton type="submit" [cargando]="enviando()">Guardar</button>
      </div>
    </form>
  `,
})
export class FormularioUsuario implements OnInit {
  private readonly api = inject(UsuariosApi);
  private readonly avisos = inject(Avisos);
  private readonly documentoHtml = inject(DOCUMENT);
  private readonly listaUsuarios = inject(ListaDeUsuarios);

  /** Usuario que se edita (null: uno nuevo). */
  readonly editado = input<FilaUsuario | null>(null);
  /** Perfiles, establecimientos, regionales y fuerzas de GET /api/usuarios. */
  readonly lista = input.required<ListaUsuarios>();
  /** Guardó: el modal se cierra (la lista se recarga y va a su fila por `ListaDeUsuarios`, aunque el modal ya se haya cerrado). */
  readonly guardado = output<string>();
  readonly cancelar = output<void>();

  protected readonly ESM = ESM;
  protected readonly OPL_REGIONAL = OPL_REGIONAL;
  protected readonly SANIDAD = SANIDAD;
  protected readonly conTexto = conTexto;

  protected readonly nombre = signal('');
  protected readonly usuario = signal('');
  protected readonly correo = signal('');
  protected readonly documento = signal('');
  protected readonly telefono = signal('');
  protected readonly perfiles = signal<string[]>([]);
  protected readonly alcancePunto = signal<string | null>(null);
  protected readonly alcanceRegional = signal<string | null>(null);
  protected readonly alcanceFuerza = signal<string | null>(null);
  protected readonly conClaveInicial = signal(false);
  protected readonly claveInicial = signal('');
  protected readonly enviando = signal(false);
  /** El error local del teléfono sale al salir del campo (no mientras se escribe). */
  private readonly telefonoTocado = signal(false);
  private telefonoGuardado: string | null = null;
  protected punteroAbajo = false;

  protected readonly v = crearValidacion({ etiquetas: ETIQUETAS_USUARIO, testid: 'error-usuario' });

  protected readonly opcionesPerfiles = computed<OpcionCasilla[]>(() => {
    const ps = this.lista().perfiles;
    const nombreDe = (id: number) => ps.find((p) => p.id === id)?.nombre ?? String(id);
    return ps.map((p) => ({
      valor: String(p.id),
      etiqueta: p.baseId == null ? p.nombre : `${p.nombre} (rol adicional · base ${nombreDe(p.baseId)})`,
      descripcion: p.descripcion ?? undefined,
    }));
  });
  protected readonly puntos = computed<OpcionSelector[]>(() =>
    this.lista().puntos.map((p) => ({ valor: String(p.id), etiqueta: `${p.sigla ? p.sigla + ' · ' : ''}${p.nombre} (${p.fuerza ?? ''})` })),
  );
  protected readonly regionales = computed<OpcionSelector[]>(() => this.lista().regionales.map((r) => ({ valor: String(r.id), etiqueta: r.nombre })));
  protected readonly fuerzas = computed<OpcionSelector[]>(() => this.lista().fuerzas.map((f) => ({ valor: f.codigo, etiqueta: f.nombre })));

  /** Perfiles base que piden su dato de alcance: los marcados y la base de cada rol adicional marcado (`conBase` del monolito). */
  private readonly bases = computed(() => {
    const ps = this.lista().perfiles;
    return new Set(this.perfiles().map((v) => {
      const p = ps.find((x) => x.id === Number(v));
      return p?.baseId ?? Number(v);
    }));
  });

  protected readonly errorDelTelefono = computed(() =>
    (this.telefonoTocado() ? errorTelefono(this.telefono(), this.telefonoGuardado) : null) ?? this.v.error('telefono'),
  );

  ngOnInit(): void {
    const u = this.editado();
    if (!u) return;
    this.nombre.set(u.nombre);
    this.usuario.set(u.usuario);
    this.correo.set(u.correo);
    this.documento.set(u.documento ?? '');
    this.telefono.set(u.telefono ?? '');
    this.telefonoGuardado = u.telefono ?? null;
    this.perfiles.set(u.perfiles.map(String));
    this.alcancePunto.set(u.alcancePunto == null ? null : String(u.alcancePunto));
    this.alcanceRegional.set(u.alcanceRegional == null ? null : String(u.alcanceRegional));
    this.alcanceFuerza.set(u.alcanceFuerza ?? null);
  }

  protected soltarPuntero(): void {
    // Después del listener del blur del teléfono (que también espera un setTimeout), para que ese aún vea el puntero abajo
    setTimeout(() => (this.punteroAbajo = false));
  }

  protected pide(base: number): boolean {
    return this.bases().has(base);
  }

  protected valor(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected minusculas(e: Event): void {
    const el = e.target as HTMLInputElement;
    const v = el.value.toLowerCase();
    if (v !== el.value) {
      const cursor = el.selectionStart;
      el.value = v;
      el.setSelectionRange(cursor, cursor);
    }
    this.usuario.set(v);
  }

  /**
   * Al salir del teléfono aparece su error; si la salida es por un clic dentro del formulario, después de soltar el botón: así el
   * mensaje no mueve lo que está debajo antes de que el clic llegue (U:516-521).
   */
  protected salirDelTelefono(): void {
    if (!this.punteroAbajo) {
      this.telefonoTocado.set(true);
      return;
    }
    this.documentoHtml.addEventListener(
      'pointerup',
      () => setTimeout(() => {
        this.punteroAbajo = false;
        this.telefonoTocado.set(true);
      }),
      { once: true },
    );
  }

  protected async guardar(): Promise<void> {
    this.punteroAbajo = false;
    const usuario = this.usuario().trim();
    const nuevo = this.editado() === null;
    const conClave = nuevo && this.conClaveInicial();
    // En el orden del monolito (U:201-214); el teléfono no detiene el envío: lo decide el servidor
    const ok = this.v.validar({
      nombre: this.nombre().trim() ? null : 'Escriba el nombre completo.',
      usuario: !usuario
        ? 'Escriba el usuario.'
        : USUARIO.test(usuario)
          ? null
          : 'El usuario debe tener de 3 a 50 caracteres: letras minúsculas sin tildes, números, punto, guion o guion bajo.',
      correo: errorCorreo(this.correo()),
      alcancePunto: this.pide(ESM) && !this.alcancePunto() ? 'Elija el establecimiento.' : null,
      alcanceRegional: this.pide(OPL_REGIONAL) && !this.alcanceRegional() ? 'Elija la regional.' : null,
      alcanceFuerza: this.pide(SANIDAD) && !this.alcanceFuerza() ? 'Elija la fuerza.' : null,
      claveInicial: conClave ? errorClave(this.claveInicial()) : null,
    });
    if (!ok) return;
    const cuerpo: DatosUsuario = {
      nombre: this.nombre().trim(),
      usuario,
      correo: this.correo().trim(),
      perfiles: this.perfiles().map(Number),
      alcanceFuerza: this.pide(SANIDAD) ? this.alcanceFuerza() : null,
      alcancePunto: this.pide(ESM) && this.alcancePunto() ? Number(this.alcancePunto()) : null,
      alcanceRegional: this.pide(OPL_REGIONAL) && this.alcanceRegional() ? Number(this.alcanceRegional()) : null,
      documento: this.documento().trim() || null,
      telefono: this.telefono().trim() || null,
      claveInicial: conClave ? this.claveInicial() : null,
    };
    this.enviando.set(true);
    try {
      const u = this.editado();
      const r = await firstValueFrom(u ? this.api.editarUsuario(u.id, cuerpo) : this.api.crearUsuario(cuerpo));
      this.v.reiniciar();
      this.listaUsuarios.recargar(usuario);
      this.guardado.emit(usuario);
      // Si el correo de bienvenida no salió, el aviso es naranja y se queda hasta que la persona lo cierra. El enlace para definir la
      // contraseña va solo por correo: `enlaceLocal` (siempre null en el contrato) no se muestra
      const mensaje = r.mensaje;
      setTimeout(() => (r.correoOk === false ? this.avisos.mostrar(mensaje, 'orange', null) : this.avisos.mostrar(mensaje, 'green', 6000)));
    } catch (x) {
      this.v.errorServidor(x, 'No se pudo guardar.');
    } finally {
      this.enviando.set(false);
    }
  }
}
