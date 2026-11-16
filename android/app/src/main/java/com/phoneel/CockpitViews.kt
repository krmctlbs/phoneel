package com.phoneel

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Center F1 / GT3 Digital Dash Unit (DDU) Telemetry View
 * Renders at 60 FPS:
 *  - 15-LED F1 Shift Light Bar (Green → Yellow → Red → Blue flash at redline)
 *  - Giant Center Gear Indicator (R, N, 1..8)
 *  - Digital Speedometer (KM/H) & RPM Bar
 *  - Live Steering Angle Arc & Degree Readout
 *  - Lap Timer (Current / Best) & Status Badges (DRS, TC, ABS, FFB SLIP, AC LIVE)
 */
class CenterDduView(context: Context) : View(context) {

    var telemetry = Protocol.TelemetryData()
    var steering: Float = 0f     // -1f to 1f
    var hz: Int = 0
    var connected: Boolean = false
    var transportLabel: String = "OFFLINE"

    private val cardRect = RectF()
    private val ledRect = RectF()
    private val steerTrackRect = RectF()
    private val steerFillRect = RectF()
    private val badgeRect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0c1018")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#243046")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val accentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1a2336")
        strokeWidth = 2f
    }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffea00")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val speedValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7889a4")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38bdf8")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val steerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00e676")
        style = Paint.Style.FILL
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 10f || h <= 10f) return

        val corner = h * 0.07f
        cardRect.set(3f, 3f, w - 3f, h - 3f)

        // 1. Carbon Dark Cockpit Panel Background + Bezel (flashes warm red on heavy rear slip!)
        canvas.drawRoundRect(cardRect, corner, corner, bgPaint)
        borderPaint.color = when {
            telemetry.ffbSlip > 110 -> Color.parseColor("#ff1744")
            telemetry.ffbKerb > 90  -> Color.parseColor("#38bdf8")
            else                    -> Color.parseColor("#243046")
        }
        canvas.drawRoundRect(cardRect, corner, corner, borderPaint)

        // 2. Top 15-LED F1 RPM Shift Strip
        val numLeds = 15
        val padX = w * 0.05f
        val stripW = w - padX * 2f
        val ledGap = stripW * 0.012f
        val ledW = (stripW - ledGap * (numLeds - 1)) / numLeds
        val ledTop = h * 0.06f
        val ledBottom = h * 0.145f

        val maxRpm = max(3000, telemetry.maxRpm)
        val rpmRatio = (telemetry.rpm.toFloat() / maxRpm.toFloat()).coerceIn(0f, 1f)
        val normShift = ((rpmRatio - 0.40f) / 0.56f).coerceIn(0f, 1f)
        val activeLeds = (normShift * numLeds).toInt()
        val shiftFlash = rpmRatio >= 0.95f && ((System.currentTimeMillis() / 70L) % 2L == 0L)

        for (i in 0 until numLeds) {
            val lx = padX + i * (ledW + ledGap)
            ledRect.set(lx, ledTop, lx + ledW, ledBottom)

            if (shiftFlash) {
                ledPaint.color = Color.parseColor("#00e5ff")
            } else if (i < activeLeds) {
                ledPaint.color = when {
                    i < 5  -> Color.parseColor("#00e676") // Green
                    i < 10 -> Color.parseColor("#ffea00") // Yellow
                    i < 13 -> Color.parseColor("#ff1744") // Red
                    else   -> Color.parseColor("#2979ff") // Blue Redline
                }
            } else {
                ledPaint.color = Color.parseColor("#182030")
            }
            canvas.drawRoundRect(ledRect, 4f, 4f, ledPaint)
        }

        // Divider below RPM LEDs
        val divY1 = h * 0.18f
        canvas.drawLine(padX, divY1, w - padX, divY1, accentLinePaint)

        // 3. Center Giant Gear Box (Middle Section)
        val centerX = w * 0.5f
        val gearBoxW = w * 0.24f
        canvas.drawLine(centerX - gearBoxW / 2, divY1, centerX - gearBoxW / 2, h * 0.75f, accentLinePaint)
        canvas.drawLine(centerX + gearBoxW / 2, divY1, centerX + gearBoxW / 2, h * 0.75f, accentLinePaint)

        labelPaint.textSize = h * 0.055f
        canvas.drawText("GEAR", centerX, h * 0.255f, labelPaint)

        val gearStr = when (telemetry.gear) {
            0 -> "R"
            1 -> "N"
            else -> (telemetry.gear - 1).toString()
        }
        gearPaint.textSize = h * 0.36f
        gearPaint.color = if (shiftFlash) Color.parseColor("#00e5ff") else Color.parseColor("#ffea00")
        canvas.drawText(gearStr, centerX, h * 0.59f, gearPaint)

        // RPM numeric readout under Gear
        valuePaint.textSize = h * 0.068f
        valuePaint.color = Color.parseColor("#94a3b8")
        canvas.drawText("${telemetry.rpm} RPM", centerX, h * 0.705f, valuePaint)

        // 4. Left Wing: Digital Speedometer (KM/H)
        val leftX = (padX + (centerX - gearBoxW / 2)) * 0.5f
        labelPaint.textSize = h * 0.055f
        canvas.drawText("SPEED", leftX, h * 0.26f, labelPaint)

        speedValPaint.textSize = h * 0.23f
        canvas.drawText("${telemetry.speedKmh.toInt()}", leftX, h * 0.50f, speedValPaint)

        labelPaint.textSize = h * 0.058f
        canvas.drawText("KM/H", leftX, h * 0.58f, labelPaint)

        // Connection + Hz badge on bottom-left wing
        val connColor = if (connected) Color.parseColor("#00c853") else Color.parseColor("#ff1744")
        badgePaint.color = Color.parseColor("#151e2e")
        badgeRect.set(leftX - w * 0.145f, h * 0.635f, leftX + w * 0.145f, h * 0.725f)
        canvas.drawRoundRect(badgeRect, 8f, 8f, badgePaint)
        badgeTextPaint.textSize = h * 0.042f
        badgeTextPaint.color = connColor
        val hzText = if (connected) "$transportLabel • ${hz}Hz" else "OFFLINE"
        canvas.drawText(hzText, leftX, h * 0.692f, badgeTextPaint)

        // 5. Right Wing: Lap Time & Electronics (DRS / TC / ABS / Slip)
        val rightX = ((centerX + gearBoxW / 2) + (w - padX)) * 0.5f
        labelPaint.textSize = h * 0.055f
        canvas.drawText("LAP TIME", rightX, h * 0.26f, labelPaint)

        valuePaint.textSize = h * 0.095f
        valuePaint.color = Color.WHITE
        canvas.drawText(formatLapTime(telemetry.lapTimeMs), rightX, h * 0.39f, valuePaint)

        labelPaint.textSize = h * 0.048f
        canvas.drawText("BEST: ${formatLapTime(telemetry.bestLapMs)}", rightX, h * 0.47f, labelPaint)

        // Telemetry status pills (DRS, TC/SLIP, ABS)
        drawStatusPill(canvas, rightX - w * 0.09f, h * 0.53f, w * 0.08f, h * 0.075f, "DRS", telemetry.drsActive, Color.parseColor("#00e676"))
        drawStatusPill(canvas, rightX, h * 0.53f, w * 0.08f, h * 0.075f, "TC", telemetry.tcActive || telemetry.ffbSlip > 85, Color.parseColor("#ffab00"))
        drawStatusPill(canvas, rightX + w * 0.09f, h * 0.53f, w * 0.08f, h * 0.075f, "ABS", telemetry.absActive, Color.parseColor("#ff1744"))

        // Live / Sim source pill
        badgePaint.color = if (telemetry.acActive) Color.parseColor("#064e3b") else Color.parseColor("#172554")
        badgeRect.set(rightX - w * 0.145f, h * 0.635f, rightX + w * 0.145f, h * 0.725f)
        canvas.drawRoundRect(badgeRect, 8f, 8f, badgePaint)
        badgeTextPaint.textSize = h * 0.041f
        badgeTextPaint.color = if (telemetry.acActive) Color.parseColor("#34d399") else Color.parseColor("#60a5fa")
        canvas.drawText(if (telemetry.acActive) "● AC LIVE" else "SYNTHETIC", rightX, h * 0.692f, badgeTextPaint)

        // 6. Bottom Section: Precision Steering Horizon Bar + Angle Readout
        val divY2 = h * 0.76f
        canvas.drawLine(padX, divY2, w - padX, divY2, accentLinePaint)

        val trackTop = h * 0.81f
        val trackBottom = h * 0.91f
        val trackR = (trackBottom - trackTop) / 2f
        steerTrackRect.set(padX, trackTop, w - padX, trackBottom)
        ledPaint.color = Color.parseColor("#151c2c")
        canvas.drawRoundRect(steerTrackRect, trackR, trackR, ledPaint)

        val clampedSteer = steering.coerceIn(-1f, 1f)
        val halfSpan = (steerTrackRect.width() * 0.5f) - trackR
        val steerPos = centerX + clampedSteer * halfSpan

        if (abs(clampedSteer) > 0.005f) {
            steerFillRect.set(min(centerX, steerPos), trackTop + 2f, max(centerX, steerPos), trackBottom - 2f)
            steerPaint.color = Color.parseColor("#00b0ff")
            canvas.drawRoundRect(steerFillRect, trackR, trackR, steerPaint)
        }

        // Center notch & thumb marker
        canvas.drawLine(centerX, trackTop - 3f, centerX, trackBottom + 3f, badgeTextPaint)
        steerPaint.color = Color.parseColor("#00e676")
        canvas.drawCircle(steerPos, (trackTop + trackBottom) * 0.5f, trackR * 0.9f, steerPaint)
    }

    private fun drawStatusPill(canvas: Canvas, cx: Float, top: Float, w: Float, h: Float, text: String, active: Boolean, activeColor: Int) {
        badgeRect.set(cx - w / 2, top, cx + w / 2, top + h)
        badgePaint.color = if (active) activeColor else Color.parseColor("#161f30")
        canvas.drawRoundRect(badgeRect, 6f, 6f, badgePaint)
        badgeTextPaint.color = if (active) Color.BLACK else Color.parseColor("#526077")
        badgeTextPaint.textSize = h * 0.62f
        canvas.drawText(text, cx, top + h * 0.72f, badgeTextPaint)
    }

    private fun formatLapTime(ms: Int): String {
        if (ms <= 0) return "--:--.---"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val remMs = ms % 1000
        return String.format("%d:%02d.%03d", min, sec, remMs)
    }
}

