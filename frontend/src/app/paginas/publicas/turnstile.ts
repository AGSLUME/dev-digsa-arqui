import { afterNextRender, ChangeDetectionStrategy, Component, DestroyRef, DOCUMENT, ElementRef, inject, output, signal, viewChild } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { IngresoApi, type Inicio } from '../../api/seguridad';

interface WidgetTurnstile {
  render: (el: HTMLElement, o: { sitekey: string; callback: (t: string) => void; 'expired-callback': () => void; language: string }) => string;
  reset: (id?: string) => void;
  remove?: (id: string) => void;
}

const SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

/**
 * Captcha Cloudflare Turnstile (port de frontend/src/shared/Turnstile.tsx). Pide `GET /api/auth/inicio` (contrato `Inicio`), que trae la
 * clave de sitio y deja lista la cookie CSRF. En local sin claves el servidor lo desactiva y aquí solo se avisa. En local y en e2e la
 * clave es la de PRUEBA pública de Cloudflare, que siempre aprueba (ADR-014). El servidor exige el captcha igual: si el script no carga,
 * se dice (antes el envío fallaba con un error genérico).
 */
@Component({
  selector: 'lume-turnstile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (desactivado()) {
      <p class="lume-texto-ayuda">Captcha desactivado en local</p>
    } @else {
      <div #caja class="lume-turnstile" role="group" aria-label="Verificación de seguridad"></div>
      @if (sinScript()) {
        <p class="lume-texto-ayuda" role="status">No se pudo cargar la verificación de seguridad. Recargue la página.</p>
      }
    }
  `,
})
export class Turnstile {
  private readonly ingreso = inject(IngresoApi);
  private readonly documento = inject(DOCUMENT);
  /** La respuesta del widget, o null si venció o se reinició. */
  readonly resuelto = output<string | null>();

  protected readonly desactivado = signal(false);
  protected readonly sinScript = signal(false);
  private readonly caja = viewChild<ElementRef<HTMLElement>>('caja');
  private id: string | null = null;
  private vivo = true;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.vivo = false;
      const t = this.widget();
      if (this.id && t?.remove) t.remove(this.id);
    });
    afterNextRender(() => void this.preparar());
  }

  /** Pide otro desafío (después de un error: el anterior ya se usó). */
  reiniciar(): void {
    if (this.id) this.widget()?.reset(this.id);
    this.resuelto.emit(null);
  }

  private widget(): WidgetTurnstile | undefined {
    return (this.documento.defaultView as (Window & { turnstile?: WidgetTurnstile }) | null)?.turnstile;
  }

  private async preparar(): Promise<void> {
    let cfg: Pick<Inicio, 'captchaSitio' | 'captchaDesactivado'>;
    try {
      cfg = await firstValueFrom(this.ingreso.inicio());
    } catch {
      cfg = { captchaSitio: null, captchaDesactivado: false };
    }
    if (!this.vivo) return;
    this.desactivado.set(cfg.captchaDesactivado);
    const sitio = cfg.captchaSitio;
    if (!sitio) return;
    const dibujar = () => {
      const t = this.widget();
      const caja = this.caja()?.nativeElement;
      if (!t || !caja || !this.vivo) return;
      this.id = t.render(caja, {
        sitekey: sitio,
        language: 'es',
        callback: (token) => this.resuelto.emit(token),
        'expired-callback': () => this.resuelto.emit(null),
      });
    };
    const fallo = () => this.vivo && this.sinScript.set(true);
    if (this.widget()) return dibujar();
    const previo = this.documento.querySelector<HTMLScriptElement>(`script[src="${SCRIPT}"]`);
    if (previo) {
      previo.addEventListener('load', dibujar, { once: true });
      previo.addEventListener('error', fallo, { once: true });
      return;
    }
    const s = this.documento.createElement('script');
    s.src = SCRIPT;
    s.async = true;
    s.onload = dibujar;
    s.onerror = fallo;
    this.documento.head.appendChild(s);
  }
}
