# Deploy

See **[docs/PHASE-8.md](../docs/PHASE-8.md)** and **[GO-LIVE.md](GO-LIVE.md)**.

**VPS (API only — SPA on Cloudflare):**

```bash
cp deploy/.env.example deploy/.env
# set APP_SECURITY_JWT_SECRET; APP_CORS_ORIGINS mainly for local Vite; edit nginx.conf server_name
# SPA: empty VITE_API_BASE + Cloudflare proxies /api /live /gateway /actuator to this edge
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --build
```

**Optional local SPA container:** `--profile spa` (uses `nginx.spa-local.conf`, same-origin proxy).

**HTTPS:** Cloudflare public TLS preferred; optional origin TLS via [`nginx.api.https.conf.example`](nginx.api.https.conf.example).
