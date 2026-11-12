package com.phoneel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.*
import android.widget.*
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Main landscape controller activity:
 *  - Left cluster: LB paddle shifter, D-Pad, analog brake trigger (LT)
 *  - Center DDU: 15-LED RPM bar, gear indicator, speed, lap timer, status flags, steering bar
 *  - Right cluster: RB paddle shifter, ABXY face buttons, analog throttle trigger (RT)
 *  - Bottom console: Settings, View, E-Brake, Gyro re-center, Menu
 *  - Interactive HUD layout editor (drag-and-drop position + scale persistence)
 */
class MainActivity : Activity() {

    // ─── Core Engine ──────────────────────────────────
    private lateinit var sensorReader: SensorReader
    private val sender = UdpSender()
    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null

    // ─── Live Control & Telemetry State ───────────────
    @Volatile private var gasValue = 0f
    @Volatile private var brakeValue = 0f
    @Volatile private var latestTelemetry = Protocol.TelemetryData()
    @Volatile private var lastHapticMs = 0L

    // ─── UI Views & Editable HUD Registry ─────────────
    private lateinit var rootFrame: CockpitFrameLayout
    private lateinit var centerDdu: CenterDduView
    private lateinit var gasBtn: AnalogPedalView
    private lateinit var brakeBtn: AnalogPedalView
    private lateinit var editBanner: LinearLayout
    private lateinit var editSelectedLabel: TextView
    private lateinit var editScaleBar: SeekBar

    data class EditableWidget(
        val key: String,
        val title: String,
        val view: View
    )

    private val editableWidgets = mutableListOf<EditableWidget>()
    private var isEditLayoutMode = false
    private var selectedWidget: EditableWidget? = null

    // ─── Stats & Preferences ──────────────────────────
    private var ppsCounter = 0L
    private var lastPpsTime = System.currentTimeMillis()
    private var displayPps = 0
    private var savedIp: String = ""
    private var sensitivity: Int = 4            // Default 4 => ±80° full lock
    private var invertSteering: Boolean = false
    private var hapticFfbEnabled: Boolean = true
    private var tractionCurveEnabled: Boolean = true

    companion object {
        val BG          = Color.parseColor("#07090e")
        val GAS_NORM    = Color.parseColor("#1b5e20")
        val GAS_PRESS   = Color.parseColor("#00e676")
        val BRAKE_NORM  = Color.parseColor("#7f1d1d")
        val BRAKE_PRESS = Color.parseColor("#ff1744")
        val SHIFTER_COL = Color.parseColor("#00b0ff")
        val HB_COL      = Color.parseColor("#ff9100")
        val SYS_COL     = Color.parseColor("#64748b")

        // Authentic Xbox Controller Face Button Colors
        val XBOX_A      = Color.parseColor("#10b981") // Green
        val XBOX_B      = Color.parseColor("#ef4444") // Red
        val XBOX_X      = Color.parseColor("#3b82f6") // Blue
        val XBOX_Y      = Color.parseColor("#f59e0b") // Amber/Yellow
    }

    // ═══════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        initVibrator()
        setContentView(buildCockpitUI())

        sensorReader = SensorReader(this)
        if (!sensorReader.isAvailable) {
            showToast("Rotation sensor not available")
            return
        }

        // High-frequency sensor callback (~120-200 Hz, lock-free latest-state send)
        sensorReader.onSteeringUpdate = { steering ->
            if (sender.connected && !isEditLayoutMode) {
                sender.sendDriveState(steering, gasValue, brakeValue)
                ppsCounter++
            }
        }

        // Incoming 60Hz Telemetry + Haptic FFB from PC Server
        sender.onTelemetry = { telem ->
            latestTelemetry = telem
            if (hapticFfbEnabled && !isEditLayoutMode) {
                processHapticFfb(telem)
            }
        }

        sender.onConnectionStateChanged = { isConn ->
            handler.post {
                val modeStr = if (sender.currentMode == UdpSender.Mode.USB_TCP) "USB" else "WiFi"
                centerDdu.connected = isConn
                centerDdu.transportLabel = modeStr
                centerDdu.invalidate()
            }
        }

        startUiRenderLoop()

