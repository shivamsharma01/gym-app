#!/usr/bin/env python3
"""Interactive Biometric Device Stub (Python GUI).

Simulates two TrueFace 3000 terminals (Entry & Exit) with full support for:
- Member creation / edit / delete downstream from Gateway/Backend
- Face photo rendering (JPEG decoding)
- Local terminal enrollment & upstream sync (DEVICE_USER_CHANGED)
- Simulating Entry and Exit face recognition events (DEVICE_EVENT)

Requirements:
    pip install pillow
"""

from __future__ import annotations

import base64
import io
import json
import os
import sys
import threading
import time
import tkinter as tk
from dataclasses import dataclass
from datetime import datetime, timezone
from tkinter import filedialog, messagebox, ttk
from typing import Any, Dict, Optional
import urllib.error
import urllib.request
import uuid

try:
    from PIL import Image, ImageTk
except ImportError:
    print("Please install Pillow: pip install pillow", file=sys.stderr)
    sys.exit(1)


@dataclass
class TerminalUser:
    user_id: str
    name: str
    enabled: bool = True
    valid_from: Optional[str] = None
    valid_to: Optional[str] = None
    photo_bytes: Optional[bytes] = None
    photo_image: Optional[Any] = None


class DeviceState:
    """State store for a single simulated TrueFace terminal."""

    def __init__(self, device_id: str, device_name: str, role: str):
        self.device_id = device_id
        self.device_name = device_name
        self.role = role  # ENTRY or EXIT
        self.users: Dict[str, TerminalUser] = {}

    def add_or_update_user(self, user_id: str, name: str, enabled: bool = True,
                           valid_from: Optional[str] = None, valid_to: Optional[str] = None) -> TerminalUser:
        if user_id in self.users:
            user = self.users[user_id]
            user.name = name or user.name
            user.enabled = enabled
            user.valid_from = valid_from or user.valid_from
            user.valid_to = valid_to or user.valid_to
        else:
            user = TerminalUser(user_id=user_id, name=name, enabled=enabled,
                                valid_from=valid_from, valid_to=valid_to)
            self.users[user_id] = user
        return user

    def set_user_photo(self, user_id: str, photo_bytes: bytes) -> bool:
        if user_id not in self.users:
            self.users[user_id] = TerminalUser(user_id=user_id, name="Unknown")
        user = self.users[user_id]
        user.photo_bytes = photo_bytes
        try:
            pil_img = Image.open(io.BytesIO(photo_bytes)).convert("RGB")
            pil_img = pil_img.resize((100, 120), Image.Resampling.LANCZOS)
            user.photo_image = ImageTk.PhotoImage(pil_img)
            return True
        except Exception as ex:
            print(f"Failed to process thumbnail for {user_id}: {ex}")
            user.photo_image = None
            return False

    def remove_user(self, user_id: str) -> None:
        self.users.pop(user_id, None)


