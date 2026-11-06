#!/usr/bin/env bash
# Arranca un servicio Spring en el host con el .env de la sesión.
#   bash infra/arrancar.sh <gateway|seguridad|hola|...>
# Requiere haber construido el servicio DENTRO DEL REACTOR, sin install: ./mvnw -q package -DskipTests -pl servicios/<servicio> -am
# (~/.m2 lo comparten todas las sesiones: un install simultáneo de los SNAPSHOT de comun corrompe el jar de las demás).
set -euo pipefail
servicio="${1:?Uso: $0 <servicio>}"
raiz="$(cd "$(dirname "$0")/.." && pwd)"
set -a
# shellcheck disable=SC1091
. "$raiz/infra/.env"
set +a
puerto_var="$(echo "$servicio" | tr '[:lower:]' '[:upper:]')_PUERTO"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
export SERVER_PORT="${!puerto_var:?No hay ${puerto_var} en infra/.env}"
export REDIS_PUERTO
jar="$(ls "$raiz/servicios/$servicio/target/lume-$servicio-"*.jar 2>/dev/null | grep -v original | head -1 || true)"
if [[ -z "$jar" ]]; then
  echo "No hay jar de $servicio: ejecute ./mvnw -q package -DskipTests -pl servicios/$servicio -am" >&2
  exit 1
fi
exec java -jar "$jar"
