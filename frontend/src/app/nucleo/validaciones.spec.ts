import { conTexto, errorClave, erroresClaveNueva } from './validaciones';

// Portado de frontend/src/shared/validaciones.test.ts del monolito (la parte de contraseñas).
describe('Contraseña nueva (reglas/Claves.java)', () => {
  it('mínimo 12 caracteres y sin ñ ni tildes, con los mensajes del servidor', () => {
    expect(errorClave('')).toBe('La contraseña debe tener mínimo 12 caracteres.');
    expect(errorClave('Corta.2026')).toBe('La contraseña debe tener mínimo 12 caracteres.');
    expect(errorClave('Contraseña.Larga.2026')).toBe('La contraseña no puede tener ñ ni tildes.');
    expect(errorClave('Canción.Larga.2026')).toBe('La contraseña no puede tener ñ ni tildes.');
    expect(errorClave('Propia.Del.Usuario.2026')).toBeNull();
    // Sin número ni especial: pasa en el cliente (lo rechaza el servidor con su mensaje)
    expect(errorClave('abcdefghijklmn')).toBeNull();
  });

  it('la confirmación debe estar y coincidir', () => {
    expect(erroresClaveNueva('Propia.Del.Usuario.2026', '')).toEqual({ clave: null, confirmacion: 'Confirme la contraseña nueva.' });
    expect(erroresClaveNueva('Propia.Del.Usuario.2026', 'Otra.Del.Usuario.2026')).toEqual({
      clave: null,
      confirmacion: 'Las contraseñas no coinciden.',
    });
    expect(erroresClaveNueva('Propia.Del.Usuario.2026', 'Propia.Del.Usuario.2026')).toEqual({ clave: null, confirmacion: null });
  });

  it('un campo marcado solo con color lleva texto que remite al aviso', () => {
    expect(conTexto(true)).toBe('Revise este campo: el motivo está en el aviso de arriba.');
    expect(conTexto('Elija una.')).toBe('Elija una.');
    expect(conTexto(undefined)).toBeUndefined();
  });
});
