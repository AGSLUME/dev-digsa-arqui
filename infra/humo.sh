#!/usr/bin/env bash
# Humo de punta a punta (S1, ADR-014): navegador simulado con curl contra el gateway, el emisor real (seguridad) y hola.
#   0. Sin sesión, la API responde 401 sin_sesion.
#   1. /bff/oauth2/authorization/lume → /oauth2/authorize (emisor, por el gateway) → /ingreso (pantalla Angular; no se pide).
#   2. /api/auth/ingreso con el usuario de desarrollo del .env (perfil local) → el código MFA llega a Mailpit.
#   3. Se lee el código de Mailpit filtrando por el destinatario de la sesión (@<LUME_DOMINIO_PRUEBA>) → /api/auth/codigo → continuar.
#   4. continuar → código OIDC → el gateway lo canjea → LUME_SESION (ningún token en el navegador).
#   5. /api/hola y /api/sesion con solo la cookie (TokenRelay; hola valida aud=hola).
#   6. Salir: POST /bff/salir → 204; luego la API vuelve a 401 y una autorización nueva vuelve a /ingreso, sin código (B1, B6).
#   7. hola directo sin token → 401.
# Requiere: compose arriba, seguridad (perfil local), hola y gateway corriendo (infra/arrancar.sh), y el Mailpit ya instalado.
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
set -a
# shellcheck disable=SC1091
. "$raiz/infra/.env"
set +a
gw="http://localhost:$GATEWAY_PUERTO"
mailpit="${MAILPIT_URL:-http://127.0.0.1:8025}"
correo="$LUME_USUARIO_DEV@$LUME_DOMINIO_PRUEBA"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
jar="$tmp/cookies"
falla() { echo "HUMO FALLÓ: $*" >&2; exit 1; }
xsrf() { awk '$6 == "XSRF-TOKEN" {print $7}' "$jar" | tail -1; }
# Sigue redirecciones a mano (sin pedir la SPA): imprime la última ubicación (se detiene en /ingreso o en la raíz de la SPA).
seguir() {
  local url="$1" loc
  for _ in 1 2 3 4 5 6; do
    loc=$(curl -s -o /dev/null -c "$jar" -b "$jar" -w '%{redirect_url}' "$url")
    [[ -z "$loc" ]] && { echo "$url"; return; }
    case "$loc" in
      */ingreso|*/ingreso\?*|"$gw/"|"/") echo "$loc"; return ;;
    esac
    url="$loc"
  done
  echo "$url"
}

# 0. Sin sesión, la API responde 401 en JSON
codigo=$(curl -s -o "$tmp/r0" -w '%{http_code}' "$gw/api/hola")
[[ "$codigo" == "401" ]] || falla "sin sesión se esperaba 401 y llegó $codigo"
grep -q '"motivo":"sin_sesion"' "$tmp/r0" || falla "el 401 no trae motivo sin_sesion"
echo "ok 0: sin sesión -> 401 sin_sesion"

# 1. Inicio del ingreso: el emisor guarda la autorización y manda a /ingreso
destino=$(seguir "$gw/bff/oauth2/authorization/lume")
[[ "$destino" == */ingreso ]] || falla "la autorización debía terminar en /ingreso y terminó en $destino"
grep -q "LUME_IDP" "$jar" || falla "el emisor no dejó su cookie LUME_IDP"
echo "ok 1: /bff/oauth2/authorization/lume -> /oauth2/authorize -> /ingreso"

