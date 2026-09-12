# TrueFace Windows gym-visit POC

Console tool that talks to a TrueFace3000 tablet through **`TrueFaceDeviceAdapter`** (same path as the production gateway). **No Spring, no MySQL.**

Printable runbook: [docs/GYM-VISIT.html](docs/GYM-VISIT.html).

## Quick commands

```bat
REM Live tablet (Windows, IAS closed):
set TRUEFACE_IP=192.168.x.x
set TRUEFACE_USERNAME=admin
set TRUEFACE_PASSWORD=********
dotnet run -- --face-image C:\path\to\test.jpg

REM CI / Linux (no hardware):
dotnet run -- --mock --skip-door
dotnet test
```

Never commit device passwords or face images. Logs: `poc-run-<timestamp>.log`.