class GatewayClient:
    """Handles REST and polling communications with Spring Boot Backend."""

    def __init__(self, backend_url: str, gateway_id: str, token: str):
        self.backend_url = backend_url.rstrip("/")
        self.gateway_id = gateway_id
        self.token = token

    def request(self, method: str, path: str, body: Optional[dict] = None) -> tuple[int, str]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(
            self.backend_url + path,
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, resp.read().decode("utf-8")
        except urllib.error.HTTPError as ex:
            return ex.code, ex.read().decode("utf-8", errors="replace")
        except Exception as ex:
            return 599, str(ex)

    def envelope(self, msg_type: str, payload: dict, device_id: Optional[str] = None,
                 correlation_id: Optional[str] = None) -> dict:
        return {
            "messageId": str(uuid.uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "gatewayId": self.gateway_id,
            "deviceId": device_id,
            "type": msg_type,
            "correlationId": correlation_id or str(uuid.uuid4()),
            "payload": payload,
        }

    def send_message(self, msg_type: str, payload: dict, device_id: Optional[str] = None,
                     correlation_id: Optional[str] = None) -> tuple[int, str]:
        msg = self.envelope(msg_type, payload, device_id, correlation_id)
        return self.request("POST", "/internal/gateway/messages", msg)


class BiometricStubApp(tk.Tk):
    """Main Desktop UI for the Dual Terminal Biometric Stub."""

    def __init__(self):
        super().__init__()
        self.title("Gym Biometric Device Stub — TrueFace 3000 Simulation")
        self.geometry("1150x760")
        self.minsize(950, 650)

        self.backend_url = tk.StringVar(value=os.environ.get("GYM_BACKEND", "http://127.0.0.1:8080"))
        self.gateway_id = tk.StringVar(value=os.environ.get("GYM_GATEWAY_ID", "sim-gateway-1"))
        self.gateway_token = tk.StringVar(value=os.environ.get("GYM_GATEWAY_TOKEN", "sim-token-123"))
        self.entry_device_id = tk.StringVar(value=os.environ.get("GYM_ENTRY_DEVICE_ID", "dev-entry-01"))
        self.exit_device_id = tk.StringVar(value=os.environ.get("GYM_EXIT_DEVICE_ID", "dev-exit-02"))

        self.entry_device = DeviceState(self.entry_device_id.get(), "Entry Gate Terminal", "ENTRY")
        self.exit_device = DeviceState(self.exit_device_id.get(), "Exit Gate Terminal", "EXIT")

        self.client: Optional[GatewayClient] = None
        self.poller_thread: Optional[threading.Thread] = None
        self.is_connected = False

        self._build_ui()

    def _build_ui(self):
        # Top Bar
        top_bar = ttk.LabelFrame(self, text="Gateway & Cloud Backend Connection", padding=10)
        top_bar.pack(fill=tk.X, padx=10, pady=5)

        ttk.Label(top_bar, text="Backend URL:").grid(row=0, column=0, sticky=tk.W, padx=3)
        ttk.Entry(top_bar, textvariable=self.backend_url, width=26).grid(row=0, column=1, padx=3)

        ttk.Label(top_bar, text="Gateway ID:").grid(row=0, column=2, sticky=tk.W, padx=3)
        ttk.Entry(top_bar, textvariable=self.gateway_id, width=16).grid(row=0, column=3, padx=3)

        ttk.Label(top_bar, text="Token:").grid(row=0, column=4, sticky=tk.W, padx=3)
        ttk.Entry(top_bar, textvariable=self.gateway_token, width=16, show="*").grid(row=0, column=5, padx=3)

        self.btn_connect = ttk.Button(top_bar, text="Connect / Start Sync", command=self._toggle_connection)
        self.btn_connect.grid(row=0, column=6, padx=8)

        self.lbl_status = ttk.Label(top_bar, text="Status: DISCONNECTED", foreground="red", font=("Segoe UI", 9, "bold"))
        self.lbl_status.grid(row=0, column=7, padx=5)

        # Notebook Tabs
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)

        self.tab_entry = self._build_terminal_tab(self.entry_device)
        self.tab_exit = self._build_terminal_tab(self.exit_device)
        self.tab_logs = self._build_logs_tab()

        self.notebook.add(self.tab_entry, text="📍 Terminal 1: ENTRY (TrueFace 3000)")
        self.notebook.add(self.tab_exit, text="🚪 Terminal 2: EXIT (TrueFace 3000)")
        self.notebook.add(self.tab_logs, text="📜 Protocol & Traffic Logs")

    def _build_terminal_tab(self, device: DeviceState) -> ttk.Frame:
        tab = ttk.Frame(self.notebook, padding=10)

        ctrl_frame = ttk.Frame(tab)
        ctrl_frame.pack(fill=tk.X, pady=(0, 10))

        ttk.Label(ctrl_frame, text=f"{device.device_name} ({device.role})", font=("Segoe UI", 12, "bold")).pack(side=tk.LEFT)
        
        btn_enroll = ttk.Button(ctrl_frame, text="➕ Local Terminal Enrollment", 
                                command=lambda d=device: self._open_enroll_dialog(d))
        btn_enroll.pack(side=tk.RIGHT, padx=5)

        split_pane = ttk.PanedWindow(tab, orient=tk.HORIZONTAL)
        split_pane.pack(fill=tk.BOTH, expand=True)

        left_frame = ttk.LabelFrame(split_pane, text="Device Roster (Users on Hardware)", padding=5)
        split_pane.add(left_frame, weight=3)

        columns = ("id", "name", "status", "valid_from", "valid_to")
        tree = ttk.Treeview(left_frame, columns=columns, show="headings", selectmode="browse")
        tree.heading("id", text="Device User ID")
        tree.heading("name", text="Full Name")
        tree.heading("status", text="Access Status")
        tree.heading("valid_from", text="Valid From")
        tree.heading("valid_to", text="Valid To")

        tree.column("id", width=95, anchor=tk.CENTER)
        tree.column("name", width=160)
        tree.column("status", width=90, anchor=tk.CENTER)
        tree.column("valid_from", width=100, anchor=tk.CENTER)
        tree.column("valid_to", width=100, anchor=tk.CENTER)

        tree.pack(fill=tk.BOTH, expand=True, side=tk.LEFT)
        scrollbar = ttk.Scrollbar(left_frame, orient=tk.VERTICAL, command=tree.yview)
        tree.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        right_frame = ttk.LabelFrame(split_pane, text="Selected User & Biometric Trigger", padding=10)
        split_pane.add(right_frame, weight=2)

        lbl_photo = ttk.Label(right_frame, text="[No Photo Enrolled]", anchor=tk.CENTER, relief=tk.SOLID, borderwidth=1)
        lbl_photo.config(width=22)
        lbl_photo.pack(pady=5, ipadx=10, ipady=15)

        lbl_user_details = ttk.Label(right_frame, text="Select a user from the roster table", font=("Segoe UI", 10), justify=tk.CENTER)
        lbl_user_details.pack(pady=5)

        action_box = ttk.LabelFrame(right_frame, text="Simulate Biometric Scan", padding=10)
        action_box.pack(fill=tk.X, pady=10)

        btn_punch_grant = ttk.Button(
            action_box, 
            text=f"🟢 Punch {device.role} (Grant Access)", 
            command=lambda d=device, t=tree: self._trigger_punch(d, t, granted=True)
        )
        btn_punch_grant.pack(fill=tk.X, pady=3)

        btn_punch_deny = ttk.Button(
            action_box, 
            text=f"🔴 Punch {device.role} (Deny Access)", 
            command=lambda d=device, t=tree: self._trigger_punch(d, t, granted=False)
        )
        btn_punch_deny.pack(fill=tk.X, pady=3)

        btn_edit_local = ttk.Button(
            right_frame,
            text="✏️ Edit User on Terminal",
            command=lambda d=device, t=tree: self._edit_local_user(d, t)
        )
        btn_edit_local.pack(fill=tk.X, pady=3)

        setattr(device, "tree", tree)
        setattr(device, "lbl_photo", lbl_photo)
        setattr(device, "lbl_user_details", lbl_user_details)

        tree.bind("<<TreeviewSelect>>", lambda evt, d=device: self._on_user_selected(d))
        return tab

    def _build_logs_tab(self) -> ttk.Frame:
        tab = ttk.Frame(self.notebook, padding=10)
        self.txt_logs = tk.Text(tab, wrap=tk.WORD, bg="#1e1e1e", fg="#00ff66", font=("Consolas", 10))
        self.txt_logs.pack(fill=tk.BOTH, expand=True, side=tk.LEFT)
        scrollbar = ttk.Scrollbar(tab, orient=tk.VERTICAL, command=self.txt_logs.yview)
        self.txt_logs.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        return tab

    def log(self, message: str):
        timestamp = datetime.now().strftime("%H:%M:%S")
        log_line = f"[{timestamp}] {message}\n"
        self.txt_logs.insert(tk.END, log_line)
        self.txt_logs.see(tk.END)

    def _toggle_connection(self):
        if self.is_connected:
            self.is_connected = False
            self.btn_connect.config(text="Connect / Start Sync")
            self.lbl_status.config(text="Status: DISCONNECTED", foreground="red")
            self.log("Gateway poller stopped.")
        else:
            self.client = GatewayClient(
                self.backend_url.get().strip(),
                self.gateway_id.get().strip(),
                self.gateway_token.get().strip()
            )
            self.is_connected = True
            self.btn_connect.config(text="Disconnect")
            self.lbl_status.config(text="Status: CONNECTED & POLLING", foreground="green")
            self.log(f"Connecting to {self.backend_url.get()} as Gateway {self.gateway_id.get()}...")

            self.poller_thread = threading.Thread(target=self._poll_loop, daemon=True)
            self.poller_thread.start()

    def _poll_loop(self):
        self.client.send_message("REGISTER_GATEWAY", {"agentVersion": "interactive-stub-1.0"})
        self.log("REGISTER_GATEWAY sent.")

        while self.is_connected:
            try:
                self.client.send_message("HEARTBEAT", {})
                status, raw = self.client.request("GET", "/internal/gateway/commands")
                if status == 200:
                    commands = json.loads(raw) if raw else []
                    for cmd in commands:
                        self.after(0, self._handle_command, cmd)
                elif status >= 400:
                    self.log(f"Command poll failed HTTP {status}: {raw}")
            except Exception as ex:
                self.log(f"Poll loop error: {ex}")

            time.sleep(2.0)

    def _handle_command(self, cmd: dict):
        ctype = cmd.get("type")
        payload = cmd.get("payload") or {}
        device_id = cmd.get("deviceId")
        corr_id = cmd.get("correlationId")
        user_id = str(payload.get("deviceUserId", ""))

        self.log(f"📥 Received Command: {ctype} for User {user_id} on Device {device_id}")

        devices = [self.entry_device, self.exit_device] if not device_id else (
            [self.entry_device] if device_id == self.entry_device.device_id else [self.exit_device]
        )

        result = {"ok": True}

        for dev in devices:
            if ctype == "CREATE_USER":
                dev.add_or_update_user(user_id, payload.get("name", "New Member"), True,
                                       payload.get("validFrom"), payload.get("validTo"))
            elif ctype == "UPDATE_USER" or ctype == "UPDATE_VALIDITY":
                dev.add_or_update_user(user_id, payload.get("name"), payload.get("enabled", True),
                                       payload.get("validFrom"), payload.get("validTo"))
            elif ctype == "DISABLE_USER":
                if user_id in dev.users:
                    dev.users[user_id].enabled = False
            elif ctype == "ENABLE_USER":
                if user_id in dev.users:
                    dev.users[user_id].enabled = True
            elif ctype == "REMOVE_USER":
                dev.remove_user(user_id)
            elif ctype == "UPSERT_FACE":
                photo_b64 = payload.get("photoBase64") or payload.get("image")
                if photo_b64:
                    try:
                        photo_bytes = base64.b64decode(photo_b64)
                        dev.set_user_photo(user_id, photo_bytes)
                        self.log(f"📷 Face photo updated for user {user_id} ({len(photo_bytes)} bytes)")
                    except Exception as err:
                        self.log(f"Error decoding photo base64: {err}")
                result["faceVersion"] = payload.get("faceVersion", 1)

            self._refresh_device_table(dev)

        self.client.send_message("SYNC_RESULT", result, device_id, corr_id)
        self.log(f"📤 Sent SYNC_RESULT: {result}")

    def _refresh_device_table(self, dev: DeviceState):
        tree: ttk.Treeview = getattr(dev, "tree")
        for item in tree.get_children():
            tree.delete(item)

        for u in dev.users.values():
            status_str = "🟢 Active" if u.enabled else "🔴 Disabled"
            tree.insert("", tk.END, iid=u.user_id, values=(
                u.user_id, u.name, status_str, u.valid_from or "-", u.valid_to or "-"
            ))

    def _on_user_selected(self, dev: DeviceState):
        tree: ttk.Treeview = getattr(dev, "tree")
        lbl_photo: ttk.Label = getattr(dev, "lbl_photo")
        lbl_details: ttk.Label = getattr(dev, "lbl_user_details")

        selected = tree.selection()
        if not selected:
            return

        user_id = selected[0]
        user = dev.users.get(user_id)
        if not user:
            return

        lbl_details.config(text=f"User: {user.name}\nID: {user.user_id}\nStatus: {'Active' if user.enabled else 'Disabled'}")

        if user.photo_image:
            lbl_photo.config(image=user.photo_image, text="")
        else:
            lbl_photo.config(image="", text="[No Photo Enrolled]")

    def _trigger_punch(self, dev: DeviceState, tree: ttk.Treeview, granted: bool):
        selected = tree.selection()
        if not selected:
            messagebox.showwarning("Select Member", "Please select a member from the table to simulate punch.")
            return

        user_id = selected[0]
        user = dev.users.get(user_id)
        if not user:
            return

        if not self.is_connected:
            messagebox.showerror("Not Connected", "Please connect to the backend first.")
            return

        payload = {
            "deviceUserId": user_id,
            "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "method": "FACE",
            "granted": granted and user.enabled,
            "recNo": int(time.time()),
        }

        self.client.send_message("DEVICE_EVENT", payload, dev.device_id)
        self.log(f"⚡ Punched {dev.role} for {user.name} ({user_id}) -> Granted: {payload['granted']}")

    def _open_enroll_dialog(self, dev: DeviceState):
        win = tk.Toplevel(self)
        win.title(f"Local Terminal Enrolment — {dev.device_name}")
        win.geometry("400x320")

        ttk.Label(win, text="Device User ID:").pack(pady=2)
        txt_id = ttk.Entry(win)
        txt_id.pack(pady=2)

        ttk.Label(win, text="Full Name:").pack(pady=2)
        txt_name = ttk.Entry(win)
        txt_name.pack(pady=2)

        photo_data = {"bytes": None}

        def pick_photo():
            path = filedialog.askopenfilename(filetypes=[("Images", "*.jpg *.jpeg *.png")])
            if path:
                with open(path, "rb") as f:
                    photo_data["bytes"] = f.read()
                lbl_file.config(text=os.path.basename(path))

        btn_pic = ttk.Button(win, text="Select Photo File", command=pick_photo)
        btn_pic.pack(pady=5)
        lbl_file = ttk.Label(win, text="No file chosen")
        lbl_file.pack()

        def save_and_sync():
            uid = txt_id.get().strip()
            name = txt_name.get().strip()
            if not uid or not name:
                messagebox.showerror("Validation", "User ID and Name are required.")
                return

            user = dev.add_or_update_user(uid, name, True)
            if photo_data["bytes"]:
                dev.set_user_photo(uid, photo_data["bytes"])

            self._refresh_device_table(dev)

            if self.is_connected:
                payload = {
                    "deviceUserId": uid,
                    "name": name,
                    "enabled": True,
                    "changedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                }
                self.client.send_message("DEVICE_USER_CHANGED", payload, dev.device_id)
                self.log(f"📤 Sent local enrollment upstream for {name} ({uid})")

            win.destroy()

        ttk.Button(win, text="Save & Sync to Cloud", command=save_and_sync).pack(pady=15)

    def _edit_local_user(self, dev: DeviceState, tree: ttk.Treeview):
        selected = tree.selection()
        if not selected:
            messagebox.showwarning("Select Member", "Select a user to edit.")
            return

        user_id = selected[0]
        user = dev.users[user_id]

        win = tk.Toplevel(self)
        win.title(f"Edit User on Terminal — {user.name}")
        win.geometry("380x280")

        ttk.Label(win, text=f"Device User ID: {user_id}").pack(pady=5)

        ttk.Label(win, text="Name:").pack()
        txt_name = ttk.Entry(win)
        txt_name.insert(0, user.name)
        txt_name.pack(pady=2)

        var_enabled = tk.BooleanVar(value=user.enabled)
        chk_enabled = ttk.Checkbutton(win, text="Access Enabled", variable=var_enabled)
        chk_enabled.pack(pady=5)

        def save():
            user.name = txt_name.get().strip()
            user.enabled = var_enabled.get()
            self._refresh_device_table(dev)

            if self.is_connected:
                payload = {
                    "deviceUserId": user_id,
                    "name": user.name,
                    "enabled": user.enabled,
                    "changedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                }
                self.client.send_message("DEVICE_USER_CHANGED", payload, dev.device_id)
                self.log(f"📤 Sent local edit upstream for {user.name} ({user_id})")

            win.destroy()

        ttk.Button(win, text="Update & Push Upstream", command=save).pack(pady=15)


if __name__ == "__main__":
    app = BiometricStubApp()
    app.mainloop()
