"""
phoneel Racing Test Sender
============================
Simulates phone sending racing data (steering + gas + brake).

Usage:
    python test_sender.py                  # Sine wave steering
    python test_sender.py --mode race      # Simulated race (gas+steer)
    python test_sender.py --mode buttons   # Test ABXY buttons
"""

import socket
import time
import math
import argparse
from protocol import (
    build_sensor_packet, build_button_packet, ButtonID
)


def sine_steering(sock, addr, hz=120):
    """Smooth steering left-right with occasional gas/brake."""
    print(f"[test] Sine steering → {addr[0]}:{addr[1]} @ {hz}Hz")
    print("[test] Ctrl+C to stop\n")

    dt = 1.0 / hz
    start = time.monotonic()
    t = 0

    while True:
        elapsed = time.monotonic() - start
        ts = int(elapsed * 1000) & 0xFFFFFFFF

        # Steering: smooth sine
        steering = math.sin(elapsed * 0.5) * 0.8

        # Gas: on most of the time
        gas = 1.0 if math.sin(elapsed * 0.2) > -0.5 else 0.0

        # Brake: occasional
        brake = 1.0 if math.sin(elapsed * 0.3) > 0.8 else 0.0

        packet = build_sensor_packet(steering, gas, brake, ts)
        sock.sendto(packet, addr)

        t += 1
        if t % 60 == 0:
            print(f"  steer={steering:+.3f}  gas={gas:.0f}  brake={brake:.0f}  "
                  f"t={elapsed:.1f}s")

        time.sleep(dt)


def simulated_race(sock, addr, hz=120):
    """Simulated race: accelerate, turn, brake, repeat."""
    print(f"[test] Simulated race → {addr[0]}:{addr[1]} @ {hz}Hz")

    dt = 1.0 / hz
    start = time.monotonic()
    t = 0

    while True:
        elapsed = time.monotonic() - start
        ts = int(elapsed * 1000) & 0xFFFFFFFF

        # Race pattern: straight → turn → straight → hard brake → repeat
        cycle = elapsed % 8.0  # 8-second cycle

        if cycle < 3.0:
            # Straight, full gas
            steering = 0.0
            gas = 1.0
            brake = 0.0
        elif cycle < 5.0:
            # Turn right, partial gas
            steering = 0.6 * math.sin((cycle - 3.0) * math.pi / 2)
            gas = 0.5
            brake = 0.0
        elif cycle < 6.5:
            # Hard brake + turn left
            steering = -0.4
            gas = 0.0
            brake = 1.0
        else:
            # Accelerate out
            steering = -0.2 * (8.0 - cycle) / 1.5
            gas = 1.0
            brake = 0.0

        packet = build_sensor_packet(steering, gas, brake, ts)
        sock.sendto(packet, addr)

        t += 1
        if t % 60 == 0:
            phase = "STRAIGHT" if cycle < 3 else "TURN" if cycle < 5 else "BRAKE" if cycle < 6.5 else "EXIT"
            print(f"  [{phase:8s}] steer={steering:+.3f}  gas={gas:.1f}  brake={brake:.1f}")

        time.sleep(dt)


def button_test(sock, addr):
    """Test all buttons."""
    print(f"[test] Button test → {addr[0]}:{addr[1]}")

    buttons = [
        (ButtonID.A, "A"), (ButtonID.B, "B"),
        (ButtonID.X, "X"), (ButtonID.Y, "Y"),
        (ButtonID.LB, "LB/Horn"), (ButtonID.RB, "RB"),
        (ButtonID.START, "Start"), (ButtonID.SELECT, "Select"),
    ]

    for btn_id, name in buttons:
        print(f"  Press {name}")
        sock.sendto(build_button_packet(True, btn_id), addr)
        time.sleep(0.3)
        print(f"  Release {name}")
        sock.sendto(build_button_packet(False, btn_id), addr)
        time.sleep(0.2)

    print("[test] Done!")


def main():
    parser = argparse.ArgumentParser(description="phoneel racing test sender")
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=9999)
    parser.add_argument('--hz', type=int, default=120)
    parser.add_argument('--mode', choices=['sine', 'race', 'buttons'], default='sine')
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    addr = (args.host, args.port)

    print("=" * 40)
    print("  phoneel Racing Test")
    print("=" * 40)

    try:
        if args.mode == 'sine':
            sine_steering(sock, addr, args.hz)
        elif args.mode == 'race':
            simulated_race(sock, addr, args.hz)
        elif args.mode == 'buttons':
            button_test(sock, addr)
    except KeyboardInterrupt:
        print("\n[test] Stopped.")
    finally:
        sock.close()


if __name__ == '__main__':
    main()
