# Fresh VPS bootstrap (from empty root)

Repeatable install for **MySQL + Spring Boot + Nginx edge** only.
React stays on Cloudflare Pages (or another CDN) — do **not** run `--profile spa` on the VPS.

Assumes Ubuntu 22.04/24.04 (or Debian 12). You are logged in as `root` with an empty home.

What you must keep **off the VPS** for disaster recovery:

| Artifact | Why |
|----------|-----|
| `deploy/.env` (secrets) | Passwords, JWT secret — without this, restore is painful |
| MySQL dumps from `backup-mysql.sh` | Application data |
| Git repo URL + deploy key or PAT | To re-clone and rebuild images |

Docker volume `gym-mysql-data` is the live DB; dumps are what you copy off-box weekly (or daily).

---

## 0. Know the box

```bash
uname -a
. /etc/os-release && echo "$PRETTY_NAME"
free -h
df -h /
```

Need roughly **4 GB RAM**, **20 GB+** disk. Open ports later: **22**, **80**, optionally **443**. Never expose **3306** / **8080** publicly.

---

## 1. OS update

```bash
apt update
apt upgrade -y
# If the kernel changed: reboot; then SSH back in as root for the next steps
# reboot
```

---

## 2. Create deploy user + group

Do not run the app stack as `root` day to day.

```bash
# System group + user (no interactive GECOS prompts)
groupadd --system gym
useradd --system --create-home --home-dir /home/gym --shell /bin/bash \
  --gid gym --groups sudo gym

# SSH: allow the same key you use for root (adjust if you use a different pubkey)
mkdir -p /home/gym/.ssh
chmod 700 /home/gym/.ssh
if [[ -f /root/.ssh/authorized_keys ]]; then
  cp /root/.ssh/authorized_keys /home/gym/.ssh/authorized_keys
  chmod 600 /home/gym/.ssh/authorized_keys
  chown -R gym:gym /home/gym/.ssh
fi

# Passwordless sudo for package/docker ops (optional but convenient)
echo 'gym ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/gym
chmod 440 /etc/sudoers.d/gym
```

Confirm you can SSH as `gym` in a **second** session before locking down root (optional later).

Remaining steps: either `su - gym` or reconnect as `gym`.

```bash
su - gym
```

---

## 3. Base packages

```bash
sudo apt install -y ca-certificates curl gnupg git ufw openssl util-linux
```

(`util-linux` provides `newgrp` if you want it; a full SSH re-login is enough without it.)

---

## 4. Docker Engine + Compose v2

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
sudo usermod -aG docker gym
```

Group membership only applies after a **new login**. Do **not** rely on `newgrp` (minimal images often lack it).

```bash
# Log out of SSH completely, then SSH back in as gym
id
# must include docker, e.g. groups=...docker...
docker version          # Client + Server — no "permission denied" on docker.sock
docker compose version
```

If `id` still has no `docker`, run `sudo usermod -aG docker gym` again and re-login.

Temporary workaround only: `sudo docker …` (prefer fixing the group).

Debian: use `https://download.docker.com/linux/debian` instead of `.../ubuntu`.

---

## 5. Firewall

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp   # only if TLS terminates on this VPS
sudo ufw --force enable
sudo ufw status
```

Compose binds MySQL and backend to loopback; do not `ufw allow 3306` or `8080`.

WebSockets (`/live`, `/gateway`) use the **same** 80/443 ports (HTTP Upgrade). No extra UFW rules.

---

## 6. Clone the repo (deploy key — private GitHub)

**Do not** `sudo git clone` over SSH: that runs as `root`, which has no GitHub key → `Permission denied (publickey)` and no `/opt/gym` directory.

### 6.1 Create a passphrase-less deploy key as `gym`

```bash
ssh-keygen -t ed25519 -C "gym-vps-deploy" -f ~/.ssh/gym_deploy -N ""
cat ~/.ssh/gym_deploy.pub
```

Confirm no passphrase prompt:

```bash
ssh-keygen -y -f ~/.ssh/gym_deploy </dev/null
```

### 6.2 Add the public key on GitHub

Repo → **Settings → Deploy keys → Add deploy key**:

- Title: e.g. `vps-kainazi`
- Key: paste the full `gym_deploy.pub` line
- Leave **Allow write access** unchecked (read-only is enough)

(Alternatively: your user **SSH and GPG keys** — also works.)

### 6.3 Point SSH at that key

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
# expect: successfully authenticated (or "Hi …!")
```

Accept GitHub’s host key when prompted (`yes`). Fingerprint for `github.com` ED25519 is commonly  
`SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU` (verify against [GitHub’s docs](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints) if unsure).

