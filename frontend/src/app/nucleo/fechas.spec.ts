import { aTextoFecha, diasEntre, errorFecha, leerFecha, MENSAJE_FORMATO, MENSAJE_NO_EXISTE, periodo } from './fechas';

describe('fechas DD/MM/AAAA', () => {
  it('lee fechas válidas a «aaaa-mm-dd»', () => {
    expect(leerFecha('23/10/2026')).toBe('2026-10-23');
    expect(leerFecha(' 01/01/2027 ')).toBe('2027-01-01');
    expect(leerFecha('29/02/2028')).toBe('2028-02-29'); // bisiesto
  });

  it.each(['31/02/2026', '29/02/2027', '31/04/2026', '00/10/2026', '10/13/2026', '10/00/2026', '32/01/2026', '01/01/1899'])(
    'una fecha imposible no se lee: %s',
    (t) => {
      expect(leerFecha(t)).toBeNull();
      expect(errorFecha(t)).toBe(MENSAJE_NO_EXISTE);
    },
  );

  it.each(['2026-10-23', '23/10/26', '23-10-2026', '1/1/2026', 'hoy'])('otro formato: %s', (t) => {
    expect(leerFecha(t)).toBeNull();
    expect(errorFecha(t)).toBe(MENSAJE_FORMATO);
  });

  it('vacía: bien si no es obligatoria; si lo es, «Escriba …»', () => {
    expect(errorFecha('')).toBeNull();
    expect(errorFecha('  ', { obligatoria: true, nombre: 'la fecha de la visita' })).toBe('Escriba la fecha de la visita.');
  });

  it('con rango: desde y hasta, con la fecha en pantalla', () => {
    expect(errorFecha('01/11/2026', { min: '2026-10-01', max: '2026-11-30' })).toBeNull();
    expect(errorFecha('30/09/2026', { min: '2026-10-01' })).toBe('La fecha debe ser desde el 01/10/2026.');
    expect(errorFecha('01/12/2026', { max: '2026-11-30' })).toBe('La fecha debe ser hasta el 30/11/2026.');
  });

  it('del ISO al campo con formato.ts', () => {
    expect(aTextoFecha('2026-10-23')).toBe('23/10/2026');
    expect(aTextoFecha(null)).toBe('');
    expect(aTextoFecha('x')).toBe('');
  });
});

describe('periodo de un filtro (reglas de ControladorLogs del monolito)', () => {
  const hoy = '2026-11-05';
  it('sin fechas: los últimos 7 días en Bogotá', () => {
    expect(periodo(null, null, hoy)).toEqual({ desde: '2026-10-29', hasta: '2026-11-05' });
  });
  it('fechas invertidas se intercambian', () => {
    expect(periodo('2026-11-01', '2026-10-01', hoy)).toEqual({ desde: '2026-10-01', hasta: '2026-11-01' });
  });
  it('días entre dos fechas de calendario (también cruzando el año)', () => {
    expect(diasEntre('2026-10-01', '2026-12-31')).toBe(91);
    expect(diasEntre('2026-12-31', '2027-01-01')).toBe(1);
  });
});
