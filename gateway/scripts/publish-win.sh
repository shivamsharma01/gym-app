#!/usr/bin/env bash
# Cross-publish the gateway worker for Windows from Linux.
# Does NOT produce an MSI (WiX requires windows-latest CI).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/artifacts/win-x64}"
CONFIG="${CONFIG:-Release}"

export DOTNET_ROOT="${DOTNET_ROOT:-$HOME/.dotnet}"
export PATH="$DOTNET_ROOT:$PATH"

echo "Publishing Gym.Gateway (self-contained win-x64) → $OUT"
dotnet publish "$ROOT/src/Gym.Gateway/Gym.Gateway.csproj" \
  -c "$CONFIG" \
  -r win-x64 \
  --self-contained true \
  -o "$OUT"

echo "Done. Copy $OUT to a Windows PC or use GitHub Actions (gateway-msi.yml) for the MSI + Configurator."
echo "Note: WPF Configurator and WiX MSI cannot be built on Linux."
