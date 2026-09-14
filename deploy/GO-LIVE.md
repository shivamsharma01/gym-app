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
- `POST /api/v1/public/.../enquiries` — `APP_RATE_LIMIT_ENQUIRY_PER_MINUTE` (default 10)

Disable with `APP_RATE_LIMIT_ENABLED=false` only for local debugging. For multi-node production, add edge rate limits (CDN / WAF) as well.

## 4. Windows TrueFace gateway cutover

The device gateway is **not** in Docker. Run it on the gym LAN Windows PC.

1. Stop Interactive Attendance (IAS) completely (no tray residual).
2. In staff UI: create a Gateway, copy token; create/register the Device with LAN IP `37777`.
3. On the Windows PC (with `gateway/` + `native/win-x64`):

```bat
set GYM_BACKEND=https://gym.example.com
set GYM_GATEWAY_ID=...
set GYM_GATEWAY_TOKEN=...
set GYM_ADAPTER=TrueFace
set GYM_DEVICE_ID=...
set GYM_DEVICE_IP=192.168.x.x
set GYM_DEVICE_PORT=37777
set GYM_DEVICE_USERNAME=admin
set GYM_DEVICE_PASSWORD=...
dotnet run --project src\Gym.Gateway -c Release
```

4. Confirm backend shows gateway online / heartbeats; never run IAS and gateway together.
5. Enroll members via staff UI; prefer on-device face enroll until remote INSERT is validated on more devices ([PRODUCT-FOLLOWUPS.md](../docs/PRODUCT-FOLLOWUPS.md)).

## 5. Checklist

- [ ] Strong JWT secret and DB passwords
- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] Bootstrap password cleared after first SUPER_ADMIN
- [ ] TLS terminated; CORS locked
- [ ] MySQL not public
- [ ] Gateway dials `wss://…/gateway` with per-gateway token
- [ ] IAS stopped on cutover day
