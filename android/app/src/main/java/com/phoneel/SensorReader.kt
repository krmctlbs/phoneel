package com.phoneel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Steering-focused sensor reader for racing games.
 *
 * Uses Rotation Vector sensor with landscape coordinate remapping and
 * tilt-invariant gravity projection so steering remains 1:1 and free of
 * Euler gimbal lock whether held upright (60°–90° steering wheel posture)
 * or flat.
 *
 * Runs on a dedicated background thread for direct network I/O.
 */
class SensorReader(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Prefer GAME_ROTATION_VECTOR (no magnetic interference → less drift)
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Background thread for sensor callbacks
    private val sensorThread = HandlerThread("phoneel-sensor").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    // Pre-allocated buffers (avoid GC during hot loop)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)

    // Sign of in-screen vertical gravity component in upright landscape hold (+1f in standard landscape)
    @Volatile private var dirY: Float = 1f

    // ─── Output ────────────────────────────────────────
    /** Current steering value: -1.0 (full left) to +1.0 (full right) */
    @Volatile var steering: Float = 0f
        private set

    /** Raw roll angle in radians (before normalization) */
    @Volatile var rawRoll: Float = 0f
        private set

    @Volatile var centerOffset: Float = 0f

    fun recenter() {
        centerOffset = rawRoll
    }

    // ─── Config ────────────────────────────────────────
    /** Tilt angle (radians) for full steering lock. Default ±80° (Sens 4) */
    var maxAngle: Float = Math.toRadians(80.0).toFloat()

    /** Flip steering direction if phone orientation is reversed */
    var invertSteering: Boolean = false

    // ─── Callback ──────────────────────────────────────
    /** Called on every sensor update with current steering value */
    var onSteeringUpdate: ((steering: Float) -> Unit)? = null

    // ─── Stats ─────────────────────────────────────────
    @Volatile var updateCount: Long = 0
        private set

    val isAvailable: Boolean get() = rotationSensor != null

    // ─── Lifecycle ─────────────────────────────────────

    fun start() {
        val sensor = rotationSensor
        if (sensor == null) {
            Log.e("phoneel", "No rotation sensor available")
            return
        }
        sensorManager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_FASTEST,
            sensorHandler
        )
        Log.i("phoneel", "Steering sensor started (landscape, ${sensor.name})")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun destroy() {
        stop()
        sensorThread.quitSafely()
    }

    // ─── Sensor Callback ───────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        // 1. Get rotation matrix from sensor
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // 2. Remap for landscape orientation (phone rotated 90° CCW)
        //    New screen X = device Y axis
        //    New screen Y = device -X axis
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_Y,
            SensorManager.AXIS_MINUS_X,
            remappedMatrix
        )

        // 3. Extract world-Up vector in landscape device frame:
        //    ux = lateral axis, uy = vertical in-screen axis (+sin(θ) when upright), uz = screen normal (+cos(θ))
        val ux = remappedMatrix[6]
        val uy = remappedMatrix[7]
        val uz = remappedMatrix[8]

        if (abs(ux) < 0.30f && abs(uy) > 0.50f) {
            dirY = if (uy >= 0f) 1f else -1f
        }

        // Smooth continuous blend between upright hold (uz <= 0.70, θ >= 45°) and flat desk hold (uz >= 0.85, θ <= 31°)
        val tFlat = ((uz - 0.70f) / 0.15f).coerceIn(0f, 1f)
        val denom = (1f - tFlat) * (dirY * uy) + tFlat * uz
        val angle = atan2(-ux, denom)
        rawRoll = angle

        var roll = angle - centerOffset
        val pi = PI.toFloat()
        if (roll > pi) roll -= 2f * pi
        else if (roll < -pi) roll += 2f * pi

        if (invertSteering) roll = -roll

        // 4. Normalize to [-1, +1] based on maxAngle
        steering = (roll / maxAngle).coerceIn(-1f, 1f)

        updateCount++
        onSteeringUpdate?.invoke(steering)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
