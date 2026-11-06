import { nombreDeArchivo } from './descarga';

describe('nombre del archivo descargado (descargar de api.ts del monolito)', () => {
  it('de filename*, de filename entre comillas o sin ellas; sin cabecera, el de omisión', () => {
    expect(nombreDeArchivo(`attachment; filename*=UTF-8''logs%20auditor%C3%ADa.xlsx`, 'x')).toBe('logs auditoría.xlsx');
    expect(nombreDeArchivo('attachment; filename="logs_auditoria_20261105_0830.xlsx"', 'x')).toBe('logs_auditoria_20261105_0830.xlsx');
    expect(nombreDeArchivo('attachment; filename=reporte.csv', 'x')).toBe('reporte.csv');
    expect(nombreDeArchivo(null, 'logs.xlsx')).toBe('logs.xlsx');
  });

  it('solo el nombre: sin rutas ni caracteres no admitidos', () => {
    expect(nombreDeArchivo('attachment; filename="../../etc/clave.txt"', 'x')).toBe('clave.txt');
    expect(nombreDeArchivo('attachment; filename="a:b?.csv"', 'x')).toBe('a_b_.csv');
    expect(nombreDeArchivo(`attachment; filename*=UTF-8''%E0%A4%A`, 'x')).toBe('x');
    expect(nombreDeArchivo('attachment; filename=".."', 'x')).toBe('x');
    expect(nombreDeArchivo(`attachment; filename*=UTF-8''%2F..%2Fx.csv`, 'y')).toBe('x.csv');
    expect(nombreDeArchivo('attachment; filename="CON.xlsx"', 'x')).toBe('x');
    expect(nombreDeArchivo('attachment; filename="reporte. "', 'x')).toBe('reporte');
    expect(nombreDeArchivo('attachment; filename="a;b.csv"', 'x')).toBe('x');
  });
});
