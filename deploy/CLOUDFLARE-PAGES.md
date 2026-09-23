# React on Cloudflare Pages

The SPA is **not** on the VPS. Cloudflare Pages serves static assets; the VPS serves
`/api`, `/live`, `/gateway`, and `/actuator` on the **same hostname** (same-origin).

```text
Browser
  ├─ /, /app/*, /g/*, JS/CSS  → Cloudflare Pages
  └─ /api/*, /live, /gateway, /actuator/*  → Worker proxy → VPS Nginx → Spring Boot
```

Why same host? The staff live socket is built as `wss://<current-host>/live`
([`backendUrls.ts`](../frontend/src/lib/backendUrls.ts)). Do **not** put the SPA on
`*.pages.dev` and the API on another host unless you change that code and CORS.

`VITE_API_BASE` must stay **empty** for production builds.

---

## 1. Prerequisites

- VPS stack healthy (`/healthz`, `/actuator/health`) — [VPS-BOOTSTRAP.md](VPS-BOOTSTRAP.md)
- Domain on Cloudflare (orange-cloud DNS)
- GitHub repo access (Pages will build from `frontend/`)

Pick a public hostname, e.g. `app.yourdomain.com` (or the apex). Windows gateway and
browsers will use this host.

---

## 2. Create the Pages project

1. Cloudflare Dashboard → **Workers & Pages** → **Create** → **Pages** → **Connect to Git**.
2. Select `shivamsharma01/gym-app` (or your fork).
3. Configure build:

| Setting | Value |
|---------|--------|
| Production branch | `main` (or your release branch) |
| Root directory | `frontend` |
| Build command | `npm ci && npm run build` |
| Build output directory | `dist` |

4. **Environment variables** (Production):  
   - Do **not** set `VITE_API_BASE`, or set it to empty.  
5. Save and deploy. Note the `*.pages.dev` URL for a smoke test of static assets only
   (API calls will fail on `pages.dev` until the custom domain + proxy in §4–5).

SPA deep links (`/app/...`, `/g/...`) are handled by Pages automatically: if there is
**no** top-level `404.html`, unmatched paths are served as the SPA (`index.html`).
Do **not** add `public/_redirects` with `→ /index.html 200` — Wrangler/API rejects those
rules as an infinite loop (error 100324) and the deploy fails.

---

## 3. Attach the custom domain to Pages

1. Pages project → **Custom domains** → **Set up a domain** → `app.yourdomain.com`.
2. Cloudflare will create/adjust the DNS record for that hostname → Pages.

---

## 4. Point API paths at the VPS (Worker proxy)

Pages owns the hostname. A small Worker on the **same** hostname must intercept API
paths and forward them to the VPS.

### 4.1 Origin DNS (VPS)

Create a DNS record used only as the Worker upstream (example):

| Type | Name | Content | Proxy |
|------|------|---------|--------|
| A | `origin` | your VPS public IP | **DNS only** (grey cloud) |

So `origin.yourdomain.com` → VPS. UFW still allows 80 (and 443 if you terminate TLS on the VPS).

Prefer **Full (strict)** later with an origin certificate; for first bring-up, Worker →
`http://origin.yourdomain.com` on port 80 is fine if the edge container listens on 80.

### 4.2 Worker

Create a Worker (e.g. `gym-api-proxy`) with:

```javascript
const ORIGIN = "http://origin.yourdomain.com"; // or https:// if origin TLS is ready

function shouldProxy(pathname) {
  return (
    pathname.startsWith("/api/") ||
    pathname.startsWith("/actuator/") ||
    pathname === "/live" ||
    pathname.startsWith("/live?") ||
    pathname === "/gateway" ||
    pathname.startsWith("/gateway?") ||
    pathname.startsWith("/gateway/")
  );
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (!shouldProxy(url.pathname + url.search) && !shouldProxy(url.pathname)) {
      return new Response("Not found", { status: 404 });
    }

    const target = new URL(url.pathname + url.search, ORIGIN);
    const headers = new Headers(request.headers);
    headers.set("Host", new URL(ORIGIN).host);
    // Preserve client IP for rate limits / logs
    headers.set("X-Forwarded-Proto", url.protocol.replace(":", ""));
    if (!headers.has("X-Forwarded-For")) {
      headers.set("X-Forwarded-For", request.headers.get("CF-Connecting-IP") ?? "");
    }

    return fetch(new Request(target, {
      method: request.method,
      headers,
      body: request.body,
      redirect: "manual",
      // WebSocket upgrades are supported when the Worker route receives WS
    }));
  },
};
```

Set `ORIGIN` to your real origin host (or a Worker secret / env binding).

### 4.3 Routes (order matters)

On the Worker → **Triggers** → **Routes**, add (adjust hostname):

```text
app.yourdomain.com/api/*
app.yourdomain.com/actuator/*
app.yourdomain.com/live
app.yourdomain.com/live*
app.yourdomain.com/gateway
app.yourdomain.com/gateway*
```

Worker routes for those paths take precedence over Pages. Everything else stays on Pages.

Confirm WebSockets: staff UI live feed and Windows gateway `wss://app.yourdomain.com/gateway`
must connect through Cloudflare (proxied). If WS fails, check Worker route coverage and
Cloudflare Network → WebSockets enabled (default on).

---

## 5. VPS / `.env` tweaks for the public host

On the VPS `deploy/.env`:

```bash
# Same-origin SPA: browser Origin is https://app.yourdomain.com
APP_CORS_ORIGINS=https://app.yourdomain.com
```

Same-origin requests often need little CORS; keeping the SPA origin listed is still safe.
Recreate backend after editing:

```bash
cd /opt/gym
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --force-recreate backend
```

SSL mode (Cloudflare → origin): start with **Flexible** only if origin is HTTP; prefer
**Full** / **Full (strict)** with HTTPS on the VPS ([`nginx.api.https.conf.example`](nginx.api.https.conf.example)).

---

## 6. Smoke test

```bash
# Static (Pages)
curl -sS -o /dev/null -w "%{http_code}\n" https://app.yourdomain.com/

# API via Worker → VPS
curl -sS https://app.yourdomain.com/actuator/health
curl -sS https://app.yourdomain.com/healthz

# Login (same as VPS bootstrap, public URL)
curl -sS -X POST https://app.yourdomain.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"superadmin","password":"YOUR_PASSWORD"}'
```

In the browser: open `https://app.yourdomain.com/app/login`, sign in, confirm no CORS errors
and that `/live` connects (DevTools → Network → WS).

Windows gateway Configurator **Backend URL**: `https://app.yourdomain.com`
(gateway enrolls and opens `wss://app.yourdomain.com/gateway`).

---

## 7. Deploys going forward

- Merge to the Pages production branch → Cloudflare rebuilds `frontend/`.
- API/backend changes → on the VPS: `git pull` && `./deploy/scripts/up.sh --build`.

---

## Checklist

- [ ] Pages: root `frontend`, `npm ci && npm run build`, output `dist`, empty `VITE_API_BASE`
- [ ] `_redirects` present for SPA fallback
- [ ] Custom domain on Pages
- [ ] `origin.*` DNS → VPS (grey cloud or TLS-ready)
- [ ] Worker proxies `/api`, `/live`, `/gateway`, `/actuator`
- [ ] `APP_CORS_ORIGINS` includes `https://app.yourdomain.com`
- [ ] Browser login + WS; gateway can enroll against the same host
