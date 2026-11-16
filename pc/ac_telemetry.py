"""
phoneel Assetto Corsa Shared Memory Reader
==========================================
Reads vehicle physics and session state from Assetto Corsa shared memory
(`Local\\acpmf_physics`, `Local\\acpmf_graphics`, `Local\\acpmf_static`)
and computes tire-slip and suspension/shift haptic feedback amplitudes (0..255).
"""

import ctypes
from ctypes import wintypes
import mmap
import sys
import time
from dataclasses import dataclass
from protocol import (
    TELEM_FLAG_ACTIVE,
    TELEM_FLAG_PIT_LIMITER,
    TELEM_FLAG_DRS,
    TELEM_FLAG_TC,
    TELEM_FLAG_ABS,
    TELEM_FLAG_SHIFT_CUE,
)

# Windows API constants for checking if a shared memory mapping already exists
FILE_MAP_READ = 0x0004
if sys.platform == "win32":
    _kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _OpenFileMappingW = _kernel32.OpenFileMappingW
    _OpenFileMappingW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
    _OpenFileMappingW.restype = wintypes.HANDLE
    _CloseHandle = _kernel32.CloseHandle
    _CloseHandle.argtypes = [wintypes.HANDLE]
    _CloseHandle.restype = wintypes.BOOL


def _mapping_exists_by_ac(name: str) -> bool:
    """Return True ONLY if Assetto Corsa has already created the shared memory block."""
    if sys.platform != "win32":
        return False
    handle = _OpenFileMappingW(FILE_MAP_READ, False, name)
    if handle:
        _CloseHandle(handle)
        return True
    return False


# ─── Assetto Corsa Shared Memory C-Structs ───

class SPageFilePhysics(ctypes.Structure):
    _pack_ = 4
    _fields_ = [
        ('packetId', ctypes.c_int32),
        ('gas', ctypes.c_float),
        ('brake', ctypes.c_float),
        ('fuel', ctypes.c_float),
        ('gear', ctypes.c_int32),         # 0=R, 1=N, 2=1st, 3=2nd, ...
        ('rpms', ctypes.c_int32),
        ('steerAngle', ctypes.c_float),
        ('speedKmh', ctypes.c_float),
        ('velocity', ctypes.c_float * 3),
        ('accG', ctypes.c_float * 3),
        ('wheelSlip', ctypes.c_float * 4),
        ('wheelLoad', ctypes.c_float * 4),
        ('wheelsPressure', ctypes.c_float * 4),
        ('wheelAngularSpeed', ctypes.c_float * 4),
        ('tyreWear', ctypes.c_float * 4),
        ('tyreDirtyLevel', ctypes.c_float * 4),
        ('tyreCoreTemperature', ctypes.c_float * 4),
        ('camberRAD', ctypes.c_float * 4),
        ('suspensionTravel', ctypes.c_float * 4),
        ('drs', ctypes.c_float),
        ('tc', ctypes.c_float),
        ('heading', ctypes.c_float),
        ('pitch', ctypes.c_float),
        ('roll', ctypes.c_float),
        ('cgHeight', ctypes.c_float),
        ('carDamage', ctypes.c_float * 5),
        ('numberOfTyresOut', ctypes.c_int32),
        ('pitLimiterOn', ctypes.c_int32),
        ('abs', ctypes.c_float),
    ]


class SPageFileGraphics(ctypes.Structure):
    _pack_ = 4
    _fields_ = [
        ('packetId', ctypes.c_int32),
        ('status', ctypes.c_int32),       # 0=OFF, 1=REPLAY, 2=LIVE, 3=PAUSE
        ('session', ctypes.c_int32),
        ('currentTime', ctypes.c_wchar * 15),
        ('lastTime', ctypes.c_wchar * 15),
        ('bestTime', ctypes.c_wchar * 15),
        ('split', ctypes.c_wchar * 15),
        ('completedLaps', ctypes.c_int32),
        ('position', ctypes.c_int32),
        ('iCurrentTime', ctypes.c_int32), # ms
        ('iLastTime', ctypes.c_int32),    # ms
        ('iBestTime', ctypes.c_int32),    # ms
    ]


class SPageFileStatic(ctypes.Structure):
    _pack_ = 4
    _fields_ = [
        ('smVersion', ctypes.c_wchar * 15),
        ('acVersion', ctypes.c_wchar * 15),
        ('numberOfSessions', ctypes.c_int32),
        ('numCars', ctypes.c_int32),
        ('carModel', ctypes.c_wchar * 33),
        ('track', ctypes.c_wchar * 33),
        ('playerName', ctypes.c_wchar * 33),
        ('playerSurname', ctypes.c_wchar * 33),
        ('playerNick', ctypes.c_wchar * 33),
        ('sectorCount', ctypes.c_int32),
        ('maxTorque', ctypes.c_float),
        ('maxPower', ctypes.c_float),
        ('maxRpm', ctypes.c_int32),
        ('maxFuel', ctypes.c_float),
    ]


