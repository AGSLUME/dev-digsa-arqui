#!/bin/sh
# ADR-006: un usuario de RabbitMQ por servicio, con permisos mínimos (vhost /). Para el servicio X:
#   configurar  ^X\..+$                     su exchange X.eventos y sus colas X.<algo> (+ .reintento, .dlq). Nada ajeno.
#   escribir    ^X\..+$                     solo publica en SU exchange (nadie publica en nombre de otro) y enlaza sus colas.
#   leer        ^(X\..+|[a-z-]+\.eventos)$  consume sus colas y puede enlazarlas a los exchanges *.eventos de los demás.
# El reintento con TTL y la DLQ usan un exchange directo propio <svc>.<cola>.dlx (no el exchange por defecto): RabbitMQ exige
# escritura sobre el dead-letter exchange al declarar la cola, y ^X\..+$ no cubre amq.default (lo detectó seguridad, S2-OUTBOX).
# Corre dentro del contenedor, en segundo plano, cuando el nodo responde. Es idempotente (re-crea la clave si ya existe).
set -eu

export HOME=/var/lib/rabbitmq

until rabbitmq-diagnostics -q check_running >/dev/null 2>&1; do sleep 2; done

usuario() {
  nombre="$1"
  clave="$2"
  if [ -z "$clave" ]; then
    echo "usuarios.sh: falta la clave de $nombre" >&2
    return 1
  fi
  rabbitmqctl -q add_user "$nombre" "$clave" >/dev/null 2>&1 || rabbitmqctl -q change_password "$nombre" "$clave" >/dev/null
  rabbitmqctl -q set_permissions -p / "$nombre" "^${nombre}\\..+\$" "^${nombre}\\..+\$" "^(${nombre}\\..+|[a-z-]+\\.eventos)\$" >/dev/null
}

usuario seguridad "${SEGURIDAD_RABBIT_CLAVE:-}"
usuario visitas "${VISITAS_RABBIT_CLAVE:-}"
usuario reportes "${REPORTES_RABBIT_CLAVE:-}"
usuario notificaciones "${NOTIFICACIONES_RABBIT_CLAVE:-}"
usuario gateway "${GATEWAY_RABBIT_CLAVE:-}"

touch /var/lib/rabbitmq/.usuarios-lume-listos
echo "usuarios.sh: usuarios de servicio listos"
