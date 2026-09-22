#!/usr/bin/env bash
# Bring up the portable VPS stack (MySQL + backend + nginx edge).
# Usage (from repo root):
#   ./deploy/scripts/up.sh
#   ./deploy/scripts/up.sh --build
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/.env}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Copy deploy/.env.example → deploy/.env and set secrets first." >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a && source "$ENV_FILE" && set +a

if [[ -z "${APP_SECURITY_JWT_SECRET:-}" || "$APP_SECURITY_JWT_SECRET" == REPLACE_WITH_openssl_rand_base64_48 ]]; then
  echo "Set APP_SECURITY_JWT_SECRET in deploy/.env (openssl rand -base64 48)." >&2
  exit 1
fi

if [[ "${SPRING_PROFILES_ACTIVE:-}" != "prod" ]]; then
  echo "Warning: SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-unset} (expected prod on VPS)." >&2
fi

cd "$ROOT"
exec docker compose -f deploy/docker-compose.yml --env-file "$ENV_FILE" --profile full up -d "$@"
