# Gym Device Gateway

.NET 10 worker on the **gym LAN** PC. Talks to TrueFace3000 (Dahua NetSDK) and the Spring Boot backend (outbound WebSocket `/gateway`, REST `/internal/gateway/*`).

Default adapter on Linux is **Mock**. Production Windows installs via **MSI** (Windows CI) and is configured with the elevated **Gym Gateway Configurator**.

## Production flow (Windows)

1. In staff UI: **Devices → create Gateway**. Copy the **one-time enrollment token** (valid ~24h). This is **not** the long-lived credential.
2. On the gym PC, install `GymGateway-*-win-x64.msi` (from GitHub Actions artifact).
3. Run **Gym Gateway Configurator** (Administrator):
   - Backend URL, Gateway ID, enrollment token → **Enroll**
   - Set each device IP / port / tablet password (pre-filled from `GET /internal/gateway/devices`)
   - Optional **Test TrueFace connect**
   - **Save config & start service** → writes DPAPI-protected `%ProgramData%\GymGateway\config.json` and starts the **Gym Gateway** Windows Service
4. The service renews credentials automatically before expiry (`POST /internal/gateway/credentials/rotate`). Secrets are never logged.

### Replacing the gateway PC

Use **Reissue enrollment** in the staff UI (`POST /api/v1/gateways/{id}/enrollment`), then enroll on the new PC with the Configurator. Enroll replaces the operational credential hash, so the old PC can no longer authenticate.

### MSI notes

- Install dir: `Program Files\Gym Gateway\`
- Configurator: `C:\Program Files\Gym Gateway\Gym.Gateway.Configurator.exe` (no Start Menu shortcut — run as Administrator)
- Service binary must be `C:\Program Files\Gym Gateway\Gym.Gateway.exe` — if `sc qc "Gym Gateway"` shows
  `!(bindpath.PublishDir)` in the path, that MSI is broken; uninstall and install a build ≥ the WiX
  `$(PublishDir)` fix.
- Data/logs: `%ProgramData%\GymGateway\` (config retained across upgrades)
- MSI registers the Windows Service but **does not start it** (no config yet). Use Configurator → **Save config & start service**.
- Uninstall leaves ProgramData unless `PURGE_CONFIG=1` is passed to msiexec
- **Linux cannot build the MSI or WPF configurator** — use [`.github/workflows/gateway-msi.yml`](../.github/workflows/gateway-msi.yml) (`windows-latest`)

## Develop on Linux (this machine)

Core + tests only (`Gym.Gateway.slnx` — no WPF/WiX):

```bash
export DOTNET_ROOT="$HOME/.dotnet"
export PATH="$HOME/.dotnet:$PATH"
cd gateway
dotnet test Gym.Gateway.slnx -c Release
```

Dev / Mock against a local backend (env overlay; no ProgramData):

```bash
export GYM_BACKEND=http://127.0.0.1:8080
export GYM_GATEWAY_ID=<id>
# After POST /api/v1/gateways, call POST /internal/gateway/enroll with the enrollment token
# and use the returned operational credential here:
export GYM_GATEWAY_TOKEN=<operational credential>
export GYM_ADAPTER=Mock
export GYM_DEVICE_ID=<device public id>
dotnet run --project src/Gym.Gateway -c Release
```

Cross-publish the worker for Windows (no MSI):

```bash
./scripts/publish-win.sh
```

Windows solution (CI / Windows SDK): `Gym.Gateway.Windows.slnx` includes Configurator + WiX.

## Layout

```
gateway/
  src/Gym.Gateway/                 worker, WSS/REST, ProgramData config, rotation, Windows Service host
  src/Gym.Gateway.Adapters/        IDeviceAdapter, Mock, TrueFace
  src/Gym.Gateway.NetSdk/          NetSDKCS (WINDOWS_X64)
  src/Gym.Gateway.Configurator/    WPF setup UI (Windows only)
  installer/                       WiX MSI (Windows CI only)
  native/win-x64/                  dhnetsdk.dll and companions
  tests/Gym.Gateway.Tests/
  scripts/publish-win.sh
```

Vendor C# under `TrueFace_SDK/` is not modified. Passwords and tokens are never written to logs.
