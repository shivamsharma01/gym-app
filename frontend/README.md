# Gym frontend (staff app + multi-gym public site)

Public marketing pages live under `/g/{slug}`. Staff app is `/app/*`.
Platform SUPER_ADMIN enrolls gyms at `/app/platform/gyms`.

## Production

Leave `VITE_API_BASE` empty. `/api`, `/live`, and `/actuator` are proxied to the VPS. `/gateway` is the Windows gateway, not this app. Deploy steps: [deploy/README.md](../deploy/README.md).

## Develop

1. Run the backend on `http://127.0.0.1:8080` (`dev` profile).
2. `npm install`
3. `npm run dev` — http://localhost:5173 (proxies `/api`, `/live`, `/actuator` → Spring Boot).

Dev bootstrap creates **only** `superadmin` / `ChangeMe123!`. Enroll a gym from **Gyms**, then sign in as that gym’s owner to add staff and members.

## Build

`npm run build` (`tsc -b && vite build`). Do **not** set `VITE_API_BASE` for customer domains.
