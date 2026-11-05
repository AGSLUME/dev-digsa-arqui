import { BreakpointObserver } from '@angular/cdk/layout';
import { afterNextRender, ChangeDetectionStrategy, Component, computed, DestroyRef, ElementRef, inject, Injector, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationCancel, NavigationEnd, NavigationError, NavigationStart, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { fechaHora } from '../nucleo/formato';
import { mmss } from '../nucleo/inactividad';
import { agrupar, GRUPO_CUENTA, ICONO_INICIO } from '../nucleo/menu';
import { iniciales, migasDe } from '../nucleo/migas';
import { Sesion } from '../nucleo/sesion';
import { CambiarMiClave } from '../paginas/cuenta/cambiar-mi-clave';
import { Ayuda } from '../ui/ayuda';
import { Icono } from '../ui/icono';
import { CuerpoModal, Modal } from '../ui/modal';
import { InterruptorSonido } from '../ui/interruptor-sonido';
import { AvisoInactividad, RelojSesion } from './aviso-inactividad';
import { EntradaMenu } from './entrada-menu';

/** Medidas y preferencia del shell LUME (LUME_design_system.md), como frontend/src/shared/Plantilla.tsx. */
const CLAVE_COLAPSADO = 'lume.sidebar.colapsado';
const CELULAR = '(max-width: 47.99em)';
const ESTRECHO = '(max-width: 900px)';

function leerPreferencia(): boolean | null {
  try {
    const v = localStorage.getItem(CLAVE_COLAPSADO);
    return v === null ? null : v === 'si';
  } catch {
    return null;
  }
}

function guardarPreferencia(colapsado: boolean): void {
  try {
    localStorage.setItem(CLAVE_COLAPSADO, colapsado ? 'si' : 'no');
  } catch {
    // sin almacenamiento: vale para esta pestaña
  }
}

/**
 * Shell del design system LUME (port de frontend/src/shared/Plantilla.tsx): sidebar de 250 px en --lume-blue-dark, colapsable a 68 px
 * solo con íconos (por debajo de 900 px colapsa solo; en celular es un panel que se abre con «Menú»), cabecera blanca de 64 px con la
 * ruta de migas, el reloj de Bogotá, la cuenta regresiva de inactividad, la campana (solo si el perfil tiene Alertas), el sonido y el
 * usuario. El menú sale del servidor (`menu` de la sesión, Anexo 5 num. 3.7), agrupado por módulos con «Mi cuenta» fija al pie. La marca
 * no es un encabezado: el primero es el h1 de la página.
 */
@Component({
  selector: 'lume-plantilla',
  imports: [RouterOutlet, RouterLink, Icono, Ayuda, EntradaMenu, AvisoInactividad, Modal, CuerpoModal, CambiarMiClave, InterruptorSonido],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './plantilla.html',
  host: {
    class: 'lume-shell',
    '[class.lume-shell--colapsado]': 'colapsado()',
    '[class.lume-shell--celular]': 'celular()',
  },
})
export class Plantilla {
  private readonly router = inject(Router);
  protected readonly sesion = inject(Sesion);
  private readonly reloj = inject(RelojSesion);
  private readonly pantallas = inject(BreakpointObserver);
  private readonly injector = inject(Injector);
  private readonly hamburguesa = viewChild<ElementRef<HTMLButtonElement>>('hamburguesa');
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly contenido = viewChild<ElementRef<HTMLElement>>('contenido');

  protected readonly datos = computed(() => this.sesion.datos()!);
  protected readonly grupos = computed(() => agrupar(this.datos().menu));
  protected readonly perfiles = computed(() => this.datos().perfiles.join(' · '));
  protected readonly iniciales = computed(() => iniciales(this.datos().nombre));
  protected readonly GRUPO_CUENTA = GRUPO_CUENTA;
  protected readonly ICONO_INICIO = ICONO_INICIO;

  private readonly ruta = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );
  /** Ruta sin consulta ni fragmento. */
  protected readonly camino = computed(() => this.ruta().split(/[?#]/)[0] || '/');
  protected readonly migas = computed(() => migasDe(this.camino(), this.grupos()));

  // Valor inicial en la primera pintura (sin esperar): el sidebar no se pinta a 250 px para pasar enseguida a 68 px
  protected readonly celular = toSignal(this.pantallas.observe(CELULAR).pipe(map((r) => r.matches)), {
    initialValue: this.pantallas.isMatched(CELULAR),
  });
  private readonly estrecho = toSignal(this.pantallas.observe(ESTRECHO).pipe(map((r) => r.matches)), {
    initialValue: this.pantallas.isMatched(ESTRECHO),
  });
  private readonly preferencia = signal<boolean | null>(leerPreferencia());
  /** En celular el panel siempre va completo; en tableta (< 900 px) colapsa solo salvo que la persona lo abra. */
  protected readonly colapsado = computed(() => !this.celular() && (this.preferencia() ?? this.estrecho()));
  /** Panel del menú abierto en celular. */
  protected readonly abierto = signal(false);
  protected readonly claveAbierta = signal(false);
  /** El panel del menú tapa la página (celular): lo de atrás queda inerte. */
  protected readonly panelEncima = computed(() => this.celular() && this.abierto());

  /** Cada pantalla se descarga al abrirla: mientras tanto, el indicador (el menú y la cabecera siguen a la vista). */
  protected readonly cargando = signal(false);

  protected readonly ahora = signal(Date.now());
  protected readonly fechaHora = computed(() => fechaHora(this.ahora()));
  protected readonly cuentaRegresiva = computed(() => {
    const r = this.reloj.restantes();
    return r === null ? null : mmss(r);
  });

  constructor() {
    // Fecha y hora militar de Bogotá (Anexo 5 num. 2.4), que avanza sola
    const t = setInterval(() => this.ahora.set(Date.now()), 15_000);
    let primera = true;
    const s = this.router.events.subscribe((e) => {
      if (e instanceof NavigationStart) this.cargando.set(true);
      if (e instanceof NavigationEnd || e instanceof NavigationCancel || e instanceof NavigationError) {
        this.cargando.set(false);
        this.abierto.set(false);
      }
      // Cambio de pantalla: el foco va al contenido (el lector empieza por el h1 de la pantalla nueva; en celular el enlace pulsado
      // desaparece con el panel). No en la primera carga: ahí el foco es del navegador
      if (e instanceof NavigationEnd) {
        if (!primera) this.despues(() => this.contenido()?.nativeElement.focus({ preventScroll: true }));
        primera = false;
      }
    });
    inject(DestroyRef).onDestroy(() => {
      clearInterval(t);
      s.unsubscribe();
    });
  }

  protected enlace(ruta: string): string {
    return ruta === 'inicio' ? '/' : '/' + ruta;
  }

  protected activa(ruta: string): boolean {
    const c = this.camino();
    return ruta === 'inicio' ? c === '/' : c === '/' + ruta || c.startsWith('/' + ruta + '/');
  }

  protected alternarColapsado(): void {
    const nuevo = !this.colapsado();
    this.preferencia.set(nuevo);
    guardarPreferencia(nuevo);
  }

  private despues(f: () => void): void {
    afterNextRender(f, { injector: this.injector });
  }

  /** «Ir al contenido»: el foco al contenido sin seguir el ancla (con <base href> iría a «/#contenido»). */
  protected irAlContenido(e: Event): void {
    e.preventDefault();
    this.contenido()?.nativeElement.focus();
  }

  /** «Menú» en celular: al abrir, el foco va a la primera entrada del panel. */
  protected alternarPanel(): void {
    if (this.abierto()) return this.cerrarPanel();
    this.abierto.set(true);
    this.despues(() => this.panel()?.nativeElement.querySelector<HTMLElement>('a, button')?.focus());
  }

  /** Escape o «Menú» con el panel abierto: se cierra y el foco vuelve a «Menú». */
  protected cerrarPanel(): void {
    if (!this.panelEncima()) return;
    this.abierto.set(false);
    this.despues(() => this.hamburguesa()?.nativeElement.focus());
  }

  protected abrirClave(): void {
    this.abierto.set(false);
    this.claveAbierta.set(true);
  }

  /** Al cerrar «Cambiar mi contraseña» desde el panel del celular, el botón que lo abrió ya no se ve: el foco va a «Menú». */
  protected cerrarClave(): void {
    this.claveAbierta.set(false);
    if (this.celular()) this.despues(() => this.hamburguesa()?.nativeElement.focus());
  }

  protected salir(): void {
    void this.sesion.salir();
  }
}
