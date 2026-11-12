"""
phoneel Binary Protocol
=======================
Little-endian binary packet definitions for sensor input, button events,
and vehicle telemetry streaming.

Packet Types:
  0x01 = Sensor Data       (17 bytes) — Phone -> PC
  0x02 = Button Press       (3 bytes)
  0x03 = Button Release     (3 bytes)
  0x04 = Touch Data        (13 bytes)
  0x05 = Discovery Ping     (5 bytes)
  0x06 = Discovery Pong    (variable)
  0x07 = Calibrate Request  (1 byte)
  0x08 = Mode Switch        (2 bytes)
  0x09 = Heartbeat          (5 bytes)
  0x0A = Telemetry + FFB   (23 bytes) — PC -> Phone
"""

import struct
from dataclasses import dataclass
from enum import IntEnum
from typing import Optional

# ─── Packet Types ──────────────────────────────────────────
class PacketType(IntEnum):
    SENSOR      = 0x01
    BTN_PRESS   = 0x02
    BTN_RELEASE = 0x03
    TOUCH       = 0x04
    DISC_PING   = 0x05
    DISC_PONG   = 0x06
    CALIBRATE   = 0x07
    MODE_SWITCH = 0x08
    HEARTBEAT   = 0x09
    TELEMETRY   = 0x0A

# ─── Control Modes ─────────────────────────────────────────
class ControlMode(IntEnum):
    STEERING  = 0x01
    GAMEPAD   = 0x02
    MOUSE     = 0x03
    MEDIA     = 0x04

# ─── Button IDs ────────────────────────────────────────────
class ButtonID(IntEnum):
    A      = 0x0001
    B      = 0x0002
    X      = 0x0003
    Y      = 0x0004
    LB     = 0x0005
    RB     = 0x0006
    LT     = 0x0007
    RT     = 0x0008
    START  = 0x0009
    SELECT = 0x000A
    L3     = 0x000B
    R3     = 0x000C
    DPAD_U = 0x000D
    DPAD_D = 0x000E
    DPAD_L = 0x000F
    DPAD_R = 0x0010

# ─── Telemetry Flags ───────────────────────────────────────
TELEM_FLAG_ACTIVE      = 1 << 0
TELEM_FLAG_PIT_LIMITER = 1 << 1
TELEM_FLAG_DRS         = 1 << 2
TELEM_FLAG_TC          = 1 << 3
TELEM_FLAG_ABS         = 1 << 4
TELEM_FLAG_SHIFT_CUE   = 1 << 5

# ─── Data Classes ──────────────────────────────────────────
@dataclass
class SensorPacket:
    yaw: float
    pitch: float
    roll: float
    timestamp: int  # ms

@dataclass
class ButtonPacket:
    pressed: bool
    button_id: int

@dataclass
class TouchPacket:
    x: float  # 0.0 - 1.0
    y: float  # 0.0 - 1.0
    timestamp: int

@dataclass
class ModePacket:
    mode: ControlMode

@dataclass
class HeartbeatPacket:
    timestamp: int

@dataclass
class TelemetryPacket:
    speed_kmh: float
    rpm: int
    max_rpm: int
    gear: int          # 0=R, 1=N, 2=1st, 3=2nd...
    flags: int
    lap_time_ms: int
    best_lap_ms: int
    position: int
    ffb_slip: int = 0  # 0..255 rear/front tire slip haptic intensity
    ffb_kerb: int = 0  # 0..255 kerb/road vibration haptic intensity

# ─── Struct Formats (little-endian) ───────────────────────
FMT_SENSOR    = '<BfffI'       # type + 3 floats + uint32 = 17 bytes
FMT_BUTTON    = '<BH'          # type + uint16            =  3 bytes
FMT_TOUCH     = '<BffI'        # type + 2 floats + uint32 = 13 bytes
FMT_DISC_PING = '<BI'          # type + uint32 port       =  5 bytes
FMT_MODE      = '<BB'          # type + mode byte         =  2 bytes
FMT_HEARTBEAT = '<BI'          # type + uint32 timestamp   =  5 bytes
FMT_CALIBRATE = '<B'           # type only                =  1 byte
FMT_TELEMETRY = '<BfHHbBIIHBB' # 21 + 2 FFB bytes         = 23 bytes

# ─── Sizes ─────────────────────────────────────────────────
SIZE_SENSOR    = struct.calcsize(FMT_SENSOR)     # 17
SIZE_BUTTON    = struct.calcsize(FMT_BUTTON)     #  3
SIZE_TOUCH     = struct.calcsize(FMT_TOUCH)      # 13
SIZE_DISC_PING = struct.calcsize(FMT_DISC_PING)  #  5
SIZE_MODE      = struct.calcsize(FMT_MODE)       #  2
SIZE_HEARTBEAT = struct.calcsize(FMT_HEARTBEAT)  #  5
SIZE_TELEMETRY = struct.calcsize(FMT_TELEMETRY)  # 23

