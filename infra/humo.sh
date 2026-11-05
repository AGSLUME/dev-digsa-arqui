#!/usr/bin/env bash
# Humo de punta a punta (I1): navegador simulado con curl.
#   1. /bff/oauth2/authorization/lume (gateway) -> /oauth2/authorize (emisor, vía gateway) -> /login
#   2. POST /login con el usuario de desarrollo del .env -> código -> el gateway lo canjea y guarda los tokens en Redis
#   3. GET /api/hola con solo la cookie LUME_SESION -> el gateway releva el token -> hola valida el JWT y saluda
# Requiere: compose arriba y seguridad, hola y gateway corriendo (infra/arrancar.sh).
set -euo pipefail
raiz="$(cd "$(dirname "$0")/.." && pwd)"
set -a
# shellcheck disable=SC1091
. "$raiz/infra/.env"
set +a
gw="http://localhost:$GATEWAY_PUERTO"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
jar="$tmp/cookies"
falla() { echo "HUMO FALLÓ: $*" >&2; exit 1; }

# 0. Sin sesión, la API responde 401 en JSON
codigo=$(curl -s -o "$tmp/r0" -w '%{http_code}' "$gw/api/hola")
[[ "$codigo" == "401" ]] || falla "sin sesión se esperaba 401 y llegó $codigo"
grep -q '"motivo":"sin_sesion"' "$tmp/r0" || falla "el 401 no trae motivo sin_sesion"
echo "ok 0: sin sesión -> 401 sin_sesion"

# 1. Inicio del ingreso: termina en el formulario del emisor
curl -s -L -c "$jar" -b "$jar" -o "$tmp/login.html" "$gw/bff/oauth2/authorization/lume"
csrf=$(grep -oE 'name="_csrf"[^>]*value="[^"]+"' "$tmp/login.html" | sed -E 's/.*value="([^"]+)".*/\1/' | head -1)
[[ -n "$csrf" ]] || falla "no llegó el formulario de ingreso del emisor"
echo "ok 1: el gateway llevó al formulario del emisor (vía $gw/login)"

# 2. Ingreso: el emisor emite el código y el gateway lo canjea (sigue redirecciones hasta "/")
curl -s -L -c "$jar" -b "$jar" -o /dev/null \
     --data-urlencode "username=$LUME_USUARIO_DEV" --data-urlencode "password=$LUME_CLAVE_DEV" --data-urlencode "_csrf=$csrf" \
     "$gw/login"
grep -q "LUME_SESION" "$jar" || falla "el gateway no creó la sesión LUME_SESION"
if grep -qiE "access_token|refresh_token|eyJ" "$jar"; then falla "un token llegó al navegador"; fi
echo "ok 2: sesión LUME_SESION creada; ningún token en las cookies del navegador"

# 3. API con la cookie: TokenRelay + validación del JWT en hola
codigo=$(curl -s -b "$jar" -o "$tmp/r3" -w '%{http_code}' "$gw/api/hola")
[[ "$codigo" == "200" ]] || { cat "$tmp/r3" >&2; falla "/api/hola con sesión respondió $codigo"; }
grep -q "\"usuario\":\"$LUME_USUARIO_DEV\"" "$tmp/r3" || falla "hola no recibió el usuario del token: $(cat "$tmp/r3")"
echo "ok 3: /api/hola -> $(cat "$tmp/r3")"

# 4. Directo a hola sin token: rechazado (no confía en nada que no sea un JWT válido)
codigo=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Usuario: $LUME_USUARIO_DEV" "http://localhost:$HOLA_PUERTO/api/hola")
[[ "$codigo" == "401" ]] || falla "hola sin token respondió $codigo"
echo "ok 4: hola directo sin token -> 401"

echo "HUMO OK"
