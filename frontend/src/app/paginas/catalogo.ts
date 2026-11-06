import { ChangeDetectionStrategy, Component, inject, signal, viewChild } from '@angular/core';
import { errorFecha } from '../nucleo/fechas';
import {
  Avisos,
  crearPaginacion,
  CuerpoModal,
  EncabezadoPagina,
  errorEntero,
  Fecha,
  FORMULARIO_LUME,
  GrupoCasillas,
  Interruptor,
  Modal,
  Numero,
  Paginacion,
  Selector,
  TablaAdaptable,
  crearValidacion,
  type OpcionCasilla,
  type OpcionSelector,
} from '../ui';

/**
 * Catálogo de componentes LUME, SOLO en desarrollo (`/__catalogo`, registrado con `ngDevMode` en app.routes.ts: el build de producción
 * lo elimina; ningún entorno publicado debe usar la configuración development).
 * Sirve para revisar los componentes con teclado y lector de pantalla, y para las e2e de teclado mientras ninguna pantalla los usa.
 * Datos ficticios.
 */
@Component({
  selector: 'lume-catalogo',
  imports: [...FORMULARIO_LUME, EncabezadoPagina, Selector, GrupoCasillas, Interruptor, Numero, Fecha, Paginacion, TablaAdaptable, Modal, CuerpoModal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="lume-catalogo">
      <div class="lume-pila">
        <lume-encabezado-pagina titulo="Catálogo de componentes" descripcion="Solo en desarrollo. Datos ficticios." />

        <form class="lume-card lume-pila" [lumeValidacion]="v" novalidate (submit)="$event.preventDefault(); validar()">
          <h2>Formulario</h2>
          <lume-resumen-errores [validacion]="v" />
          <lume-campo etiqueta="Fuerza (Sanidad)" obligatorio [error]="v.error('fuerza')">
            <lume-selector [opciones]="fuerzas" [(valor)]="fuerza" />
          </lume-campo>
          <lume-campo etiqueta="Establecimiento (ESM)" [error]="v.error('esm')">
            <lume-selector [opciones]="establecimientos" [(valor)]="esm" buscable limpiable />
          </lume-campo>
          <lume-grupo-casillas leyenda="Perfiles" obligatorio [opciones]="perfiles" [(valores)]="elegidos" [error]="v.error('perfiles')" />
          <lume-interruptor etiqueta="Asignar contraseña inicial (se cambia al primer ingreso)" [(activo)]="conClave" />
          <lume-campo etiqueta="Tiempo de inactividad (TIME OUT), en minutos" descripcion="Solo números enteros, entre 1 y 480." obligatorio [error]="v.error('minutos')">
            <input lumeEntrada lumeNumero data-testid="minutos-sesion" [value]="minutos()" (input)="minutos.set($any($event.target).value)" />
          </lume-campo>
          <lume-campo etiqueta="Desde" descripcion="Formato DD/MM/AAAA (día, mes y año)." [error]="v.error('desde')">
            <lume-fecha #desde [(valor)]="fechaDesde" />
          </lume-campo>
          <div class="lume-fila">
            <button lumeBoton type="submit">Validar</button>
            <button lumeBoton="secundario" type="button" (click)="modal.set(true)">Abrir modal</button>
            <button lumeBoton="outline" type="button" (click)="avisoNaranja()">Aviso persistente</button>
          </div>
          <p data-testid="valores">{{ resumen() }}</p>
        </form>

        <section class="lume-card">
          <h2>Tabla paginada</h2>
          <div lumeTablaAdaptable="Registros">
            <table class="lume-tabla">
              <thead><tr><th>N.º</th><th>Nombre</th></tr></thead>
              <tbody>
                @for (f of pag.visibles(); track f) {
                  <tr><td>{{ f }}</td><td>Registro ficticio {{ f }}</td></tr>
                }
              </tbody>
            </table>
          </div>
          <lume-paginacion nombre="registros" [total]="pag.total()" [pagina]="pag.pagina()" (paginaChange)="pag.irAPagina($event)" />
        </section>
      </div>

      <lume-modal [abierto]="modal()" titulo="Selector en un modal" (cerrar)="modal.set(false)">
        <ng-template lumeCuerpoModal>
          <lume-campo etiqueta="Regional (OPL regional)">
            <lume-selector [opciones]="regionales" [(valor)]="regional" buscable />
          </lume-campo>
          <div class="lume-modal__acciones">
            <button lumeBoton type="button" (click)="modal.set(false)">Listo</button>
          </div>
        </ng-template>
      </lume-modal>
    </main>
  `,
})
export class Catalogo {
  private readonly avisos = inject(Avisos);
  protected readonly fuerzas: OpcionSelector[] = [
    { valor: 'EJC', etiqueta: 'Ejército' },
    { valor: 'ARC', etiqueta: 'Armada' },
    { valor: 'FAC', etiqueta: 'Fuerza Aérea' },
    { valor: 'PON', etiqueta: 'Policía' },
  ];
  protected readonly establecimientos: OpcionSelector[] = Array.from({ length: 30 }, (_, i) => ({
    valor: String(i + 1),
    etiqueta: `ESM ${String(i + 1).padStart(2, '0')} · Establecimiento ficticio ${i % 3 === 0 ? 'Bogotá' : i % 3 === 1 ? 'Medellín' : 'Cali'}`,
  }));
  protected readonly regionales: OpcionSelector[] = ['Norte', 'Centro', 'Sur', 'Oriente', 'Occidente'].map((r) => ({ valor: r, etiqueta: `Regional ${r}` }));
  protected readonly perfiles: OpcionCasilla[] = [
    { valor: '1', etiqueta: 'Administrador' },
    { valor: '3', etiqueta: 'Auditor', descripcion: 'Hace las visitas de campo.' },
    { valor: '2', etiqueta: 'Coordinador' },
  ];

  protected readonly fuerza = signal<string | null>(null);
  protected readonly esm = signal<string | null>(null);
  protected readonly regional = signal<string | null>(null);
  protected readonly elegidos = signal<string[]>([]);
  protected readonly conClave = signal(false);
  protected readonly minutos = signal('10');
  protected readonly fechaDesde = signal<string | null>(null);
  protected readonly modal = signal(false);
  protected readonly filas = signal(Array.from({ length: 60 }, (_, i) => i + 1));
  protected readonly pag = crearPaginacion(this.filas);
  protected readonly v = crearValidacion({
    etiquetas: { fuerza: 'Fuerza (Sanidad)', perfiles: 'Perfiles', minutos: 'Tiempo de inactividad', desde: 'Desde' },
    testid: 'error-catalogo',
  });
  private readonly campoDesde = viewChild.required<Fecha>('desde');

  protected resumen(): string {
    return `fuerza=${this.fuerza() ?? '-'} esm=${this.esm() ?? '-'} regional=${this.regional() ?? '-'} perfiles=${this.elegidos().join(',') || '-'} clave=${this.conClave()} minutos=${this.minutos()} desde=${this.fechaDesde() ?? '-'}`;
  }

  protected validar(): void {
    this.v.validar({
      fuerza: this.fuerza() ? null : 'Elija la fuerza.',
      perfiles: this.elegidos().length ? null : 'Elija al menos un perfil.',
      minutos: errorEntero(this.minutos(), { min: 1, max: 480, obligatorio: true, nombre: 'los minutos' }),
      desde: errorFecha(this.campoDesde().texto()),
    });
  }

  protected avisoNaranja(): void {
    this.avisos.mostrar('Usuario creado, pero el correo de bienvenida no salió.', 'orange', null);
  }
}
