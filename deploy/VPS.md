# Portable VPS production deploy (provider-agnostic)

This guide deploys **only** the API stack on any Linux VPS (Ubuntu or similar).
The React SPA is hosted separately (typically Cloudflare Pages).

```text
Internet → Cloudflare (TLS + static SPA) → Nginx (VPS) → Spring Boot + MySQL (Docker)
```

No Kubernetes. No provider-specific lock-in. Move to another VPS by copying
`deploy/.env`, the MySQL volume backup, and re-pointing DNS.

## Requirements

| Host | Recommendation |
|------|----------------|
| OS | Ubuntu 22.04+ / Debian 12+ (any Docker-capable Linux) |
| RAM | **4 GB minimum**, 8 GB comfortable |
| Disk | 20 GB+ SSD (MySQL + logs + images) |
| Software | Docker Engine + Compose v2 plugin |
| Ports | 80 (and 443 if origin TLS); **not** 3306/8080 publicly |

Sizing is based on this app: Spring Boot 4 / Java 21, Hikari pool default **20**,
outbox dispatcher every 10s, WebSocket `/live` + `/gateway`, in-memory rate limits.

## One-time VPS setup

```bash
# Install Docker (official docs for your distro), then:
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp   # if using origin TLS
sudo ufw enable

git clone <your-repo> /opt/gym && cd /opt/gym
cp deploy/.env.example deploy/.env
# Edit deploy/.env — at least:
#   MYSQL_PASSWORD, MYSQL_ROOT_PASSWORD
#   APP_SECURITY_JWT_SECRET=$(openssl rand -base64 48)
#   SPRING_PROFILES_ACTIVE=prod
# Optional first boot only:
#   APP_BOOTSTRAP_SUPERADMIN_PASSWORD=...

chmod +x deploy/scripts/*.sh
./deploy/scripts/up.sh --build
```

Verify:

```bash
curl -sS http://127.0.0.1/actuator/health
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
```

After the first SUPER_ADMIN login, **remove** `APP_BOOTSTRAP_SUPERADMIN_PASSWORD`
from `.env` and recreate the backend container.

## What runs on the VPS

| Service | Role | Default resources |
|---------|------|-------------------|
| `mysql` | Persistent data (`gym-mysql-data` volume) | 1 GB RAM, 256M InnoDB buffer |
| `backend` | Spring Boot API + WebSockets | 2 GB RAM, G1, MaxRAMPercentage=75 |
| `edge` | Nginx reverse proxy | 128 MB RAM |

MySQL is bound to **127.0.0.1** on the host only. Backend is on **127.0.0.1:8080**.
Public traffic should hit Nginx (`EDGE_HTTP_PORT`, default 80).

## Cloudflare / SPA

1. Build the SPA with **empty** `VITE_API_BASE` (same-origin `/api`, `/live`).
2. Host static assets on Cloudflare Pages (or any CDN).
3. Proxy `/api/*`, `/live`, `/gateway`, `/actuator/*` from each customer hostname to this VPS.
4. Prefer Cloudflare Universal SSL for browsers; Nginx can stay HTTP to origin (Flexible)
   or use Full (strict) with [`nginx.api.https.conf.example`](nginx.api.https.conf.example).

Tenant resolution stays **JWT-based** in Spring Boot. Do not add Host-based tenant
overrides for staff APIs.

## Vertical scaling (no redesign)

Edit `deploy/.env` and recreate containers:

| Symptom | Knobs |
|---------|--------|
| JVM OOM / GC thrash | Raise `BACKEND_MEMORY_LIMIT` (e.g. `3g` / `4g`) |
| Slow queries / buffer misses | Raise `MYSQL_MEMORY_LIMIT` + `MYSQL_INNODB_BUFFER_POOL_SIZE` |
| Pool exhaustion | Raise `DB_POOL_MAX` and `MYSQL_MAX_CONNECTIONS` together |
| Many WebSocket gateways | More backend RAM first; keep single node (rate limits are in-memory) |

Then: `./deploy/scripts/up.sh --build` (or `docker compose ... up -d` after env change).

## Backup & restore

```bash
./deploy/scripts/backup-mysql.sh /var/backups/gym/$(date +%F).sql
# cron example (daily 02:15):
# 15 2 * * * /opt/gym/deploy/scripts/backup-mysql.sh /var/backups/gym/$(date +\%F).sql

./deploy/scripts/restore-mysql.sh /var/backups/gym/2026-09-21.sql
```

Copy backups **off the VPS**. Migrating providers: restore dump → new compose stack → update DNS.

## Local development (unchanged)

- Backend laptop: `SPRING_PROFILES_ACTIVE=dev` via `backend/.env` (see `backend/.env.example`).
- Frontend: `npm run dev` with Vite proxy to `:8080`.
- Optional compose SPA profile: `--profile spa` (not for production VPS).

## Related docs

- Cutover checklist & gateway: [GO-LIVE.md](GO-LIVE.md)
- Actuator / logs / rate limits: [../docs/OBSERVABILITY.md](../docs/OBSERVABILITY.md)
- Compose overview: [README.md](README.md)
