// Proxy de `ng serve` (solo desarrollo y e2e): lo que en producción atiende el gateway va al gateway de la sesión, o al mock de
// seguridad que lo reemplaza mientras no exista (mock/servidor.mjs, mismo puerto). Mismo origen: la cookie del BFF y el CSRF funcionan.
const destino = `http://127.0.0.1:${process.env.DIGSA_GATEWAY_PUERTO ?? 9200}`;

export default {
  '/api': { target: destino, changeOrigin: false },
  '/bff': { target: destino, changeOrigin: false },
  '/oauth2': { target: destino, changeOrigin: false },
  '/__mock': { target: destino, changeOrigin: false },
};
