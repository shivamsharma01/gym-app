# Go-live runbook (UI + backend + MySQL + gym gateway)

## 1. Full Docker stack

```bash
cp deploy/.env.example deploy/.env
# Required: APP_SECURITY_JWT_SECRET=$(openssl rand -base64 48)
# First boot only: APP_BOOTSTRAP_SUPERADMIN_PASSWORD='your-strong-password'
# Optional: APP_BOOTSTRAP_SUPERADMIN_USERNAME / EMAIL

docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --build
```

- SPA: http://localhost:8088  
- After first login as SUPER_ADMIN, **remove** `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` from `deploy/.env` and recreate the backend container so the secret is not left in env.

## 2. TLS (production)

Do not expose MySQL (`3306`) or raw backend (`8080`) on the public internet.

Put a reverse proxy (Caddy, nginx, Traefik, cloud LB) in front of the SPA container (or terminate TLS and proxy to nginx `:80`):

- HTTPS → frontend container
- Forward `X-Forwarded-Proto`, `X-Forwarded-For`, `Host`
- WebSocket upgrade for `/live` and `/gateway`
- Set `APP_CORS_ORIGINS` to the real `https://…` origin(s)

Example Caddy sketch:

```
gym.example.com {
  reverse_proxy frontend:80
}
```

## 3. Rate limits

Backend applies an in-memory sliding window (single node):

- `POST /api/v1/auth/login` — `APP_RATE_LIMIT_LOGIN_PER_MINUTE` (default 20)
- `POST /api/v1/auth/refresh` — `APP_RATE_LIMIT_REFRESH_PER_MINUTE` (default 30)
- `POST /api/v1/public/.../enquiries` — `APP_RATE_LIMIT_ENQUIRY_PER_MINUTE` (default 10)
- Authenticated `/api/**` — per user+tenant (`APP_RATE_LIMIT_AUTHENTICATED_PER_MINUTE`, default 300)
- Reports — `APP_RATE_LIMIT_REPORTS_PER_MINUTE` (default 30)

Disable with `APP_RATE_LIMIT_ENABLED=false` only for local debugging. For multi-node production, add edge rate limits (CDN / WAF) as well. Full matrix: [docs/OBSERVABILITY.md](../docs/OBSERVABILITY.md).

## 3b. Observability

- Public: `/actuator/health` (nginx proxies this only).
- SUPER_ADMIN: `/actuator/metrics`, `/actuator/info`, `/actuator/threaddump` on the backend port (not via public nginx).
- Rolling logs + request correlation: see [docs/OBSERVABILITY.md](../docs/OBSERVABILITY.md).
- Hostinger monitors VPS CPU/RAM/disk; Spring monitors app/DB/JVM.
- Point an external uptime check at `https://<host>/actuator/health`.

## 4. Windows TrueFace gateway cutover

The device gateway is **not** in Docker. Install it on the gym LAN Windows PC.

1. Stop Interactive Attendance (IAS) completely (no tray residual).
2. In staff UI: create a **Gateway** (and devices with LAN IPs). Copy the **one-time enrollment token** shown at create (or **Reissue enrollment** if replacing a PC). Token TTL is ~24 hours; it is **not** the long-lived service credential.
3. Install `GymGateway-*-win-x64.msi` from CI (`gateway-msi` workflow). Run **Gym Gateway Configurator** as Administrator:
   - Backend URL (e.g. `https://gym.example.com`), Gateway ID, enrollment token → Enroll
   - Confirm device IPs / ports / tablet passwords → optional TrueFace connect test
   - Save config & start service (DPAPI config under `%ProgramData%\GymGateway\`)
4. Confirm backend shows gateway online / heartbeats; never run IAS and the Gym Gateway service together.
5. Enroll members via staff UI; prefer on-device face enroll until remote INSERT is validated on more devices ([PRODUCT-FOLLOWUPS.md](../docs/PRODUCT-FOLLOWUPS.md)).

**Dev-only alternative** (no MSI): env vars + `dotnet run` / published `Gym.Gateway.exe` with an **operational** credential from `POST /internal/gateway/enroll` (not the enrollment token after consume).

Linux hosts can run `gateway/scripts/publish-win.sh` to cross-publish the worker; they **cannot** build the MSI or WPF configurator.

## 5. Checklist

- [ ] Strong JWT secret and DB passwords
- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] Bootstrap password cleared after first SUPER_ADMIN
- [ ] TLS terminated; CORS locked
- [ ] MySQL not public
- [ ] Gateway dials `wss://…/gateway` with per-gateway **operational** credential (enrolled via MSI configurator)
- [ ] IAS stopped on cutover day
