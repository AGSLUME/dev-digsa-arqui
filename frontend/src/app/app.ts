import { Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Raíz de LUME en Angular (ADR-011). Esqueleto de la fase I1: la sesión angular-base pone aquí el shell, el design system LUME
 * (tokens CSS) y el cliente generado desde contratos/.
 */
@Component({
  selector: 'lume-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly titulo = signal('LUME · Suite DIGSA');
}
