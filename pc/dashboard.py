"""
phoneel Desktop UI
==================
Tkinter desktop interface for monitoring input state, Assetto Corsa telemetry,
USB ADB port-forwarding status, and steering filter parameters.
"""

import socket
import threading
import tkinter as tk


class RacingDashboard:
    def __init__(self, config=None):
        self.config = config

        self.connected = False
        self.client_ip = ""
        self.transport_mode = ""   # "USB" or "WiFi"
        self.pps = 0
        self.steering = 0.0        # -1.0 to 1.0
        self.raw_steering = 0.0    # -1.0 to 1.0
        self.gas = 0.0             # 0.0 to 1.0
        self.brake = 0.0           # 0.0 to 1.0
        self.buttons_pressed = set()
        self.pc_ips = self._get_local_ips()

        self.usb_ready = False
        self.usb_status_text = "Checking USB..."

        self.ac_active = False
        self.car_model = ""
        self.speed_kmh = 0.0
        self.rpm = 0
        self.max_rpm = 8500
        self.gear = 1
        self.ffb_slip = 0
        self.ffb_kerb = 0

        self.on_quit = None
        self.on_filter_change = None
        self.on_usb_refresh = None
        self.on_recenter = None

        self._root = None
        self._thread = None
        self._running = False
        self._labels = {}
        self._steer_canvas = None
        self._telem_canvas = None

    @staticmethod
    def _get_local_ips():
        ips = []
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
                ip = info[4][0]
                if not ip.startswith('127.') and not ip.startswith('169.254.'):
                    if ip not in ips:
                        ips.append(ip)
        except Exception:
            pass
        return ips

    def start(self):
        self._running = True
        self._thread = threading.Thread(target=self._run, daemon=True, name="ui-thread")
        self._thread.start()

    def stop(self):
        self._running = False
        if self._root:
            try:
                self._root.quit()
            except Exception:
                pass

    def _run(self):
        self._root = tk.Tk()
        self._root.title("Phoneel Server v0.1-beta")
        self._root.geometry("630x630")
        self._root.minsize(590, 590)
        self._root.configure(bg='#090b10')
        self._root.protocol("WM_DELETE_WINDOW", self._on_close)

        self._build_ui()
        self._update_loop()
        self._root.mainloop()

    def _build_ui(self):
        root = self._root
        BG = '#090b10'
        CARD = '#121622'
        CARD_BORDER = '#1e2638'
        ACCENT = '#00e676'
        CYAN = '#00b0ff'
        TEXT = '#f0f4f8'
        DIM = '#78859b'

        header = tk.Frame(root, bg='#0d111a', padx=18, pady=12)
        header.pack(fill='x')

        title_col = tk.Frame(header, bg='#0d111a')
        title_col.pack(side='left')
        tk.Label(
            title_col, text="PHONEEL", font=('Segoe UI', 16, 'bold'),
            bg='#0d111a', fg='#ffffff'
        ).pack(side='left')
        tk.Label(
            title_col, text="  SERVER & TELEMETRY MONITOR", font=('Segoe UI', 9, 'bold'),
            bg='#0d111a', fg=CYAN
        ).pack(side='left', pady=(4, 0))

        self._labels['conn_badge'] = tk.Label(
            header, text=" WAITING FOR CLIENT ", font=('Consolas', 10, 'bold'),
            bg='#1b2234', fg='#ffb300', padx=10, pady=4
        )
        self._labels['conn_badge'].pack(side='right')

        conn_card = tk.Frame(root, bg=CARD, highlightbackground=CARD_BORDER, highlightthickness=1, padx=14, pady=10)
        conn_card.pack(fill='x', padx=16, pady=(12, 6))

        left_conn = tk.Frame(conn_card, bg=CARD)
        left_conn.pack(side='left', fill='x', expand=True)

        ip_str = "  |  ".join(self.pc_ips) if self.pc_ips else "No LAN IP"
        tk.Label(
            left_conn, text=f"Wi-Fi UDP:  {ip_str}  (Port 9999)",
            font=('Consolas', 10, 'bold'), bg=CARD, fg='#90caf9', anchor='w'
        ).pack(fill='x')

        self._labels['usb_status'] = tk.Label(
            left_conn, text="USB ADB: Checking...",
            font=('Consolas', 9), bg=CARD, fg=DIM, anchor='w'
        )
        self._labels['usb_status'].pack(fill='x', pady=(3, 0))

        usb_btn = tk.Button(
            conn_card, text="Re-Sync USB", font=('Segoe UI', 9, 'bold'),
            bg='#1e293b', fg='#38bdf8', activebackground='#334155', activeforeground='#ffffff',
            relief='flat', padx=10, pady=4, cursor='hand2',
            command=self._trigger_usb_refresh
        )
        usb_btn.pack(side='right', padx=(8, 0))

        mon_card = tk.Frame(root, bg=CARD, highlightbackground=CARD_BORDER, highlightthickness=1, padx=14, pady=12)
        mon_card.pack(fill='x', padx=16, pady=6)

        top_mon = tk.Frame(mon_card, bg=CARD)
        top_mon.pack(fill='x')

        self._labels['steer_val'] = tk.Label(
            top_mon, text="STEERING: +0.000  (0.0°)",
            font=('Consolas', 11, 'bold'), bg=CARD, fg=TEXT
        )
        self._labels['steer_val'].pack(side='left')

        self._labels['hz'] = tk.Label(
            top_mon, text="0 Hz", font=('Consolas', 13, 'bold'),
            bg=CARD, fg=ACCENT
        )
        self._labels['hz'].pack(side='right')

        self._steer_canvas = tk.Canvas(mon_card, height=68, bg='#090c14', highlightthickness=1, highlightbackground='#1e293b')
        self._steer_canvas.pack(fill='x', pady=(8, 8))

        self._labels['buttons'] = tk.Label(
            mon_card, text="Active Buttons: None",
            font=('Consolas', 9), bg=CARD, fg=DIM, anchor='w'
        )
        self._labels['buttons'].pack(fill='x')

        telem_card = tk.Frame(root, bg=CARD, highlightbackground=CARD_BORDER, highlightthickness=1, padx=14, pady=10)
        telem_card.pack(fill='x', padx=16, pady=6)

        telem_header = tk.Frame(telem_card, bg=CARD)
        telem_header.pack(fill='x')

        tk.Label(
            telem_header, text="TELEMETRY STREAM (60 Hz)",
            font=('Segoe UI', 9, 'bold'), bg=CARD, fg=DIM
        ).pack(side='left')

        self._labels['ac_badge'] = tk.Label(
            telem_header, text="STANDBY",
            font=('Consolas', 9, 'bold'), bg='#1e293b', fg='#94a3b8', padx=8, pady=1
        )
        self._labels['ac_badge'].pack(side='right')

        self._telem_canvas = tk.Canvas(telem_card, height=58, bg='#090c14', highlightthickness=0)
        self._telem_canvas.pack(fill='x', pady=(8, 2))

        tune_card = tk.Frame(root, bg=CARD, highlightbackground=CARD_BORDER, highlightthickness=1, padx=14, pady=10)
        tune_card.pack(fill='x', padx=16, pady=6)

        tune_hdr = tk.Frame(tune_card, bg=CARD)
        tune_hdr.pack(fill='x', pady=(0, 6))

        tk.Label(
            tune_hdr, text="STEERING FILTER PARAMETERS",
            font=('Segoe UI', 9, 'bold'), bg=CARD, fg=DIM, anchor='w'
        ).pack(side='left')

        tk.Button(
            tune_hdr, text="Reset Defaults", font=('Segoe UI', 8, 'bold'),
            bg='#1e293b', fg='#94a3b8', activebackground='#334155', activeforeground='#ffffff',
            relief='flat', padx=8, pady=1, cursor='hand2',
            command=self._reset_filter_defaults
        ).pack(side='right')

        alpha_init = self.config.filter_alpha if self.config else 0.65
        expo_init = getattr(self.config, 'steering_expo', 1.30) if self.config else 1.30
        damp_init = getattr(self.config, 'speed_damping', 0.50) if self.config else 0.50
        dz_init = self.config.deadzone if self.config else 0.005
        adz_init = self.config.anti_deadzone if self.config else 0.0

        self._alpha_var = tk.DoubleVar(value=alpha_init)
        self._expo_var = tk.DoubleVar(value=expo_init)
        self._damp_var = tk.DoubleVar(value=damp_init)
        self._dz_var = tk.DoubleVar(value=dz_init)
        self._adz_var = tk.DoubleVar(value=adz_init)
        self._slider_labels = {}

        self._build_slider_row(
            tune_card, "EMA Filter Alpha", self._alpha_var, 0.10, 1.00, 0.05
        )
        self._build_slider_row(
            tune_card, "Response Curve (Expo)", self._expo_var, 1.00, 2.00, 0.05
        )
        self._build_slider_row(
            tune_card, "High-Speed Damping", self._damp_var, 0.00, 1.00, 0.05
        )
        self._build_slider_row(
            tune_card, "Center Deadzone", self._dz_var, 0.000, 0.050, 0.002
        )
        self._build_slider_row(
            tune_card, "Anti-Deadzone", self._adz_var, 0.00, 0.20, 0.01
        )

        footer = tk.Frame(root, bg=BG, padx=16, pady=8)
        footer.pack(fill='x', side='bottom')

        tk.Label(
            footer, text="ViGEmBus Xbox 360 Virtual Controller",
            font=('Segoe UI', 9), bg=BG, fg=DIM
        ).pack(side='left')

        tk.Button(
            footer, text="Stop Server", font=('Segoe UI', 9, 'bold'),
            bg='#b91c1c', fg='white', activebackground='#dc2626',
            relief='flat', padx=14, pady=4, cursor='hand2',
            command=self._on_close
        ).pack(side='right')

    def _build_slider_row(self, parent, title, var, min_v, max_v, res):
        row = tk.Frame(parent, bg='#121622')
        row.pack(fill='x', pady=2)

        lbl = tk.Label(row, text=f"{title}:", width=25, anchor='w', font=('Segoe UI', 9), bg='#121622', fg='#e2e8f0')
        lbl.pack(side='left')

        val_lbl = tk.Label(row, text=f"{var.get():.3f}", width=6, font=('Consolas', 9, 'bold'), bg='#121622', fg='#38bdf8')
        val_lbl.pack(side='right')
        self._slider_labels[title] = (val_lbl, var)

        def _on_slide(_=None):
            val_lbl.config(text=f"{var.get():.3f}")
            if self.on_filter_change:
                self.on_filter_change(
                    round(self._alpha_var.get(), 3),
                    round(self._dz_var.get(), 4),
                    round(self._adz_var.get(), 3),
                    round(self._expo_var.get(), 2),
                    round(self._damp_var.get(), 2),
                )

        scale = tk.Scale(
            row, variable=var, from_=min_v, to=max_v, resolution=res,
            orient='horizontal', showvalue=False, bg='#121622', fg='#38bdf8',
            troughcolor='#090c14', highlightthickness=0, bd=0, sliderrelief='flat',
            activebackground='#00e676', command=_on_slide, length=180
        )
        scale.pack(side='right', fill='x', expand=True, padx=8)

    def _reset_filter_defaults(self):
        self._alpha_var.set(0.65)
        self._expo_var.set(1.30)
        self._damp_var.set(0.50)
        self._dz_var.set(0.005)
        self._adz_var.set(0.00)
        for val_lbl, var in self._slider_labels.values():
            val_lbl.config(text=f"{var.get():.3f}")
        if self.on_filter_change:
            self.on_filter_change(0.65, 0.005, 0.00, 1.30, 0.50)

    def _trigger_usb_refresh(self):
        if self.on_usb_refresh:
            threading.Thread(target=self.on_usb_refresh, daemon=True).start()

    def _update_loop(self):
        if not self._running:
            return

        if self.connected:
            mode_tag = f"{self.transport_mode} " if self.transport_mode else ""
            self._labels['conn_badge'].config(
                text=f" {mode_tag}CONNECTED ({self.client_ip}) ",
                bg='#064e3b', fg='#34d399'
            )
        else:
            self._labels['conn_badge'].config(
                text=" WAITING FOR CLIENT ",
                bg='#1b2234', fg='#ffb300'
            )

        usb_fg = '#34d399' if self.usb_ready else '#94a3b8'
        self._labels['usb_status'].config(text=f"USB ADB: {self.usb_status_text}", fg=usb_fg)

        self._labels['hz'].config(text=f"{self.pps} Hz")
        approx_deg = self.steering * 90.0
        self._labels['steer_val'].config(text=f"STEERING: {self.steering:+.3f}  ({approx_deg:+.1f}°)")

        self._draw_steering_and_pedals()
        self._draw_telemetry()

        if self.buttons_pressed:
            btns = "  ".join(f"[{b}]" for b in sorted(self.buttons_pressed))
            self._labels['buttons'].config(text=f"Active Buttons: {btns}", fg='#38bdf8')
        else:
            self._labels['buttons'].config(text="Active Buttons: None", fg='#64748b')

        if self.ac_active:
            car_txt = f" ({self.car_model})" if self.car_model else ""
            self._labels['ac_badge'].config(
                text=f"AC LIVE{car_txt}",
                bg='#064e3b', fg='#34d399'
            )
        else:
            self._labels['ac_badge'].config(
                text="STANDBY / SYNTHETIC",
                bg='#1e293b', fg='#94a3b8'
            )

        self._root.after(16, self._update_loop)

    def _draw_steering_and_pedals(self):
        c = self._steer_canvas
        c.delete('all')
        w = c.winfo_width()
        h = c.winfo_height()
        if w <= 10:
            return

        brake_w = int(90 * max(0.0, min(1.0, self.brake)))
        c.create_rectangle(8, 8, 98, h - 8, fill='#1a0f14', outline='#3f1d28')
        if brake_w > 0:
            c.create_rectangle(8, 8, 8 + brake_w, h - 8, fill='#ef4444', outline='')
        c.create_text(53, h / 2, text=f"BRK {int(self.brake * 100)}%", fill='#ffffff', font=('Consolas', 9, 'bold'))

        gas_w = int(90 * max(0.0, min(1.0, self.gas)))
        c.create_rectangle(w - 98, 8, w - 8, h - 8, fill='#0f1f18', outline='#1d3f2d')
        if gas_w > 0:
            c.create_rectangle(w - 98, 8, w - 98 + gas_w, h - 8, fill='#10b981', outline='')
        c.create_text(w - 53, h / 2, text=f"GAS {int(self.gas * 100)}%", fill='#ffffff', font=('Consolas', 9, 'bold'))

        track_l = 112
        track_r = w - 112
        track_w = track_r - track_l
        center_x = track_l + track_w / 2
        cy = h / 2

        c.create_rectangle(track_l, cy - 12, track_r, cy + 12, fill='#111827', outline='#1f2937')
        c.create_line(center_x, 6, center_x, h - 6, fill='#475569', width=2)

        clamped = max(-1.0, min(1.0, self.steering))
        bar_x = center_x + clamped * (track_w / 2 - 8)

        if abs(clamped) > 0.003:
            x1, x2 = min(center_x, bar_x), max(center_x, bar_x)
            c.create_rectangle(x1, cy - 9, x2, cy + 9, fill='#00b0ff', outline='')

        c.create_oval(bar_x - 8, cy - 12, bar_x + 8, cy + 12, fill='#00e676', outline='#ffffff', width=1)

    def _draw_telemetry(self):
        c = self._telem_canvas
        c.delete('all')
        w = c.winfo_width()
        h = c.winfo_height()
        if w <= 10:
            return

        num_leds = 15
        led_w = (w - 20) / num_leds
        rpm_ratio = self.rpm / max(1000, self.max_rpm)
        active_leds = int(rpm_ratio * num_leds)

        for i in range(num_leds):
            x1 = 10 + i * led_w + 2
            x2 = 10 + (i + 1) * led_w - 2
            if i < active_leds:
                if i < 5:
                    color = '#00e676'
                elif i < 10:
                    color = '#ffea00'
                elif i < 13:
                    color = '#ff1744'
                else:
                    color = '#00b0ff'
            else:
                color = '#161f30'
            c.create_rectangle(x1, 6, x2, 16, fill=color, outline='')

        gear_map = {0: 'R', 1: 'N'}
        gear_str = gear_map.get(self.gear, str(self.gear - 1))

        c.create_text(52, 38, text=f"GEAR {gear_str}", fill='#ffea00', font=('Consolas', 14, 'bold'))
        c.create_text(w * 0.36, 38, text=f"{int(self.speed_kmh):3d} KM/H", fill='#ffffff', font=('Consolas', 15, 'bold'))
        c.create_text(w * 0.64, 38, text=f"{self.rpm:5d} RPM", fill='#38bdf8', font=('Consolas', 13, 'bold'))

        ffb_pct = int(max(self.ffb_slip, self.ffb_kerb) / 255.0 * 100)
        ffb_col = '#ff1744' if self.ffb_slip > 80 else ('#00e676' if ffb_pct > 5 else '#475569')
        c.create_text(w - 58, 38, text=f"FFB {ffb_pct:2d}%", fill=ffb_col, font=('Consolas', 11, 'bold'))

    def _on_close(self):
        self._running = False
        if self.on_quit:
            self.on_quit()
        try:
            self._root.destroy()
        except Exception:
            pass