        val prefs = getSharedPreferences("phoneel", MODE_PRIVATE)
        savedIp = prefs.getString("last_ip", "") ?: ""
        sensitivity = prefs.getInt("sensitivity_v21", 4)
        invertSteering = prefs.getBoolean("invert_steering", false)
        hapticFfbEnabled = prefs.getBoolean("haptic_ffb", true)
        tractionCurveEnabled = prefs.getBoolean("traction_curve", true)

        applyDrivingSettings()
        loadHudLayout()

        handler.postDelayed({ showConnectionDialog() }, 350)
    }

    @Suppress("DEPRECATION")
    private fun initVibrator() {
        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Real-time Haptic Force Feedback Engine (throttled to ~28 Hz so the linear resonant actuator stays crisp).
     * - Rear tire slip (`ffbSlip`): continuous textured vibration warning of oversteer/spin onset
     * - Kerbs & gear shifts (`ffbKerb`): sharp tactile thump
     */
    private fun processHapticFfb(telem: Protocol.TelemetryData) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val slip = telem.ffbSlip
        val kerb = telem.ffbKerb
        val combined = max(slip, kerb)
        if (combined < 22) return

        val now = System.currentTimeMillis()
        val minInterval = if (kerb > 140) 28L else 42L
        if (now - lastHapticMs < minInterval) return
        lastHapticMs = now

        try {
            val durationMs = if (kerb > slip) 22L else 36L
            val amplitude = combined.coerceIn(25, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        sensorReader.start()
        goImmersive()
    }

    override fun onPause() {
        super.onPause()
        sensorReader.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        sender.disconnect()
        sensorReader.destroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    // ═══════════════════════════════════════════════════
    // COCKPIT & XBOX CONTROLLER UI BUILDER
    // ═══════════════════════════════════════════════════

    private fun buildCockpitUI(): View {
        editableWidgets.clear()
        rootFrame = CockpitFrameLayout(this).apply { setBackgroundColor(BG) }

        // ─── 1. CENTER DDU TELEMETRY SCREEN (Slightly more compact for breathing room) ───
        centerDdu = CenterDduView(this)
        rootFrame.addView(
            centerDdu,
            flp(dp(306), dp(204), Gravity.CENTER).apply {
                bottomMargin = dp(20)
            }
        )
        registerEditable("ddu", "Telemetry DDU", centerDdu)

        // ─── 2. TOP-LEFT & TOP-RIGHT PADDLE SHIFTERS ─────
        val lbPaddle = XboxButtonView(this, "◄ GEAR -", "LB SHIFTER", SHIFTER_COL, isCircular = false)
        lbPaddle.onPressChanged = { pressed -> if (!isEditLayoutMode) sender.sendButton(pressed, Protocol.BTN_LB) }
        rootFrame.addView(
            lbPaddle,
            flp(dp(136), dp(58), Gravity.START or Gravity.TOP).apply {
                leftMargin = dp(12)
                topMargin = dp(10)
            }
        )
        registerEditable("lb_paddle", "LB Gear -", lbPaddle)

        val rbPaddle = XboxButtonView(this, "GEAR + ►", "RB SHIFTER", SHIFTER_COL, isCircular = false)
        rbPaddle.onPressChanged = { pressed -> if (!isEditLayoutMode) sender.sendButton(pressed, Protocol.BTN_RB) }
        rootFrame.addView(
            rbPaddle,
            flp(dp(136), dp(58), Gravity.END or Gravity.TOP).apply {
                rightMargin = dp(12)
                topMargin = dp(10)
            }
        )
        registerEditable("rb_paddle", "RB Gear +", rbPaddle)

        // ─── 3. BOTTOM-LEFT & BOTTOM-RIGHT ANALOG PEDALS ─
        brakeBtn = AnalogPedalView(this, "BRAKE", "LT", BRAKE_NORM, BRAKE_PRESS, curveExpo = 1.0f)
        brakeBtn.onValueChanged = { v -> if (!isEditLayoutMode) brakeValue = v }
        rootFrame.addView(
            brakeBtn,
            flp(dp(122), dp(178), Gravity.START or Gravity.BOTTOM).apply {
                leftMargin = dp(12)
                bottomMargin = dp(12)
            }
        )
        registerEditable("brake_pedal", "Brake (LT)", brakeBtn)

        gasBtn = AnalogPedalView(this, "GAS", "RT", GAS_NORM, GAS_PRESS, curveExpo = 1.45f)
        gasBtn.onValueChanged = { v -> if (!isEditLayoutMode) gasValue = v }
        rootFrame.addView(
            gasBtn,
            flp(dp(122), dp(178), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(12)
                bottomMargin = dp(12)
            }
        )
        registerEditable("gas_pedal", "Gas (RT)", gasBtn)

        // ─── 4. INNER-LEFT XBOX D-PAD CLUSTER ────────────
        val dpadCluster = buildXboxDPad()
        rootFrame.addView(
            dpadCluster,
            flp(dp(114), dp(114), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(144)
                topMargin = dp(-22)
            }
        )
        registerEditable("dpad", "D-Pad Cluster", dpadCluster)

        // ─── 5. INNER-RIGHT 3D XBOX ABXY CLUSTER ─────────
        val abxyCluster = buildXboxABXY()
        rootFrame.addView(
            abxyCluster,
            flp(dp(120), dp(120), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(144)
                topMargin = dp(-22)
            }
        )
        registerEditable("abxy", "ABXY Buttons", abxyCluster)

        // ─── 6. BOTTOM CENTER CONTROL CONSOLE ────────────
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val settingsBtn = XboxButtonView(this, "CFG", "SETUP", SYS_COL, isCircular = false).apply {
            onPressChanged = { pressed -> if (!pressed && !isEditLayoutMode) showSettingsDialog() }
        }
        bottomBar.addView(settingsBtn, llp(dp(58), dp(44)).apply { rightMargin = dp(6) })

        val viewBtn = XboxButtonView(this, "VIEW", "SELECT", SYS_COL, isCircular = false).apply {
            onPressChanged = { pressed -> if (!isEditLayoutMode) sender.sendButton(pressed, Protocol.BTN_SELECT) }
        }
        bottomBar.addView(viewBtn, llp(dp(62), dp(44)).apply { rightMargin = dp(6) })

        val hbBtn = XboxButtonView(this, "E-BRAKE", "HOLD", HB_COL, isCircular = false).apply {
            onPressChanged = { pressed -> if (!isEditLayoutMode) sender.sendButton(pressed, Protocol.BTN_A) }
        }
        bottomBar.addView(hbBtn, llp(dp(98), dp(46)).apply { rightMargin = dp(6) })

        val recenterBtn = XboxButtonView(this, "CENTER", "GYRO", Color.parseColor("#38bdf8"), isCircular = false).apply {
            onPressChanged = { pressed ->
                if (pressed && !isEditLayoutMode) {
                    sensorReader.recenter()
                    sender.sendCalibrate()
                    showToast("Gyro centered")
                }
            }
        }
        bottomBar.addView(recenterBtn, llp(dp(70), dp(44)).apply { rightMargin = dp(6) })

        val startBtn = XboxButtonView(this, "MENU", "START", SYS_COL, isCircular = false).apply {
            onPressChanged = { pressed -> if (!isEditLayoutMode) sender.sendButton(pressed, Protocol.BTN_START) }
        }
        bottomBar.addView(startBtn, llp(dp(62), dp(44)))

        rootFrame.addView(
            bottomBar,
            flp(WRAP, dp(48), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
                bottomMargin = dp(8)
            }
        )
        registerEditable("bottom_bar", "Bottom Console", bottomBar)

        // ─── 7. FLOATING HUD EDITOR TOOLBAR (Hidden until Edit Mode) ───
        editBanner = buildEditHudBanner()
        editBanner.visibility = View.GONE
        rootFrame.addView(
            editBanner,
            flp(WRAP, dp(46), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = dp(6)
            }
        )

        return rootFrame
    }

    private fun registerEditable(key: String, title: String, view: View) {
        editableWidgets.add(EditableWidget(key, title, view))
    }

    private fun buildEditHudBanner(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6111827"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        editSelectedLabel = TextView(this).apply {
            text = "Drag any element"
            textSize = 12f
            setTextColor(Color.parseColor("#38bdf8"))
            setPadding(0, 0, dp(10), 0)
        }
        bar.addView(editSelectedLabel)

        val sizeTxt = TextView(this).apply {
            text = "Size:"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        bar.addView(sizeTxt)

        editScaleBar = SeekBar(this).apply {
            max = 90 // maps to 0.60x .. 1.50x
            progress = 40 // 1.00x
        }
        editScaleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val target = selectedWidget ?: return
                val scale = (0.60f + progress / 100f).coerceIn(0.60f, 1.50f)
                applyWidgetScale(target.view, scale)
                editSelectedLabel.text = "${target.title} (${(scale * 100).roundToInt()}%)"
                rootFrame.invalidate()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        bar.addView(editScaleBar, llp(dp(130), WRAP).apply { rightMargin = dp(8) })

        val resetBtn = Button(this).apply {
            text = "Reset"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener {
                resetHudLayout()
                showToast("HUD layout reset to default")
            }
        }
        bar.addView(resetBtn, llp(WRAP, dp(34)).apply { rightMargin = dp(8) })

        val saveBtn = Button(this).apply {
            text = "Save HUD"
            textSize = 11f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#00e676"))
            setPadding(dp(12), dp(2), dp(12), dp(2))
            setOnClickListener {
                saveHudLayout()
                exitEditLayoutMode()
                showToast("Custom HUD saved")
            }
        }
        bar.addView(saveBtn, llp(WRAP, dp(34)))

        return bar
    }

    private fun buildXboxDPad(): FrameLayout {
        val s = dp(38)
        val container = FrameLayout(this)
        val dpadColor = Color.parseColor("#94a3b8")

        val up = XboxButtonView(this, "▲", "", dpadColor, isCircular = false).apply {
            onPressChanged = { p -> if (!isEditLayoutMode) sender.sendButton(p, Protocol.BTN_DPAD_U) }
        }
        container.addView(up, flp(s, s).apply { leftMargin = s; topMargin = 0 })

        val down = XboxButtonView(this, "▼", "", dpadColor, isCircular = false).apply {
            onPressChanged = { p -> if (!isEditLayoutMode) sender.sendButton(p, Protocol.BTN_DPAD_D) }
        }
        container.addView(down, flp(s, s).apply { leftMargin = s; topMargin = s * 2 })

        val left = XboxButtonView(this, "◄", "", dpadColor, isCircular = false).apply {
            onPressChanged = { p -> if (!isEditLayoutMode) sender.sendButton(p, Protocol.BTN_DPAD_L) }
        }
        container.addView(left, flp(s, s).apply { leftMargin = 0; topMargin = s })

        val right = XboxButtonView(this, "►", "", dpadColor, isCircular = false).apply {
            onPressChanged = { p -> if (!isEditLayoutMode) sender.sendButton(p, Protocol.BTN_DPAD_R) }
        }
        container.addView(right, flp(s, s).apply { leftMargin = s * 2; topMargin = s })

        return container
    }

    private fun buildXboxABXY(): FrameLayout {
        val s = dp(42)
        val span = dp(120)
        val mid = (span - s) / 2
        val container = FrameLayout(this)

        // Y (Top - Amber)
        val btnY = XboxButtonView(this, "Y", "CAM", XBOX_Y, isCircular = true).apply {
            onPressChanged = { p -> if (!isEditLayoutMode) sender.sendButton(p, Protocol.BTN_Y) }
        }
        container.addView(btnY, flp(s, s).apply { leftMargin = mid; topMargin = 0 })

        // X (Left - Blue)
        val btnX = XboxButtonView(this, "X", "", XBOX_X, isCircular = true).apply {
            onPressChanged = { p -> if (!isEditLayoutMode) sender.sendButton(p, Protocol.BTN_X) }
        }
        container.addView(btnX, flp(s, s).apply { leftMargin = 0; topMargin = mid })

        // B (Right - Red)
        val btnB = XboxButtonView(this, "B", "LOOK", XBOX_B, isCircular = true).apply {
            onPressChanged = { p -> if (!isEditLayoutMode) sender.sendButton(p, Protocol.BTN_B) }
        }
        container.addView(btnB, flp(s, s).apply { leftMargin = span - s; topMargin = mid })

        // A (Bottom - Green)
        val btnA = XboxButtonView(this, "A", "", XBOX_A, isCircular = true).apply {
            onPressChanged = { p -> if (!isEditLayoutMode) sender.sendButton(p, Protocol.BTN_A) }
        }
        container.addView(btnA, flp(s, s).apply { leftMargin = mid; topMargin = span - s })

        return container
    }

    // ═══════════════════════════════════════════════════
    // INTERACTIVE HUD EDITOR (DRAG, SCALE, SAVE, RESET)
    // ═══════════════════════════════════════════════════

    private fun enterEditLayoutMode() {
        isEditLayoutMode = true
        gasValue = 0f
        brakeValue = 0f
        editBanner.visibility = View.VISIBLE
        selectEditableWidget(editableWidgets.firstOrNull())
        rootFrame.invalidate()
        showToast("Drag any element to move, use top slider to resize!")
    }

    private fun exitEditLayoutMode() {
        isEditLayoutMode = false
        selectedWidget = null
        editBanner.visibility = View.GONE
        rootFrame.invalidate()
    }

    private fun selectEditableWidget(w: EditableWidget?) {
        selectedWidget = w
        if (w != null) {
            val scale = w.view.scaleX
            editSelectedLabel.text = "${w.title} (${(scale * 100).roundToInt()}%)"
            editScaleBar.progress = ((scale - 0.60f) * 100f).roundToInt().coerceIn(0, 90)
        }
        rootFrame.invalidate()
    }

    private fun applyWidgetScale(v: View, scale: Float) {
        v.scaleX = scale
        v.scaleY = scale
        if (v is XboxButtonView) {
            v.baseScale = scale
        }
    }

    private fun saveHudLayout() {
        val prefs = getSharedPreferences("phoneel_layout", MODE_PRIVATE).edit()
        for (w in editableWidgets) {
            prefs.putFloat("${w.key}_dx", w.view.translationX)
            prefs.putFloat("${w.key}_dy", w.view.translationY)
            prefs.putFloat("${w.key}_scale", w.view.scaleX)
        }
        prefs.apply()
    }

    private fun loadHudLayout() {
        val prefs = getSharedPreferences("phoneel_layout", MODE_PRIVATE)
        for (w in editableWidgets) {
            val dx = prefs.getFloat("${w.key}_dx", 0f)
            val dy = prefs.getFloat("${w.key}_dy", 0f)
            val sc = prefs.getFloat("${w.key}_scale", 1.0f).coerceIn(0.60f, 1.50f)
            w.view.translationX = dx
            w.view.translationY = dy
            applyWidgetScale(w.view, sc)
        }
    }

    private fun resetHudLayout() {
        getSharedPreferences("phoneel_layout", MODE_PRIVATE).edit().clear().apply()
        for (w in editableWidgets) {
            w.view.translationX = 0f
            w.view.translationY = 0f
            applyWidgetScale(w.view, 1.0f)
        }
        selectedWidget?.let { selectEditableWidget(it) }
        rootFrame.invalidate()
    }

    /**
     * Custom root FrameLayout that intercepts touches during `isEditLayoutMode`
     * to allow smooth drag-and-drop positioning of any cockpit widget and draws alignment outlines.
     */
    inner class CockpitFrameLayout(context: Context) : FrameLayout(context) {
        private var dragTarget: EditableWidget? = null
        private var downRawX = 0f
        private var downRawY = 0f
        private var startTransX = 0f
        private var startTransY = 0f

        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1e293b")
            strokeWidth = 1.5f
        }
        private val boxRect = RectF()

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!isEditLayoutMode) return super.onInterceptTouchEvent(ev)
            // Let touches on the top editBanner pass through to its buttons/slider
            if (isTouchInsideView(ev.rawX, ev.rawY, editBanner)) {
                return false
            }
            return true
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!isEditLayoutMode) return super.onTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = findHitWidget(ev.rawX, ev.rawY)
                    dragTarget = hit
                    if (hit != null) {
                        selectEditableWidget(hit)
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startTransX = hit.view.translationX
                        startTransY = hit.view.translationY
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val target = dragTarget ?: return true
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    target.view.translationX = startTransX + dx
                    target.view.translationY = startTransY + dy
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragTarget = null
                    return true
                }
            }
            return true
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (isEditLayoutMode) {
                // Draw subtle center crosshair & grid lines
                val cx = width / 2f
                val cy = height / 2f
                canvas.drawLine(cx, 0f, cx, height.toFloat(), gridPaint)
                canvas.drawLine(0f, cy, width.toFloat(), cy, gridPaint)
            }

            super.dispatchDraw(canvas)

            if (isEditLayoutMode) {
                for (w in editableWidgets) {
                    val v = w.view
                    val halfW = (v.width * v.scaleX) / 2f
                    val halfH = (v.height * v.scaleY) / 2f
                    val centerX = v.left + v.width / 2f + v.translationX
                    val centerY = v.top + v.height / 2f + v.translationY
                    boxRect.set(centerX - halfW - 4f, centerY - halfH - 4f, centerX + halfW + 4f, centerY + halfH + 4f)

                    val isSel = (w == selectedWidget)
                    dashPaint.color = if (isSel) Color.parseColor("#00e676") else Color.parseColor("#38bdf8")
                    dashPaint.strokeWidth = if (isSel) 4.5f else 2.5f
                    canvas.drawRoundRect(boxRect, 12f, 12f, dashPaint)
                }
            }
        }

        private fun findHitWidget(rawX: Float, rawY: Float): EditableWidget? {
            for (i in editableWidgets.indices.reversed()) {
                val w = editableWidgets[i]
                if (isTouchInsideView(rawX, rawY, w.view)) {
                    return w
                }
            }
            return null
        }

        private fun isTouchInsideView(rawX: Float, rawY: Float, v: View): Boolean {
            if (v.visibility != View.VISIBLE) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val halfW = (v.width * v.scaleX) / 2f
            val halfH = (v.height * v.scaleY) / 2f
            val cx = loc[0] + v.width / 2f
            val cy = loc[1] + v.height / 2f
            return rawX >= (cx - halfW) && rawX <= (cx + halfW) && rawY >= (cy - halfH) && rawY <= (cy + halfH)
        }
    }

    // ═══════════════════════════════════════════════════
    // 60 FPS DDU RENDER LOOP
    // ═══════════════════════════════════════════════════

    private fun startUiRenderLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return

                val now = System.currentTimeMillis()
                val elapsed = (now - lastPpsTime) / 1000.0
                if (elapsed >= 0.5) {
                    displayPps = (ppsCounter / elapsed).toInt()
                    ppsCounter = 0
                    lastPpsTime = now
                }

                centerDdu.steering = sensorReader.steering
                centerDdu.telemetry = latestTelemetry
                centerDdu.hz = displayPps
                centerDdu.connected = sender.connected
                centerDdu.transportLabel = if (sender.currentMode == UdpSender.Mode.USB_TCP) "USB" else "WiFi"
                centerDdu.invalidate()

                handler.postDelayed(this, 16) // 60 FPS smooth DDU refresh
            }
        }, 16)
    }

    // ═══════════════════════════════════════════════════
    // CONNECTION & SETTINGS DIALOGS
    // ═══════════════════════════════════════════════════

    private fun showConnectionDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val ipInput = EditText(this).apply {
            hint = "PC IP (e.g. 192.168.1.109)"
            setText(savedIp)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 16f
        }
        layout.addView(ipInput)

        val findStatus = TextView(this).apply {
            text = "USB mode uses ADB reverse port forwarding on port 9999."
            textSize = 12f
            setTextColor(Color.parseColor("#38bdf8"))
            setPadding(0, dp(6), 0, 0)
        }

        val findBtn = Button(this).apply {
            text = "Scan LAN (Wi-Fi)"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1e293b"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        findBtn.setOnClickListener {
            findBtn.isEnabled = false
            findBtn.text = "Scanning LAN..."
            thread {
                val servers = Discovery.scan(timeoutMs = 2000)
                handler.post {
                    findBtn.isEnabled = true
                    findBtn.text = "Scan LAN (Wi-Fi)"
                    if (servers.isNotEmpty()) {
                        val server = servers.first()
                        ipInput.setText(server.ip)
                        findStatus.text = "Found: ${server.hostname} (${server.ip})"
                        findStatus.setTextColor(Color.GREEN)
                    } else {
                        findStatus.text = "No server found on LAN. Enter PC IP manually or use USB."
                        findStatus.setTextColor(Color.parseColor("#f87171"))
                    }
                }
            }
        }
        layout.addView(findBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        layout.addView(findStatus)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Phoneel v0.1-beta — Connection")
            .setView(layout)
            .setPositiveButton("USB (ADB)") { _, _ -> connectUsb() }
            .setNeutralButton("Wi-Fi (UDP)") { _, _ ->
                val ip = ipInput.text.toString().trim()
                if (ip.isNotEmpty()) connectWifi(ip)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    private fun connectWifi(ip: String) {
        savedIp = ip
        getSharedPreferences("phoneel", MODE_PRIVATE).edit()
            .putString("last_ip", ip).apply()
        thread {
            sender.connectWifi(ip)
            handler.post {
                if (sender.connected) showToast("Connected (Wi-Fi UDP)")
                else showToast("Connecting to $ip...")
            }
        }
    }

    private fun connectUsb() {
        thread {
            sender.connectUsb()
            handler.post {
                if (sender.connected) showToast("Connected (USB ADB)")
                else showToast("Waiting for USB ADB tunnel...")
            }
        }
    }

    /**
     * Expanded Steering Scale (v2.1):
     * Sens 1 = ±100° (ultra relaxed), Sens 4 (Default) = ±80°, Sens 10 = ±40° (F1 quick rack).
     */
    private fun sensitivityToDegrees(sens: Int): Int {
        return (100.0 - (sens - 1) * (60.0 / 9.0)).roundToInt()
    }

    private fun applyDrivingSettings() {
        val maxDeg = sensitivityToDegrees(sensitivity).toDouble()
        sensorReader.maxAngle = Math.toRadians(maxDeg).toFloat()
        sensorReader.invertSteering = invertSteering
        gasBtn.curveExpo = if (tractionCurveEnabled) 1.45f else 1.0f
    }

    private fun saveSettings() {
        getSharedPreferences("phoneel", MODE_PRIVATE).edit()
            .putInt("sensitivity_v21", sensitivity)
            .putBoolean("invert_steering", invertSteering)
            .putBoolean("haptic_ffb", hapticFfbEnabled)
            .putBoolean("traction_curve", tractionCurveEnabled)
            .apply()
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(8))
        }

        val maxDegInit = sensitivityToDegrees(sensitivity)
        val sensLabel = TextView(this).apply {
            text = "Steering Sensitivity: $sensitivity / 10  (±${maxDegInit}° Full Lock)"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        layout.addView(sensLabel)

        val sensBar = SeekBar(this).apply {
            max = 9
            progress = (sensitivity - 1).coerceIn(0, 9)
            setPadding(0, dp(6), 0, dp(6))
        }
        sensBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                sensitivity = progress + 1
                val deg = sensitivityToDegrees(sensitivity)
                sensLabel.text = "Steering Sensitivity: $sensitivity / 10  (±${deg}° Full Lock)"
                applyDrivingSettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        layout.addView(sensBar)

        val ffbCheck = CheckBox(this).apply {
            text = "Haptic Force Feedback (Tire Slip & Kerb Vibration)"
            isChecked = hapticFfbEnabled
            setTextColor(Color.WHITE)
        }
        ffbCheck.setOnCheckedChangeListener { _, checked ->
            hapticFfbEnabled = checked
        }
        layout.addView(ffbCheck)

        val tractionCheck = CheckBox(this).apply {
            text = "Progressive Throttle Curve (Smooth Corner-Exit Gas)"
            isChecked = tractionCurveEnabled
            setTextColor(Color.WHITE)
        }
        tractionCheck.setOnCheckedChangeListener { _, checked ->
            tractionCurveEnabled = checked
            applyDrivingSettings()
        }
        layout.addView(tractionCheck)

        val invertCheck = CheckBox(this).apply {
            text = "Invert Steering Axis"
            isChecked = invertSteering
            setTextColor(Color.WHITE)
        }
        invertCheck.setOnCheckedChangeListener { _, checked ->
            invertSteering = checked
            applyDrivingSettings()
        }
        layout.addView(invertCheck)

        // Button Row: Edit HUD Layout + Switch Connection
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        val editHudBtn = Button(this).apply {
            text = "Edit HUD Layout"
            textSize = 12f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#38bdf8"))
        }
        btnRow.addView(editHudBtn, llp(0, WRAP, 1f).apply { rightMargin = dp(8) })

        val reconnectBtn = Button(this).apply {
            text = "Connection"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1e293b"))
        }
        btnRow.addView(reconnectBtn, llp(0, WRAP, 1f))
        layout.addView(btnRow)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Cockpit & Driving Assists")
            .setView(layout)
            .setPositiveButton("Done") { _, _ -> saveSettings() }
            .setOnDismissListener { saveSettings() }
            .create()

        editHudBtn.setOnClickListener {
            dialog.dismiss()
            enterEditLayoutMode()
        }

        reconnectBtn.setOnClickListener {
            dialog.dismiss()
            showConnectionDialog()
        }

        dialog.show()
    }

    // ─── Helpers ──────────────────────────────────────

    private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

    private fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) =
        FrameLayout.LayoutParams(w, h, gravity)

    private fun llp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
