#!/usr/bin/env bash
# Two virtual devices on this machine, published with ngrok for a gateway on another network.
# Usage: ./simulator/start-public-devices.sh [http|tcp]
#   http (default) — free ngrok HTTPS URLs. Paste each URL into that device's IP field.
#   tcp             — ngrok host and port. Put those in the device IP and Port fields.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-http}"
if [[ "$MODE" != "http" && "$MODE" != "tcp" ]]; then
  echo "Usage: $0 [http|tcp]" >&2
  exit 1
fi
if ! command -v ngrok >/dev/null 2>&1; then
  echo "ngrok is not installed. Install it, then: ngrok config add-authtoken <token>" >&2
  exit 1
fi

NGROK_USER_CONFIG=""
for candidate in \
  "${NGROK_CONFIG:-}" \
  "$HOME/.config/ngrok/ngrok.yml" \
  "$HOME/Library/Application Support/ngrok/ngrok.yml" \
  "$HOME/.ngrok2/ngrok.yml"
do
  if [[ -n "$candidate" && -f "$candidate" ]]; then
    NGROK_USER_CONFIG="$candidate"
    break
  fi
done
if [[ -z "$NGROK_USER_CONFIG" ]]; then
  echo "No ngrok login found. Run: ngrok config add-authtoken <token>" >&2
  exit 1
fi

python3 "$ROOT/simulator/device_server.py" --config "$ROOT/simulator/devices-3-4.json" --memory &
device_pid=$!

TUNNELS="$(mktemp)"
LOG="$(mktemp)"
cleanup() {
  kill "$device_pid" 2>/dev/null || true
  if [[ -n "${ngrok_pid:-}" ]]; then
    kill "$ngrok_pid" 2>/dev/null || true
  fi
  rm -f "$TUNNELS" "$LOG"
}
trap cleanup EXIT INT TERM

if [[ "$MODE" == "tcp" ]]; then
  cat > "$TUNNELS" <<'EOF'
version: 3
endpoints:
  - name: device3
    url: tcp://
    upstream:
      url: 9003
  - name: device4
    url: tcp://
    upstream:
      url: 9004
EOF
else
  cat > "$TUNNELS" <<'EOF'
version: 3
endpoints:
  - name: device3
    upstream:
      url: 9003
  - name: device4
    upstream:
      url: 9004
EOF
fi

ngrok start device3 device4 \
  --config "$NGROK_USER_CONFIG" \
  --config "$TUNNELS" \
  --log stdout > "$LOG" 2>&1 &
ngrok_pid=$!

python3 - "$MODE" "$LOG" <<'PY'
import json, sys, time, urllib.request
mode, log_path = sys.argv[1], sys.argv[2]
data = None
for _ in range(40):
    try:
        with urllib.request.urlopen("http://127.0.0.1:4040/api/tunnels", timeout=1) as resp:
            data = json.load(resp)
        if data.get("tunnels"):
            break
    except Exception:
        pass
    time.sleep(0.5)
if not data or not data.get("tunnels"):
    print("ngrok did not publish the tunnels. Log:", file=sys.stderr)
    print(open(log_path, encoding="utf-8", errors="replace").read(), file=sys.stderr)
    raise SystemExit(1)

def public_for(port: str) -> str:
    for tunnel in data["tunnels"]:
        addr = str(tunnel.get("config", {}).get("addr", ""))
        if addr.endswith(":" + port) or addr == port:
            return tunnel.get("public_url", "")
    return ""

print()
print("Local dashboard (this machine only): http://127.0.0.1:9100")
print("On the other machine the gateway Adapter must be Remote.")
print("Create devices 3 and 4 in the staff app, assign them to that gateway, then set:")
print()
for label, port in (("Device 3", "9003"), ("Device 4", "9004")):
    public = public_for(port)
    if mode == "tcp" and public.startswith("tcp://"):
        host, _, ngrok_port = public[len("tcp://"):].rpartition(":")
        print(f"  {label}:  IP {host}    Port {ngrok_port}")
    elif public:
        print(f"  {label}:  IP {public}")
        print(f"           Port is ignored when IP is a full URL.")
    else:
        print(f"  {label}: tunnel for port {port} was not found")
print()
print("Leave this running. Ctrl+C stops ngrok and both devices.")
print("Ngrok URLs change on each start unless you reserved them. Update the gateway when they change.")
PY

wait "$ngrok_pid"
