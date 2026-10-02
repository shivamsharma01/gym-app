# TrueFace Windows gym-visit POC

Console tool that talks to a TrueFace3000 tablet through **`TrueFaceDeviceAdapter`** (same path as the production gateway). **No Spring, no MySQL.**

Printable runbook: [docs/GYM-VISIT.html](docs/GYM-VISIT.html).

## Quick commands

```bat
Gym-visit evidence (2026-09-13): [docs/GYM-VISIT-2026-09-13.md](docs/GYM-VISIT-2026-09-13.md)

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

## Re-adding people a restore skipped

Stop the `Gym Gateway` service and close IAS, then from PowerShell in this folder:

```powershell
.\scripts\Import-MissingDeviceUsers.ps1 -Csv C:\gym\missing.csv -Ip 192.168.x.x          # dry run
.\scripts\Import-MissingDeviceUsers.ps1 -Csv C:\gym\missing.csv -Ip 192.168.x.x -Apply   # write
```

The CSV needs `deviceUserId,name,valid_from,valid_to` and optionally `photo_base64`. Ids already on the device are skipped and never modified, so the import can be rerun safely. If PowerShell blocks the script, run `powershell -ExecutionPolicy Bypass -File .\scripts\Import-MissingDeviceUsers.ps1 ...`.
