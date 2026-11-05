#!/bin/sh
# ADR-005: un esquema y un usuario por servicio; cada usuario solo tiene permisos sobre su esquema.
# Las claves llegan por variables de entorno desde el .env de la sesión (nunca en el repo).
set -eu

crear() {
  servicio="$1"
  clave="$2"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
       -v servicio="$servicio" -v clave="$clave" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'servicio', :'clave') \gexec
SELECT format('CREATE SCHEMA %I AUTHORIZATION %I', :'servicio', :'servicio') \gexec
SELECT format('ALTER ROLE %I SET search_path = %I', :'servicio', :'servicio') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'servicio') \gexec
SQL
}

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -c "REVOKE ALL ON SCHEMA public FROM PUBLIC;"

crear seguridad "$SEGURIDAD_DB_CLAVE"
crear visitas "$VISITAS_DB_CLAVE"
crear reportes "$REPORTES_DB_CLAVE"
crear notificaciones "$NOTIFICACIONES_DB_CLAVE"
