# Phase 3 — Device Gateway Protocol

Transport options:

1. **Primary (production):** gateway-initiated WebSocket at `/gateway` (WSS in production).
2. **Fallback / simulator:** REST at `/internal/gateway/messages` (ingest) and
   `/internal/gateway/commands` (poll + claim).

Both use the same JSON envelope and the same per-gateway token. The backend never
links against the native TrueFace/Dahua SDK; Phase 4 owns that adapter.

## Authentication (Phase 3 decision)

**Per-gateway hashed token**, issued once at `POST /api/v1/gateways`. Only the SHA-256
hash is stored. The plaintext is in the create response and must be configured on the
LAN agent; it is never logged and never returned again.

Optional `APP_GATEWAY_SHARED_TOKEN` authenticates the link without identifying a
gateway (gateway id is bound on `REGISTER_GATEWAY`). Production should prefer
per-gateway tokens. Device passwords stay on the LAN gateway, not in this API.

Handshake: `Authorization: Bearer <token>` or `?token=`.

## Envelope

```json
{
  "messageId": "uuid",
  "timestamp": "ISO-8601",
  "gatewayId": "uuid",
  "deviceId": "uuid|null",
  "type": "REGISTER_GATEWAY|HEARTBEAT|DEVICE_STATUS|DEVICE_EVENT|SYNC_RESULT|...",
  "correlationId": "uuid",
  "payload": { }
}
```

Idempotent on `messageId` / `correlationId`. Reconnect replay is safe. Attendance
dedupes on `(tenant, device, fingerprint)` where fingerprint prefers the device
record number (`nRecNo`) and otherwise uses `deviceUserId|time|method|result`.

### Gateway → Backend

`REGISTER_GATEWAY`, `HEARTBEAT`, `DEVICE_STATUS`, `DEVICE_METADATA`,
`DEVICE_EVENT`, `DEVICE_ALARM`, `SYNC_RESULT`, `RECONCILIATION_RESULT`,
`ENROLLMENT_RESULT`.

### Backend → Gateway (outbox command `type`)

`CREATE_USER`, `UPDATE_USER`, `DISABLE_USER`, `ENABLE_USER`, `REMOVE_USER`,
`UPDATE_VALIDITY`, `UPDATE_ACCESS_POLICY`, `ENROLL_FACE`, `DELETE_FACE`,
`SYNC_DEVICE_TIME`, `OPEN_DOOR`, `CLOSE_DOOR`, `REFRESH_DEVICE_USERS`,
`RECONCILE_DEVICE`, `CLEAR_DEVICE_LOGS`.

Outbox states: `PENDING → DISPATCHED → SUCCEEDED` with `RETRYING → DEAD_LETTER`
and `CANCELLED`. Exponential backoff + jitter. A command is **never** marked
`SUCCEEDED` without an explicit `SYNC_RESULT` `{ "ok": true }` from the gateway.

## Simulator

`simulator/gateway_simulator.py` is a stdlib Python poller. It is **not** the
TrueFace adapter. `ENROLL_FACE` is reported as failed/`UNVERIFIED` so we do not
fake biometric enrolment.

Alternatively set `APP_GATEWAY_SIMULATOR_ENABLED=true` for an in-process mock
used only in local demos — never in production.

## Unresolved (Phase 4)

Native `TrueFaceDeviceAdapter`, Linux `libdhnetsdk.so`, remote face enrolment,
and exact offline-authorization behaviour of the standalone terminal.
