"""
phoneel Network Receiver
========================
Dual-transport listener and telemetry broadcaster:
  - UDP on port 9999 for Wi-Fi transport
  - TCP on port 9999 (length-prefixed, TCP_NODELAY) for USB ADB reverse transport
  - UDP broadcast responder on port 9998 for LAN discovery
"""

import socket
import struct
import sys
import threading
import time
from typing import Callable, Optional

from protocol import (
    parse_packet, DEFAULT_PORT, DISCOVERY_PORT, MAX_PACKET_SIZE,
    PacketType, SensorPacket, ButtonPacket, TouchPacket,
    ModePacket, HeartbeatPacket, build_discovery_pong
)


class PhoneelReceiver:
    """Listens for phone data via UDP (WiFi) and TCP (USB), and broadcasts telemetry back."""

    def __init__(self, port: int = DEFAULT_PORT, discovery_port: int = DISCOVERY_PORT):
        self.port = port
        self.discovery_port = discovery_port

        # Callbacks
        self.on_sensor:     Optional[Callable] = None
        self.on_button:     Optional[Callable] = None
        self.on_touch:      Optional[Callable] = None
        self.on_mode:       Optional[Callable] = None
        self.on_calibrate:  Optional[Callable] = None
        self.on_connect:    Optional[Callable] = None
        self.on_disconnect: Optional[Callable] = None

        # State
        self._running = False
        self._udp_sock: Optional[socket.socket] = None
        self._tcp_sock: Optional[socket.socket] = None
        self._disc_sock: Optional[socket.socket] = None
        self._threads = []

        # Active client return channels for bidirectional telemetry
        self._udp_client_addrs: dict[str, tuple[str, int]] = {}
        self._tcp_client_conns: dict[str, socket.socket] = {}
        self._tcp_send_locks: dict[str, threading.Lock] = {}
        self._lock = threading.Lock()

        # Stats
        self._packets_received = 0
        self._packets_per_second = 0
        self._last_pps_time = time.monotonic()
        self._last_pps_count = 0
        self._connected_clients: dict[str, float] = {}
        self._client_transport: dict[str, str] = {}   # ip -> "USB" or "WiFi"
        self._heartbeat_timeout = 4.0

    @property
    def stats(self) -> dict:
        with self._lock:
            clients = list(self._connected_clients.keys())
            mode = self._client_transport.get(clients[0], "") if clients else ""
        return {
            'packets_received': self._packets_received,
            'packets_per_second': self._packets_per_second,
            'connected_clients': clients,
            'transport': mode,
        }

    def start(self):
        """Start all listeners."""
        if self._running:
            return
        self._running = True

        # UDP listener (WiFi)
        self._udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._udp_sock.settimeout(0.25)
        self._udp_sock.bind(('0.0.0.0', self.port))
        try:
            self._udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)
            self._udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
            # Prevent Windows ICMP Port Unreachable (WinError 10054) from crashing recvfrom
            if sys.platform == 'win32' and hasattr(socket, 'SIO_UDP_CONNRESET'):
                self._udp_sock.ioctl(socket.SIO_UDP_CONNRESET, False)
        except OSError:
            pass

        t = threading.Thread(target=self._udp_loop, daemon=True, name='udp-listen')
        t.start()
        self._threads.append(t)

        # TCP listener (USB)
        self._tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._tcp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._tcp_sock.settimeout(0.5)
        self._tcp_sock.bind(('0.0.0.0', self.port))
        self._tcp_sock.listen(4)

        t = threading.Thread(target=self._tcp_accept_loop, daemon=True, name='tcp-accept')
        t.start()
        self._threads.append(t)

        # Discovery responder
        self._disc_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._disc_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._disc_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self._disc_sock.settimeout(0.5)
        self._disc_sock.bind(('0.0.0.0', self.discovery_port))
        try:
            if sys.platform == 'win32' and hasattr(socket, 'SIO_UDP_CONNRESET'):
                self._disc_sock.ioctl(socket.SIO_UDP_CONNRESET, False)
        except OSError:
            pass

        t = threading.Thread(target=self._discovery_loop, daemon=True, name='discovery')
        t.start()
        self._threads.append(t)

        print(f"[phoneel] UDP :{self.port} | TCP :{self.port} | Discovery :{self.discovery_port}")

    def stop(self):
        """Stop all listeners."""
        self._running = False
        with self._lock:
            for conn in list(self._tcp_client_conns.values()):
                try:
                    conn.close()
                except Exception:
                    pass
            self._tcp_client_conns.clear()

        for s in [self._udp_sock, self._tcp_sock, self._disc_sock]:
            if s:
                try:
                    s.close()
                except Exception:
                    pass
        for t in self._threads:
            t.join(timeout=1.5)
        print("[phoneel] Receiver stopped.")

    # ─── Broadcast Telemetry (PC → Phone) ─────────────

    def broadcast_telemetry(self, packet_bytes: bytes):
        """Send 21-byte telemetry packet to all active UDP and TCP phone clients."""
        if not self._running:
            return

        with self._lock:
            udp_targets = list(self._udp_client_addrs.values())
            tcp_targets = [(ip, conn, self._tcp_send_locks.get(ip)) for ip, conn in self._tcp_client_conns.items()]

        # Send via UDP (WiFi clients)
        if self._udp_sock and udp_targets:
            for addr in udp_targets:
                try:
                    self._udp_sock.sendto(packet_bytes, addr)
                except OSError:
                    pass

        # Send via TCP (USB clients, length-prefixed, non-blocking)
        if tcp_targets:
            framed = struct.pack('<H', len(packet_bytes)) + packet_bytes
            for client_ip, conn, slock in tcp_targets:
                if slock and slock.acquire(blocking=False):
                    try:
                        conn.sendall(framed)
                    except OSError:
                        pass
                    finally:
                        slock.release()

    # ─── UDP Loop (WiFi) ──────────────────────────────

    def _udp_loop(self):
        while self._running:
            try:
                data, addr = self._udp_sock.recvfrom(MAX_PACKET_SIZE)
            except socket.timeout:
                self._check_heartbeats()
                continue
            except ConnectionResetError:
                # Windows ICMP port unreachable — ignore and keep listening
                continue
            except OSError:
                if not self._running:
                    break
                continue

            client_ip = addr[0]
            with self._lock:
                self._udp_client_addrs[client_ip] = addr
                self._client_transport[client_ip] = "WiFi"
            self._handle_packet(data, client_ip)

    # ─── TCP Accept Loop (USB) ────────────────────────

    def _tcp_accept_loop(self):
        while self._running:
            try:
                conn, addr = self._tcp_sock.accept()
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                conn.settimeout(4.0)
                client_id = f"{addr[0]}"
                with self._lock:
                    # Close stale connection from same IP if any
                    old = self._tcp_client_conns.get(client_id)
                    if old:
                        try:
                            old.close()
                        except Exception:
                            pass
                    self._tcp_client_conns[client_id] = conn
                    self._tcp_send_locks[client_id] = threading.Lock()
                    self._client_transport[client_id] = "USB"

                print(f"[phoneel] USB client connected: {client_id}")
                t = threading.Thread(
                    target=self._tcp_client_loop,
                    args=(conn, client_id),
                    daemon=True,
                    name=f'tcp-{client_id}'
                )
                t.start()
            except socket.timeout:
                self._check_heartbeats()
                continue
            except OSError:
                if not self._running:
                    break
                continue

    def _tcp_client_loop(self, conn: socket.socket, client_ip: str):
        """Handle a single TCP client (length-prefixed packets)."""
        try:
            while self._running:
                length_data = self._recv_exact(conn, 2)
                if not length_data:
                    break
                length = struct.unpack('<H', length_data)[0]

                if length > MAX_PACKET_SIZE or length == 0:
                    break

                data = self._recv_exact(conn, length)
                if not data:
                    break

                self._handle_packet(data, client_ip)
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass
            disconnected = False
            with self._lock:
                if self._tcp_client_conns.get(client_ip) is conn:
                    del self._tcp_client_conns[client_ip]
                    self._tcp_send_locks.pop(client_ip, None)
                    if client_ip in self._connected_clients:
                        del self._connected_clients[client_ip]
                        disconnected = True
            if disconnected and self.on_disconnect:
                self.on_disconnect(client_ip)
            print(f"[phoneel] USB client disconnected: {client_ip}")

    @staticmethod
    def _recv_exact(conn: socket.socket, n: int) -> Optional[bytes]:
        data = b''
        while len(data) < n:
            try:
                chunk = conn.recv(n - len(data))
                if not chunk:
                    return None
                data += chunk
            except (socket.timeout, OSError):
                return None
        return data

    # ─── Discovery Loop ───────────────────────────────

    def _discovery_loop(self):
        hostname = socket.gethostname()
        while self._running:
            try:
                data, addr = self._disc_sock.recvfrom(MAX_PACKET_SIZE)
            except (socket.timeout, ConnectionResetError):
                continue
            except OSError:
                if not self._running:
                    break
                continue

            if len(data) >= 1 and data[0] == PacketType.DISC_PING:
                pong = build_discovery_pong(hostname)
                try:
                    self._disc_sock.sendto(pong, addr)
                    print(f"[phoneel] Discovery: {addr[0]} found us")
                except OSError:
                    pass

    # ─── Packet Handler (shared by UDP & TCP) ─────────

    def _handle_packet(self, data: bytes, client_ip: str):
        now = time.monotonic()

        with self._lock:
            is_new = client_ip not in self._connected_clients
            self._connected_clients[client_ip] = now

        if is_new and self.on_connect:
            self.on_connect(client_ip)

        packet = parse_packet(data)
        if packet is None:
            return

        self._packets_received += 1
        self._update_pps(now)

        if isinstance(packet, SensorPacket) and self.on_sensor:
            self.on_sensor(packet, client_ip)
        elif isinstance(packet, ButtonPacket) and self.on_button:
            self.on_button(packet, client_ip)
        elif isinstance(packet, TouchPacket) and self.on_touch:
            self.on_touch(packet, client_ip)
        elif isinstance(packet, ModePacket) and self.on_mode:
            self.on_mode(packet, client_ip)
        elif isinstance(packet, HeartbeatPacket):
            pass
        elif packet == "CALIBRATE" and self.on_calibrate:
            self.on_calibrate(client_ip)

    def _update_pps(self, now: float):
        elapsed = now - self._last_pps_time
        if elapsed >= 0.5:
            self._packets_per_second = int(
                (self._packets_received - self._last_pps_count) / elapsed
            )
            self._last_pps_count = self._packets_received
            self._last_pps_time = now

    def _check_heartbeats(self):
        now = time.monotonic()
        expired = []
        with self._lock:
            for ip, ts in list(self._connected_clients.items()):
                if now - ts > self._heartbeat_timeout:
                    del self._connected_clients[ip]
                    self._udp_client_addrs.pop(ip, None)
                    expired.append(ip)
            if not self._connected_clients:
                self._packets_per_second = 0

        for ip in expired:
            if self.on_disconnect:
                self.on_disconnect(ip)
            print(f"[phoneel] Client timeout: {ip}")
