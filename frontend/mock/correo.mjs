// Envío SMTP mínimo al Mailpit ya instalado (ADR-010: 127.0.0.1:1025), sin dependencias. Solo para el mock de seguridad.
import { connect } from 'node:net';

const HOST = process.env.DIGSA_MOCK_SMTP_HOST ?? '127.0.0.1';
const PUERTO = Number(process.env.DIGSA_MOCK_SMTP_PUERTO ?? 1025);

/** Codificación de un encabezado con tildes (RFC 2047). */
const encabezado = (t) => `=?UTF-8?B?${Buffer.from(t, 'utf8').toString('base64')}?=`;

/** Envía un correo de texto. Rechaza si Mailpit no responde (el mock lo traduce a 502, como el monolito). */
export function enviarCorreo({ para, asunto, texto }) {
  const mensaje = [
    'From: LUME Suite DIGSA <no-responder@angular.prueba.local>',
    `To: <${para}>`,
    `Subject: ${encabezado(asunto)}`,
    `Date: ${new Date().toUTCString()}`,
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset=UTF-8',
    'Content-Transfer-Encoding: base64',
    '',
    Buffer.from(texto, 'utf8').toString('base64').replace(/.{1,76}/g, '$&\r\n'),
  ].join('\r\n');
  const pasos = ['EHLO mock-angular', 'MAIL FROM:<no-responder@angular.prueba.local>', `RCPT TO:<${para}>`, 'DATA', `${mensaje}\r\n.`, 'QUIT'];
  return new Promise((resolver, rechazar) => {
    const s = connect(PUERTO, HOST);
    let i = -1;
    let bufer = '';
    s.setTimeout(5000, () => s.destroy(new Error('Mailpit no responde')));
    s.on('error', rechazar);
    s.on('data', (d) => {
      bufer += d.toString('utf8');
      // Una respuesta termina con «NNN <texto>\r\n» (las líneas intermedias del EHLO son «NNN-...»)
      if (!/(^|\r\n)\d{3} [^\r\n]*\r\n$/.test(bufer)) return;
      const codigo = Number(bufer.trimEnd().split('\r\n').at(-1).slice(0, 3));
      bufer = '';
      if (codigo >= 400) return s.destroy(new Error(`SMTP ${codigo}`));
      i++;
      if (i < pasos.length) s.write(pasos[i] + '\r\n');
      else s.end();
    });
    s.on('close', () => (i >= pasos.length - 1 ? resolver() : rechazar(new Error('SMTP cortado'))));
  });
}
