import { Component, DestroyRef, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { RegionAvisos } from './ui/avisos';
import { Sonido } from './ui/sonido';

/**
 * Raíz de LUME en Angular (ADR-011): las rutas (públicas o el shell con sesión) y la región de avisos. El sonido de validación queda
 * listo en el primer gesto del usuario y también suena con la validación nativa del navegador, como en main.tsx del monolito.
 */
@Component({
  selector: 'lume-root',
  imports: [RouterOutlet, RegionAvisos],
  template: '<router-outlet /><lume-avisos />',
})
export class App {
  constructor() {
    const sonido = inject(Sonido);
    const quitarGesto = sonido.prepararEnPrimerGesto();
    const quitarNativa = sonido.sonarEnValidacionNativa();
    inject(DestroyRef).onDestroy(() => {
      quitarGesto();
      quitarNativa();
    });
  }
}
