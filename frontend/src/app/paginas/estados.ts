import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { rutaInterna } from '../nucleo/navegacion';
import { Sesion } from '../nucleo/sesion';
import { RUTAS_DE_MODULOS } from '../rutas-modulos';
import { Boton } from '../ui/boton';
import { EncabezadoPagina, tituloDocumento } from '../ui/encabezado-pagina';
import { Alerta } from '../ui/insignia';

/** Primer segmento de cada ruta de módulo registrada (`usuarios`, `agenda`...). */
function rutasConocidas(): Set<string> {
  return new Set(RUTAS_DE_MODULOS.map((r) => (r.path ?? '').split('/')[0]!).filter(Boolean));
}

/**
 * Comodín del shell:
 * - una pantalla de Angular que el perfil no tiene (su guarda `pantalla(...)` no coincidió) muestra «Acceso restringido.» en el lugar,
 *   sin cambiar la dirección, como `Protegida` del monolito;
 * - una pantalla que el servidor sí le da (está en su menú) pero que todavía no se ha portado a Angular muestra su título y lo dice
 *   (mientras dura la migración; cada sesión de módulo la reemplaza al registrar su ruta en `rutas-modulos.ts`);
 * - una dirección que no existe vuelve al inicio, como el `<Navigate to="/" />` del monolito.
 */
@Component({
  selector: 'lume-sin-acceso',
  imports: [EncabezadoPagina, Alerta],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (registrada()) {
      <p class="lume-texto-error">Acceso restringido.</p>
    } @else if (sinPortar(); as e) {
      <div class="lume-pila">
        <lume-encabezado-pagina [titulo]="e.titulo" />
        <lume-alerta color="blue" rol="status">Esta pantalla todavía no está disponible en la versión nueva.</lume-alerta>
      </div>
    }
  `,
})
export class SinAcceso {
  private readonly router = inject(Router);
  private readonly sesion = inject(Sesion);
  /** La dirección actual: el comodín se reutiliza al pasar de una dirección sin ruta a otra, así que se sigue en cada navegación. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );
  private readonly camino = computed(() => this.url().split(/[?#]/)[0]!.replace(/^\/+/, ''));
  protected readonly registrada = computed(() => rutasConocidas().has(this.camino().split('/')[0] ?? ''));
  /** La entrada del menú (la más larga que coincide: «analitica/cargues» antes que «analitica»). */
  protected readonly sinPortar = computed(
    () =>
      (this.sesion.datos()?.menu ?? [])
        .filter((m) => this.camino() === m.ruta || this.camino().startsWith(m.ruta + '/'))
        .sort((a, b) => b.ruta.length - a.ruta.length)[0] ?? null,
  );

  constructor() {
    effect(() => {
      if (!this.registrada() && !this.sinPortar()) void this.router.navigateByUrl('/', { replaceUrl: true });
    });
  }
}

/** Vacía: la página ya va al ingreso por el gateway (sin sesión). */
@Component({ selector: 'lume-saliendo', template: '', changeDetection: ChangeDetectionStrategy.OnPush })
export class Saliendo {}

/** La sesión no se pudo consultar (sin red o servidor caído): se dice y se ofrece reintentar la misma dirección. */
@Component({
  selector: 'lume-sin-conexion',
  imports: [Alerta, Boton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="lume-publica">
      <div class="lume-card lume-publica__tarjeta lume-pila">
        <h1>Interventoría Integral DIGSA</h1>
        <lume-alerta color="red" titulo="No hay conexión con el servidor">Revise su conexión e intente de nuevo.</lume-alerta>
        <button lumeBoton type="button" (click)="reintentar()">Reintentar</button>
      </div>
    </main>
  `,
})
export class SinConexion {
  private readonly router = inject(Router);
  private readonly volver = inject(ActivatedRoute).snapshot.queryParamMap.get('volver');

  constructor() {
    inject(Title).setTitle(tituloDocumento('Sin conexión'));
  }

  /** Vuelve a intentar la misma dirección, por el router y solo dentro de la aplicación (`?volver=` lo controla quien arma el enlace). */
  protected reintentar(): void {
    void this.router.navigateByUrl(rutaInterna(this.volver) ?? '/');
  }
}
