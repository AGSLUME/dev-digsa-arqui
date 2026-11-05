#!/bin/sh
# ADR-005: un esquema por servicio con DOS roles (decisión D8, 2026-11-05):
#   <svc>_ddl  dueño del esquema; solo lo usa Flyway (spring.flyway.user / password).
#   <svc>      el de la aplicación: sin DDL y sin privilegios por defecto. Cada migración le concede, explícitamente, lo que
#              necesita sobre cada tabla (como el monolito con ${rol_app}); p. ej. la bitácora solo SELECT, INSERT.
# Ningún rol tiene permisos sobre el esquema de otro servicio ni sobre public. Las claves llegan del .env (nunca en el repo).
set -eu

crear() {
  servicio="$1"
  clave_ddl="$2"
  clave_app="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
       -v servicio="$servicio" -v ddl="${servicio}_ddl" -v clave_ddl="$clave_ddl" -v clave_app="$clave_app" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'ddl', :'clave_ddl') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'servicio', :'clave_app') \gexec
SELECT format('CREATE SCHEMA %I AUTHORIZATION %I', :'servicio', :'ddl') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', :'servicio', :'servicio') \gexec
SELECT format('ALTER ROLE %I SET search_path = %I', :'ddl', :'servicio') \gexec
SELECT format('ALTER ROLE %I SET search_path = %I', :'servicio', :'servicio') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), :'ddl', :'servicio') \gexec
SQL
}

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SQL

crear seguridad "$SEGURIDAD_DB_CLAVE_DDL" "$SEGURIDAD_DB_CLAVE"
crear visitas "$VISITAS_DB_CLAVE_DDL" "$VISITAS_DB_CLAVE"
crear reportes "$REPORTES_DB_CLAVE_DDL" "$REPORTES_DB_CLAVE"
crear notificaciones "$NOTIFICACIONES_DB_CLAVE_DDL" "$NOTIFICACIONES_DB_CLAVE"