If you still get `Permission denied (publickey)`, the pubkey is not on the repo yet, or it does not match `~/.ssh/gym_deploy.pub`.

### 6.4 Clone as `gym` (no sudo on git)

```bash
sudo mkdir -p /opt
sudo chown gym:gym /opt
cd /opt
git clone git@github.com:shivamsharma01/gym-app.git gym
cd /opt/gym
```

### Alternative: HTTPS + PAT

```bash
sudo mkdir -p /opt && sudo chown gym:gym /opt
cd /opt
git clone https://github.com/shivamsharma01/gym-app.git gym
# Username: GitHub username
# Password: classic PAT with repo read (not your GitHub account password)
cd /opt/gym
```

Images are **built on the VPS** from Dockerfiles (`./deploy/scripts/up.sh --build`). You do not need a registry for day one.

---

## 7. Secrets (`deploy/.env`)

```bash
cd /opt/gym
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

Edit with `nano deploy/.env` (or vim). Minimum:

```bash
# Generate once; store a copy OFF the server
openssl rand -base64 48
```

Set at least:

| Variable | Value |
|----------|--------|
| `MYSQL_PASSWORD` | Strong password |
| `MYSQL_ROOT_PASSWORD` | Strong password (different) |
| `APP_SECURITY_JWT_SECRET` | Output of `openssl rand -base64 48` |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` | Strong password (**first boot only**) |
| `APP_CORS_ORIGINS` | Your real SPA origin(s), e.g. `https://app.yourdomain.com` (comma-separated). Not localhost. |

Copy `deploy/.env` to a password manager or encrypted backup **now**. Losing it makes rotation/restore harder.

---

## 8. Start the stack

```bash
cd /opt/gym
chmod +x deploy/scripts/*.sh
./deploy/scripts/up.sh --build
```

That starts **mysql + backend + edge** (`--profile full` only).

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
curl -sS http://127.0.0.1/healthz
curl -sS http://127.0.0.1/actuator/health
```

Log in as SUPER_ADMIN once, then **remove** `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` from `deploy/.env` and:

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --force-recreate backend
```

Point Cloudflare (or DNS) API paths at this host. SPA is separate — see [README.md](README.md) §2.

---

## 9. Backups (do this from day one)

On the VPS:

```bash
sudo mkdir -p /var/backups/gym
sudo chown gym:gym /var/backups/gym

cd /opt/gym
./deploy/scripts/backup-mysql.sh /var/backups/gym/gym-$(date +%F).sql
```

Copy off-box (example):

```bash
# From your laptop
scp gym@YOUR_VPS_IP:/var/backups/gym/gym-YYYY-MM-DD.sql ./
```

Optional cron as `gym`:

```bash
crontab -e
# Daily 03:15 UTC
15 3 * * * cd /opt/gym && ./deploy/scripts/backup-mysql.sh /var/backups/gym/gym-$(date +\%F).sql && find /var/backups/gym -name 'gym-*.sql' -mtime +14 -delete
```

Also keep an off-box copy of `deploy/.env`.

---

## 10. Rebuild after crash / new provider (restore)

On a **new** empty VPS, repeat §1–§7 (same `deploy/.env` from backup).

```bash
cd /opt/gym
./deploy/scripts/up.sh --build

# Wait until mysql is healthy, then:
./deploy/scripts/restore-mysql.sh /path/to/gym-YYYY-MM-DD.sql

docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --force-recreate backend
```

Update DNS / Cloudflare to the new IP. Windows gym gateways keep working once they can reach the same public URL.

You do **not** need Terraform for this shape: git + `.env` + SQL dump is enough.

---

## 11. Useful ops

```bash
cd /opt/gym

# Logs
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f backend

# Pull latest code and rebuild
git pull
./deploy/scripts/up.sh --build

# Stop stack (data volume kept)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full down
```

---

## Checklist

- [ ] `gym` user + sudo + docker group (`id` shows `docker`; `docker version` works without sudo)
- [ ] OS updated; Docker Engine + Compose installed
- [ ] UFW: SSH + 80 (+ 443 if needed); WebSockets share those ports
- [ ] Deploy key (or PAT) works: `ssh -T git@github.com` / clone **without** `sudo git`
- [ ] `/opt/gym` cloned; `deploy/.env` filled; **copied off-box**
- [ ] `./deploy/scripts/up.sh --build` healthy
- [ ] Bootstrap password removed after first SUPER_ADMIN
- [ ] First MySQL dump copied off-box
- [ ] Cloudflare / DNS → this VPS for `/api`, `/live`, `/gateway`
