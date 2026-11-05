import { ChangeDetectionStrategy, Component, inject, Injectable, signal } from '@angular/core';
import { Alerta } from './insignia';

export interface Aviso {
  id: number;
  color: string;
  mensaje: string;
}

/**
 * Avisos de éxito que se cierran solos (el `notifications.show` de Mantine del monolito, 4 s): solo para confirmar algo que salió bien.
 * Los errores no van aquí: van en el resumen de la validación, junto al formulario (Anexo 5 num. 2.4).
 */
@Injectable({ providedIn: 'root' })
export class Avisos {
  private siguiente = 0;
  readonly lista = signal<Aviso[]>([]);

  mostrar(mensaje: string, color = 'green', ms = 4000): void {
    const id = ++this.siguiente;
    this.lista.update((l) => [...l, { id, color, mensaje }]);
    setTimeout(() => this.quitar(id), ms);
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
  host: { class: 'lume-avisos', role: 'status', 'aria-live': 'polite' },
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
}
