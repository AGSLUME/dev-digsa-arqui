// Excel (.xlsx) y CSV mínimos para el mock de logs, sin dependencias: el xlsx es un ZIP sin compresión (método «stored») con una hoja de
// celdas de texto en línea. Solo para desarrollo y e2e; el reporte real lo genera el servicio (Exportador del monolito).
import { crc32 } from 'node:zlib';

/** Una celda que empieza por =, +, -, @, tabulador o retorno se ejecutaría como fórmula al abrirla (inyección): se antepone «'». */
const sinFormula = (t) => (/^[=+\-@\t\r]/.test(t) ? "'" + t : t);
/** Texto para el XML: sin caracteres de control (XML inválido) y con las entidades. */
const xml = (t) =>
  sinFormula(String(t ?? ''))
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '')
    .replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' })[c]);

/** Las filas del reporte: el título, la generación, los filtros, una fila vacía, los encabezados y los datos (como el Exportador). */
function filasDelReporte({ titulo, generado, por, filtros, columnas, filas }) {
  return [
    [titulo],
    [`Generado: ${generado}`, `Por: ${por}`],
    ...Object.entries(filtros).map(([k, v]) => [k, v]),
    [],
    columnas,
    ...filas,
  ];
}

function zip(archivos) {
  const locales = [];
  const centrales = [];
  let desplazamiento = 0;
  for (const [nombre, texto] of archivos) {
    const datos = Buffer.from(texto, 'utf8');
    const n = Buffer.from(nombre, 'utf8');
    const crc = crc32(datos);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // nombres en UTF-8
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(datos.length, 18);
    local.writeUInt32LE(datos.length, 22);
    local.writeUInt16LE(n.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(datos.length, 20);
    central.writeUInt32LE(datos.length, 24);
    central.writeUInt16LE(n.length, 28);
    central.writeUInt32LE(desplazamiento, 42);
    locales.push(local, n, datos);
    centrales.push(central, n);
    desplazamiento += 30 + n.length + datos.length;
  }
  const directorio = Buffer.concat(centrales);
  const fin = Buffer.alloc(22);
  fin.writeUInt32LE(0x06054b50, 0);
  fin.writeUInt16LE(archivos.length, 8);
  fin.writeUInt16LE(archivos.length, 10);
  fin.writeUInt32LE(directorio.length, 12);
  fin.writeUInt32LE(desplazamiento, 16);
  return Buffer.concat([...locales, directorio, fin]);
}

export const TIPO_XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
export const TIPO_CSV = 'text/csv; charset=utf-8';

/** El reporte en Excel: una hoja «Logs» con todas las celdas como texto. */
export function excel(reporte) {
  const columna = (i) => (i < 26 ? '' : String.fromCharCode(64 + Math.floor(i / 26))) + String.fromCharCode(65 + (i % 26));
  const filas = filasDelReporte(reporte)
    .map(
      (f, r) =>
        `<row r="${r + 1}">${f.map((v, c) => `<c r="${columna(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">${xml(v)}</t></is></c>`).join('')}</row>`,
    )
    .join('');
  return zip([
    [
      '[Content_Types].xml',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
    ],
    [
      '_rels/.rels',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    ],
    [
      'xl/workbook.xml',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Logs" sheetId="1" r:id="rId1"/></sheets></workbook>',
    ],
    [
      'xl/_rels/workbook.xml.rels',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
    ],
    [
      'xl/worksheets/sheet1.xml',
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${filas}</sheetData></worksheet>`,
    ],
  ]);
}

/** El reporte en CSV (UTF-8 con BOM, separado por punto y coma, como lo abre Excel en es-CO). */
export function csv(reporte) {
  const celda = (v) => {
    const t = sinFormula(String(v ?? ''));
    return /[";\r\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t;
  };
  return Buffer.from('﻿' + filasDelReporte(reporte).map((f) => f.map(celda).join(';')).join('\r\n') + '\r\n', 'utf8');
}
