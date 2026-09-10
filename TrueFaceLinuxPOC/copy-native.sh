#!/usr/bin/env bash
# Optional: refresh Native/ from a local Dahua SDK pack.
# Not required on the gym machine — Native/*.so are tracked in git.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="${DAHUA_LIN64_DIR:-$HOME/Downloads/dahua-sdk-master/libs/lin64}"
DEST="$ROOT/Native"
mkdir -p "$DEST"
if [[ ! -d "$SRC" ]]; then
  echo "SDK pack not found at $SRC" >&2
  echo "Gym-machine test does not need this script; use the Native/*.so already in the repo." >&2
  exit 1
fi
for lib in libdhnetsdk.so libdhconfigsdk.so libavnetsdk.so libInfra.so libNetFramework.so libStream.so libStreamSvr.so; do
  if [[ -f "$SRC/$lib" ]]; then
    cp -a "$SRC/$lib" "$DEST/"
    echo "copied $lib"
  else
    echo "MISSING $SRC/$lib" >&2
  fi
done
file "$DEST/libdhnetsdk.so"
ldd "$DEST/libdhnetsdk.so"
