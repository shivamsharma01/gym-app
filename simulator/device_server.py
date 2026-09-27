#!/usr/bin/env python3
"""Multi-Device Hardware Simulator Server.

Runs virtual TrueFace 3000 biometric devices on independent TCP ports (e.g. 9001 for Entry, 9002 for Exit),
plus a central Admin REST API and Web Dashboard on port 9000.

The actual .NET Gym.Gateway connects to these ports using RemoteDeviceAdapter.

Stdlib-only (no third-party dependencies required; optional Pillow for image thumbnail rendering).
"""

from __future__ import annotations

import base64
import cgi
import io
import json
import os
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from typing import Any, Dict, List, Optional
from urllib.parse import parse_qs, urlparse

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False


@dataclass
class VirtualUser:
    user_id: str
    name: Optional[str] = None
    enabled: bool = True
    valid_from: Optional[str] = None
    valid_to: Optional[str] = None
    photo_bytes: Optional[bytes] = None
    photo_updated_at: Optional[str] = None

    def to_dict(self, include_photo: bool = False) -> dict:
        d = {
            "deviceUserId": self.user_id,
            "name": self.name,
            "enabled": self.enabled,
            "validFrom": self.valid_from,
            "validTo": self.valid_to,
            "hasPhoto": self.photo_bytes is not None,
            "photoUpdatedAt": self.photo_updated_at,
        }
        if include_photo and self.photo_bytes:
            d["photoBase64"] = base64.b64encode(self.photo_bytes).decode("ascii")
        return d


@dataclass
class AttendanceLog:
    device_user_id: Optional[str]
    occurred_at: str
    method: str
    granted: bool
    rec_no: int
    error_code: Optional[int] = None

    def to_dict(self) -> dict:
        return {
            "deviceUserId": self.device_user_id,
            "occurredAt": self.occurred_at,
            "method": self.method,
            "granted": self.granted,
            "recNo": self.rec_no,
            "errorCode": self.error_code,
        }


@dataclass
class DeviceEvent:
    kind: str
    device_user_id: Optional[str]
    occurred_at: str
    method: str
    granted: bool
    rec_no: Optional[int]
    alarm_type: Optional[str] = None
    details: Optional[str] = None
    error_code: Optional[int] = None

    def to_dict(self) -> dict:
        return {
            "kind": self.kind,
            "deviceUserId": self.device_user_id,
            "occurredAt": self.occurred_at,
            "method": self.method,
            "granted": self.granted,
            "recNo": self.rec_no,
            "alarmType": self.alarm_type,
            "details": self.details,
            "errorCode": self.error_code,
        }


class VirtualDevice:
    """Stateful emulation of one TrueFace 3000 terminal."""

    def __init__(self, device_id: str, name: str, role: str, port: int, serial: str):
        self.device_id = device_id
        self.name = name
        self.role = role  # ENTRY or EXIT
        self.port = port
        self.serial = serial
        self.firmware = "V1.000.004H003.0.R.20250424"
        self.users: Dict[str, VirtualUser] = {}
        self.attendance: List[AttendanceLog] = []
        self.event_queue: List[DeviceEvent] = []
        self.event_cv = threading.Condition()
        self.lock = threading.Lock()
        self.rec_counter = 1000
        self.door_open = False
        self.simulated_offline = False
        self.simulated_latency_ms = 0
        self.simulated_error_rate = 0.0

    def add_or_update_user(self, user_id: str, name: Optional[str] = None, enabled: Optional[bool] = None,
                           valid_from: Optional[str] = None, valid_to: Optional[str] = None) -> VirtualUser:
        with self.lock:
            if user_id in self.users:
                u = self.users[user_id]
                if name is not None:
                    u.name = name
                if enabled is not None:
                    u.enabled = enabled
                if valid_from is not None:
                    u.valid_from = valid_from
                if valid_to is not None:
                    u.valid_to = valid_to
            else:
                u = VirtualUser(
                    user_id=user_id,
                    name=name,
                    enabled=True if enabled is None else enabled,
                    valid_from=valid_from,
                    valid_to=valid_to,
                )
                self.users[user_id] = u
            return u

    def set_photo(self, user_id: str, photo_bytes: bytes) -> bool:
        with self.lock:
            if user_id not in self.users:
                self.users[user_id] = VirtualUser(user_id=user_id, name="Unknown")
            u = self.users[user_id]
            u.photo_bytes = photo_bytes
            u.photo_updated_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            return True

    def remove_photo(self, user_id: str) -> bool:
        with self.lock:
            if user_id in self.users:
                self.users[user_id].photo_bytes = None
                self.users[user_id].photo_updated_at = None
                return True
            return False

    def remove_user(self, user_id: str) -> bool:
        with self.lock:
            return self.users.pop(user_id, None) is not None

    def record_punch(self, user_id: Optional[str], method: str = "FACE",
                     override_granted: Optional[bool] = None) -> AttendanceLog:
        with self.lock:
            self.rec_counter += 1
            now_iso = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            granted = True
            if override_granted is not None:
                granted = override_granted
            elif user_id:
                u = self.users.get(user_id)
                granted = u is not None and u.enabled

            log = AttendanceLog(
                device_user_id=user_id,
                occurred_at=now_iso,
                method=method,
                granted=granted,
                rec_no=self.rec_counter,
            )
            self.attendance.append(log)

            # Enqueue live event for Gateway listener
            ev = DeviceEvent(
                kind="ACCESS",
                device_user_id=user_id,
                occurred_at=now_iso,
                method=method,
                granted=granted,
                rec_no=self.rec_counter,
            )
            self.emit_event_unlocked(ev)
            return log

    def emit_event(self, ev: DeviceEvent) -> None:
        with self.lock:
            self.emit_event_unlocked(ev)

    def emit_event_unlocked(self, ev: DeviceEvent) -> None:
        with self.event_cv:
            self.event_queue.append(ev)
            self.event_cv.notify_all()

    def poll_events(self, timeout_sec: float = 25.0) -> List[DeviceEvent]:
        with self.event_cv:
            if not self.event_queue:
                self.event_cv.wait(timeout_sec)
            events = list(self.event_queue)
            self.event_queue.clear()
            return events


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


