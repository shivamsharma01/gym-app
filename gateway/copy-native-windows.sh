#!/usr/bin/env bash
# Optional: refresh gateway/native/win-x64 from the AccessDemo2s x64 build.
# Not required on the gym Windows PC — those DLLs are tracked in git.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="${DAHUA_WIN64_DIR:-$ROOT/../TrueFace_SDK/AccessDemo2s/bin/x64Debug}"
DEST="$ROOT/native/win-x64"
mkdir -p "$DEST"
if [[ ! -d "$SRC" ]]; then
  echo "SDK build dir not found at $SRC" >&2
  exit 1
fi
for lib in dhnetsdk.dll dhconfigsdk.dll avnetsdk.dll dhplay.dll Infra.dll ImageAlg.dll IvsDrawer.dll RenderEngine.dll StreamConvertor.dll; do
  if [[ -f "$SRC/$lib" ]]; then
    cp -a "$SRC/$lib" "$DEST/"
    echo "copied $lib"
  else
    echo "MISSING $SRC/$lib" >&2
  fi
done