# ─── Defaults ──────────────────────────────────────────────
DEFAULT_PORT       = 9999
DISCOVERY_PORT     = 9998
MAX_PACKET_SIZE    = 64
PROTOCOL_VERSION   = 2

# ─── Parser ────────────────────────────────────────────────
def parse_packet(data: bytes):
    """Parse raw bytes into a typed packet. Returns None if invalid."""
    if not data:
        return None

    ptype = data[0]

    try:
        if ptype == PacketType.SENSOR and len(data) >= SIZE_SENSOR:
            _, yaw, pitch, roll, ts = struct.unpack(FMT_SENSOR, data[:SIZE_SENSOR])
            return SensorPacket(yaw, pitch, roll, ts)

        elif ptype == PacketType.BTN_PRESS and len(data) >= SIZE_BUTTON:
            _, btn_id = struct.unpack(FMT_BUTTON, data[:SIZE_BUTTON])
            return ButtonPacket(pressed=True, button_id=btn_id)

        elif ptype == PacketType.BTN_RELEASE and len(data) >= SIZE_BUTTON:
            _, btn_id = struct.unpack(FMT_BUTTON, data[:SIZE_BUTTON])
            return ButtonPacket(pressed=False, button_id=btn_id)

        elif ptype == PacketType.TOUCH and len(data) >= SIZE_TOUCH:
            _, x, y, ts = struct.unpack(FMT_TOUCH, data[:SIZE_TOUCH])
            return TouchPacket(x, y, ts)

        elif ptype == PacketType.MODE_SWITCH and len(data) >= SIZE_MODE:
            _, mode = struct.unpack(FMT_MODE, data[:SIZE_MODE])
            return ModePacket(ControlMode(mode))

        elif ptype == PacketType.HEARTBEAT and len(data) >= SIZE_HEARTBEAT:
            _, ts = struct.unpack(FMT_HEARTBEAT, data[:SIZE_HEARTBEAT])
            return HeartbeatPacket(ts)

        elif ptype == PacketType.CALIBRATE:
            return "CALIBRATE"

        elif ptype == PacketType.TELEMETRY and len(data) >= SIZE_TELEMETRY:
            _, speed, rpm, max_rpm, gear, flags, lap_ms, best_ms, pos, ffb_slip, ffb_kerb = struct.unpack(
                FMT_TELEMETRY, data[:SIZE_TELEMETRY]
            )
            return TelemetryPacket(speed, rpm, max_rpm, gear, flags, lap_ms, best_ms, pos, ffb_slip, ffb_kerb)

    except (struct.error, ValueError):
        return None

    return None

# ─── Builder ───────────────────────────────────────────────
def build_sensor_packet(yaw: float, pitch: float, roll: float, timestamp: int) -> bytes:
    return struct.pack(FMT_SENSOR, PacketType.SENSOR, yaw, pitch, roll, timestamp)

def build_button_packet(pressed: bool, button_id: int) -> bytes:
    ptype = PacketType.BTN_PRESS if pressed else PacketType.BTN_RELEASE
    return struct.pack(FMT_BUTTON, ptype, button_id)

def build_touch_packet(x: float, y: float, timestamp: int) -> bytes:
    return struct.pack(FMT_TOUCH, PacketType.TOUCH, x, y, timestamp)

def build_discovery_pong(hostname: str) -> bytes:
    return bytes([PacketType.DISC_PONG]) + hostname.encode('utf-8')

def build_heartbeat(timestamp: int) -> bytes:
    return struct.pack(FMT_HEARTBEAT, PacketType.HEARTBEAT, timestamp)

def build_telemetry_packet(
    speed_kmh: float,
    rpm: int,
    max_rpm: int,
    gear: int,
    flags: int,
    lap_time_ms: int = 0,
    best_lap_ms: int = 0,
    position: int = 0,
    ffb_slip: int = 0,
    ffb_kerb: int = 0
) -> bytes:
    return struct.pack(
        FMT_TELEMETRY,
        PacketType.TELEMETRY,
        float(max(0.0, speed_kmh)),
        min(65535, max(0, int(rpm))),
        min(65535, max(1000, int(max_rpm))),
        max(-1, min(15, int(gear))),
        min(255, max(0, int(flags))),
        max(0, int(lap_time_ms)),
        max(0, int(best_lap_ms)),
        min(65535, max(0, int(position))),
        min(255, max(0, int(ffb_slip))),
        min(255, max(0, int(ffb_kerb))),
    )
