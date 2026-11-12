"""
phoneel Auto-ADB USB Watchdog
==============================
Eliminates the need for `usb_setup.bat`.
Automatically detects connected Android devices via USB and configures
`adb reverse tcp:9999 tcp:9999` in the background.
"""

import os
import sys
import subprocess
import threading
import time
from typing import Optional


def _get_adb_path() -> Optional[str]:
    """Locate adb.exe in local tools folder or system PATH."""
    base_dir = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(base_dir, "tools", "adb.exe"),
        os.path.join(os.path.dirname(base_dir), "pc", "tools", "adb.exe"),
        os.path.join(os.getcwd(), "tools", "adb.exe"),
        os.path.join(os.getcwd(), "pc", "tools", "adb.exe"),
    ]
    if getattr(sys, 'frozen', False):
        exe_dir = os.path.dirname(sys.executable)
        candidates.insert(0, os.path.join(exe_dir, "tools", "adb.exe"))
        candidates.insert(1, os.path.join(exe_dir, "pc", "tools", "adb.exe"))
        if hasattr(sys, '_MEIPASS'):
            candidates.insert(0, os.path.join(sys._MEIPASS, "tools", "adb.exe"))

    for path in candidates:
        if os.path.isfile(path):
            return path
    return "adb"


class AdbWatchdog:
    """
    Background daemon that monitors USB Android devices and maintains
    `adb reverse tcp:<port> tcp:<port>` automatically.
    """

    def __init__(self, port: int = 9999):
        self.port = port
        self.adb_path = _get_adb_path()
        self.usb_device_id: Optional[str] = None
        self.usb_ready: bool = False
        self.status_text: str = "Searching USB..."
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._forwarded_serial: Optional[str] = None

    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True, name="adb-watchdog")
        self._thread.start()

    def stop(self):
        self._running = False

    def force_refresh(self) -> bool:
        """Manually trigger an immediate ADB reverse check."""
        self._forwarded_serial = None
        return self._check_once()

    def _run_cmd(self, args: list[str], timeout: float = 3.0) -> Optional[str]:
        try:
            creationflags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
            res = subprocess.run(
                [self.adb_path] + args,
                capture_output=True,
                text=True,
                timeout=timeout,
                creationflags=creationflags,
            )
            if res.returncode == 0:
                return res.stdout
            return None
        except Exception:
            return None

    def _check_once(self) -> bool:
        out = self._run_cmd(["devices"])
        if out is None:
            self.usb_device_id = None
            self.usb_ready = False
            self.status_text = "ADB Not Found"
            return False

        devices = []
        unauthorized = False
        offline = False
        for line in out.strip().splitlines()[1:]:
            parts = line.strip().split()
            if len(parts) >= 2:
                serial, state = parts[0], parts[1]
                if state == "device":
                    devices.append(serial)
                elif state == "unauthorized":
                    unauthorized = True
                elif state == "offline":
                    offline = True

        if offline and not devices:
            self._run_cmd(["reconnect", "offline"])

        if not devices:
            self.usb_device_id = None
            self.usb_ready = False
            self._forwarded_serial = None
            if unauthorized:
                self.status_text = "Allow USB Debugging on Phone"
            elif offline:
                self.status_text = "Reconnecting USB Device..."
            else:
                self.status_text = "No USB Phone Plugged"
            return False

        serial = devices[0]
        self.usb_device_id = serial

        # Ensure `adb reverse` is active for this device
        if self._forwarded_serial != serial or not self.usb_ready:
            rev = self._run_cmd(["-s", serial, "reverse", f"tcp:{self.port}", f"tcp:{self.port}"])
            if rev is not None:
                self._forwarded_serial = serial
                self.usb_ready = True
                self.status_text = f"USB Tunnel Active ({serial})"
                print(f"[phoneel] Auto-ADB: USB reverse tcp:{self.port} active on {serial}")
                return True
            else:
                self.usb_ready = False
                self.status_text = f"USB Reverse Failed ({serial})"
                return False

        return True

    def _loop(self):
        while self._running:
            self._check_once()
            time.sleep(2.5)