@dataclass
class TelemetryState:
    ac_active: bool = False
    car_model: str = ""
    track_name: str = ""
    speed_kmh: float = 0.0
    rpm: int = 0
    max_rpm: int = 8500
    gear: int = 1          # 0=R, 1=N, 2=1st, 3=2nd...
    flags: int = 0
    lap_time_ms: int = 0
    best_lap_ms: int = 0
    position: int = 0
    ffb_slip: int = 0      # 0..255 tire slip / oversteer vibration
    ffb_kerb: int = 0      # 0..255 kerb / shift / road vibration


class ACTelemetryReader:
    """
    Safe, non-intrusive Assetto Corsa Shared Memory reader + Haptic FFB synthesizer.
    """

    def __init__(self):
        self._phys_mmap = None
        self._gfx_mmap = None
        self._stat_mmap = None
        self._last_packet_id = -1
        self._last_active_time = 0.0
        self._last_probe_time = 0.0
        self._peak_rpm_seen = 8000
        self._last_ac_gear = 1
        self._shift_pulse_frames = 0
        self._last_susp = [0.0, 0.0, 0.0, 0.0]
        self.state = TelemetryState()

        # Synthetic fallback state (reactive to gas/brake/gear buttons when AC is not active)
        self.sim_gear = 2      # starts in 1st gear (2)
        self.sim_speed = 0.0
        self.sim_rpm = 1200

    def _ensure_mapped(self, now: float) -> bool:
        if self._phys_mmap is not None and self._gfx_mmap is not None and self._stat_mmap is not None:
            return True

        if now - self._last_probe_time < 1.0:
            return False
        self._last_probe_time = now

        if not (
            _mapping_exists_by_ac("Local\\acpmf_physics")
            and _mapping_exists_by_ac("Local\\acpmf_graphics")
            and _mapping_exists_by_ac("Local\\acpmf_static")
        ):
            return False

        try:
            self._phys_mmap = mmap.mmap(-1, ctypes.sizeof(SPageFilePhysics), "Local\\acpmf_physics", access=mmap.ACCESS_READ)
            self._gfx_mmap = mmap.mmap(-1, ctypes.sizeof(SPageFileGraphics), "Local\\acpmf_graphics", access=mmap.ACCESS_READ)
            self._stat_mmap = mmap.mmap(-1, ctypes.sizeof(SPageFileStatic), "Local\\acpmf_static", access=mmap.ACCESS_READ)
            return True
        except Exception:
            self.close()
            return False

    def read(self, gas_input: float = 0.0, brake_input: float = 0.0) -> TelemetryState:
        now = time.monotonic()

        if self._ensure_mapped(now):
            try:
                self._phys_mmap.seek(0)
                phys = SPageFilePhysics.from_buffer_copy(self._phys_mmap.read(ctypes.sizeof(SPageFilePhysics)))

                if phys.packetId > 0 and (
                    phys.packetId != self._last_packet_id
                    or self._last_packet_id < 0
                ):
                    self._last_packet_id = phys.packetId
                    self._last_active_time = now

                self._gfx_mmap.seek(0)
                gfx = SPageFileGraphics.from_buffer_copy(self._gfx_mmap.read(ctypes.sizeof(SPageFileGraphics)))

                # Keep session alive while paused in ESC menu (status == 3) or Replay (status == 1)
                if gfx.status in (1, 3) and self._last_active_time > 0:
                    self._last_active_time = now

                if (now - self._last_active_time) < 0.6 and self._last_packet_id > 0:
                    # If Assetto Corsa session is OFF (0), immediately release mmaps and return to standby
                    if gfx.status == 0:
                        self._last_packet_id = -1
                        self.close()
                    else:
                        self._stat_mmap.seek(0)
                        stat = SPageFileStatic.from_buffer_copy(self._stat_mmap.read(ctypes.sizeof(SPageFileStatic)))

                        max_rpm = stat.maxRpm if stat.maxRpm > 2000 else self._peak_rpm_seen
                        if phys.rpms > self._peak_rpm_seen:
                            self._peak_rpm_seen = phys.rpms
                        if max_rpm < phys.rpms:
                            max_rpm = phys.rpms

                        flags = TELEM_FLAG_ACTIVE
                        if phys.pitLimiterOn:
                            flags |= TELEM_FLAG_PIT_LIMITER
                        if phys.drs > 0.5:
                            flags |= TELEM_FLAG_DRS
                        if phys.tc > 0.01:
                            flags |= TELEM_FLAG_TC
                        if phys.abs > 0.01:
                            flags |= TELEM_FLAG_ABS

                        # Detect gear shift kick (only when LIVE = 2)
                        current_gear = int(phys.gear)
                        is_live_driving = (gfx.status == 2)
                        if current_gear != self._last_ac_gear:
                            self._last_ac_gear = current_gear
                            if is_live_driving:
                                self._shift_pulse_frames = 3  # 50ms crisp shift thump
                                flags |= TELEM_FLAG_SHIFT_CUE

                        # 1. Calculate Tire Slip / Oversteer Spin Warning (0..255) — ONLY when actively driving (status == 2)
                        ffb_slip = 0
                        ffb_kerb = 0
                        if is_live_driving:
                            rear_slip = max(abs(float(phys.wheelSlip[2])), abs(float(phys.wheelSlip[3])))
                            front_slip = max(abs(float(phys.wheelSlip[0])), abs(float(phys.wheelSlip[1])))
                            combined_slip = max(rear_slip * 1.15, front_slip * 0.65)
                            if phys.speedKmh > 5.0 and combined_slip > 0.85:
                                norm_slip = min(1.0, (combined_slip - 0.85) / 2.2)
                                ffb_slip = int(norm_slip * 255)
                            if phys.tc > 0.01 or phys.abs > 0.01:
                                ffb_slip = max(ffb_slip, 175)

                            # 2. Calculate Kerb / Suspension / Shift Vibration (0..255)
                            susp_delta = 0.0
                            for i in range(4):
                                st = float(phys.suspensionTravel[i])
                                susp_delta = max(susp_delta, abs(st - self._last_susp[i]))
                                self._last_susp[i] = st
                            vert_g = abs(float(phys.accG[1]))
                            if phys.speedKmh > 10.0 and (susp_delta > 0.0035 or vert_g > 1.35):
                                norm_k = min(1.0, (susp_delta * 140.0) + max(0.0, (vert_g - 1.35) * 0.45))
                                ffb_kerb = int(norm_k * 255)
                            if self._shift_pulse_frames > 0:
                                self._shift_pulse_frames -= 1
                                ffb_kerb = 255
                        else:
                            self._shift_pulse_frames = 0

                        self.state = TelemetryState(
                            ac_active=True,
                            car_model=stat.carModel.strip(),
                            track_name=stat.track.strip(),
                            speed_kmh=max(0.0, float(phys.speedKmh)),
                            rpm=max(0, int(phys.rpms)),
                            max_rpm=max(3000, int(max_rpm)),
                            gear=current_gear,
                            flags=flags,
                            lap_time_ms=max(0, int(gfx.iCurrentTime)),
                            best_lap_ms=max(0, int(gfx.iBestTime)),
                            position=max(0, int(gfx.position)),
                            ffb_slip=ffb_slip,
                            ffb_kerb=ffb_kerb,
                        )
                        return self.state
                elif (now - self._last_active_time) >= 0.6 and self._last_active_time > 0:
                    self.close()
            except Exception:
                self.close()

        # Fallback: Synthetic responsive cockpit telemetry when AC is not in a live session
        gear_ratios = {0: 45.0, 1: 0.0, 2: 68.0, 3: 115.0, 4: 162.0, 5: 208.0, 6: 255.0, 7: 298.0, 8: 335.0}
        top_speed_for_gear = gear_ratios.get(self.sim_gear, 200.0)

        if self.sim_gear == 1:
            target_rpm = 1100 + int(gas_input * 7400)
            self.sim_rpm = int(self.sim_rpm * 0.8 + target_rpm * 0.2)
            self.sim_speed = max(0.0, self.sim_speed - 0.5 - brake_input * 2.5)
        else:
            accel = (gas_input * 2.2) - (brake_input * 4.0) - 0.18
            self.sim_speed = max(0.0, min(top_speed_for_gear, self.sim_speed + accel))
            ratio = (self.sim_speed / max(1.0, top_speed_for_gear))
            target_rpm = int(1200 + (ratio * 7300) + (gas_input * 450))
            self.sim_rpm = max(1000, min(8800, int(self.sim_rpm * 0.75 + target_rpm * 0.25)))

        # Simulate wheelspin FFB in low gears with high throttle
        sim_slip = 0
        if self.sim_gear in (2, 3) and gas_input > 0.78:
            sim_slip = int(((gas_input - 0.78) / 0.22) * 200)
        sim_kerb = 240 if self._shift_pulse_frames > 0 else 0
        if self._shift_pulse_frames > 0:
            self._shift_pulse_frames -= 1

        self.state = TelemetryState(
            ac_active=False,
            car_model="SIMULATED DDU",
            track_name="",
            speed_kmh=self.sim_speed,
            rpm=self.sim_rpm,
            max_rpm=8500,
            gear=self.sim_gear,
            flags=0,
            lap_time_ms=0,
            best_lap_ms=0,
            position=1,
            ffb_slip=sim_slip,
            ffb_kerb=sim_kerb,
        )
        return self.state

    def on_shift_up(self):
        if self.sim_gear < 8:
            self.sim_gear += 1
            self._shift_pulse_frames = 2

    def on_shift_down(self):
        if self.sim_gear > 0:
            self.sim_gear -= 1
            self._shift_pulse_frames = 2

    def close(self):
        for m in (self._phys_mmap, self._gfx_mmap, self._stat_mmap):
            if m is not None:
                try:
                    m.close()
                except Exception:
                    pass
        self._phys_mmap = None
        self._gfx_mmap = None
        self._stat_mmap = None
        self._last_active_time = 0.0
