# React on Cloudflare (Workers static assets)

The SPA is **not** on the VPS. Cloudflare serves static assets from Worker `gym-app`;
the VPS serves `/api`, `/live`, `/gateway`, and `/actuator`. Same public hostname for the
browser (e.g. `gym.kainazi.com`); API traffic is proxied by the Worker to
`https://app.kainazi.com`.

```text
Browser (gym.kainazi.com)
  ├─ /, /app/*, /g/*, JS/CSS  → Worker assets (SPA)
  └─ /api/*, /live, /gateway, /actuator/*  → worker.js → https://app.kainazi.com (VPS)
```

Why same host for the SPA? The staff live socket is `wss://<current-host>/live`
([`backendUrls.ts`](../frontend/src/lib/backendUrls.ts)). Keep `VITE_API_BASE` **empty**
in production.

Windows gateway can keep using **`https://app.kainazi.com`** directly (no need for `gym`).

---

## 1. Prerequisites

- VPS stack healthy (`/healthz`, `/actuator/health`) — [VPS-BOOTSTRAP.md](VPS-BOOTSTRAP.md)
- Domain on Cloudflare (orange-cloud DNS)
- GitHub repo connected to Cloudflare Workers/Pages build

---

## 2. Cloudflare project settings

This repo deploys with **`wrangler deploy`** (Workers + static assets), **not** classic
Pages Functions. Do **not** rely on `frontend/functions/` — that folder is ignored by
this deploy path and `/api` POSTs become **405 Method Not Allowed**.

| Setting | Value |
|---------|--------|
| Root directory | `frontend` |
| Build command | `npm ci && npm run build` |
| Deploy command | `npx wrangler deploy` (or `npm run deploy`) |
| Build output | `dist` (also set in `wrangler.jsonc` → `assets.directory`) |

Commit these files (already in the repo):

- [`frontend/wrangler.jsonc`](../frontend/wrangler.jsonc) — SPA assets +
  `run_worker_first` for API paths
- [`frontend/worker.js`](../frontend/worker.js) — proxies those paths to
  `https://app.kainazi.com`

Do **not** add `public/_redirects` → `/index.html` (error 100324). SPA fallback is
`assets.not_found_handling = "single-page-application"`.

Environment: do **not** set `VITE_API_BASE` (or leave it empty).

---

## 3. Custom domain

Worker `gym-app` → **Domains** → `gym.kainazi.com` (or your SPA host).

`app.kainazi.com` stays an A/AAAA (or CNAME) to the VPS for origin + gateway.

---

## 4. CORS / SSL on the VPS

On the VPS `deploy/.env`:

```bash
APP_CORS_ORIGINS=https://gym.kainazi.com
```

Recreate backend after editing. Prefer Cloudflare SSL **Full** / **Full (strict)** with
HTTPS on the edge container ([`nginx.api.https.conf.example`](nginx.api.https.conf.example)).

---

## 5. Smoke test

```bash
# SPA
curl -sS -o /dev/null -w "%{http_code}\n" https://gym.kainazi.com/app/login

# Must be 401 (Spring), not 405 (static assets)
curl -sS -o /dev/null -w "%{http_code}\n" -X POST \
  https://gym.kainazi.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"x","password":"y"}'

curl -sS https://gym.kainazi.com/actuator/health
```

Browser: `https://gym.kainazi.com/app/login`. Gateway Backend URL:
`https://app.kainazi.com`.

---

## 6. Deploys

- Frontend: merge to the production branch → Cloudflare build runs `wrangler deploy`.
- Backend: on the VPS `git pull` && `./deploy/scripts/up.sh --build`.

---

## Checklist

- [ ] `wrangler.jsonc` + `worker.js` committed under `frontend/`
- [ ] Deploy command is `wrangler deploy` (not Pages Functions-only)
- [ ] Custom domain on Worker `gym-app`
- [ ] Login POST on `gym` returns 401/200, never 405
- [ ] `APP_CORS_ORIGINS` includes the SPA origin
- [ ] Gateway uses `https://app.kainazi.com`