/**
 * Tactile 3D Xbox Face Button (A, B, X, Y) & D-Pad / System Button View
 */
class XboxButtonView(
    context: Context,
    val label: String,
    val subLabel: String = "",
    val accentColor: Int,
    val isCircular: Boolean = true
) : View(context) {

    var onPressChanged: ((Boolean) -> Unit)? = null
    private var pressedState = false
    var baseScale: Float = 1.0f

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#05070b")
        style = Paint.Style.FILL
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#b0bec5")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        bodyPaint.color = if (pressedState) accentColor else Color.parseColor("#171d2b")
        rimPaint.color = if (pressedState) Color.WHITE else accentColor
        rimPaint.strokeWidth = if (pressedState) 5f else 3.5f
        textPaint.color = if (pressedState) Color.WHITE else accentColor

        if (isCircular) {
            val r = min(w, h) * 0.44f
            canvas.drawCircle(cx, cy + 3f, r, shadowPaint)
            canvas.drawCircle(cx, cy, r, bodyPaint)
            canvas.drawCircle(cx, cy, r, rimPaint)

            textPaint.textSize = r * 0.95f
            val textOffset = if (subLabel.isEmpty()) r * 0.34f else r * 0.18f
            canvas.drawText(label, cx, cy + textOffset, textPaint)

            if (subLabel.isNotEmpty()) {
                subPaint.textSize = r * 0.36f
                canvas.drawText(subLabel, cx, cy + r * 0.62f, subPaint)
            }
        } else {
            val pad = 4f
            rect.set(pad, pad, w - pad, h - pad)
            val corner = min(w, h) * 0.25f
            canvas.drawRoundRect(rect, corner, corner, bodyPaint)
            canvas.drawRoundRect(rect, corner, corner, rimPaint)

            textPaint.textSize = min(w, h) * 0.36f
            textPaint.color = Color.WHITE
            val yOff = if (subLabel.isEmpty()) textPaint.textSize * 0.35f else 0f
            canvas.drawText(label, cx, cy + yOff, textPaint)

            if (subLabel.isNotEmpty()) {
                subPaint.textSize = min(w, h) * 0.20f
                canvas.drawText(subLabel, cx, cy + min(w, h) * 0.30f, subPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedState = true
                scaleX = baseScale * 0.93f
                scaleY = baseScale * 0.93f
                @Suppress("DEPRECATION")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onPressChanged?.invoke(true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedState) {
                    pressedState = false
                    scaleX = baseScale
                    scaleY = baseScale
                    onPressChanged?.invoke(false)
                    invalidate()
                }
            }
        }
        return true
    }
}

