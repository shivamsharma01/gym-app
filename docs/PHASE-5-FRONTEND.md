# Phase 5 — React operations app

Staff UI for the gym backend. Public site, reports, notifications, settings, and live WebSocket push are **Phase 6**.

## Stack

Vite 8 + React 19 + TypeScript + Tailwind 4 + React Router 8 + TanStack Query 5 + React Hook Form + Zod. Tokens stay **in memory** (not `localStorage`).

## Routes

| Path | Notes |
| --- | --- |
| `/app/login` | Username/email + password |
| `/app/forgot-password` | Honest: no self-serve reset yet |
| `/app/dashboard` | Counts from list APIs + recent attendance |
| `/app/members` | Search, create, detail, edit, deactivate |
| `/app/plans` | Create / update / archive |
| `/app/memberships` | Per-member (no global list API) |
| `/app/payments` | List + record + refund |
| `/app/attendance` | History |
| `/app/attendance/live` | 15s poll; not WebSocket |
| `/app/devices` | List, register, create gateway (token once) |
| `/app/devices/:id` | Health, events, sync outbox, settings, remote door |
| `/app/profile` | `GET /api/v1/me` |

Nav items hide without the matching permission. Every mutation is still authorized on the server.

## Honest device behaviour

- Gateway token is shown **once** in a dialog; it is not logged and not stored in the browser.
- Device passwords are not collected in the UI.
- Remote door requires confirmation + reason; the UI reports the **queued command**, not a moved lock.
- Member→device mapping queues `CREATE_USER`. Face enrolment is **not** claimed as success.

## How to run

Backend on `http://127.0.0.1:8080`, then `cd frontend && npm install && npm run dev` (port 5173, proxies `/api`). Dev login: `admin` / `ChangeMe123!` when the backend `dev` profile is on.
