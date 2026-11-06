import { afterNextRender, ChangeDetectionStrategy, Component, computed, DOCUMENT, inject, Injector, OnInit, signal, viewChild } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { type CursorLogs, type FilaLog, LogsApi, type OpcionesLogs, type OperacionLog } from '../../api/seguridad';
import { guardarArchivo } from '../../nucleo/descarga';
import { ErrorApi } from '../../nucleo/error-api';
import { diasEntre, errorFecha, periodo } from '../../nucleo/fechas';
import { conteo, fechaHora, hoyBogota, sumarDias } from '../../nucleo/formato';
import { errorCorreo } from '../../nucleo/validaciones';
import {
  Avisos,
  crearValidacion,
  CuerpoModal,
  EncabezadoPagina,
  Fecha,
  FORMULARIO_LUME,
  Insignia,
  Modal,
  type OpcionSelector,
  Selector,
  TablaAdaptable,
} from '../../ui';

/** Nombre de cada operación de la bitácora (NOMBRES de Logs.tsx). */
const NOMBRES: Record<string, string> = {
  insercion: 'Inserción',
  actualizacion: 'Actualización',
  eliminacion: 'Eliminación',
  consulta: 'Consulta',
  ingreso: 'Ingreso',
  salida: 'Salida',
};

const MAX_DIAS_TEXTO = 92;

/** Los filtros que se aplicaron con «Buscar»: lo que se ve es lo que se descarga y se envía. Sin parámetros = los últimos 7 días. */
interface Filtros {
  desde?: string;
  hasta?: string;
  usuarioId?: number;
  operacion?: OperacionLog;
  texto?: string;
  modulo?: string;
  tipo?: string;
}

/**
 * Logs del sistema (Anexo 5, prueba 15; Logs.tsx del monolito): solo el Administrador. Filtros que se aplican con «Buscar», tabla de 100
 * en 100 con «Ver más» (cursor), antes y después de cada cambio, descarga en Excel o CSV y envío por correo con los filtros aplicados.
 * Textos y data-testid del monolito.
 */