/**
 * Progressive Segmented Analog Trigger Pedal View (LT Brake / RT Gas)
 * Supports non-linear `curveExpo` (e.g. 1.45f on Gas pedal for smooth corner-exit traction control).
 */
class AnalogPedalView(
    context: Context,
    val label: String,
    val triggerTag: String,
    val normColor: Int,
    val pressColor: Int,
    var curveExpo: Float = 1.0f
) : View(context) {

    var value: Float = 0f
        set(v) {
            field = v
            invalidate()
        }
    var onValueChanged: ((Float) -> Unit)? = null

    private val rect = RectF()
    private val segRect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#101520")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = normColor
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pressColor
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = w * 0.14f
        rect.set(3f, 3f, w - 3f, h - 3f)

        // Outer pedal housing
        canvas.drawRoundRect(rect, corner, corner, bgPaint)

        // Progressive segmented fill from bottom to top
        val numSegments = 16
        val pad = 9f
        val innerH = h - pad * 2f
        val segGap = 3.5f
        val segH = (innerH - segGap * (numSegments - 1)) / numSegments
        val litCount = (value * numSegments).toInt()

        for (i in 0 until numSegments) {
            val segBottom = h - pad - i * (segH + segGap)
            val segTop = segBottom - segH
            segRect.set(pad, segTop, w - pad, segBottom)
            if (i < litCount || (value > 0.02f && i == 0)) {
                fillPaint.color = pressColor
                fillPaint.alpha = 230
            } else {
                fillPaint.color = normColor
                fillPaint.alpha = 45
            }
            canvas.drawRoundRect(segRect, 4f, 4f, fillPaint)
        }

        borderPaint.color = if (value > 0.05f) pressColor else normColor
        canvas.drawRoundRect(rect, corner, corner, borderPaint)

        // Labels
        titlePaint.textSize = w * 0.15f
        canvas.drawText("$label ($triggerTag)", w / 2f, h * 0.42f, titlePaint)

        pctPaint.textSize = w * 0.21f
        canvas.drawText("${(value * 100).toInt()}%", w / 2f, h * 0.62f, pctPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                @Suppress("DEPRECATION")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updateFromY(event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromY(event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                value = 0f
                onValueChanged?.invoke(0f)
            }
        }
        return true
    }

    private fun updateFromY(y: Float) {
        val raw = 1f - (y / height.toFloat())
        val linear = ((raw - 0.06f) / 0.88f).coerceIn(0f, 1f)
        val curved = if (curveExpo != 1.0f && linear > 0f) {
            linear.toDouble().pow(curveExpo.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            linear
        }
        value = curved
        onValueChanged?.invoke(curved)
    }
}
