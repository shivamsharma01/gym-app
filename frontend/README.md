# Gym frontend (staff app + multi-gym public site)

Public marketing pages live under `/g/{slug}` (see Phase 9). Staff app is `/app/*`.
Platform SUPER_ADMIN enrolls gyms at `/app/platform/gyms`.

## Same-origin API (multi-domain SaaS)

Production builds leave `VITE_API_BASE` empty. The SPA calls relative paths against the
current browser origin:

| Path | Purpose |
|------|---------|
| `/api/*` | REST (JWT) |
| `/live` | Staff WebSocket |
| `/actuator/*` | Platform SUPER_ADMIN Actuator UI |
| `/gateway` | **Not used by React** — Windows device gateway only |

So `https://gym.kainazi.co.in` and `https://gym.heavyreps.in` can share one build.
Cloudflare (or equivalent) must proxy `/api`, `/live`, `/gateway`, and `/actuator` to the
VPS Nginx edge; static assets stay on Pages/Workers.

## Develop

1. Run the backend on `http://127.0.0.1:8080` (`dev` profile).
2. `npm install`
3. `npm run dev` — http://localhost:5173 (proxies `/api`, `/live`, `/actuator` → Spring Boot).

Dev bootstrap creates **only** `superadmin` / `ChangeMe123!`. Enroll a gym from **Gyms**, then sign in as that gym’s owner to add staff and members.

## Build

`npm run build` (`tsc -b && vite build`). Do **not** set `VITE_API_BASE` for customer domains.
