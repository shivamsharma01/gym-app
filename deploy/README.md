# Deploy

One Linux VPS runs MySQL, Spring Boot, and Nginx. The React app is a Cloudflare Worker. The TrueFace gateway is a Windows service on the gym PC, not on the VPS.

```text
Browser → Cloudflare Worker (SPA)
            └─ /api, /live, /gateway, /actuator → Nginx on the VPS → Spring Boot
Gym PC  → wss://<api-host>/gateway → same Nginx → Spring Boot
```

Leave `VITE_API_BASE` empty. The SPA calls the current host. Tenant comes from the JWT.

| Need | Minimum |
|------|---------|
| OS | Ubuntu 22.04+ or Debian 12 |
| RAM / disk | 4 GB (8 GB easier) / 20 GB SSD |
| Software | Docker Engine + Compose v2 |
| Public ports | 22, 80, and 443 only if TLS ends on the VPS |
| Keep off the box | `deploy/.env`, MySQL dumps, face-photo archives, the git URL |

Do not publish 3306 or 8080. Do not install Nginx on the host. Do not run `--profile spa` on the VPS.

---

## 1. VPS

Log in as root on an empty machine.

```bash
uname -a
free -h
df -h /
apt update && apt upgrade -y
```

### 1.1 User

```bash
groupadd --system gym
useradd --system --create-home --home-dir /home/gym --shell /bin/bash \
  --gid gym --groups sudo gym
mkdir -p /home/gym/.ssh
chmod 700 /home/gym/.ssh
if [[ -f /root/.ssh/authorized_keys ]]; then
  cp /root/.ssh/authorized_keys /home/gym/.ssh/authorized_keys
  chmod 600 /home/gym/.ssh/authorized_keys
  chown -R gym:gym /home/gym/.ssh
fi
echo 'gym ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/gym
chmod 440 /etc/sudoers.d/gym
```

Confirm a second SSH session works as `gym`, then `su - gym` for the rest.

### 1.2 Docker

```bash
sudo apt install -y ca-certificates curl gnupg git ufw openssl
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
sudo usermod -aG docker gym
```

Debian: use `https://download.docker.com/linux/debian`. Log out and SSH back in as `gym`. `id` must include `docker`, and `docker version` must work without sudo.

### 1.3 Firewall

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

WebSockets use 80/443. No extra ports.

### 1.4 Clone

Do not `sudo git clone`. Root has no GitHub key.

```bash
ssh-keygen -t ed25519 -C "gym-vps-deploy" -f ~/.ssh/gym_deploy -N ""
cat ~/.ssh/gym_deploy.pub
```

GitHub → repo → Settings → Deploy keys → add that public key, read-only.

```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  HostName github.com
  User git
  IdentityFile ~/.ssh/gym_deploy
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config
ssh -T git@github.com
sudo mkdir -p /opt && sudo chown gym:gym /opt
git clone git@github.com:shivamsharma01/gym-app.git /opt/gym
```

HTTPS alternative: `git clone https://github.com/shivamsharma01/gym-app.git /opt/gym` and use a read-only PAT as the password.

### 1.5 Secrets

```bash
cd /opt/gym
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
openssl rand -base64 48
```

| Variable | Set to |
|----------|--------|
| `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD` | Two different strong passwords |
| `APP_SECURITY_JWT_SECRET` | The `openssl` output |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` | First login only |
| `APP_CORS_ORIGINS` | SPA origin, e.g. `https://gym.kainazi.com` |

Copy `.env` off the server before continuing.

### 1.6 Start

```bash
cd /opt/gym
chmod +x deploy/scripts/*.sh
./deploy/scripts/up.sh --build
curl -sS http://127.0.0.1/healthz
curl -sS http://127.0.0.1/actuator/health
```

Log in once as the bootstrap super admin. Remove `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` from `.env`, then:

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --force-recreate backend
```

Nginx config is [`nginx.conf`](nginx.conf). It proxies `/api/`, `/live`, `/gateway`, and `/actuator/` to the backend. Origin TLS: [`nginx.api.https.conf.example`](nginx.api.https.conf.example). Prefer Cloudflare Full (strict).

---

## 2. React on Cloudflare

The Worker name is `gym-app`. Static files stay on Cloudflare. `frontend/worker.js` proxies API paths to the VPS (`https://app.kainazi.com`). Deploy is `wrangler deploy` from GitHub Actions on `main` ([`.github/workflows/frontend.yml`](../.github/workflows/frontend.yml)), not classic Pages Functions. Do not add `public/_redirects` (error 100324). Do not set `VITE_API_BASE`.

