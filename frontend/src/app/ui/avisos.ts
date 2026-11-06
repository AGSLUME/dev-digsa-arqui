import { ChangeDetectionStrategy, Component, DOCUMENT, inject, Injectable, signal } from '@angular/core';
import { Alerta } from './insignia';

export interface Aviso {
  id: number;
  color: string;
  mensaje: string;
}

/**
 * Avisos de éxito que se cierran solos (el `notifications.show` de Mantine del monolito, 4 s): solo para confirmar algo que salió bien.
 * Con `ms = null` el aviso no se cierra solo (el naranja del monolito con `autoClose: false`, p. ej. «el correo de bienvenida no
 * salió»): se queda hasta que la persona lo cierra. Los errores de un formulario no van aquí: van en el resumen de la validación.
 */
@Injectable({ providedIn: 'root' })
export class Avisos {
  private siguiente = 0;
  readonly lista = signal<Aviso[]>([]);

  mostrar(mensaje: string, color = 'green', ms: number | null = 4000): void {
    const id = ++this.siguiente;
    this.lista.update((l) => [...l, { id, color, mensaje }]);
    if (ms !== null) setTimeout(() => this.quitar(id), ms);
  }

  quitar(id: number): void {
    this.lista.update((l) => l.filter((a) => a.id !== id));
  }
}

/** Región donde aparecen los avisos: `role="status"` (el lector los anuncia sin interrumpir). Va una vez, en la raíz. */
@Component({
  selector: 'lume-avisos',
  imports: [Alerta],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Solo se lee lo que llega (no todo de nuevo con cada aviso); Escape cierra el último persistente si no hay un modal abierto
  host: {
    class: 'lume-avisos',
    role: 'status',
    'aria-live': 'polite',
    'aria-atomic': 'false',
    'aria-relevant': 'additions',
    '(document:keydown.escape)': 'cerrarConEscape()',
  },
  template: `
    @for (a of avisos.lista(); track a.id) {
      <lume-alerta class="lume-aviso" [color]="a.color" rol="none">
        {{ a.mensaje }}
        <button type="button" class="lume-aviso__cerrar" aria-label="Cerrar el aviso" (click)="avisos.quitar(a.id)">
          <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" focusable="false"><path d="M18 6L6 18M6 6l12 12" /></svg>
        </button>
      </lume-alerta>
    }
  `,
})
export class RegionAvisos {
  protected readonly avisos = inject(Avisos);
  private readonly documento = inject(DOCUMENT);

  protected cerrarConEscape(): void {
    const lista = this.avisos.lista();
    if (lista.length === 0 || this.documento.querySelector('[role="dialog"], [role="alertdialog"]')) return;
    this.avisos.quitar(lista[lista.length - 1]!.id);
  }
}
