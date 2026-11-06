import { conTexto, correoValido, errorClave, errorCorreo, erroresClaveNueva, errorTelefono, ERROR_TELEFONO, normalizarTelefono } from './validaciones';

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

describe('Correo (validaciones.ts del monolito)', () => {
  it('vacío, inválido, válido y el máximo de 150', () => {
    expect(errorCorreo('  ')).toBe('Escriba el correo.');
    expect(errorCorreo('sin-arroba')).toBe('Escriba un correo válido.');
    expect(errorCorreo('a@b')).toBe('Escriba un correo válido.');
    expect(errorCorreo(" ana.o'neil+x@angular.prueba.local ")).toBeNull();
    expect(correoValido('a'.repeat(140) + '@prueba.co')).toBe(true);
    expect(correoValido('a'.repeat(141) + '@prueba.co')).toBe(false);
    expect(errorCorreo('x', 'Otro texto.', 'Vacío.')).toBe('Otro texto.');
  });
});

describe('Teléfono móvil (alertas.ts y reglas/Alertas.normalizarTelefono)', () => {
  it('normaliza a E.164 con o sin indicativo y separadores', () => {
    expect(normalizarTelefono('+57 300 000 0000')).toBe('+573000000000');
    expect(normalizarTelefono('(300) 000-0000')).toBe('+573000000000');
    expect(normalizarTelefono('0057 3001234567')).toBe('+573001234567');
    expect(normalizarTelefono('573001234567')).toBe('+573001234567');
    expect(normalizarTelefono('601 000 0000')).toBeNull();
    expect(normalizarTelefono('300 000 000')).toBeNull();
  });

  it('vacío o igual al guardado es válido; si no, el texto del monolito', () => {
    expect(errorTelefono('')).toBeNull();
    expect(errorTelefono('601 000 0000', '601 000 0000')).toBeNull();
    expect(errorTelefono('601 000 0000')).toBe(ERROR_TELEFONO);
    expect(ERROR_TELEFONO).toBe('El teléfono debe ser un móvil colombiano: +57 y 10 dígitos que empiezan por 3 (p. ej. +57 300 000 0000).');
    expect(errorTelefono('3001234567')).toBeNull();
  });
});
