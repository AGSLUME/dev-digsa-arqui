#!/bin/sh
# ADR-006: un usuario de RabbitMQ por servicio, con permisos mínimos (vhost /). Para el servicio X:
#   configurar  ^X\..+$                     su exchange X.eventos y sus colas X.<algo> (+ .reintento, .dlq, .dlx). Nada ajeno.
#   escribir    ^X\..+$                     solo publica en SU exchange (nadie publica en nombre de otro) y enlaza sus colas.
#   leer        ^(X\..+|[a-z-]+\.eventos)$  consume sus colas y puede enlazarlas a los exchanges *.eventos de los demás...
# ...pero SOLO con las claves que permite la matriz de lectura (topic permissions, más abajo): sin ella, cualquier servicio podría
# enlazar seguridad.eventos con "#" y recibir la foto de todos los usuarios con documento y correo (Ley 1581; lo detectó seguridad).
# El reintento con TTL y la DLQ usan un exchange directo propio <svc>.<cola>.dlx (no el exchange por defecto): RabbitMQ exige
# escritura sobre el dead-letter exchange al declarar la cola, y ^X\..+$ no cubre amq.default (lo detectó seguridad, S2-OUTBOX).
# Corre dentro del contenedor, en segundo plano, cuando el nodo responde. Es idempotente.
set -eu

export HOME=/var/lib/rabbitmq

until rabbitmq-diagnostics -q check_running >/dev/null 2>&1; do sleep 2; done

SERVICIOS="seguridad visitas reportes notificaciones gateway"
PUBLICADORES="seguridad visitas reportes notificaciones"
NADA='^$'

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

# Permiso por tema: qué claves puede ESCRIBIR (publicar) y LEER (enlazar) el usuario en ese exchange.
tema() {
  rabbitmqctl -q set_topic_permissions -p / "$1" "$2" "$3" "$4" >/dev/null
}

# Administrador: con definiciones al arrancar, RabbitMQ ya no crea el usuario por defecto (RABBITMQ_DEFAULT_USER/PASS).
if [ -z "${RABBITMQ_DEFAULT_PASS:-}" ]; then echo "usuarios.sh: falta RABBITMQ_DEFAULT_PASS" >&2; exit 1; fi
rabbitmqctl -q add_user "${RABBITMQ_DEFAULT_USER:-lume_admin}" "$RABBITMQ_DEFAULT_PASS" >/dev/null 2>&1 \
  || rabbitmqctl -q change_password "${RABBITMQ_DEFAULT_USER:-lume_admin}" "$RABBITMQ_DEFAULT_PASS" >/dev/null
rabbitmqctl -q set_user_tags "${RABBITMQ_DEFAULT_USER:-lume_admin}" administrator >/dev/null
rabbitmqctl -q set_permissions -p / "${RABBITMQ_DEFAULT_USER:-lume_admin}" '.*' '.*' '.*' >/dev/null

usuario seguridad "${SEGURIDAD_RABBIT_CLAVE:-}"
usuario visitas "${VISITAS_RABBIT_CLAVE:-}"
usuario reportes "${REPORTES_RABBIT_CLAVE:-}"
usuario notificaciones "${NOTIFICACIONES_RABBIT_CLAVE:-}"
usuario gateway "${GATEWAY_RABBIT_CLAVE:-}"

# 1) Por omisión, nada: sin un permiso por tema, RabbitMQ deja enlazar cualquier clave. Cada publicador escribe cualquier clave en
#    su propio exchange; nadie lee nada de nadie hasta la matriz.
for u in $SERVICIOS; do
  for p in $PUBLICADORES; do
    if [ "$u" = "$p" ]; then tema "$u" "${p}.eventos" '.*' "$NADA"; else tema "$u" "${p}.eventos" "$NADA" "$NADA"; fi
  done
done

# 2) Matriz de lectura (lo que cada consumidor necesita; una clave nueva se pide a la integradora).
tema gateway seguridad.eventos "$NADA" '^(sesion\..*|usuario\.(permisos_cambiados|estado_cambiado))$'
tema visitas seguridad.eventos "$NADA" '^(usuario|cuenta)\..*$'
tema reportes seguridad.eventos "$NADA" '^(usuario|cuenta)\..*$'
tema reportes visitas.eventos "$NADA" '^(visita|hallazgo)\..*$'
tema notificaciones visitas.eventos "$NADA" '^(alerta|aviso)\..*$'
# visitas recibe el resultado de los envíos que pidió (acta: la visita se cierra al generarla y el correo sale después; D-N1)
tema visitas notificaciones.eventos "$NADA" '^envio\..*$'
# seguridad consume la auditoría de todos (ADR-002/006); en su propio exchange conserva la escritura
for p in reportes notificaciones; do
  tema seguridad "${p}.eventos" "$NADA" '^auditoria\..*$'
done
# en visitas.eventos, además, los maestros de su copia local de puntos, regionales y fuerzas (ADR-002; pedido de visitas-a en VA1)
tema seguridad visitas.eventos "$NADA" '^(auditoria|punto|regional|fuerza)\..*$'

touch /var/lib/rabbitmq/.usuarios-lume-listos
echo "usuarios.sh: usuarios de servicio y matriz de lectura listos"

# Si alguien borra y vuelve a declarar un <origen>.eventos, RabbitMQ pierde sus permisos por tema y quedaría abierto: la matriz se
# reaplica cada 5 minutos (es idempotente). Solo en el proceso de arranque (LUME_REAPLICAR=1); a mano se corre una sola vez.
if [ "${LUME_REAPLICAR:-1}" = "1" ] && [ "${1:-}" != "--una-vez" ]; then
  while sleep 300; do
    LUME_REAPLICAR=0 sh "$0" --una-vez >/dev/null 2>&1 || echo "usuarios.sh: falló la reaplicación de la matriz" >&2
  done
fi
