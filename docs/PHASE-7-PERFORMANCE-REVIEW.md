# Phase 7 — Performance review

**Date:** 2026-09-13  
**Scope:** API list endpoints, staff SPA, gateway outbox, public site.

## Current posture

- List APIs are paginated (`page` / `size`) for members, payments, attendance, users, devices.
- Staff SPA uses React Query with short `staleTime` (15s) — fine for ops scale; avoid unbounded refetch.
- Device sync uses an outbox with backoff (not synchronous fan-out on every click).
- Public site fetches `/site` + `/plans` per gym slug (small payloads).
- Actuator exposes `health`, `info`, `metrics` for ops visibility.

## Hotspots to watch

| Area | Risk | Guidance |
| --- | --- | --- |
| Membership + payment screens | Extra queries per selected member | Acceptable for desk use; add batch endpoints if gyms exceed ~5k members |
| Attendance live WebSocket | Fan-in from many gateways | One connection per staff browser; gateway heartbeats already timeout |
| Reports | Aggregation queries | Keep date-bounded; avoid full-table scans without indexes |
| TrueFace reconcile | Device-side enumeration | Run off peak; never block HTTP request thread on long SDK calls (gateway owns that) |

## Indexes (baseline)

Flyway migrations add tenant + time keys on attendance, payments, enquiries, sync commands. Re-check `EXPLAIN` when a gym exceeds tens of thousands of attendance rows.

## Not in Phase 7

- k6/JMeter load suite
- CDN for brand images
- Read replicas

Those belong with Phase 8 capacity planning once a real gym size is known.