# 2. Paso 1 (con CSRF del gateway)
curl -s -o /dev/null -c "$jar" -b "$jar" "$gw/api/auth/inicio"
desde=$(date -u +%s)
codigo=$(curl -s -o "$tmp/r2" -w '%{http_code}' -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' \
  --data "{\"usuario\":\"$LUME_USUARIO_DEV\",\"clave\":\"$LUME_CLAVE_DEV\"}" "$gw/api/auth/ingreso")
[[ "$codigo" == "200" ]] || falla "/api/auth/ingreso respondió $codigo: $(cat "$tmp/r2")"
grep -q '"paso":"codigo"' "$tmp/r2" || falla "el paso 1 no pidió el código: $(cat "$tmp/r2")"
echo "ok 2: usuario y contraseña -> código enviado a $correo"

# 3. El código, de Mailpit (solo mensajes al destinatario de esta sesión y posteriores al paso 1)
mfa=""
for _ in $(seq 1 20); do
  curl -s -G "$mailpit/api/v1/search" --data-urlencode "query=to:\"$correo\"" -o "$tmp/busq"
  id=$(grep -oE '"ID":"[^"]+"' "$tmp/busq" | head -1 | cut -d'"' -f4 || true)
  creado=$(grep -oE '"Created":"[^"]+"' "$tmp/busq" | head -1 | cut -d'"' -f4 || true)
  if [[ -n "$id" && -n "$creado" ]] && (( $(date -u -d "$creado" +%s) >= desde - 2 )); then
    # El resumen (Snippet) del último mensaje trae el texto plano: «... la Suite DIGSA es: 123456 Vence ...»
    mfa=$(grep -oE '"Snippet":"[^"]*' "$tmp/busq" | head -1 | grep -oE 'es: [0-9]{6}' | grep -oE '[0-9]{6}' || true)
    [[ -n "$mfa" ]] && break
  fi
  sleep 0.5
done
[[ -n "$mfa" ]] || falla "no llegó el código a Mailpit para $correo"
codigo=$(curl -s -o "$tmp/r3" -w '%{http_code}' -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' \
  --data "{\"codigo\":\"$mfa\"}" "$gw/api/auth/codigo")
[[ "$codigo" == "200" ]] || falla "/api/auth/codigo respondió $codigo: $(cat "$tmp/r3")"
continuar=$(grep -oE '"continuar":"[^"]+"' "$tmp/r3" | cut -d'"' -f4)
[[ "$continuar" == /oauth2/authorize\?* ]] || falla "continuar no es la autorización pendiente: $continuar"
echo "ok 3: código leído de Mailpit; ingreso completo; continuar = /oauth2/authorize?..."

# 4. La autorización emite el código y el gateway lo canjea
final=$(seguir "$gw$continuar")
grep -q "LUME_SESION" "$jar" || falla "el gateway no creó la sesión LUME_SESION (terminó en $final)"
if grep -qiE "access_token|refresh_token|eyJ" "$jar"; then falla "un token llegó al navegador"; fi
echo "ok 4: sesión LUME_SESION creada; ningún token en las cookies del navegador"

# 5. APIs con la cookie
codigo=$(curl -s -b "$jar" -c "$jar" -o "$tmp/r5" -w '%{http_code}' "$gw/api/hola")
[[ "$codigo" == "200" ]] || falla "/api/hola con sesión respondió $codigo: $(cat "$tmp/r5")"
grep -q "\"usuario\":\"$LUME_USUARIO_DEV\"" "$tmp/r5" || falla "hola no recibió el usuario del token: $(cat "$tmp/r5")"
codigo=$(curl -s -b "$jar" -c "$jar" -o "$tmp/r5b" -w '%{http_code}' "$gw/api/sesion")
[[ "$codigo" == "200" ]] || falla "/api/sesion respondió $codigo: $(cat "$tmp/r5b")"
grep -q '"menu"' "$tmp/r5b" || falla "/api/sesion no trae el menú"
echo "ok 5: /api/hola y /api/sesion con solo la cookie"

# 6. Salir y no volver a entrar sin clave y código (B1, B6)
codigo=$(curl -s -o /dev/null -w '%{http_code}' -c "$jar" -b "$jar" -X POST -H "X-XSRF-TOKEN: $(xsrf)" "$gw/bff/salir")
[[ "$codigo" == "204" ]] || falla "/bff/salir respondió $codigo"
codigo=$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" "$gw/api/hola")
[[ "$codigo" == "401" ]] || falla "después de salir /api/hola respondió $codigo"
destino=$(seguir "$gw/bff/oauth2/authorization/lume")
[[ "$destino" == */ingreso ]] || falla "después de salir, una autorización nueva terminó en $destino (debía pedir el ingreso)"
echo "ok 6: salir -> 204; la API vuelve a 401 y una autorización nueva pide otra vez el ingreso"

# 7. Directo a hola sin token: rechazado (no confía en nada que no sea un JWT válido)
codigo=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Usuario: $LUME_USUARIO_DEV" "http://localhost:$HOLA_PUERTO/api/hola")
[[ "$codigo" == "401" ]] || falla "hola sin token respondió $codigo"
echo "ok 7: hola directo sin token -> 401"

echo "HUMO OK"
