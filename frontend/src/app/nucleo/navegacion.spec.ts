import { rutaInterna } from './navegacion';

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
  ])('rechaza %j', (v) => {
    expect(rutaInterna(v, origen)).toBeNull();
  });

  it('rechaza lo que no es texto', () => {
    expect(rutaInterna(null, origen)).toBeNull();
    expect(rutaInterna(3, origen)).toBeNull();
  });
});
