#!/usr/bin/env bash
# Genera infra/.env para una sesión (ADR-010): puertos = base + desfase y claves aleatorias locales.
#   bash infra/generar-env.sh <sesion>            # escribe infra/.env (no lo sobrescribe si existe)
#   bash infra/generar-env.sh <sesion> --ejemplo  # imprime la plantilla sin claves (infra/.env.ejemplo)
# El .env no se versiona. Las claves solo sirven en este equipo y nunca se copian a otro lado.
set -euo pipefail

sesion="${1:-}"
case "$sesion" in
  integradora) d=0 ;; seguridad) d=100 ;; angular) d=200 ;;
  visitas-a) d=300 ;; visitas-b) d=400 ;; reportes) d=500 ;;
  *) echo "Uso: $0 <integradora|seguridad|angular|visitas-a|visitas-b|reportes> [--ejemplo]" >&2; exit 2 ;;
esac
ejemplo="${2:-}"

dir="$(cd "$(dirname "$0")" && pwd)"
destino="$dir/.env"
if [[ "$ejemplo" != "--ejemplo" && -e "$destino" ]]; then
  echo "Ya existe $destino; bórrelo a propósito si quiere regenerarlo." >&2
  exit 1
fi

clave() {
  if [[ "$ejemplo" == "--ejemplo" ]]; then echo "<generada-por-generar-env.sh>"; else openssl rand -hex 24; fi
}

# Clave AES-256 en base64 (32 bytes): cifra las claves de firma del emisor en la tabla jwk.
clave_aes() {
  if [[ "$ejemplo" == "--ejemplo" ]]; then echo "<generada-por-generar-env.sh>"; else openssl rand -base64 32; fi
}

gw=$((9000 + d))
secreto_gateway="$(clave)"
secreto_humo="$(clave)"
secreto_visitas="$(clave)"
secreto_reportes="$(clave)"
secreto_notificaciones="$(clave)"
secreto_monolito="$(clave)"

contenido=$(cat <<EOF
# Entorno local de la sesión $sesion (desfase +$d). Generado por infra/generar-env.sh. NO SE VERSIONA.
LUME_SESION=$sesion
DESFASE=$d

# Puertos (ADR-010)
GATEWAY_PUERTO=$gw
SEGURIDAD_PUERTO=$((9101 + d))
VISITAS_PUERTO=$((9102 + d))
REPORTES_PUERTO=$((9103 + d))
NOTIFICACIONES_PUERTO=$((9104 + d))
HOLA_PUERTO=$((9109 + d))
ANGULAR_PUERTO=$((4200 + d / 10))
PG_PUERTO=$((5440 + d / 100))
RABBITMQ_PUERTO=$((5672 + d))
RABBITMQ_CONSOLA_PUERTO=$((15672 + d))
REDIS_PUERTO=$((6380 + d / 100))
PROMETHEUS_PUERTO=$((9090 + d))
GRAFANA_PUERTO=$((3000 + d))
ZIPKIN_PUERTO=$((9411 + d))
MONOLITO_PUERTO=$((9050 + d))

# URLs que leen los servicios Spring (corren en el host)
LUME_EMISOR=http://localhost:$gw
LUME_SEGURIDAD_URL=http://localhost:$((9101 + d))
LUME_VISITAS_URL=http://localhost:$((9102 + d))
LUME_HOLA_URL=http://localhost:$((9109 + d))
LUME_URL_SEGURA=false
LUME_FRONTEND_URL=http://localhost:$((4200 + d / 10))
DIGSA_MONOLITO_URL=http://localhost:$((9050 + d))
REDIS_HOST=localhost
REDIS_USUARIO_GATEWAY=gateway
LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash
JAVA_TOOL_OPTIONS=-Xmx384m

# Correo local: Mailpit ya instalado (no se levanta otro). Datos ficticios con dominio propio de la sesión.
LUME_SMTP_HOST=127.0.0.1
LUME_SMTP_PUERTO=1025
LUME_DOMINIO_PRUEBA=$sesion.prueba.local

# Claves locales aleatorias
PG_CLAVE_ADMIN=$(clave)
SEGURIDAD_DB_CLAVE=$(clave)
VISITAS_DB_CLAVE=$(clave)
REPORTES_DB_CLAVE=$(clave)
NOTIFICACIONES_DB_CLAVE=$(clave)
# Roles dueños del esquema, solo para Flyway (D8)
SEGURIDAD_DB_CLAVE_DDL=$(clave)
VISITAS_DB_CLAVE_DDL=$(clave)
REPORTES_DB_CLAVE_DDL=$(clave)
NOTIFICACIONES_DB_CLAVE_DDL=$(clave)
RABBITMQ_CLAVE_ADMIN=$(clave)
REDIS_CLAVE_GATEWAY=$(clave)
GRAFANA_CLAVE_ADMIN=$(clave)
# Usuarios de RabbitMQ por servicio (ADR-006)
SEGURIDAD_RABBIT_USUARIO=seguridad
SEGURIDAD_RABBIT_CLAVE=$(clave)
VISITAS_RABBIT_USUARIO=visitas
VISITAS_RABBIT_CLAVE=$(clave)
REPORTES_RABBIT_USUARIO=reportes
REPORTES_RABBIT_CLAVE=$(clave)
NOTIFICACIONES_RABBIT_USUARIO=notificaciones
NOTIFICACIONES_RABBIT_CLAVE=$(clave)
GATEWAY_RABBIT_USUARIO=gateway
GATEWAY_RABBIT_CLAVE=$(clave)

# Andamiaje del emisor (I1; S1 lo reemplaza). {noop} solo se admite en local.
LUME_GATEWAY_SECRETO=$secreto_gateway
LUME_GATEWAY_SECRETO_CODIFICADO='{noop}$secreto_gateway'
LUME_HUMO_SECRETO=$secreto_humo
LUME_HUMO_SECRETO_CODIFICADO='{noop}$secreto_humo'
# Clientes de máquina del emisor (client_credentials). En claro para quien llama; codificado para seguridad. {noop} solo en local.
LUME_VISITAS_SECRETO=$secreto_visitas
LUME_VISITAS_SECRETO_CODIFICADO='{noop}$secreto_visitas'
LUME_REPORTES_SECRETO=$secreto_reportes
LUME_REPORTES_SECRETO_CODIFICADO='{noop}$secreto_reportes'
LUME_NOTIFICACIONES_SECRETO=$secreto_notificaciones
LUME_NOTIFICACIONES_SECRETO_CODIFICADO='{noop}$secreto_notificaciones'
LUME_MONOLITO_SECRETO=$secreto_monolito
LUME_MONOLITO_SECRETO_CODIFICADO='{noop}$secreto_monolito'
LUME_USUARIO_DEV=dev.$sesion
LUME_CLAVE_DEV=$(clave)
LUME_PERFIL_DEV=ADMINISTRADOR

# Emisor (S1): clave AES-256 que cifra las claves de firma en la tabla jwk. Vault en el cierre (I9).
LUME_JWK_CLAVE=$(clave_aes)
EOF
)

if [[ "$ejemplo" == "--ejemplo" ]]; then
  printf '%s\n' "$contenido"
else
  umask 077
  printf '%s\n' "$contenido" > "$destino"
  echo "Escrito $destino (sesión $sesion, desfase +$d)."
fi
