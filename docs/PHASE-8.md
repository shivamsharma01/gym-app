# Phase 8 — Docker, deployment, production hardening

**Status:** Delivered  
**Depends on:** Phases 0–7, 9  
**Roadmap:** This was the last numbered implementation phase.

## What shipped

| Area | Location |
| --- | --- |
| Backend image | [deploy/Dockerfile.backend](../deploy/Dockerfile.backend) (Java 21, non-root) |
| Frontend image | [deploy/Dockerfile.frontend](../deploy/Dockerfile.frontend) (Vite build + nginx) |
| SPA / API proxy | [deploy/nginx.conf](../deploy/nginx.conf) (`/api`, `/live`, `/gateway`, history fallback) |
| Compose stack | [deploy/docker-compose.yml](../deploy/docker-compose.yml) — MySQL always; `backend`+`frontend` behind profile `full` |
| Env template | [deploy/.env.example](../deploy/.env.example) |
| Prod profile | `application-prod.yml` + `ProdSecurityGuard` (JWT secret required, simulator forbidden, Swagger off) |

## Quick start (full stack)

```bash
cp deploy/.env.example deploy/.env
# Edit APP_SECURITY_JWT_SECRET (openssl rand -base64 48) and DB passwords

docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --build
```

Open **http://localhost:8088** (SPA). API is same-origin under `/api`. Direct backend port defaults to **8080**.

MySQL-only (existing Phase 1 habit):

```bash
docker compose -f deploy/docker-compose.yml up -d mysql
```

## Production hardening checklist

1. Set a unique `APP_SECURITY_JWT_SECRET` (≥ 32 chars); never ship the yml default.
2. Run with `SPRING_PROFILES_ACTIVE=prod` (no `DevDataSeeder`, no Swagger UI).
3. Keep `APP_GATEWAY_SIMULATOR_ENABLED=false`.
4. Restrict `APP_CORS_ORIGINS` to real SPA origins (HTTPS in production).
5. Terminate TLS at a reverse proxy / load balancer; forward `X-Forwarded-*` (nginx config already passes them).
6. Change MySQL passwords; do not expose `3306` publicly.
7. Bootstrap: set `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` once under `prod`, then clear it ([GO-LIVE.md](../deploy/GO-LIVE.md)).
8. Point the Windows gym gateway at `wss://<public-host>/gateway` with a per-gateway token from the staff UI.
9. Optional: set `APP_PUBLIC_BASE_DOMAIN` for `{slug}.yourdomain.com` public sites.
10. Rate limits: in-app defaults for login/enquiry; add edge WAF for multi-node.

## What is intentionally not in Compose

- **TrueFace device gateway** — Windows LAN worker with vendor DLLs. See [gateway/README.md](../gateway/README.md).
- **Windows / Linux TrueFace POCs** — visit tools, not production services.
- Multi-node HA, managed DB, CDN, email provider — ops choices beyond this phase.

## Verify

```bash
# Unit: prod guard
cd backend && mvn -q -Dtest=ProdSecurityGuardTest test

# Compose file
docker compose -f deploy/docker-compose.yml config >/dev/null

# Optional image build (needs Docker + time for Maven/npm)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full build
```

## Follow-ups outside the phase list

Documented in [PRODUCT-FOLLOWUPS.md](PRODUCT-FOLLOWUPS.md) and [deploy/GO-LIVE.md](../deploy/GO-LIVE.md):

- Remote enroll multi-device validation
- ACCESS listen / poll / operator fallback (POC updated)
- Real notification / payment providers
