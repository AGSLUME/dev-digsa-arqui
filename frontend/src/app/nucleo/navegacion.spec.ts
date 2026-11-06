import { empiezaPorSegmento, rutaInterna } from './navegacion';

describe('rutaInterna (sin redirecciones abiertas)', () => {
  const origen = 'http://localhost:4220';

  it('acepta rutas del mismo origen, con consulta y fragmento', () => {
    expect(rutaInterna('/usuarios', origen)).toBe('/usuarios');
    expect(rutaInterna('/oauth2/authorize?response_type=code&state=x', origen)).toBe('/oauth2/authorize?response_type=code&state=x');
    expect(rutaInterna('/a/../usuarios#x', origen)).toBe('/usuarios#x');
  });

  it.each([
    ['//otro.example/x'],
    ['/\\otro.example'],
    ['/\totro'],
    ['/\t/otro.example'],
    ['/\n/otro.example'],
    ['/ /otro'],
    ['https://otro.example/'],
    ['javascript:alert(1)'],
    ['usuarios'],
    [''],
    // Segmentos con punto que, ya resueltos, terminan en «//otro» (la salida también se valida)
    ['/.//evil.com'],
    ['/..//evil.com'],
    ['/%2e//evil.com'],
    ['/%2e%2e//evil.com'],
    ['/bff/..//evil.com'],
    ['/oauth2/authorize/../..//evil.com'],
  ])('rechaza %j', (v) => {
    expect(rutaInterna(v, origen)).toBeNull();
  });

  it('rechaza lo que no es texto', () => {
    expect(rutaInterna(null, origen)).toBeNull();
    expect(rutaInterna(3, origen)).toBeNull();
  });
});

describe('empiezaPorSegmento', () => {
  it('compara el segmento exacto', () => {
    expect(empiezaPorSegmento('/oauth2/authorize', '/oauth2/authorize')).toBe(true);
    expect(empiezaPorSegmento('/oauth2/authorize?x=1', '/oauth2/authorize')).toBe(true);
    expect(empiezaPorSegmento('/oauth2/authorize/x', '/oauth2/authorize')).toBe(true);
    expect(empiezaPorSegmento('/oauth2/authorizeX', '/oauth2/authorize')).toBe(false);
    expect(empiezaPorSegmento('/oauth2/authorize-evil', '/oauth2/authorize')).toBe(false);
  });
});
