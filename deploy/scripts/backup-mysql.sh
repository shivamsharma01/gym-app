#!/usr/bin/env bash
# Dump MySQL from the running gym-mysql container to stdout or a file.
# Usage (from repo root):
#   ./deploy/scripts/backup-mysql.sh > backup-$(date +%F).sql
#   ./deploy/scripts/backup-mysql.sh /var/backups/gym/backup.sql
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/.env}"
COMPOSE=(docker compose -f "$ROOT/deploy/docker-compose.yml" --env-file "$ENV_FILE")

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE — copy deploy/.env.example first." >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a && source "$ENV_FILE" && set +a

OUT="${1:-}"
ARGS=(exec -T mysql mysqldump
  -u root
  "-p${MYSQL_ROOT_PASSWORD}"
  --single-transaction
  --routines
  --triggers
  "${MYSQL_DATABASE}"
)

if [[ -n "$OUT" ]]; then
  mkdir -p "$(dirname "$OUT")"
  "${COMPOSE[@]}" "${ARGS[@]}" >"$OUT"
  echo "Wrote $OUT" >&2
else
  "${COMPOSE[@]}" "${ARGS[@]}"
fi
