"""
phoneel Server
==============
Bidirectional sim-racing controller and telemetry server:
  - Client -> PC: Gyro steering, analog triggers, Xbox 360 buttons
  - PC -> Client: 60 Hz Assetto Corsa shared memory telemetry + haptic feedback
  - Background USB ADB reverse port-forwarding daemon
"""

import sys
import os
import time
import argparse
import signal
import subprocess
import threading

from protocol import SensorPacket, ButtonPacket, ButtonID, build_telemetry_packet
from receiver import PhoneelReceiver
from filters import SteeringFilter
from gamepad import VirtualGamepad, XBOX_BUTTONS
from config import PhoneelConfig
from dashboard import RacingDashboard
from ac_telemetry import ACTelemetryReader
from adb_manager import AdbWatchdog


def kill_old_servers(port: int = 9999):
    """Terminate existing server processes bound to the target port."""
    try:
        creationflags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        result = subprocess.run(
            ['netstat', '-ano'],
            capture_output=True, text=True, timeout=4, creationflags=creationflags
        )
        my_pid = str(os.getpid())

        pids_to_kill = set()
        for line in result.stdout.split('\n'):
            if f':{port}' in line and ('LISTENING' in line or 'UDP' in line):
                parts = line.strip().split()
                if parts:
                    pid = parts[-1]
                    if pid.isdigit() and pid != my_pid and pid != '0':
                        pids_to_kill.add(pid)

        for pid in pids_to_kill:
            try:
                proc = subprocess.run(
                    ['tasklist', '/FI', f'PID eq {pid}'],
                    capture_output=True, text=True, timeout=2, creationflags=creationflags
                )
                if 'python' in proc.stdout.lower() or 'phoneel' in proc.stdout.lower():
                    subprocess.run(
                        ['taskkill', '/F', '/PID', pid],
                        capture_output=True, timeout=2, creationflags=creationflags
                    )
                    print(f"[phoneel] Terminated previous server instance (PID {pid})")
            except Exception:
                pass
    except Exception:
        pass


