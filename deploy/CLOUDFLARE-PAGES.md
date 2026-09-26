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

## 2. CI / deploy (GitHub Actions)

Production updates come from **GitHub Actions** after a merge to `main`
([`.github/workflows/frontend.yml`](../.github/workflows/frontend.yml)):

| Event | What runs |
|-------|-----------|
| Pull request / non-`main` push | `npm ci`, lint, URL self-test, `npm run build` — **no deploy** |
| Push to `main` (merge) | same build checks, then `wrangler deploy` |

Add repository secrets (Cloudflare dashboard → My Profile → API Tokens; account ID
from the Workers overview URL):

| Secret | Purpose |
|--------|---------|
| `CLOUDFLARE_API_TOKEN` | Token with Workers Scripts Edit (+ Account read) |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare account ID |

GitHub check **Frontend / build** is the green status for PRs. The live SPA on
`gym.kainazi.com` only changes when that workflow’s **deploy** job runs on `main`.

### Cloudflare Workers Builds (optional)

If the Worker is still connected to Git in the Cloudflare dashboard, either:

1. **Recommended:** disable Workers Builds for this Worker so only GitHub Actions
   deploys (avoids double deploy and the old `wrangler preview` failure), **or**
2. Keep Builds but configure branch control as follows:

| Setting | Value |
|---------|--------|
| Production branch | `main` |
| Root directory | `frontend` |
| Build command | `npm ci && npm run build` |
| Deploy command (production) | `npx wrangler deploy` |
| Builds for non-production branches | **Off** (PR green checks come from GitHub Actions) |

If you leave non-production builds **On**, set the non-production deploy command to
`npx wrangler preview` (requires `"previews": {}` in `wrangler.jsonc`) or a no-op
like `true` — never use `wrangler deploy` on feature branches.

This repo deploys with **`wrangler deploy`** (Workers + static assets), **not** classic
Pages Functions. Do **not** rely on `frontend/functions/` — that folder is ignored by
this deploy path and `/api` POSTs become **405 Method Not Allowed**.

Commit these files (already in the repo):

- [`frontend/wrangler.jsonc`](../frontend/wrangler.jsonc) — SPA assets +
  `run_worker_first` for API paths + empty `previews` block
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

- Frontend: merge PR to `main` → GitHub Action **Frontend** builds then
  `wrangler deploy` (secrets above required).
- Backend: on the VPS `git pull` && `./deploy/scripts/up.sh --build`.

---

## Checklist

- [ ] `wrangler.jsonc` + `worker.js` committed under `frontend/`
- [ ] GitHub secrets `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` set
- [ ] Cloudflare Workers Builds disabled **or** non-production builds off + prod
      deploy = `wrangler deploy`
- [ ] Custom domain on Worker `gym-app`
- [ ] Login POST on `gym` returns 401/200, never 405
- [ ] `APP_CORS_ORIGINS` includes the SPA origin
- [ ] Gateway uses `https://app.kainazi.com`