GitHub secrets: `CLOUDFLARE_API_TOKEN` (Workers Scripts Edit) and `CLOUDFLARE_ACCOUNT_ID`.

If the Worker is also connected to Cloudflare Git builds, turn those builds off so only GitHub Actions deploys. A second deploy path returns **405** on `POST /api`.

1. Worker → Domains → the SPA host (example `gym.kainazi.com`).
2. `app.kainazi.com` points at the VPS. The Windows gateway uses that host.
3. Smoke test:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" https://gym.kainazi.com/app/login
curl -sS -o /dev/null -w "%{http_code}\n" -X POST https://gym.kainazi.com/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"usernameOrEmail":"x","password":"y"}'
curl -sS https://gym.kainazi.com/actuator/health
```

Login POST must be 401 or 200, never 405.

---

## 3. Backups

```bash
sudo mkdir -p /var/backups/gym && sudo chown gym:gym /var/backups/gym
cd /opt/gym
./deploy/scripts/backup-mysql.sh /var/backups/gym/gym-$(date +%F).sql
docker run --rm -v gym_gym-faces:/faces -v /var/backups/gym:/out alpine \
  tar czf /out/faces-$(date +%F).tgz -C /faces .
```

Copy the SQL dump, the faces archive, and `deploy/.env` off the VPS. Daily cron as `gym`:

```bash
15 3 * * * cd /opt/gym && ./deploy/scripts/backup-mysql.sh /var/backups/gym/gym-$(date +\%F).sql && find /var/backups/gym -name 'gym-*.sql' -mtime +14 -delete
```

Restore on a new machine after sections 1.1–1.5, using the saved `.env`:

```bash
./deploy/scripts/up.sh --build
./deploy/scripts/restore-mysql.sh /path/to/gym-YYYY-MM-DD.sql
docker run --rm -v gym_gym-faces:/faces -v /var/backups/gym:/in alpine \
  sh -c 'cd /faces && tar xzf /in/faces-YYYY-MM-DD.tgz'
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --force-recreate backend
```

Point DNS at the new IP. Gateways keep working when the public API URL is unchanged.

---

## 4. Day-to-day

```bash
cd /opt/gym
git pull && ./deploy/scripts/up.sh --build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f backend
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full down
```

`down` keeps the MySQL volume. Logs on disk: `/var/log/gym` inside `gym-backend` (volume `gym-backend-logs`). Health, rate limits, and alerts: [docs/operations.md](../docs/operations.md).

| Symptom | Change in `deploy/.env`, then recreate |
|---------|----------------------------------------|
| JVM out of memory | `BACKEND_MEMORY_LIMIT` |
| Slow queries | `MYSQL_MEMORY_LIMIT` and `MYSQL_INNODB_BUFFER_POOL_SIZE` |
| Pool exhausted | `DB_POOL_MAX` and `MYSQL_MAX_CONNECTIONS` together |

Stay on one backend node. Rate limits are in memory.

---

## 5. Gym PC gateway

Install details: [gateway/README.md](../gateway/README.md).

1. Stop Interactive Attendance completely.
2. In the staff app, create a Gateway and devices. Copy the one-time enrollment token (about 24 hours).
3. Install the MSI. Run Gym Gateway Configurator as Administrator, enroll, then start the service.
4. Confirm heartbeats. Do not run Interactive Attendance and Gym Gateway together.

---

## 6. Local development

- Backend: `SPRING_PROFILES_ACTIVE=dev` ([frontend/README.md](../frontend/README.md)).
- Gateway without hardware: [gateway/README.md](../gateway/README.md) (Mock or the Python simulator).

---

## Checklist

- [ ] `gym` user is in the `docker` group; `docker version` works without sudo
- [ ] UFW allows 22 and 80 (and 443 if needed); 3306 and 8080 are closed
- [ ] Clone used the deploy key, not `sudo git`
- [ ] `deploy/.env` is filled and stored off the server; profile is `prod`
- [ ] `./deploy/scripts/up.sh --build` is healthy; bootstrap password removed
- [ ] First MySQL dump and faces archive copied off the server
- [ ] SPA login POST returns 401/200, not 405; `VITE_API_BASE` is empty
- [ ] Gateway enrolled; Interactive Attendance is stopped
