# Gym Device Gateway (Phase 4)

.NET 10 worker that sits on the **gym LAN**, talks to TrueFace3000 over Dahua NetSDK (Windows `dhnetsdk.dll`), and talks to the Spring Boot backend over the Phase 3 protocol (outbound WebSocket `/gateway`, REST fallback `/internal/gateway/*`).

This is **not** the Linux POC. This gym’s LAN box is Windows. Default adapter on a Linux development host is **Mock**.

## What it does

- Registers, heartbeats, reports device status
- Applies outbox commands (`CREATE_USER`, validity, door, reconcile, …)
- Forwards live attendance events
- **Never** reports remote face enrolment as success (`ENROLL_FACE` → `GUIDED_PENDING` + `SYNC_RESULT ok:false`)

Native SDK types never leave `TrueFaceDeviceAdapter`.

## Develop on Linux (this machine)

```bash
export DOTNET_ROOT="$HOME/.dotnet"
export PATH="$HOME/.dotnet:$PATH"
cd gateway
dotnet test Gym.Gateway.slnx -c Release
```

Run against a local backend with the mock adapter (no hardware):

```bash
export GYM_BACKEND=http://127.0.0.1:8080
export GYM_GATEWAY_ID=<id from POST /api/v1/gateways>
export GYM_GATEWAY_TOKEN=<one-time token>
export GYM_ADAPTER=Mock
export GYM_DEVICE_ID=<device public id>
dotnet run --project src/Gym.Gateway -c Release
```

If the WebSocket dispatcher is already consuming the outbox, leave `UseWebSocket` on. For REST-only (like the Python simulator), set `GYM_USE_WEBSOCKET=false` and `APP_GATEWAY_OUTBOX_DISPATCHER_ENABLED=false` on the backend.

## Run on the gym Windows PC (real device)

1. Install .NET 10 SDK.
2. Copy this `gateway/` folder (it includes `native/win-x64/*.dll`).
3. Create a gateway + device in the app; put the public ids and token in env vars.
4. Use the **device** admin user (not gym-app login). Port **37777**.

```bat
set GYM_BACKEND=https://<backend-host>
set GYM_GATEWAY_ID=...
set GYM_GATEWAY_TOKEN=...
set GYM_ADAPTER=TrueFace
set GYM_DEVICE_ID=<device public id>
set GYM_DEVICE_IP=192.168.31.91
set GYM_DEVICE_PORT=37777
set GYM_DEVICE_USERNAME=admin
set GYM_DEVICE_PASSWORD=...
dotnet run --project src/Gym.Gateway -c Release
```

If Interactive Attendance / another SDK client is already logged in, this login may kick or contend with that session. Cut over; do not run two writers.

Passwords are not logged.

## Layout

```
gateway/
  src/Gym.Gateway/            worker, WSS/REST, command dispatch
  src/Gym.Gateway.Adapters/   IDeviceAdapter, Mock, TrueFace
  src/Gym.Gateway.NetSdk/     byte-identical NetSDKCS copies (WINDOWS_X64)
  native/win-x64/             dhnetsdk.dll and companions
  tests/Gym.Gateway.Tests/
```

Vendor C# under `TrueFace_SDK/` is not modified.