@Component({
  selector: 'lume-logs',
  imports: [...FORMULARIO_LUME, EncabezadoPagina, Fecha, Selector, Insignia, TablaAdaptable, Modal, CuerpoModal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './logs.html',
})
export class Logs implements OnInit {
  private readonly api = inject(LogsApi);
  private readonly avisos = inject(Avisos);
  private readonly documento = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  protected readonly fechaHora = fechaHora;
  protected readonly conteo = conteo;

  // Filtros en edición
  protected readonly desde = signal<string | null>(sumarDias(hoyBogota(), -7));
  protected readonly hasta = signal<string | null>(hoyBogota());
  protected readonly usuarioId = signal<string | null>(null);
  protected readonly operacion = signal<string | null>(null);
  protected readonly modulo = signal<string | null>(null);
  protected readonly tipo = signal('');
  protected readonly texto = signal('');
  private readonly campoDesde = viewChild.required<Fecha>('campoDesde');
  private readonly campoHasta = viewChild.required<Fecha>('campoHasta');

  protected readonly opciones = signal<OpcionesLogs | null>(null);
  protected readonly errorOpciones = signal<string | null>(null);
  protected readonly usuarios = computed<OpcionSelector[]>(() =>
    (this.opciones()?.usuarios ?? []).map((u) => ({ valor: String(u.id), etiqueta: `${u.nombre} (${u.usuario})` })),
  );
  protected readonly operaciones = computed<OpcionSelector[]>(() =>
    (this.opciones()?.operaciones ?? []).map((o) => ({ valor: o, etiqueta: NOMBRES[o] ?? o })),
  );
  protected readonly modulos = computed<OpcionSelector[]>(() => (this.opciones()?.modulos ?? []).map((m) => ({ valor: m, etiqueta: m })));

  /** Lo aplicado, las filas cargadas y el cursor de la página siguiente. */
  private aplicado: Filtros = {};
  private version = 0;
  protected readonly filas = signal<FilaLog[]>([]);
  protected readonly siguiente = signal<CursorLogs | null>(null);
  protected readonly cargando = signal(false);
  protected readonly cargandoMas = signal(false);
  protected readonly errorLista = signal<string | null>(null);
  /** Lo que se anuncia al terminar «Buscar» o «Ver más» (región viva que siempre está; en la carga inicial no se anuncia nada). */
  protected readonly anuncio = signal('');

  protected readonly vFiltros = crearValidacion({ etiquetas: { desde: 'Desde', hasta: 'Hasta', texto: 'Texto' }, testid: 'error-filtros-logs' });
  protected readonly vDescarga = crearValidacion({ testid: 'error-descarga-logs' });
  protected readonly descargando = signal<'xlsx' | 'csv' | null>(null);

  // Modales
  protected readonly cambiosDe = signal<FilaLog | null>(null);
  protected readonly envioAbierto = signal(false);
  protected readonly para = signal('');
  protected readonly enviando = signal(false);
  protected readonly vEnvio = crearValidacion({ etiquetas: { para: 'Correo de destino' }, testid: 'error-envio-logs' });

  /** Nombre de la operación (el valor tal cual si no es una de las seis). */
  protected nombreOperacion(o: string): string {
    return NOMBRES[o] ?? o;
  }

  ngOnInit(): void {
    void this.cargarOpciones();
    void this.cargar(null, false);
  }

  private async cargarOpciones(): Promise<void> {
    try {
      this.opciones.set(await firstValueFrom(this.api.opcionesLogs()));
    } catch (x) {
      this.errorOpciones.set(x instanceof ErrorApi ? x.message : 'Error');
    }
  }

  /**
   * La primera página de lo aplicado (o, con `despues`, la siguiente, que se suma a las anteriores). Una búsqueda nueva deja la tabla
   * vacía mientras llega (lo que se ve es siempre lo aplicado, lo que se descarga y se envía) y deja sin efecto un «Ver más» en curso.
   */
  private async cargar(despues: CursorLogs | null = null, anunciar = true): Promise<void> {
    const n = despues ? this.version : ++this.version;
    const f = this.aplicado;
    if (!despues) {
      this.filas.set([]);
      this.siguiente.set(null);
      this.cargandoMas.set(false);
    }
    this.errorLista.set(null);
    (despues ? this.cargandoMas : this.cargando).set(true);
    try {
      const p = await firstValueFrom(
        this.api.listarLogs(f.desde, f.hasta, f.usuarioId, f.operacion, f.texto, f.modulo, f.tipo, despues?.fecha, despues?.id),
      );
      if (n !== this.version) return;
      // Sin repetir un registro si dos páginas se solapan (track por id)
      const vistos = new Set(despues ? this.filas().map((x) => x.id) : []);
      const nuevas = p.filas.filter((x) => !vistos.has(x.id));
      const antes = despues ? this.filas().length : 0;
      this.filas.update((x) => (despues ? [...x, ...nuevas] : nuevas));
      this.siguiente.set(p.siguiente ?? null);
      if (anunciar) {
        const total = this.filas().length;
        this.anuncio.set(despues ? `${conteo(nuevas.length)} registros más; ${conteo(total)} en total.` : total ? `${conteo(total)} registros.` : '');
      }
      // «Ver más» se va con la última página: el foco pasa al primer registro nuevo (no se queda en el body)
      if (despues && !this.siguiente() && nuevas.length) {
        afterNextRender(
          () => this.documento.querySelector<HTMLElement>(`[data-testid="tabla-logs"] tbody tr:nth-child(${antes + 1})`)?.focus(),
          { injector: this.injector },
        );
      }
    } catch (x) {
      if (n === this.version) this.errorLista.set(x instanceof ErrorApi ? x.message : 'No se pudieron cargar los registros.');
    } finally {
      if (n === this.version) (despues ? this.cargandoMas : this.cargando).set(false);
    }
  }

  protected verMas(): void {
    const s = this.siguiente();
    if (s && !this.cargandoMas() && !this.cargando()) void this.cargar(s);
  }

  /** Cambiar una fecha quita los errores de las fechas y del texto (el periodo cambió). */
  protected cambioDeFecha(): void {
    for (const c of ['desde', 'hasta', 'texto']) this.vFiltros.limpiar(c);
  }

  /** «Buscar»: valida, aplica los filtros (los que se descargan y se envían) y carga la primera página. */
  protected buscar(): void {
    const { desde, hasta } = periodo(this.desde(), this.hasta());
    const texto = this.texto().trim();
    const ok = this.vFiltros.validar({
      desde: errorFecha(this.campoDesde().texto()),
      hasta: errorFecha(this.campoHasta().texto()),
      texto: texto && diasEntre(desde, hasta) > MAX_DIAS_TEXTO ? 'Para buscar por texto, use un periodo de máximo 92 días.' : null,
    });
    if (!ok) return;
    const tipo = this.tipo().trim();
    this.aplicado = {
      desde,
      hasta,
      ...(this.usuarioId() ? { usuarioId: Number(this.usuarioId()) } : {}),
      ...(this.operacion() ? { operacion: this.operacion() as OperacionLog } : {}),
      ...(texto ? { texto } : {}),
      ...(this.modulo() ? { modulo: this.modulo()! } : {}),
      ...(tipo ? { tipo } : {}),
    };
    void this.cargar();
  }

  protected async descargar(formato: 'xlsx' | 'csv'): Promise<void> {
    if (this.descargando()) return;
    const f = this.aplicado;
    this.descargando.set(formato);
    try {
      const r = await firstValueFrom(this.api.exportarLogs(f.desde, f.hasta, f.usuarioId, f.operacion, f.texto, f.modulo, f.tipo, formato, 'response'));
      guardarArchivo(r, this.documento, `logs_auditoria.${formato}`, formato);
      this.vDescarga.reiniciar();
    } catch (x) {
      this.vDescarga.errorServidor(x, 'No se pudo descargar el archivo.');
    } finally {
      this.descargando.set(null);
    }
  }

  protected abrirEnvio(): void {
    this.para.set(this.opciones()?.correoDefecto ?? '');
    this.vEnvio.reiniciar();
    this.envioAbierto.set(true);
  }

  protected async enviar(): Promise<void> {
    // Un segundo Enter mientras se envía no manda otro correo
    if (this.enviando()) return;
    const para = this.para().trim();
    if (!this.vEnvio.validar({ para: errorCorreo(para, 'Escriba un correo válido para enviar el reporte.', 'Escriba el correo de destino.') })) return;
    const f = this.aplicado;
    this.enviando.set(true);
    try {
      const r = await firstValueFrom(
        this.api.enviarLogs({
          desde: f.desde ?? null,
          hasta: f.hasta ?? null,
          usuarioId: f.usuarioId ?? null,
          operacion: f.operacion ?? null,
          texto: f.texto ?? null,
          modulo: f.modulo ?? null,
          tipo: f.tipo ?? null,
          formato: 'xlsx',
          para,
        }),
      );
      this.envioAbierto.set(false);
      setTimeout(() => this.avisos.mostrar(r.mensaje));
    } catch (x) {
      this.vEnvio.errorServidor(x, 'No se pudo enviar el correo.');
    } finally {
      this.enviando.set(false);
    }
  }
}
