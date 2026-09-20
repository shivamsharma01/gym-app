#!/usr/bin/env bash
# Restore a SQL dump into gym-mysql. DESTROYS existing data in MYSQL_DATABASE.
# Usage:
#   ./deploy/scripts/restore-mysql.sh backup.sql
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/.env}"
COMPOSE=(docker compose -f "$ROOT/deploy/docker-compose.yml" --env-file "$ENV_FILE")
DUMP="${1:?usage: restore-mysql.sh <dump.sql>}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi
if [[ ! -f "$DUMP" ]]; then
  echo "Dump not found: $DUMP" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a && source "$ENV_FILE" && set +a

echo "Restoring $DUMP into database ${MYSQL_DATABASE}…" >&2
"${COMPOSE[@]}" exec -T mysql mysql -u root "-p${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}" <"$DUMP"
echo "Restore complete. Restart backend if Flyway complains about checksums." >&2
