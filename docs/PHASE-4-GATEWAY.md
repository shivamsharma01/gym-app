# Phase 4 — TrueFace Device Gateway (.NET 10)

Windows-oriented native adapter on the gym LAN. Linux `.so` is **not** required for this gym: the PC that can reach TCP 37777 is Windows.

## Delivered

- `gateway/` worker: outbound WSS to `/gateway`, REST fallback, heartbeat, reconnect with exponential backoff + jitter.
- `IDeviceAdapter` + `MockDeviceAdapter` (Linux/dev) + `TrueFaceDeviceAdapter` (Windows `dhnetsdk.dll`).
- NetSDKCS copied byte-identical into `src/Gym.Gateway.NetSdk` with `WINDOWS_X64` (no vendor edits).
- Command mapping for the Phase 3 outbox vocabulary. `ENROLL_FACE` / `DELETE_FACE` are **not** reported as success (`0x10030110` / UNVERIFIED).
- Live events: `ALARM_ACCESS_CTL_EVENT` → `DEVICE_EVENT`; other access-control alarms → `DEVICE_ALARM`. Callbacks normalize and enqueue; they do not call the backend.
- Reconciliation uses `FindRecord(ACCESSCTLCARDREC_EX)` and `StartFindUserInfo` (wrapper APIs used by AccessDemo2s; record-query path is DOC until hardware re-check).
- Door open/close uses `ControlDevice(ACCESS_OPEN|ACCESS_CLOSE)` as in AccessDemo2s (DOC until hardware re-check). Result is whatever the SDK returns.

## How to run

See `gateway/README.md`. Default adapter is **Mock**. Set `GYM_ADAPTER=TrueFace` only on Windows with `native/win-x64/dhnetsdk.dll`.

## Not claimed

- Remote face enrolment success
- Linux production gateway
- Dual-control with iAS (cut over; one writer)

## Tests

`dotnet test gateway/Gym.Gateway.slnx -c Release` — mock, protocol, OS guard. No fake TrueFace login tests.
