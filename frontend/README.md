# Gym frontend (staff app + multi-gym public site)

Public marketing pages live under `/g/{slug}` (see Phase 9). Staff app is `/app/*`.
Platform SUPER_ADMIN enrolls gyms at `/app/platform/gyms`.

## Develop

1. Run the backend on `http://127.0.0.1:8080` (`dev` profile).
2. `npm install`
3. `npm run dev` — http://localhost:5173 (proxies `/api`).

Dev bootstrap creates **only** `superadmin` / `ChangeMe123!`. Enroll a gym from **Gyms**, then sign in as that gym’s owner to add staff and members.

## Build

`npm run build` (`tsc -b && vite build`).
