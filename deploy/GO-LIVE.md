# Go-live runbook (Cloudflare SPA + VPS API + MySQL + gym gateway)

> Portable VPS setup, sizing, backups, and vertical scaling: **[VPS.md](VPS.md)**.

## 1. VPS API stack (no React on the VPS)

```bash
cp deploy/.env.example deploy/.env
# Required: APP_SECURITY_JWT_SECRET=$(openssl rand -base64 48)
# First boot only: APP_BOOTSTRAP_SUPERADMIN_PASSWORD='your-strong-password'
# Keep SPRING_PROFILES_ACTIVE=prod
# APP_CORS_ORIGINS=http://localhost:5173 (same-origin prod SPA needs little/no CORS)

chmod +x deploy/scripts/*.sh
./deploy/scripts/up.sh --build
# equivalent:
# docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --build
```

- API edge: Nginx proxies `/api/`, `/live`, `/gateway`, `/actuator/` → Spring Boot (any Host)
- MySQL bound to `127.0.0.1` only; backend to `127.0.0.1:8080`
- After first SUPER_ADMIN login, **remove** `APP_BOOTSTRAP_SUPERADMIN_PASSWORD` and recreate the backend container

### Same-origin SPA (required for multi-domain SaaS)

Build the React app with **empty** `VITE_API_BASE` (default). One production bundle works on every
customer hostname (`gym.kainazi.co.in`, `gym.heavyreps.in`, …) because the browser calls:

- `https://<customer-host>/api/...`
- `wss://<customer-host>/live`
- `https://<customer-host>/actuator/...` (SUPER_ADMIN UI)

**Cloudflare** terminates public TLS and must route backend paths to the VPS origin while serving
static assets from Pages/Workers (Workers route / Cloudflare path rules), for example:

| Browser path | Origin |
|--------------|--------|
| `/`, `/app/*`, `/g/*`, assets | Cloudflare Pages |
| `/api/*`, `/live`, `/gateway`, `/actuator/*` | VPS Nginx → Spring Boot |

Flow: `Browser → Cloudflare (HTTPS) → Nginx on VPS → Spring Boot (HTTP on Docker network)`.

Do **not** bake `https://api.kainazi.co.in` (or any API hostname) into the SPA. Optional local SPA
container: `--profile spa` with empty `VITE_API_BASE`.

Tenant resolution stays JWT-based on the backend — do not add hostname/header tenant overrides in the SPA.

## 2. TLS (production)

Do not expose MySQL (`3306`) or raw backend (`8080`) on the public internet.

1. Point DNS for customer hostnames (e.g. `gym.kainazi.co.in`, `gym.heavyreps.in`) through Cloudflare to the VPS origin used for API paths.
2. Use real `server_name` values in [`nginx.conf`](nginx.conf) — not `server_name _`.
3. Prefer Cloudflare Universal SSL for the public hostname; on the VPS use HTTP from Cloudflare to Nginx (Flexible) **or** Full (strict) with [`nginx.api.https.conf.example`](nginx.api.https.conf.example).
4. Same-origin SPA → API does not need CORS. Keep `APP_CORS_ORIGINS` for local Vite (`http://localhost:5173`) and any intentional cross-origin clients.
5. WebSocket upgrade for `/live` and `/gateway` is already configured on the edge.

## 3. Rate limits

Backend applies an in-memory sliding window (single node):

- `POST /api/v1/auth/login` — `APP_RATE_LIMIT_LOGIN_PER_MINUTE` (default 20)
- `POST /api/v1/auth/refresh` — `APP_RATE_LIMIT_REFRESH_PER_MINUTE` (default 30)
- `POST /api/v1/public/.../enquiries` — `APP_RATE_LIMIT_ENQUIRY_PER_MINUTE` (default 10)
- Authenticated `/api/**` — per user+tenant (`APP_RATE_LIMIT_AUTHENTICATED_PER_MINUTE`, default 300)
- Reports — `APP_RATE_LIMIT_REPORTS_PER_MINUTE` (default 30)

Disable with `APP_RATE_LIMIT_ENABLED=false` only for local debugging. For multi-node production, add edge rate limits (CDN / WAF) as well. Full matrix: [docs/OBSERVABILITY.md](../docs/OBSERVABILITY.md).

## 3b. Observability

- Public: `/actuator/health` (minimal details).
- SUPER_ADMIN (JWT): `/actuator/metrics`, `/actuator/info`, `/actuator/threaddump` via nginx + platform UI at `/app/platform/actuator`.
- Platform SUPER_ADMIN nav is limited to Gyms, Staff passwords, Audit, and Actuator (no gym ops pages).
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
- [ ] TLS terminated at Cloudflare; origin Nginx proxies /api /live /gateway /actuator
- [ ] SPA built with empty VITE_API_BASE (same-origin); no hardcoded API host in bundle
- [ ] MySQL not public
- [ ] Gateway dials `wss://…/gateway` with per-gateway **operational** credential (enrolled via MSI configurator)
- [ ] IAS stopped on cutover day
