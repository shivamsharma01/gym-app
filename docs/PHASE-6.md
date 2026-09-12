# Phase 6 — Public site, reports, notifications, settings, live UI

Same React app as Phase 5. The public gym site is a different **audience**, not a second stack.

## Public (`/`, `/about`, `/services`, `/facilities`, `/membership-plans`, `/contact`)

Unauthenticated. Looks like a gym brochure, not the staff sidebar.

- `GET /api/v1/public/site` and `GET /api/v1/public/plans` (no JWT)
- `POST /api/v1/public/enquiries` writes a real lead
- Gym identity comes from settings / `APP_PUBLIC_TENANT_SLUG` (default `downtown-fitness`)

## Staff additions

| Area | Notes |
| --- | --- |
| Enquiries | Inbox + status |
| Reports | Server aggregates (`/api/v1/reports/summary`) |
| Notifications | Templates, mock send, expiry reminder job, announcements |
| Settings | Gym profile for the public site |
| Users / roles / audit | UI on Phase 1 APIs |
| Live | Staff WebSocket `/live?access_token=` invalidates attendance/device queries |

Notification **delivery is mock**. Rows are marked `SENT` and logged. No email/SMS vendor is wired.

Live push is best-effort after commit. Attendance live still has a slow poll fallback if the socket is down.

## How to run

Same as Phase 5: MySQL via compose, `SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run`, `npm run dev`. Open `/` for the public site and `/app/login` for staff.
