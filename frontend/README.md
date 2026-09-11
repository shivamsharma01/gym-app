# True Gym frontend

Operations UI for the Spring Boot API. Tokens are held in memory only — a full refresh requires sign-in again.

## Develop

1. Run the backend on `http://127.0.0.1:8080` (`dev` profile).
2. `npm install`
3. `npm run dev` — http://localhost:5173 (proxies `/api`).

Optional `VITE_API_BASE` (see `.env.example`) if the API is not on the Vite proxy.

Dev users from the backend seeder include `admin` / `ChangeMe123!`.

## Build

`npm run build` (`tsc -b && vite build`).

## Out of scope here

Public website, reports, notifications, user/role admin, live WebSocket. Those are Phase 6.
