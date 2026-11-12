"""
phoneel Virtual Gamepad
========================
ViGEm-based Xbox 360 controller emulation.
Uses 'vgamepad' library which wraps ViGEmBus driver.

Requirements:
  - ViGEmBus driver installed: https://github.com/nefarius/ViGEmBus/releases
  - pip install vgamepad
"""

import math
from typing import Optional

try:
    import vgamepad as vg
    VGAMEPAD_AVAILABLE = True
except ImportError:
    VGAMEPAD_AVAILABLE = False
    print("[phoneel] WARNING: vgamepad not installed. Run: pip install vgamepad")
    print("[phoneel] Also install ViGEmBus driver from https://github.com/nefarius/ViGEmBus/releases")


# Xbox 360 button mapping
XBOX_BUTTONS = {
    0x0001: 'A',      # A
    0x0002: 'B',      # B
    0x0003: 'X',      # X
    0x0004: 'Y',      # Y
    0x0005: 'LB',     # Left Bumper
    0x0006: 'RB',     # Right Bumper
    0x0009: 'START',  # Start
    0x000A: 'BACK',   # Back/Select
    0x000B: 'L3',     # Left Stick Click
    0x000C: 'R3',     # Right Stick Click
    0x000D: 'DPAD_UP',
    0x000E: 'DPAD_DOWN',
    0x000F: 'DPAD_LEFT',
    0x0010: 'DPAD_RIGHT',
}

# Map button names to vgamepad constants
def _get_button_flag(name: str):
    """Get vgamepad button flag from button name."""
    if not VGAMEPAD_AVAILABLE:
        return None
    mapping = {
        'A':          vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
        'B':          vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
        'X':          vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
        'Y':          vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        'LB':         vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        'RB':         vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        'START':      vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
        'BACK':       vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        'L3':         vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        'R3':         vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
        'DPAD_UP':    vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        'DPAD_DOWN':  vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        'DPAD_LEFT':  vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        'DPAD_RIGHT': vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
    }
    return mapping.get(name)


class VirtualGamepad:
    """Virtual Xbox 360 controller via ViGEm."""

    def __init__(self):
        self._pad = None
        self._active = False

    @property
    def is_available(self) -> bool:
        return VGAMEPAD_AVAILABLE

    @property
    def is_active(self) -> bool:
        return self._active and self._pad is not None

    def connect(self) -> bool:
        """Create and connect virtual Xbox 360 controller."""
        if not VGAMEPAD_AVAILABLE:
            print("[phoneel] Cannot create gamepad: vgamepad not available")
            return False

        try:
            self._pad = vg.VX360Gamepad()
            self._active = True
            print("[phoneel] Virtual Xbox 360 controller connected")
            return True
        except Exception as e:
            print(f"[phoneel] Failed to create gamepad: {e}")
            print("[phoneel] Make sure ViGEmBus driver is installed!")
            return False

    def disconnect(self):
        """Disconnect and destroy virtual controller."""
        if self._pad:
            try:
                self._pad.reset()
                self._pad.update()
            except Exception:
                pass
            self._pad = None
        self._active = False
        print("[phoneel] Virtual gamepad disconnected")

    # ─── Axis Control ──────────────────────────────────────
    def set_left_stick(self, x: float, y: float):
        """Set left stick position. Values in range [-1.0, 1.0]."""
        if not self.is_active:
            return
        x_val = self._float_to_short(x)
        y_val = self._float_to_short(y)
        self._pad.left_joystick(x_value=x_val, y_value=y_val)

    def set_right_stick(self, x: float, y: float):
        """Set right stick position. Values in range [-1.0, 1.0]."""
        if not self.is_active:
            return
        x_val = self._float_to_short(x)
        y_val = self._float_to_short(y)
        self._pad.right_joystick(x_value=x_val, y_value=y_val)

    def set_left_trigger(self, value: float):
        """Set left trigger. Value in range [0.0, 1.0]."""
        if not self.is_active:
            return
        self._pad.left_trigger_float(value_float=max(0.0, min(1.0, value)))

    def set_right_trigger(self, value: float):
        """Set right trigger. Value in range [0.0, 1.0]."""
        if not self.is_active:
            return
        self._pad.right_trigger_float(value_float=max(0.0, min(1.0, value)))

    # ─── Button Control ────────────────────────────────────
    def press_button(self, button_id: int):
        """Press a button by its protocol ID."""
        if not self.is_active:
            return
        name = XBOX_BUTTONS.get(button_id)
        if name:
            flag = _get_button_flag(name)
            if flag:
                self._pad.press_button(button=flag)

    def release_button(self, button_id: int):
        """Release a button by its protocol ID."""
        if not self.is_active:
            return
        name = XBOX_BUTTONS.get(button_id)
        if name:
            flag = _get_button_flag(name)
            if flag:
                self._pad.release_button(button=flag)

    # ─── Steering Mode ─────────────────────────────────────
    def set_steering(self, steering: float, throttle: float = 0.0, brake: float = 0.0):
        """
        Steering wheel mode mapping:
          steering → left stick X
          throttle → right trigger
          brake    → left trigger
        """
        if not self.is_active:
            return
        self.set_left_stick(steering, 0.0)
        self.set_right_trigger(max(0.0, min(1.0, throttle)))
        self.set_left_trigger(max(0.0, min(1.0, brake)))

    # ─── Gamepad Mode ──────────────────────────────────────
    def set_gamepad_axes(self, lx: float, ly: float, rx: float = 0.0, ry: float = 0.0):
        """Set both joystick axes at once."""
        if not self.is_active:
            return
        self.set_left_stick(lx, ly)
        self.set_right_stick(rx, ry)

    # ─── Update (must call after setting values) ───────────
    def update(self):
        """Push all pending changes to the virtual controller."""
        if not self.is_active:
            return
        try:
            self._pad.update()
        except Exception:
            pass

    def reset(self):
        """Reset all axes and buttons to neutral."""
        if not self.is_active:
            return
        self._pad.reset()
        self._pad.update()

    # ─── Helpers ───────────────────────────────────────────
    @staticmethod
    def _float_to_short(value: float) -> int:
        """Convert float [-1.0, 1.0] to int16 [-32768, 32767]."""
        clamped = max(-1.0, min(1.0, value))
        if clamped >= 0:
            return int(clamped * 32767)
        else:
            return int(clamped * 32768)