class DeviceRequestHandler(BaseHTTPRequestHandler):
    """Handles Gateway requests on a virtual device port (e.g. 9001/9002)."""

    device: VirtualDevice

    def log_message(self, format, *args):
        # Silence routine HTTP polling logs
        pass

    def do_GET(self):
        if self.device.simulated_offline:
            self.send_error(503, "Device Offline (Simulated)")
            return

        if self.device.simulated_latency_ms > 0:
            time.sleep(self.device.simulated_latency_ms / 1000.0)

        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/device/info":
            self._json_response({
                "serialNumber": self.device.serial,
                "deviceType": 1,
                "channelCount": 1,
                "alarmInCount": 0,
                "alarmOutCount": 0,
                "diskCount": 0,
                "firmware": self.device.firmware,
                "role": self.device.role,
            })
        elif path == "/device/users":
            with self.device.lock:
                users = [u.to_dict() for u in self.device.users.values()]
            self._json_response(users)
        elif path.startswith("/device/users/") and path.endswith("/face"):
            user_id = path[len("/device/users/"):-len("/face")]
            with self.device.lock:
                u = self.device.users.get(user_id)
                photo = u.photo_bytes if u else None
            if photo:
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(photo)))
                self.end_headers()
                self.wfile.write(photo)
            else:
                self.send_error(404, "No Face Image")
        elif path.startswith("/device/users/"):
            user_id = path[len("/device/users/"):]
            with self.device.lock:
                u = self.device.users.get(user_id)
            if u:
                self._json_response(u.to_dict())
            else:
                self.send_error(404, "User Not Found")
        elif path == "/device/attendance":
            with self.device.lock:
                records = [r.to_dict() for r in self.device.attendance]
            self._json_response(records)
        elif path == "/device/events/poll":
            events = self.device.poll_events(timeout_sec=20.0)
            self._json_response([e.to_dict() for e in events])
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        if self.device.simulated_offline:
            self.send_error(503, "Device Offline (Simulated)")
            return

        parsed = urlparse(self.path)
        path = parsed.path
        body = self._read_json_body()

        if path == "/device/users":
            uid = body.get("deviceUserId")
            if not uid:
                self.send_error(400, "Missing deviceUserId")
                return
            u = self.device.add_or_update_user(
                user_id=uid,
                name=body.get("name"),
                enabled=body.get("enabled"),
                valid_from=body.get("validFrom"),
                valid_to=body.get("validTo"),
            )
            self._json_response(u.to_dict(), status=201)
        elif path == "/device/open-door":
            self.device.door_open = True
            self._json_response({"ok": True, "door": "OPEN"})
        elif path == "/device/close-door":
            self.device.door_open = False
            self._json_response({"ok": True, "door": "CLOSED"})
        elif path == "/device/sync-time":
            self._json_response({"ok": True, "syncedAt": body.get("utcNow")})
        else:
            self.send_error(404, "Not Found")

    def do_PUT(self):
        if self.device.simulated_offline:
            self.send_error(503, "Device Offline (Simulated)")
            return

        parsed = urlparse(self.path)
        path = parsed.path

        if path.startswith("/device/users/") and path.endswith("/face"):
            user_id = path[len("/device/users/"):-len("/face")]
            length = int(self.headers.get("Content-Length", 0))
            photo_bytes = self.rfile.read(length)
            self.device.set_photo(user_id, photo_bytes)
            self._json_response({"ok": True, "size": len(photo_bytes)})
        elif path.startswith("/device/users/"):
            user_id = path[len("/device/users/"):]
            body = self._read_json_body()
            u = self.device.add_or_update_user(
                user_id=user_id,
                name=body.get("name"),
                enabled=body.get("enabled"),
                valid_from=body.get("validFrom"),
                valid_to=body.get("validTo"),
            )
            self._json_response(u.to_dict())
        else:
            self.send_error(404, "Not Found")

    def do_DELETE(self):
        if self.device.simulated_offline:
            self.send_error(503, "Device Offline (Simulated)")
            return

        parsed = urlparse(self.path)
        path = parsed.path

        if path.startswith("/device/users/") and path.endswith("/face"):
            user_id = path[len("/device/users/"):-len("/face")]
            self.device.remove_photo(user_id)
            self._json_response({"ok": True})
        elif path.startswith("/device/users/"):
            user_id = path[len("/device/users/"):]
            self.device.remove_user(user_id)
            self._json_response({"ok": True})
        else:
            self.send_error(404, "Not Found")

    def _read_json_body(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def _json_response(self, data: Any, status: int = 200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)


class AdminRequestHandler(BaseHTTPRequestHandler):
    """Admin REST API & Dashboard server on Port 9000."""

    devices: Dict[str, VirtualDevice] = {}

    def log_message(self, format, *args):
        pass

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/" or path == "/index.html":
            self._serve_static_html()
        elif path == "/api/devices":
            res = []
            for d in self.devices.values():
                with d.lock:
                    res.append({
                        "id": d.device_id,
                        "name": d.name,
                        "role": d.role,
                        "port": d.port,
                        "serial": d.serial,
                        "userCount": len(d.users),
                        "attendanceCount": len(d.attendance),
                        "isOffline": d.simulated_offline,
                        "latencyMs": d.simulated_latency_ms,
                    })
            self._json_response(res)
        elif path.startswith("/api/devices/") and path.endswith("/roster"):
            dev_id = path[len("/api/devices/"):-len("/roster")]
            dev = self.devices.get(dev_id)
            if not dev:
                self.send_error(404, "Device Not Found")
                return
            with dev.lock:
                users = [u.to_dict(include_photo=True) for u in dev.users.values()]
            self._json_response(users)
        elif path.startswith("/api/devices/") and path.endswith("/attendance"):
            dev_id = path[len("/api/devices/"):-len("/attendance")]
            dev = self.devices.get(dev_id)
            if not dev:
                self.send_error(404, "Device Not Found")
                return
            with dev.lock:
                records = [r.to_dict() for r in dev.attendance]
            self._json_response(records)
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        body = self._read_json_body()

        # Trigger simulated punch
        if path.startswith("/api/devices/") and path.endswith("/punch"):
            dev_id = path[len("/api/devices/"):-len("/punch")]
            dev = self.devices.get(dev_id)
            if not dev:
                self.send_error(404, "Device Not Found")
                return
            log = dev.record_punch(
                user_id=body.get("userId"),
                method=body.get("method", "FACE"),
                override_granted=body.get("overrideGranted"),
            )
            self._json_response(log.to_dict())

        # Simulate direct terminal walk-in enrollment / edit
        elif path.startswith("/api/devices/") and path.endswith("/users"):
            dev_id = path[len("/api/devices/"):-len("/users")]
            dev = self.devices.get(dev_id)
            if not dev:
                self.send_error(404, "Device Not Found")
                return
            uid = body.get("deviceUserId")
            u = dev.add_or_update_user(
                user_id=uid,
                name=body.get("name"),
                enabled=body.get("enabled", True),
                valid_from=body.get("validFrom"),
                valid_to=body.get("validTo"),
            )
            if body.get("photoBase64"):
                dev.set_photo(uid, base64.b64decode(body["photoBase64"]))

            # Emit USER_CHANGED event to notify Gateway
            now_iso = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            dev.emit_event(DeviceEvent(
                kind="USER_CHANGED",
                device_user_id=uid,
                occurred_at=now_iso,
                method="UNKNOWN",
                granted=True,
                rec_no=None,
                details="Local enrollment on terminal",
            ))
            self._json_response(u.to_dict(), status=201)

        # Fault injection (Toggle Offline)
        elif path.startswith("/api/devices/") and path.endswith("/faults/offline"):
            dev_id = path[len("/api/devices/"):-len("/faults/offline")]
            dev = self.devices.get(dev_id)
            if not dev:
                self.send_error(404, "Device Not Found")
                return
            dev.simulated_offline = bool(body.get("offline", True))
            self._json_response({"ok": True, "offline": dev.simulated_offline})

        # Fault injection (Latency)
        elif path.startswith("/api/devices/") and path.endswith("/faults/latency"):
            dev_id = path[len("/api/devices/"):-len("/faults/latency")]
            dev = self.devices.get(dev_id)
            if not dev:
                self.send_error(404, "Device Not Found")
                return
            dev.simulated_latency_ms = int(body.get("latencyMs", 0))
            self._json_response({"ok": True, "latencyMs": dev.simulated_latency_ms})

        # Reset device roster
        elif path.startswith("/api/devices/") and path.endswith("/reset"):
            dev_id = path[len("/api/devices/"):-len("/reset")]
            dev = self.devices.get(dev_id)
            if not dev:
                self.send_error(404, "Device Not Found")
                return
            with dev.lock:
                dev.users.clear()
                dev.attendance.clear()
            self._json_response({"ok": True, "reset": True})
        else:
            self.send_error(404, "Not Found")

    def _read_json_body(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def _json_response(self, data: Any, status: int = 200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _serve_static_html(self):
        html = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Gym Biometric Hardware Simulator</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 20px; background: #0f172a; color: #f8fafc; }
        h1 { margin-top: 0; color: #38bdf8; font-size: 24px; }
        .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
        .card { background: #1e293b; border-radius: 8px; padding: 20px; border: 1px solid #334155; }
        .badge { display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }
        .badge-online { background: #15803d; color: white; }
        .badge-offline { background: #b91c1c; color: white; }
        table { width: 100%; border-collapse: collapse; margin-top: 15px; font-size: 13px; }
        th, td { text-align: left; padding: 8px; border-bottom: 1px solid #334155; }
        th { background: #0f172a; color: #94a3b8; }
        button { background: #0284c7; color: white; border: none; border-radius: 4px; padding: 8px 14px; cursor: pointer; font-weight: bold; margin-right: 5px; }
        button:hover { background: #0369a1; }
        .btn-deny { background: #dc2626; }
        .btn-deny:hover { background: #b91c1c; }
        .btn-fault { background: #d97706; }
        .photo-thumb { width: 45px; height: 55px; object-fit: cover; border-radius: 3px; border: 1px solid #475569; }
        .controls { margin-top: 15px; display: flex; gap: 10px; flex-wrap: wrap; }
    </style>
</head>
<body>
    <h1>🏢 TrueFace 3000 Hardware Emulator Server</h1>
    <p style="color: #94a3b8;">Emulating physical biometric terminals for the .NET Gateway on Port 9001 (ENTRY) and 9002 (EXIT)</p>
    <div class="grid" id="devices-container">Loading virtual devices...</div>

    <script>
        async function fetchDevices() {
            const resp = await fetch('/api/devices');
            const devices = await resp.json();
            const container = document.getElementById('devices-container');
            container.innerHTML = '';

            for (const d of devices) {
                const rosterResp = await fetch(`/api/devices/${d.id}/roster`);
                const roster = await rosterResp.json();

                const card = document.createElement('div');
                card.className = 'card';
                card.innerHTML = `
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <h2 style="margin: 0; font-size: 18px;">${d.name} (${d.role})</h2>
                        <span class="badge ${d.isOffline ? 'badge-offline' : 'badge-online'}">${d.isOffline ? 'OFFLINE' : 'ONLINE (:' + d.port + ')'}</span>
                    </div>
                    <p style="color: #94a3b8; font-size: 13px;">Serial: ${d.serial} | Users: ${d.userCount} | Punches: ${d.attendanceCount}</p>
                    
                    <div class="controls">
                        <button onclick="punch('${d.id}', true)">🟢 Punch ${d.role} (Grant)</button>
                        <button class="btn-deny" onclick="punch('${d.id}', false)">🔴 Punch ${d.role} (Deny)</button>
                        <button class="btn-fault" onclick="toggleOffline('${d.id}', ${!d.isOffline})">${d.isOffline ? 'Bring Online' : 'Simulate Offline'}</button>
                        <button onclick="walkInEnroll('${d.id}')">➕ Direct Terminal Enroll</button>
                    </div>

                    <h3>Terminal User Roster (${roster.length})</h3>
                    <table>
                        <thead>
                            <tr>
                                <th>Photo</th>
                                <th>ID</th>
                                <th>Name</th>
                                <th>Status</th>
                                <th>Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${roster.map(u => `
                                <tr>
                                    <td>${u.photoBase64 ? '<img class="photo-thumb" src="data:image/jpeg;base64,' + u.photoBase64 + '" />' : '<span style="color: #64748b;">No Img</span>'}</td>
                                    <td><strong>${u.deviceUserId}</strong></td>
                                    <td>${u.name || '-'}</td>
                                    <td>${u.enabled ? '🟢 Active' : '🔴 Frozen'}</td>
                                    <td><button onclick="punchUser('${d.id}', '${u.deviceUserId}')">Punch</button></td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                `;
                container.appendChild(card);
            }
        }

        async function punch(deviceId, granted) {
            await fetch(`/api/devices/${deviceId}/punch`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({overrideGranted: granted})
            });
            fetchDevices();
        }

        async function punchUser(deviceId, userId) {
            await fetch(`/api/devices/${deviceId}/punch`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({userId: userId})
            });
            fetchDevices();
        }

        async function toggleOffline(deviceId, offline) {
            await fetch(`/api/devices/${deviceId}/faults/offline`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({offline: offline})
            });
            fetchDevices();
        }

        async function walkInEnroll(deviceId) {
            const uid = prompt('Enter Device User ID:');
            if (!uid) return;
            const name = prompt('Enter Full Name:');
            if (!name) return;

            await fetch(`/api/devices/${deviceId}/users`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({deviceUserId: uid, name: name, enabled: true})
            });
            fetchDevices();
        }

        setInterval(fetchDevices, 3000);
        fetchDevices();
    </script>
</body>
</html>"""
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def create_device_server(device: VirtualDevice) -> ThreadedHTTPServer:
    handler = type(f"Handler_{device.device_id}", (DeviceRequestHandler,), {"device": device})
    return ThreadedHTTPServer(("0.0.0.0", device.port), handler)


def create_admin_server(devices: Dict[str, VirtualDevice], port: int = 9000) -> ThreadedHTTPServer:
    handler = type("AdminHandler", (AdminRequestHandler,), {"devices": devices})
    return ThreadedHTTPServer(("0.0.0.0", port), handler)


def main():
    print("=" * 70)
    print("  TrueFace 3000 Multi-Device Hardware Emulator Server")
    print("=" * 70)

    devices = {
        "dev-entry-01": VirtualDevice(
            device_id="dev-entry-01",
            name="Main Entrance Gate",
            role="ENTRY",
            port=9001,
            serial="TW30000005250265"
        ),
        "dev-exit-02": VirtualDevice(
            device_id="dev-exit-02",
            name="Main Exit Gate",
            role="EXIT",
            port=9002,
            serial="TW30000005250266"
        ),
    }

    servers = []

    # Start virtual device servers on Ports 9001 and 9002
    for dev in devices.values():
        srv = create_device_server(dev)
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        servers.append(srv)
        print(f"  [+] Virtual Device ({dev.role}): http://127.0.0.1:{dev.port}  (S/N: {dev.serial})")

    # Start Admin Dashboard Server on Port 9000
    admin_srv = create_admin_server(devices, port=9000)
    admin_thread = threading.Thread(target=admin_srv.serve_forever, daemon=True)
    admin_thread.start()
    servers.append(admin_srv)

    print(f"  [+] Admin Web Dashboard:      http://127.0.0.1:9000")
    print("=" * 70)
    print("Point Gym.Gateway appsettings.json to Adapter='Remote' and ports 9001/9002.")
    print("Press Ctrl+C to terminate.")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutting down servers...")
        for s in servers:
            s.shutdown()
        sys.exit(0)


if __name__ == "__main__":
    main()
