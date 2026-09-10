#!/usr/bin/env bash
# Copy the Linux x64 Dahua native libraries into Native/.
# The repo does not track *.so files (see root .gitignore).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="${DAHUA_LIN64_DIR:-$HOME/Downloads/dahua-sdk-master/libs/lin64}"
DEST="$ROOT/Native"
mkdir -p "$DEST"
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
