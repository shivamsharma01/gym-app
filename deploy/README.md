# Deploy — VPS production runbook

Provider-agnostic stack: **MySQL + Spring Boot + Nginx** on a Linux VPS.
React is hosted separately (Cloudflare Pages / any CDN). No Kubernetes.

```text
Internet → Cloudflare (TLS + static SPA) → Nginx edge (Docker) → Spring Boot + MySQL
```

## Requirements

| Host | Recommendation |
|------|----------------|
| OS | Ubuntu 22.04+ / Debian 12+ (any Docker-capable Linux) |
| RAM | **4 GB minimum**, 8 GB comfortable |
| Disk | 20 GB+ SSD |
| Software | Docker Engine + Compose v2 |
| Ports | 80 (and 443 if origin TLS); **not** 3306 / 8080 publicly |

Sizing assumes Spring Boot 4 / Java 21, Hikari pool **20**, outbox every 10s, WebSockets `/live` + `/gateway`.

| Service | Role | Default resources |
|---------|------|-------------------|
| `mysql` | Data (`gym-mysql-data` volume) | 1 GB RAM, 256M InnoDB buffer |
| `backend` | API + WebSockets | 2 GB RAM, G1, MaxRAMPercentage=75 |
| `edge` | Nginx reverse proxy | 128 MB RAM |

**Do not install Nginx on the host** — Compose runs it in the `edge` container.

---

## 1. Prepare the VPS (before `./up.sh`)

### 1.1 Update the OS

```bash
sudo apt update
sudo apt upgrade -y
# Optional after kernel upgrades: sudo reboot
```

### 1.2 Base packages

```bash
sudo apt install -y ca-certificates curl gnupg git ufw openssl
```

### 1.3 Docker Engine + Compose plugin (Ubuntu)

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
# Log out and back in (or newgrp docker) so docker works without sudo
```

Debian: same with `https://download.docker.com/linux/debian`. Confirm:

```bash
docker version
docker compose version
```

### 1.4 Firewall (UFW)

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp   # only if TLS terminates on the VPS (§3)
sudo ufw enable
sudo ufw status
```

Do **not** open 3306 or 8080 — Compose binds them to `127.0.0.1` only.

### 1.5 Clone and configure secrets

```bash
sudo mkdir -p /opt
sudo git clone <your-repo-url> /opt/gym
sudo chown -R "$USER:$USER" /opt/gym
cd /opt/gym

cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

| Variable | Action |
|----------|--------|
| `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | Strong unique passwords |
| `APP_SECURITY_JWT_SECRET` | `openssl rand -base64 48` |
| `SPRING_PROFILES_ACTIVE` | Must be `prod` |
| `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` | Set **once** for first SUPER_ADMIN, then remove |

Leave SPA-related `VITE_API_BASE` unset — the SPA is not built on this host.

### 1.6 Nginx (Docker edge)

| Piece | Location |
|-------|----------|
| Process | Container `gym-edge` |
| Config | [`nginx.conf`](nginx.conf) |
| Proxies | `/api/`, `/live`, `/gateway`, `/actuator/` → `backend:8080` |
| Port | `EDGE_HTTP_PORT` (default **80**) |

Edit `nginx.conf` then `./deploy/scripts/up.sh --build`. Origin TLS: use
[`nginx.api.https.conf.example`](nginx.api.https.conf.example), mount certs, map 443.

### 1.7 Start

```bash
cd /opt/gym
chmod +x deploy/scripts/*.sh
./deploy/scripts/up.sh --build
```

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
curl -sS http://127.0.0.1/healthz
curl -sS http://127.0.0.1/actuator/health
```

After first SUPER_ADMIN login, remove `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` from `.env` and:

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d backend --force-recreate
```

---

## 2. Same-origin SPA (Cloudflare)

Build with **empty** `VITE_API_BASE`. One bundle works on every customer host:

| Browser path | Origin |
|--------------|--------|
| `/`, `/app/*`, `/g/*`, assets | Cloudflare Pages |
| `/api/*`, `/live`, `/gateway`, `/actuator/*` | VPS Nginx → Spring Boot |

Tenant resolution is **JWT-based** — do not add Host/header tenant overrides in the SPA.
Optional laptop SPA container: `--profile spa` (not for VPS).

---

## 3. TLS

1. DNS (proxied) for customer hosts → this VPS for API paths.
2. Nginx uses `server_name _` (any Host).
3. Prefer Cloudflare Universal SSL; origin HTTP (Flexible) or Full (strict) with the HTTPS nginx example + UFW 443.
4. Same-origin SPA needs little/no CORS; keep `APP_CORS_ORIGINS` for local Vite.

---

## 4. Rate limits & observability

In-memory sliding window (single node). Matrix and Actuator details:
[docs/operations.md](../docs/operations.md).

- Public: `/actuator/health`
- SUPER_ADMIN: metrics / info / threaddump (+ UI at `/app/platform/actuator`)
- External uptime: `https://<host>/actuator/health`

---

## 5. Vertical scaling

Edit `deploy/.env`, then recreate:

| Symptom | Knobs |
|---------|--------|
| JVM OOM / GC thrash | Raise `BACKEND_MEMORY_LIMIT` |
| Slow queries | Raise `MYSQL_MEMORY_LIMIT` + `MYSQL_INNODB_BUFFER_POOL_SIZE` |
| Pool exhaustion | Raise `DB_POOL_MAX` and `MYSQL_MAX_CONNECTIONS` together |
| Many WebSocket gateways | More backend RAM; stay single-node (rate limits are in-memory) |

---

## 6. Backup & restore

```bash
./deploy/scripts/backup-mysql.sh /var/backups/gym/$(date +%F).sql
./deploy/scripts/restore-mysql.sh /var/backups/gym/2026-09-21.sql
```

Copy backups off-VPS. Migrate providers: restore dump → new compose → update DNS.

---

## 7. Windows TrueFace gateway cutover

Not in Docker — install on the gym LAN Windows PC.

1. Stop Interactive Attendance (IAS) completely.
2. Staff UI: create **Gateway** + devices; copy **one-time enrollment token** (~24h TTL).
3. Install `GymGateway-*-win-x64.msi`; run Configurator as Administrator → enroll → start service.
4. Confirm heartbeats; never run IAS and Gym Gateway together.
5. Prefer on-device face enroll until remote INSERT is validated on more units ([product.md](../docs/product.md)).

Dev-only: env vars + `dotnet run` with an **operational** credential from enroll (not the one-time token after consume).

---

## 8. Local development

- Backend: `SPRING_PROFILES_ACTIVE=dev` via `backend/.env`
- Frontend: `npm run dev` (Vite proxies `/api`, `/live`, `/actuator`)
- Optional: `docker compose … --profile spa`

---

## 9. Checklist

- [ ] OS updated; Docker Engine + Compose installed
- [ ] UFW: SSH + 80 (+ 443 if needed); 3306/8080 closed
- [ ] `deploy/.env`: strong secrets; `SPRING_PROFILES_ACTIVE=prod`
- [ ] `./deploy/scripts/up.sh --build` healthy
- [ ] Bootstrap password cleared after first SUPER_ADMIN
- [ ] Cloudflare TLS + path routing to VPS; SPA empty `VITE_API_BASE`
- [ ] Gateway enrolled; IAS stopped on cutover day
