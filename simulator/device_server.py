#!/usr/bin/env python3
"""Virtual device server for testing the real .NET gateway without hardware.

Each virtual device listens on its own port and speaks the simulator HTTP protocol used by the
gateway's RemoteDeviceAdapter (select it with "Adapter": "Remote"). This is NOT an emulation of the
TrueFace/Dahua NetSDK protocol; TrueFaceDeviceAdapter is only exercised against a real device.

A virtual device owns its state (users, validity, photos, attendance log, record numbers) and
behaves like a terminal on the points the gateway and server rules depend on:

  * Access: a punch is granted only for a known, enabled user inside the validity window
    (validFrom .. validTo, where validTo lasts until 23:59:59 UTC of that day, as
    TrueFaceDeviceAdapter writes it). Refusals carry the codes the gateway maps:
    0x10 unknown or frozen user, 0x14 outside the validity window.
  * Writes from the gateway change only the fields sent; a missing user is created (upsert).
  * A face can only be stored for an existing user; its change time is returned as Last-Modified.
  * Local edits made through the admin API (enrol, edit, freeze, delete, photo) raise a
    USER_CHANGED notification unless "notify": false (firmware that sends no event; the
    gateway's periodic roster scan must then find the change).
  * While offline the device still records punches, but live notifications raised meanwhile
    are lost; the records stay in the attendance log.

State is kept in SQLite (simulator/data/devices.db by default) so a restart keeps it;
use --memory for a fresh, non-persistent run. Stdlib only.

Device protocol (per device port):
  GET    /device/info
  GET    /device/users                 GET /device/users/{id}
  POST   /device/users                 PUT /device/users/{id}        DELETE /device/users/{id}
  GET    /device/users/{id}/face       PUT /device/users/{id}/face   DELETE /device/users/{id}/face
  GET    /device/attendance?from=&to=
  GET    /device/events/poll           (long poll, ~20 s)
  POST   /device/open-door | /device/close-door | /device/sync-time

Admin API (default port 9000, dashboard at /):
  GET    /api/devices
  GET    /api/devices/{id}/roster | /attendance | /users/{uid}/photo
  POST   /api/devices/{id}/punch            {userId?, method?, timestamp?, overrideGranted?}
  POST   /api/devices/{id}/users            {deviceUserId, name?, enabled?, validFrom?, validTo?, photoBase64?, notify?}
  PUT    /api/devices/{id}/users/{uid}      {name?, enabled?, validFrom?, validTo?, notify?}
  DELETE /api/devices/{id}/users/{uid}      [?notify=false]
  PUT    /api/devices/{id}/users/{uid}/photo {photoBase64, notify?}
  DELETE /api/devices/{id}/users/{uid}/photo [?notify=false]
  POST   /api/devices/{id}/faults/offline   {offline}
  POST   /api/devices/{id}/faults/latency   {latencyMs}
  POST   /api/devices/{id}/faults/error-rate       {rate, status?, operations?}
  POST   /api/devices/{id}/faults/corrupt-response {rate, operations?}
  POST   /api/devices/{id}/faults/clear
  POST   /api/devices/{id}/reset            {mode: "users" | "attendance" | "factory"}

Fault operations: info, users, face, attendance, events, door, time (omit for all).
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import random
import sqlite3
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, unquote, urlparse

DENY_UNAUTHORIZED = 0x10
DENY_VALIDITY_PERIOD = 0x14
POLL_WAIT_SECONDS = 20.0
OPERATIONS = {"info", "users", "face", "attendance", "events", "door", "time"}

DEFAULT_CONFIG = {
    "adminPort": 9000,
    "devices": [
        {"id": "dev-entry-01", "name": "Main Entrance Gate", "role": "ENTRY", "port": 9001,
         "serial": "TW30000005250265"},
        {"id": "dev-exit-02", "name": "Main Exit Gate", "role": "EXIT", "port": 9002,
         "serial": "TW30000005250266"},
    ],
}

SCHEMA = """
CREATE TABLE IF NOT EXISTS device_state (
    device_id   TEXT PRIMARY KEY,
    rec_counter INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS users (
    device_id        TEXT NOT NULL,
    user_id          TEXT NOT NULL,
    name             TEXT,
    enabled          INTEGER NOT NULL,
    valid_from       TEXT,
    valid_to         TEXT,
    photo            BLOB,
    photo_updated_at TEXT,
    PRIMARY KEY (device_id, user_id)
);
CREATE TABLE IF NOT EXISTS attendance (
    device_id   TEXT NOT NULL,
    rec_no      INTEGER NOT NULL,
    user_id     TEXT,
    occurred_at TEXT NOT NULL,
    method      TEXT NOT NULL,
    granted     INTEGER NOT NULL,
    error_code  INTEGER,
    PRIMARY KEY (device_id, rec_no)
);
"""


class BadRequest(Exception):
    pass


# --- time helpers ---------------------------------------------------------------------------------

def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def parse_time(value: Any, name: str = "time") -> Optional[datetime]:
    if value is None or value == "":
        return None
    try:
        dt = datetime.fromisoformat(str(value))
    except ValueError as ex:
        raise BadRequest(f"{name}: not an ISO-8601 time: {value}") from ex
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).replace(microsecond=0)


def iso(dt: Optional[datetime]) -> Optional[str]:
    return None if dt is None else dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def end_of_day(dt: datetime) -> datetime:
    return dt.replace(hour=23, minute=59, second=59)


# --- storage --------------------------------------------------------------------------------------

class Store:
    """One SQLite connection shared by all request threads, serialised by a lock."""

    def __init__(self, path: str):
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock, self._db:
            self._db.executescript(SCHEMA)

    def rows(self, sql: str, args: tuple = ()) -> List[sqlite3.Row]:
        with self.lock:
            return self._db.execute(sql, args).fetchall()

    def row(self, sql: str, args: tuple = ()) -> Optional[sqlite3.Row]:
        with self.lock:
            return self._db.execute(sql, args).fetchone()

    def execute(self, sql: str, args: tuple = ()) -> int:
        with self.lock, self._db:
            return self._db.execute(sql, args).rowcount


# --- device model ---------------------------------------------------------------------------------

@dataclass
class DeviceUser:
    user_id: str
    name: Optional[str]
    enabled: bool
    valid_from: Optional[datetime]
    valid_to: Optional[datetime]
    has_photo: bool
    photo_updated_at: Optional[datetime]

    @staticmethod
    def from_row(r: sqlite3.Row) -> "DeviceUser":
        return DeviceUser(r["user_id"], r["name"], bool(r["enabled"]), parse_time(r["valid_from"]),
                          parse_time(r["valid_to"]), r["has_photo"] == 1, parse_time(r["photo_updated_at"]))

    def to_dict(self) -> dict:
        return {
            "deviceUserId": self.user_id,
            "name": self.name,
            "enabled": self.enabled,
            "validFrom": iso(self.valid_from),
            "validTo": iso(self.valid_to),
            "hasPhoto": self.has_photo,
            "photoUpdatedAt": iso(self.photo_updated_at),
        }


def access_decision(user: Optional[DeviceUser], at: datetime) -> Tuple[bool, Optional[int]]:
    if user is None or not user.enabled:
        return False, DENY_UNAUTHORIZED
    if user.valid_from is not None and at < user.valid_from:
        return False, DENY_VALIDITY_PERIOD
    if user.valid_to is not None and at > user.valid_to:
        return False, DENY_VALIDITY_PERIOD
    return True, None


@dataclass
class Faults:
    offline: bool = False
    latency_ms: int = 0
    error_rate: float = 0.0
    error_status: int = 500
    error_operations: Optional[set] = None
    corrupt_rate: float = 0.0
    corrupt_operations: Optional[set] = None

    def to_dict(self) -> dict:
        return {
            "offline": self.offline,
            "latencyMs": self.latency_ms,
            "errorRate": self.error_rate,
            "errorStatus": self.error_status,
            "errorOperations": sorted(self.error_operations) if self.error_operations else None,
            "corruptRate": self.corrupt_rate,
            "corruptOperations": sorted(self.corrupt_operations) if self.corrupt_operations else None,
        }


USER_COLUMNS = ("user_id, name, enabled, valid_from, valid_to, photo_updated_at, "
                "CASE WHEN photo IS NULL THEN 0 ELSE 1 END AS has_photo")


@dataclass
class VirtualDevice:
    device_id: str
    name: str
    role: str
    port: int
    serial: str
    store: Store
    firmware: str = "V1.000.004H003.0.R.20250424-SIM"
    faults: Faults = field(default_factory=Faults)
    door_open: bool = False

    def __post_init__(self) -> None:
        self._events: List[dict] = []
        self._events_cv = threading.Condition()
        self.store.execute("INSERT OR IGNORE INTO device_state (device_id, rec_counter) VALUES (?, 1000)",
                           (self.device_id,))

    # users

    def get_user(self, user_id: str) -> Optional[DeviceUser]:
        r = self.store.row(f"SELECT {USER_COLUMNS} FROM users WHERE device_id = ? AND user_id = ?",
                           (self.device_id, user_id))
        return DeviceUser.from_row(r) if r else None

    def list_users(self) -> List[DeviceUser]:
        rows = self.store.rows(f"SELECT {USER_COLUMNS} FROM users WHERE device_id = ? ORDER BY user_id",
                               (self.device_id,))
        return [DeviceUser.from_row(r) for r in rows]

    def upsert_user(self, user_id: str, changes: Dict[str, Any]) -> Tuple[DeviceUser, bool]:
        """Applies only the given fields (name, enabled, valid_from, valid_to); creates when absent."""
        if "valid_to" in changes and changes["valid_to"] is not None:
            changes = dict(changes, valid_to=end_of_day(changes["valid_to"]))
        with self.store.lock:
            existing = self.get_user(user_id)
            if existing is None:
                self.store.execute(
                    "INSERT INTO users (device_id, user_id, name, enabled, valid_from, valid_to) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (self.device_id, user_id, changes.get("name") or user_id,
                     1 if changes.get("enabled", True) else 0,
                     iso(changes.get("valid_from")), iso(changes.get("valid_to"))))
            else:
                for column in ("name", "enabled", "valid_from", "valid_to"):
                    if column in changes:
                        value = changes[column]
                        value = iso(value) if isinstance(value, datetime) else value
                        self.store.execute(
                            f"UPDATE users SET {column} = ? WHERE device_id = ? AND user_id = ?",
                            (value, self.device_id, user_id))
            return self.get_user(user_id), existing is None

    def delete_user(self, user_id: str) -> bool:
        return self.store.execute("DELETE FROM users WHERE device_id = ? AND user_id = ?",
                                  (self.device_id, user_id)) > 0

    # faces

    def photo(self, user_id: str) -> Optional[Tuple[bytes, Optional[datetime]]]:
        r = self.store.row("SELECT photo, photo_updated_at FROM users WHERE device_id = ? AND user_id = ?",
                           (self.device_id, user_id))
        if r is None or r["photo"] is None:
            return None
        return bytes(r["photo"]), parse_time(r["photo_updated_at"])

    def set_photo(self, user_id: str, photo: bytes) -> bool:
        return self.store.execute(
            "UPDATE users SET photo = ?, photo_updated_at = ? WHERE device_id = ? AND user_id = ?",
            (photo, iso(utc_now()), self.device_id, user_id)) > 0

    def remove_photo(self, user_id: str) -> bool:
        return self.store.execute(
            "UPDATE users SET photo = NULL, photo_updated_at = ? "
            "WHERE device_id = ? AND user_id = ? AND photo IS NOT NULL",
            (iso(utc_now()), self.device_id, user_id)) > 0

    # attendance

    def attendance(self, from_utc: Optional[datetime] = None, to_utc: Optional[datetime] = None) -> List[dict]:
        rows = self.store.rows("SELECT * FROM attendance WHERE device_id = ? ORDER BY rec_no", (self.device_id,))
        records = []
        for r in rows:
            at = parse_time(r["occurred_at"])
            if (from_utc and at < from_utc) or (to_utc and at > to_utc):
                continue
            records.append({
                "deviceUserId": r["user_id"],
                "occurredAt": r["occurred_at"],
                "method": r["method"],
                "granted": bool(r["granted"]),
                "recNo": r["rec_no"],
                "errorCode": r["error_code"],
            })
        return records

    def attendance_count(self) -> int:
        return self.store.row("SELECT COUNT(*) AS n FROM attendance WHERE device_id = ?", (self.device_id,))["n"]

    def punch(self, user_id: Optional[str], method: str, at: Optional[datetime],
              override_granted: Optional[bool]) -> dict:
        at = at or utc_now()
        with self.store.lock:
            user = self.get_user(user_id) if user_id else None
            if override_granted is not None:
                granted, code = override_granted, (None if override_granted else DENY_UNAUTHORIZED)
            else:
                granted, code = access_decision(user, at)
            self.store.execute("UPDATE device_state SET rec_counter = rec_counter + 1 WHERE device_id = ?",
                               (self.device_id,))
            rec_no = self.store.row("SELECT rec_counter FROM device_state WHERE device_id = ?",
                                    (self.device_id,))["rec_counter"]
            self.store.execute(
                "INSERT INTO attendance (device_id, rec_no, user_id, occurred_at, method, granted, error_code) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (self.device_id, rec_no, user_id, iso(at), method, 1 if granted else 0, code))
        record = {"deviceUserId": user_id, "occurredAt": iso(at), "method": method, "granted": granted,
                  "recNo": rec_no, "errorCode": code}
        self.emit(dict(record, kind="ACCESS"))
        return record

    def reset(self, mode: str) -> None:
        if mode not in ("users", "attendance", "factory"):
            raise BadRequest("mode must be users, attendance or factory")
        with self.store.lock:
            if mode in ("users", "factory"):
                self.store.execute("DELETE FROM users WHERE device_id = ?", (self.device_id,))
            if mode in ("attendance", "factory"):
                self.store.execute("DELETE FROM attendance WHERE device_id = ?", (self.device_id,))
            if mode == "factory":
                self.store.execute("UPDATE device_state SET rec_counter = 1000 WHERE device_id = ?",
                                   (self.device_id,))
                with self._events_cv:
                    self._events.clear()

    # live notifications

    def emit(self, event: dict) -> None:
        if self.faults.offline:
            return
        with self._events_cv:
            self._events.append(event)
            self._events_cv.notify_all()

    def user_changed(self, user_id: str, details: str) -> None:
        self.emit({"kind": "USER_CHANGED", "deviceUserId": user_id, "occurredAt": iso(utc_now()),
                   "method": "UNKNOWN", "granted": True, "recNo": None, "details": details})

    def poll(self, timeout_sec: float) -> List[dict]:
        with self._events_cv:
            if not self._events:
                self._events_cv.wait(timeout_sec)
            if self.faults.offline:
                return []
            events, self._events = self._events, []
            return events

    def go_offline(self, offline: bool) -> None:
        self.faults.offline = offline
        if offline:
            with self._events_cv:
                self._events.clear()
                self._events_cv.notify_all()

    def summary(self) -> dict:
        return {
            "id": self.device_id,
            "name": self.name,
            "role": self.role,
            "port": self.port,
            "serial": self.serial,
            "userCount": len(self.list_users()),
            "attendanceCount": self.attendance_count(),
            "doorOpen": self.door_open,
            "isOffline": self.faults.offline,
            "latencyMs": self.faults.latency_ms,
            "faults": self.faults.to_dict(),
        }


# --- HTTP plumbing --------------------------------------------------------------------------------

class JsonHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):  # noqa: A002 - silence routine request logs
        pass

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def do_PUT(self):
        self._dispatch("PUT")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _dispatch(self, method: str) -> None:
        url = urlparse(self.path)
        segments = [unquote(s) for s in url.path.strip("/").split("/") if s]
        query = {k: v[-1] for k, v in parse_qs(url.query).items()}
        try:
            self.route(method, segments, query)
        except BadRequest as ex:
            self.send_json({"error": str(ex)}, 400)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def route(self, method: str, segments: List[str], query: Dict[str, str]) -> None:
        raise NotImplementedError

    def body_bytes(self) -> bytes:
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    break
                chunk_len = int(line, 16)
                if chunk_len == 0:
                    self.rfile.readline() # trailing CRLF
                    break
                chunks.append(self.rfile.read(chunk_len))
                self.rfile.readline() # chunk trailing CRLF
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def body_json(self) -> dict:
        raw = self.body_bytes()
        if not raw:
            return {}
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as ex:
            raise BadRequest("body is not valid JSON") from ex
        if not isinstance(data, dict):
            raise BadRequest("body must be a JSON object")
        return data

    def send_json(self, data: Any, status: int = 200) -> None:
        self.send_bytes(json.dumps(data).encode("utf-8"), "application/json", status)

    def send_bytes(self, body: bytes, content_type: str, status: int = 200,
                   headers: Optional[Dict[str, str]] = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def not_found(self, what: str = "Not Found") -> None:
        self.send_json({"error": what}, 404)

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")


def user_changes(body: dict) -> Dict[str, Any]:
    """Fields present in the body, parsed; absent fields are left unchanged on the device."""
    changes: Dict[str, Any] = {}
    if "name" in body:
        changes["name"] = body["name"]
    if "enabled" in body and body["enabled"] is not None:
        if not isinstance(body["enabled"], bool):
            raise BadRequest("enabled must be true or false")
        changes["enabled"] = body["enabled"]
    if "validFrom" in body:
        changes["valid_from"] = parse_time(body["validFrom"], "validFrom")
    if "validTo" in body:
        changes["valid_to"] = parse_time(body["validTo"], "validTo")
    return changes


def operation_of(segments: List[str]) -> str:
    if len(segments) < 2:
        return "info"
    kind = segments[1]
    if kind == "users":
        return "face" if len(segments) == 4 and segments[3] == "face" else "users"
    return {"info": "info", "attendance": "attendance", "events": "events", "open-door": "door",
            "close-door": "door", "sync-time": "time"}.get(kind, kind)


def applies(rate: float, operations: Optional[set], operation: str) -> bool:
    return rate > 0 and (not operations or operation in operations) and random.random() < rate


class DeviceHandler(JsonHandler):
    """The gateway-facing protocol of one virtual device."""

    device: VirtualDevice

    def route(self, method: str, segments: List[str], query: Dict[str, str]) -> None:
        d = self.device
        f = d.faults
        operation = operation_of(segments)
        if f.offline:
            self.send_json({"error": "Device offline (simulated)"}, 503)
            return
        if f.latency_ms > 0:
            time.sleep(f.latency_ms / 1000.0)
        if applies(f.error_rate, f.error_operations, operation):
            self.send_json({"error": "Simulated device error", "code": "0xFFFFFFFF"}, f.error_status)
            return
        if applies(f.corrupt_rate, f.corrupt_operations, operation):
            self.send_bytes(b'{"corrupt":[\x00\xff', "application/json")
            return

        if not segments or segments[0] != "device":
            self.not_found()
        elif segments == ["device", "info"] and method == "GET":
            self.send_json({"serialNumber": d.serial, "deviceType": 1, "channelCount": 1, "alarmInCount": 0,
                            "alarmOutCount": 0, "diskCount": 0, "firmware": d.firmware, "role": d.role})
        elif segments == ["device", "users"] and method == "GET":
            self.send_json([u.to_dict() for u in d.list_users()])
        elif segments == ["device", "users"] and method == "POST":
            body = self.body_json()
            user_id = body.get("deviceUserId")
            if not user_id:
                raise BadRequest("deviceUserId is required")
            user, created = d.upsert_user(str(user_id), user_changes(body))
            self.send_json(user.to_dict(), 201 if created else 200)
        elif len(segments) == 3 and segments[1] == "users":
            self._user(method, segments[2])
        elif len(segments) == 4 and segments[1] == "users" and segments[3] == "face":
            self._face(method, segments[2])
        elif segments == ["device", "attendance"] and method == "GET":
            self.send_json(d.attendance(parse_time(query.get("from"), "from"), parse_time(query.get("to"), "to")))
        elif segments == ["device", "events", "poll"] and method == "GET":
            self.send_json(d.poll(POLL_WAIT_SECONDS))
        elif segments == ["device", "open-door"] and method == "POST":
            d.door_open = True
            self.send_json({"ok": True, "door": "OPEN"})
        elif segments == ["device", "close-door"] and method == "POST":
            d.door_open = False
            self.send_json({"ok": True, "door": "CLOSED"})
        elif segments == ["device", "sync-time"] and method == "POST":
            self.send_json({"ok": True, "syncedAt": self.body_json().get("utcNow")})
        else:
            self.not_found()

    def _user(self, method: str, user_id: str) -> None:
        d = self.device
        if method == "GET":
            user = d.get_user(user_id)
            self.send_json(user.to_dict()) if user else self.not_found("User not found")
        elif method == "PUT":
            user, created = d.upsert_user(user_id, user_changes(self.body_json()))
            self.send_json(user.to_dict(), 201 if created else 200)
        elif method == "DELETE":
            d.delete_user(user_id)
            self.send_json({"ok": True})
        else:
            self.not_found()

    def _face(self, method: str, user_id: str) -> None:
        d = self.device
        if method == "GET":
            found = d.photo(user_id)
            if found is None:
                self.not_found("No face image")
                return
            photo, updated_at = found
            headers = {"Last-Modified": formatdate(updated_at.timestamp(), usegmt=True)} if updated_at else None
            self.send_bytes(photo, "image/jpeg", headers=headers)
        elif method == "PUT":
            photo = self.body_bytes()
            if not photo:
                raise BadRequest("empty face image")
            if not d.set_photo(user_id, photo):
                self.not_found("User not found")
                return
            self.send_json({"ok": True, "size": len(photo)})
        elif method == "DELETE":
            d.remove_photo(user_id)
            self.send_json({"ok": True})
        else:
            self.not_found()


class AdminHandler(JsonHandler):
    """Test-control API and dashboard: acts on the devices as a person at the terminal would."""

    devices: Dict[str, VirtualDevice]

    def route(self, method: str, segments: List[str], query: Dict[str, str]) -> None:
        if method == "GET" and segments in ([], ["index.html"]):
            self.send_bytes(DASHBOARD_HTML.encode("utf-8"), "text/html; charset=utf-8")
            return
        if segments == ["api", "devices"] and method == "GET":
            self.send_json([d.summary() for d in self.devices.values()])
            return
        if len(segments) < 4 or segments[:2] != ["api", "devices"]:
            self.not_found()
            return
        d = self.devices.get(segments[2])
        if d is None:
            self.not_found("Device not found")
            return
        action, rest = segments[3], segments[4:]

        if action == "roster" and method == "GET":
            self.send_json([u.to_dict() for u in d.list_users()])
        elif action == "attendance" and method == "GET":
            self.send_json(d.attendance())
        elif action == "punch" and method == "POST":
            body = self.body_json()
            override = body.get("overrideGranted")
            if override is not None and not isinstance(override, bool):
                raise BadRequest("overrideGranted must be true, false or null")
            user_id = body.get("userId")
            self.send_json(d.punch(str(user_id) if user_id not in (None, "") else None, body.get("method") or "FACE",
                                   parse_time(body.get("timestamp"), "timestamp"), override))
        elif action == "users":
            self._users(d, method, rest, query)
        elif action == "faults" and method == "POST" and len(rest) == 1:
            self._fault(d, rest[0])
        elif action == "reset" and method == "POST":
            mode = self.body_json().get("mode", "users")
            d.reset(mode)
            self.send_json({"ok": True, "reset": mode})
        else:
            self.not_found()

    def _users(self, d: VirtualDevice, method: str, rest: List[str], query: Dict[str, str]) -> None:
        if not rest and method == "POST":
            body = self.body_json()
            user_id = str(body.get("deviceUserId") or "")
            if not user_id:
                raise BadRequest("deviceUserId is required")
            if d.get_user(user_id) is not None:
                self.send_json({"error": "User already exists; use PUT to edit"}, 409)
                return
            photo = self._photo(body) if body.get("photoBase64") else None
            user, _ = d.upsert_user(user_id, user_changes(body))
            if photo:
                d.set_photo(user_id, photo)
                user = d.get_user(user_id)
            self._notify(d, body.get("notify", True), user_id, "Enrolled on terminal")
            self.send_json(user.to_dict(), 201)
            return
        if not rest:
            self.not_found()
            return

        user_id = rest[0]
        if d.get_user(user_id) is None:
            self.not_found("User not found")
            return
        if len(rest) == 1 and method == "PUT":
            body = self.body_json()
            user, _ = d.upsert_user(user_id, user_changes(body))
            self._notify(d, body.get("notify", True), user_id, "Edited on terminal")
            self.send_json(user.to_dict())
        elif len(rest) == 1 and method == "DELETE":
            d.delete_user(user_id)
            self._notify(d, query.get("notify", "true") != "false", user_id, "Deleted on terminal")
            self.send_json({"ok": True})
        elif rest[1:] == ["photo"] and method == "GET":
            found = d.photo(user_id)
            self.send_bytes(found[0], "image/jpeg") if found else self.not_found("No face image")
        elif rest[1:] == ["photo"] and method == "PUT":
            body = self.body_json()
            d.set_photo(user_id, self._photo(body))
            self._notify(d, body.get("notify", True), user_id, "Face enrolled on terminal")
            self.send_json(d.get_user(user_id).to_dict())
        elif rest[1:] == ["photo"] and method == "DELETE":
            d.remove_photo(user_id)
            self._notify(d, query.get("notify", "true") != "false", user_id, "Face removed on terminal")
            self.send_json(d.get_user(user_id).to_dict())
        else:
            self.not_found()

    def _fault(self, d: VirtualDevice, kind: str) -> None:
        body = self.body_json()
        f = d.faults
        if kind == "offline":
            d.go_offline(bool(body.get("offline", True)))
        elif kind == "latency":
            f.latency_ms = max(0, int(body.get("latencyMs", 0)))
        elif kind == "error-rate":
            f.error_rate = self._rate(body)
            f.error_status = int(body.get("status", 500))
            f.error_operations = self._operations(body)
        elif kind == "corrupt-response":
            f.corrupt_rate = self._rate(body)
            f.corrupt_operations = self._operations(body)
        elif kind == "clear":
            d.go_offline(False)
            d.faults = Faults()
        else:
            self.not_found("Unknown fault")
            return
        self.send_json({"ok": True, "faults": d.faults.to_dict()})

    @staticmethod
    def _notify(d: VirtualDevice, notify: Any, user_id: str, details: str) -> None:
        if notify is not False:
            d.user_changed(user_id, details)

    @staticmethod
    def _photo(body: dict) -> bytes:
        try:
            photo = base64.b64decode(body.get("photoBase64") or "", validate=True)
        except (binascii.Error, ValueError) as ex:
            raise BadRequest("photoBase64 is not valid base64") from ex
        if not photo:
            raise BadRequest("photoBase64 is required")
        return photo

    @staticmethod
    def _rate(body: dict) -> float:
        rate = float(body.get("rate", 0))
        if not 0 <= rate <= 1:
            raise BadRequest("rate must be between 0 and 1")
        return rate

    @staticmethod
    def _operations(body: dict) -> Optional[set]:
        ops = body.get("operations")
        if not ops:
            return None
        unknown = set(ops) - OPERATIONS
        if unknown:
            raise BadRequest(f"unknown operations: {sorted(unknown)}; allowed: {sorted(OPERATIONS)}")
        return set(ops)


DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Virtual Device Server</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 20px;
         background: #0f172a; color: #f8fafc; }
  h1 { margin-top: 0; color: #38bdf8; font-size: 22px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
  .card { background: #1e293b; border-radius: 8px; padding: 18px; border: 1px solid #334155; }
  .badge { display: inline-block; padding: 3px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }
  .on { background: #15803d; } .off { background: #b91c1c; }
  table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 12px; }
  th, td { text-align: left; padding: 6px; border-bottom: 1px solid #334155; vertical-align: middle; }
  th { color: #94a3b8; }
  button { background: #0284c7; color: white; border: none; border-radius: 4px; padding: 5px 9px; cursor: pointer;
           font-size: 12px; margin: 2px; }
  .warn { background: #d97706; } .danger { background: #dc2626; }
  .thumb { width: 36px; height: 44px; object-fit: cover; border-radius: 3px; }
  .muted { color: #94a3b8; font-size: 12px; }
</style>
</head>
<body>
<h1>Virtual Device Server</h1>
<p class="muted">Stateful virtual devices for the gateway's RemoteDeviceAdapter (simulator HTTP protocol, not NetSDK).
Edits here are local terminal edits; the gateway detects and syncs them.</p>
<div class="grid" id="devices">Loading...</div>
<script>
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const day = s => s ? s.substring(0, 10) : '-';
async function call(method, url, body) {
  const r = await fetch(url, {method, headers: {'Content-Type': 'application/json'},
                              body: body === undefined ? undefined : JSON.stringify(body)});
  if (!r.ok) alert(method + ' ' + url + ' -> ' + r.status + ' ' + await r.text());
  render();
}
const api = (d, path) => `/api/devices/${encodeURIComponent(d)}${path}`;
const u = id => `/users/${encodeURIComponent(id)}`;
function enroll(d) {
  const id = prompt('Device user id:'); if (!id) return;
  const name = prompt('Name:', 'Walk-in ' + id); if (name === null) return;
  call('POST', api(d, '/users'), {deviceUserId: id, name});
}
function rename(d, id, current) {
  const name = prompt('New name:', current); if (name) call('PUT', api(d, u(id)), {name});
}
function dates(d, id, from, to) {
  const f = prompt('Valid from (YYYY-MM-DD, empty to clear):', from === '-' ? '' : from); if (f === null) return;
  const t = prompt('Valid to (YYYY-MM-DD, empty to clear):', to === '-' ? '' : to); if (t === null) return;
  call('PUT', api(d, u(id)), {validFrom: f ? f + 'T00:00:00Z' : null, validTo: t ? t + 'T00:00:00Z' : null});
}
function photo(d, id) {
  const input = document.createElement('input'); input.type = 'file'; input.accept = 'image/jpeg';
  input.onchange = () => { const r = new FileReader();
    r.onload = () => call('PUT', api(d, u(id) + '/photo'), {photoBase64: r.result.split(',')[1]});
    r.readAsDataURL(input.files[0]); };
  input.click();
}
function punchAt(d, id) {
  const t = prompt('Punch time (ISO, e.g. 2026-10-01T09:00:00Z):', new Date().toISOString().substring(0, 19) + 'Z');
  if (t) call('POST', api(d, '/punch'), {userId: id, timestamp: t});
}
async function render() {
  const devices = await (await fetch('/api/devices')).json();
  const parts = await Promise.all(devices.map(async d => {
    const roster = await (await fetch(api(d.id, '/roster'))).json();
    const rows = roster.map(x => `<tr>
      <td>${x.hasPhoto ? `<img class="thumb" src="${api(d.id, u(x.deviceUserId) + '/photo')}?t=${esc(x.photoUpdatedAt)}">` : '<span class="muted">none</span>'}</td>
      <td><b>${esc(x.deviceUserId)}</b></td><td>${esc(x.name)}</td>
      <td>${x.enabled ? 'Active' : 'Frozen'}</td><td>${day(x.validFrom)} .. ${day(x.validTo)}</td>
      <td>
        <button onclick="call('POST', '${api(d.id, '/punch')}', {userId: '${esc(x.deviceUserId)}'})">Punch</button>
        <button onclick="punchAt('${esc(d.id)}', '${esc(x.deviceUserId)}')">Punch at...</button>
        <button onclick="rename('${esc(d.id)}', '${esc(x.deviceUserId)}', '${esc(x.name)}')">Rename</button>
        <button class="warn" onclick="call('PUT', '${api(d.id, u(x.deviceUserId))}', {enabled: ${!x.enabled}})">${x.enabled ? 'Freeze' : 'Unfreeze'}</button>
        <button onclick="dates('${esc(d.id)}', '${esc(x.deviceUserId)}', '${day(x.validFrom)}', '${day(x.validTo)}')">Dates</button>
        <button onclick="photo('${esc(d.id)}', '${esc(x.deviceUserId)}')">Photo</button>
        ${x.hasPhoto ? `<button class="warn" onclick="call('DELETE', '${api(d.id, u(x.deviceUserId) + '/photo')}')">Remove photo</button>` : ''}
        <button class="danger" onclick="if (confirm('Delete ${esc(x.deviceUserId)}?')) call('DELETE', '${api(d.id, u(x.deviceUserId))}')">Delete</button>
      </td></tr>`).join('');
    return `<div class="card">
      <div style="display:flex; justify-content:space-between; align-items:center">
        <h2 style="margin:0; font-size:17px">${esc(d.name)} (${esc(d.role)})</h2>
        <span class="badge ${d.isOffline ? 'off' : 'on'}">${d.isOffline ? 'OFFLINE' : 'ONLINE :' + d.port}</span>
      </div>
      <p class="muted">${esc(d.id)} | S/N ${esc(d.serial)} | users ${d.userCount} | punches ${d.attendanceCount}
         | latency ${d.faults.latencyMs} ms | error rate ${d.faults.errorRate} | corrupt ${d.faults.corruptRate}</p>
      <div>
        <button onclick="enroll('${esc(d.id)}')">Enrol on terminal</button>
        <button class="danger" onclick="call('POST', '${api(d.id, '/punch')}', {})">Stranger punch</button>
        <button class="warn" onclick="call('POST', '${api(d.id, '/faults/offline')}', {offline: ${!d.isOffline}})">${d.isOffline ? 'Bring online' : 'Go offline'}</button>
        <button class="warn" onclick="call('POST', '${api(d.id, '/faults/clear')}', {})">Clear faults</button>
        <button class="danger" onclick="if (confirm('Wipe all users?')) call('POST', '${api(d.id, '/reset')}', {mode: 'users'})">Wipe users</button>
        <button class="danger" onclick="if (confirm('Factory reset (users, attendance, record numbers)?')) call('POST', '${api(d.id, '/reset')}', {mode: 'factory'})">Factory reset</button>
      </div>
      <table><thead><tr><th>Photo</th><th>ID</th><th>Name</th><th>Status</th><th>Valid</th><th></th></tr></thead>
      <tbody>${rows}</tbody></table></div>`;
  }));
  document.getElementById('devices').innerHTML = parts.join('');
}
setInterval(render, 3000);
render();
</script>
</body>
</html>"""


# --- startup --------------------------------------------------------------------------------------

def load_config(path: Optional[str]) -> dict:
    if not path:
        return DEFAULT_CONFIG
    with open(path, encoding="utf-8") as fh:
        config = json.load(fh)
    config.setdefault("adminPort", DEFAULT_CONFIG["adminPort"])
    return config


def build_devices(config: dict, store: Store) -> Dict[str, VirtualDevice]:
    devices = {}
    for c in config["devices"]:
        devices[c["id"]] = VirtualDevice(c["id"], c.get("name", c["id"]), c.get("role", "ENTRY"), int(c["port"]),
                                         c.get("serial", "SIM-" + c["id"]), store)
    return devices


def start_server(port: int, handler: type, host: str = "0.0.0.0") -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="Virtual device server for the gateway's RemoteDeviceAdapter")
    parser.add_argument("--config", help="JSON file: {adminPort, devices: [{id, name, role, port, serial}]}")
    parser.add_argument("--db", default=str(Path(__file__).parent / "data" / "devices.db"),
                        help="SQLite file holding device state (default: simulator/data/devices.db)")
    parser.add_argument("--memory", action="store_true", help="keep state in memory only (lost on restart)")
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()

    config = load_config(args.config)
    store = Store(":memory:" if args.memory else args.db)
    devices = build_devices(config, store)

    servers = []
    print("Virtual device server (simulator HTTP protocol for RemoteDeviceAdapter)")
    print(f"  state: {'in memory' if args.memory else args.db}")
    for d in devices.values():
        handler = type(f"DeviceHandler_{d.port}", (DeviceHandler,), {"device": d})
        servers.append(start_server(d.port, handler, args.host))
        print(f"  device {d.device_id} ({d.role}): http://127.0.0.1:{d.port}  S/N {d.serial}")
    admin = type("BoundAdminHandler", (AdminHandler,), {"devices": devices})
    servers.append(start_server(int(config["adminPort"]), admin, args.host))
    print(f"  admin API + dashboard: http://127.0.0.1:{config['adminPort']}")
    print('Gateway: set "Adapter": "Remote" and point each device at 127.0.0.1 and its port. Ctrl+C to stop.')

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        for s in servers:
            s.shutdown()
        sys.exit(0)


if __name__ == "__main__":
    main()