class PhoneelRacingServer:
    def __init__(self, config: PhoneelConfig, use_dashboard: bool = True):
        self.config = config
        self.use_dashboard = use_dashboard

        self.receiver = PhoneelReceiver(
            port=config.port,
            discovery_port=config.discovery_port
        )
        self.filter = SteeringFilter(
            alpha=config.filter_alpha,
            deadzone=config.deadzone,
            anti_deadzone=config.anti_deadzone,
            steering_expo=config.steering_expo,
            speed_damping=config.speed_damping,
        )
        self.gamepad = VirtualGamepad()
        self.telemetry = ACTelemetryReader()
        self.adb = AdbWatchdog(port=config.port)
        self.dashboard = RacingDashboard(config=config) if use_dashboard else None

        self._running = False
        self._last_gas = 0.0
        self._last_brake = 0.0
        self._telem_thread = None

    def start(self):
        self._running = True

        print("=" * 60)
        print("  Phoneel Server v0.1-beta")
        print("=" * 60)
        print(f"  Port:   {self.config.port} (UDP / TCP)")
        print(
            f"  Filter: alpha={self.config.filter_alpha} expo={self.config.steering_expo} "
            f"damp={self.config.speed_damping} dz={self.config.deadzone} anti-dz={self.config.anti_deadzone}"
        )
        print("=" * 60)

        if not self.gamepad.connect():
            print("[!] Virtual gamepad unavailable (install ViGEmBus + vgamepad)")

        self.receiver.on_sensor = self._on_sensor
        self.receiver.on_button = self._on_button
        self.receiver.on_calibrate = self._on_calibrate
        self.receiver.on_connect = self._on_connect
        self.receiver.on_disconnect = self._on_disconnect

        self.receiver.start()
        self.adb.start()

        self._telem_thread = threading.Thread(target=self._telemetry_loop, daemon=True, name="ac-telemetry")
        self._telem_thread.start()

        if self.dashboard:
            self.dashboard.on_quit = self.stop
            self.dashboard.on_filter_change = self._on_live_filter_change
            self.dashboard.on_usb_refresh = self.adb.force_refresh
            self.dashboard.start()

        try:
            while self._running:
                time.sleep(0.05)
                if self.dashboard:
                    stats = self.receiver.stats
                    self.dashboard.pps = stats['packets_per_second']
                    self.dashboard.connected = len(stats['connected_clients']) > 0
                    self.dashboard.transport_mode = stats['transport']
                    if stats['connected_clients']:
                        self.dashboard.client_ip = stats['connected_clients'][0]
                    self.dashboard.usb_ready = self.adb.usb_ready
                    self.dashboard.usb_status_text = self.adb.status_text
        except KeyboardInterrupt:
            self.stop()

    def stop(self):
        if not self._running:
            return
        self._running = False
        self.adb.stop()
        self.receiver.stop()
        self.telemetry.close()
        self.gamepad.disconnect()
        if self.dashboard:
            self.dashboard.stop()
        print("[phoneel] Server stopped.")

    def _telemetry_loop(self):
        interval = 1.0 / 60.0
        while self._running:
            t0 = time.perf_counter()
            state = self.telemetry.read(gas_input=self._last_gas, brake_input=self._last_brake)

            if self.dashboard:
                self.dashboard.ac_active = state.ac_active
                self.dashboard.car_model = state.car_model
                self.dashboard.speed_kmh = state.speed_kmh
                self.dashboard.rpm = state.rpm
                self.dashboard.max_rpm = state.max_rpm
                self.dashboard.gear = state.gear
                self.dashboard.ffb_slip = state.ffb_slip
                self.dashboard.ffb_kerb = state.ffb_kerb

            pkt = build_telemetry_packet(
                speed_kmh=state.speed_kmh,
                rpm=state.rpm,
                max_rpm=state.max_rpm,
                gear=state.gear,
                flags=state.flags,
                lap_time_ms=state.lap_time_ms,
                best_lap_ms=state.best_lap_ms,
                position=state.position,
                ffb_slip=state.ffb_slip,
                ffb_kerb=state.ffb_kerb,
            )
            self.receiver.broadcast_telemetry(pkt)

            elapsed = time.perf_counter() - t0
            sleep_time = interval - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)

    def _on_live_filter_change(
        self,
        alpha: float,
        deadzone: float,
        anti_deadzone: float,
        steering_expo: float = 1.30,
        speed_damping: float = 0.50,
    ):
        self.filter.alpha = alpha
        self.filter.deadzone = deadzone
        self.filter.anti_deadzone = anti_deadzone
        self.filter.steering_expo = steering_expo
        self.filter.speed_damping = speed_damping
        self.config.filter_alpha = alpha
        self.config.deadzone = deadzone
        self.config.anti_deadzone = anti_deadzone
        self.config.steering_expo = steering_expo
        self.config.speed_damping = speed_damping
        try:
            self.config.save()
        except Exception:
            pass

    def _on_sensor(self, packet: SensorPacket, client_ip: str):
        steering = self.filter.process(packet.yaw, speed_kmh=self.telemetry.state.speed_kmh)
        gas = max(0.0, min(1.0, packet.pitch))
        brake = max(0.0, min(1.0, packet.roll))

        self._last_gas = gas
        self._last_brake = brake

        self.gamepad.set_left_stick(steering, 0.0)
        self.gamepad.set_right_trigger(gas)
        self.gamepad.set_left_trigger(brake)
        self.gamepad.update()

        if self.dashboard:
            self.dashboard.raw_steering = packet.yaw
            self.dashboard.steering = steering
            self.dashboard.gas = gas
            self.dashboard.brake = brake

    def _on_button(self, packet: ButtonPacket, client_ip: str):
        if packet.pressed:
            self.gamepad.press_button(packet.button_id)
            if packet.button_id == ButtonID.RB:
                self.telemetry.on_shift_up()
            elif packet.button_id == ButtonID.LB:
                self.telemetry.on_shift_down()
        else:
            self.gamepad.release_button(packet.button_id)
        self.gamepad.update()

        if self.dashboard:
            name = XBOX_BUTTONS.get(packet.button_id, f"0x{packet.button_id:04X}")
            if packet.pressed:
                self.dashboard.buttons_pressed.add(name)
            else:
                self.dashboard.buttons_pressed.discard(name)

    def _on_calibrate(self, client_ip: str):
        print(f"[phoneel] Gyro re-centered by {client_ip}")
        self.filter.reset()

    def _on_connect(self, client_ip: str):
        print(f"[phoneel] Connected: {client_ip}")
        self.filter.reset()
        self.gamepad.reset()

    def _on_disconnect(self, client_ip: str):
        print(f"[phoneel] Disconnected: {client_ip}")
        self._last_gas = 0.0
        self._last_brake = 0.0
        self.gamepad.reset()
        if self.dashboard:
            self.dashboard.steering = 0.0
            self.dashboard.gas = 0.0
            self.dashboard.brake = 0.0
            self.dashboard.buttons_pressed.clear()


def main():
    exe_or_script_dir = os.path.dirname(os.path.abspath(sys.executable if getattr(sys, 'frozen', False) else __file__))
    if os.path.isdir(os.path.join(exe_or_script_dir, 'pc')) and not os.path.isfile(os.path.join(exe_or_script_dir, 'phoneel_config.json')):
        os.chdir(os.path.join(exe_or_script_dir, 'pc'))
    else:
        os.chdir(exe_or_script_dir)

    parser = argparse.ArgumentParser(description="Phoneel Server")
    parser.add_argument('--port', type=int, default=None)
    parser.add_argument('--no-dashboard', action='store_true')
    parser.add_argument('--config', default='phoneel_config.json')
    args = parser.parse_args()

    config = PhoneelConfig.load(args.config)
    if args.port:
        config.port = args.port

    kill_old_servers(config.port)

    server = PhoneelRacingServer(
        config=config,
        use_dashboard=not args.no_dashboard
    )

    signal.signal(signal.SIGINT, lambda s, f: server.stop())
    server.start()


if __name__ == '__main__':
    main()
