#!/usr/bin/env bash
# Rangos de Flyway del servicio visitas (ACUERDOS_OLEADA_2): VISITAS A usa V1-V199 y VISITAS B usa V200-V399.
#   bash infra/ci/rangos-flyway.sh <rama>
# - En las ramas visitas-a / visitas-b: toda migración versionada AGREGADA respecto a develop debe caer en el rango de esa rama.
# - En cualquier rama: no hay dos migraciones con el mismo número de versión, ni fuera de V1-V399.
set -euo pipefail
rama="${1:-}"
dir="servicios/visitas/src/main/resources/db/migration"
[[ -d "$dir" ]] || { echo "rangos-flyway: aún no existe $dir; nada que revisar"; exit 0; }

version() { basename "$1" | sed -nE 's/^V([0-9]+)(_[0-9]+)*__.*\.sql$/\1/p'; }
falla=0

# Duplicados y fuera de rango, en el árbol completo
declare -A visto
for f in "$dir"/V*.sql; do
  [[ -e "$f" ]] || continue
  v=$(version "$f")
  [[ -n "$v" ]] || { echo "::error::nombre de migración no válido: $f"; falla=1; continue; }
  if (( v < 1 || v > 399 )); then echo "::error::$f fuera de V1-V399"; falla=1; fi
  if [[ -n "${visto[$v]:-}" ]]; then echo "::error::versión V$v repetida: ${visto[$v]} y $f"; falla=1; fi
  visto[$v]="$f"
done

# Lo agregado por la rama respecto a develop
case "$rama" in
  visitas-a) min=1; max=199 ;;
  visitas-b) min=200; max=399 ;;
  *) min=""; max="" ;;
esac
if [[ -n "$min" ]] && git rev-parse --verify -q origin/develop >/dev/null; then
  while read -r f; do
    [[ -n "$f" ]] || continue
    v=$(version "$f")
    [[ -n "$v" ]] || continue
    if (( v < min || v > max )); then echo "::error::$rama agrega $f (V$v) fuera de su rango V$min-V$max"; falla=1; fi
  done < <(git diff --name-only --diff-filter=A origin/develop...HEAD -- "$dir" | grep -E '/V[0-9]' || true)
fi

(( falla == 0 )) && echo "rangos-flyway: OK ($rama)"
exit "$falla"
