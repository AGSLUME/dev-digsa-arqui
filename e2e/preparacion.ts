import { MAILPIT } from './pruebas/apoyo';

/** Antes de las pruebas: Mailpit debe estar arriba (ahí llegan el código MFA y los enlaces). Mejor un error claro que 30 fallos. */
export default async function preparacion(): Promise<void> {
  try {
    const r = await fetch(`${MAILPIT}/api/v1/info`);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
  } catch (e) {
    throw new Error(
      `Mailpit no responde en ${MAILPIT} (${String(e)}). Arránquelo como dice digsa-java/docs/guia_mailpit.md:\n` +
        '  & "$env:LOCALAPPDATA\\Programs\\mailpit\\mailpit.exe" --smtp 127.0.0.1:1025 --listen 127.0.0.1:8025',
    );
  }
}
