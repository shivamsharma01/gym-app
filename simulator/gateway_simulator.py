#!/usr/bin/env python3
"""Local Device Gateway simulator (Phase 3).

Talks to the Spring Boot backend over the REST fallback of the gateway protocol
(stdlib only — no third-party packages, no native SDK).

This is NOT hardware and does not call TrueFace/Dahua APIs. Face enrolment is
never reported as success (remote enrolment is UNVERIFIED on the real device).

Usage:
  1. Create a gateway:  POST /api/v1/gateways  { "name": "sim" }
     Save the returned `id` and one-time `token`.
  2. Create a device assigned to that gateway.
  3. Run:

       export GYM_BACKEND=http://127.0.0.1:8080
       export GYM_GATEWAY_ID=<gateway public id>
       export GYM_GATEWAY_TOKEN=<one-time token>
       # Disable the WSS dispatcher so this poller is the only consumer:
       #   APP_GATEWAY_OUTBOX_DISPATCHER_ENABLED=false
       python3 simulator/gateway_simulator.py

  Optional:
       python3 simulator/gateway_simulator.py --event --device-id <device public id> \\
           --user 1001 --granted
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

BACKEND = os.environ.get("GYM_BACKEND", "http://127.0.0.1:8080").rstrip("/")
GATEWAY_ID = os.environ.get("GYM_GATEWAY_ID", "")
TOKEN = os.environ.get("GYM_GATEWAY_TOKEN", "")

# In-memory mock device users (deviceUserId -> record). Never stores biometrics.
USERS: dict[str, dict] = {}


def _request(method: str, path: str, body: dict | None = None) -> tuple[int, str]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        BACKEND + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as ex:
        return ex.code, ex.read().decode("utf-8", errors="replace")


def envelope(msg_type: str, payload: dict, device_id: str | None, correlation_id: str | None) -> dict:
    return {
        "messageId": str(uuid.uuid4()),
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "gatewayId": GATEWAY_ID,
        "deviceId": device_id,
        "type": msg_type,
        "correlationId": correlation_id or str(uuid.uuid4()),
        "payload": payload,
    }


def send(msg: dict) -> None:
    status, raw = _request("POST", "/internal/gateway/messages", msg)
    if status >= 400:
        print(f"ingest {msg['type']} failed HTTP {status}: {raw}", file=sys.stderr)


def apply_command(cmd: dict) -> dict:
    """Apply a backend→gateway command to the in-memory mock. Returns SYNC_RESULT payload."""
    ctype = cmd.get("type")
    payload = cmd.get("payload") or {}
    user_id = payload.get("deviceUserId")

    if ctype == "ENROLL_FACE":
        # Do not fabricate biometric success — remote enrolment is UNVERIFIED.
        return {"ok": False, "error": "UNVERIFIED: remote face enrollment is not simulated as success"}

    if ctype == "CREATE_USER" and user_id:
        USERS[user_id] = {"enabled": True, "name": payload.get("name"), "validFrom": None, "validTo": None}
    elif ctype == "DISABLE_USER" and user_id:
        USERS.setdefault(user_id, {})["enabled"] = False
    elif ctype in ("ENABLE_USER", "UPDATE_VALIDITY") and user_id:
        rec = USERS.setdefault(user_id, {})
        rec["enabled"] = bool(payload.get("enabled", True))
        rec["validFrom"] = payload.get("validFrom")
        rec["validTo"] = payload.get("validTo")
    elif ctype == "REMOVE_USER" and user_id:
        USERS.pop(user_id, None)
    elif ctype in ("OPEN_DOOR", "CLOSE_DOOR", "RECONCILE_DEVICE", "SYNC_DEVICE_TIME",
                   "REFRESH_DEVICE_USERS", "UPDATE_USER", "UPDATE_ACCESS_POLICY",
                   "DELETE_FACE", "CLEAR_DEVICE_LOGS"):
        pass  # accepted by the mock; no native SDK call
    else:
        return {"ok": False, "error": f"unsupported command {ctype}"}

    return {"ok": True}


def poll_loop(interval: float) -> None:
    send(envelope("REGISTER_GATEWAY", {"agentVersion": "simulator-0.1"}, None, None))
    print(f"simulator polling {BACKEND} as gateway {GATEWAY_ID} (ctrl-c to stop)")
    while True:
        send(envelope("HEARTBEAT", {}, None, None))
        status, raw = _request("GET", "/internal/gateway/commands")
        if status == 200:
            try:
                commands = json.loads(raw)
            except json.JSONDecodeError:
                commands = []
            for cmd in commands:
                result = apply_command(cmd)
                send(envelope("SYNC_RESULT", result, cmd.get("deviceId"), cmd.get("correlationId")))
                print(f"  {cmd.get('type')} -> {result}")
        else:
            print(f"poll failed HTTP {status}: {raw}", file=sys.stderr)
        time.sleep(interval)


def emit_event(device_id: str, user_id: str, granted: bool, rec_no: int | None) -> None:
    payload = {
        "deviceUserId": user_id,
        "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "method": "FACE",
        "granted": granted,
        "recNo": rec_no,
    }
    send(envelope("DEVICE_EVENT", payload, device_id, None))
    print(f"emitted DEVICE_EVENT user={user_id} granted={granted}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Smart Gym device-gateway simulator")
    parser.add_argument("--interval", type=float, default=2.0, help="poll interval seconds")
    parser.add_argument("--event", action="store_true", help="emit one DEVICE_EVENT and exit")
    parser.add_argument("--device-id", help="device public id (for --event)")
    parser.add_argument("--user", default="1001", help="device user id for --event")
    parser.add_argument("--granted", action="store_true", help="granted access (default: denied)")
    parser.add_argument("--rec-no", type=int, default=None)
    args = parser.parse_args()

    if not GATEWAY_ID or not TOKEN:
        print("Set GYM_GATEWAY_ID and GYM_GATEWAY_TOKEN", file=sys.stderr)
        return 2

    if args.event:
        if not args.device_id:
            print("--device-id is required with --event", file=sys.stderr)
            return 2
        emit_event(args.device_id, args.user, args.granted, args.rec_no)
        return 0

    try:
        poll_loop(args.interval)
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
