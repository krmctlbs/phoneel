package com.phoneel

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * phoneel Binary Protocol
 * Little-endian binary packet definitions for control inputs and telemetry.
 */
object Protocol {
    // ─── Packet Types ─────────────────────────────────
    const val SENSOR: Byte      = 0x01
    const val BTN_PRESS: Byte   = 0x02
    const val BTN_RELEASE: Byte = 0x03
    const val TOUCH: Byte       = 0x04
    const val DISC_PING: Byte   = 0x05
    const val DISC_PONG: Byte   = 0x06
    const val CALIBRATE: Byte   = 0x07
    const val MODE_SWITCH: Byte = 0x08
    const val HEARTBEAT: Byte   = 0x09
    const val TELEMETRY: Byte   = 0x0A

    // ─── Button IDs (matches Xbox 360 layout) ─────────
    const val BTN_A: Short      = 0x0001
    const val BTN_B: Short      = 0x0002
    const val BTN_X: Short      = 0x0003
    const val BTN_Y: Short      = 0x0004
    const val BTN_LB: Short     = 0x0005  // Shift Down
    const val BTN_RB: Short     = 0x0006  // Shift Up
    const val BTN_LT: Short     = 0x0007  // Brake (digital fallback)
    const val BTN_RT: Short     = 0x0008  // Gas (digital fallback)
    const val BTN_START: Short  = 0x0009
    const val BTN_SELECT: Short = 0x000A
    const val BTN_L3: Short     = 0x000B
    const val BTN_R3: Short     = 0x000C
    const val BTN_DPAD_U: Short = 0x000D
    const val BTN_DPAD_D: Short = 0x000E
    const val BTN_DPAD_L: Short = 0x000F
    const val BTN_DPAD_R: Short = 0x0010

    // ─── Telemetry Flags ──────────────────────────────
    const val TELEM_FLAG_ACTIVE      = 1 shl 0
    const val TELEM_FLAG_PIT_LIMITER = 1 shl 1
    const val TELEM_FLAG_DRS         = 1 shl 2
    const val TELEM_FLAG_TC          = 1 shl 3
    const val TELEM_FLAG_ABS         = 1 shl 4
    const val TELEM_FLAG_SHIFT_CUE   = 1 shl 5

    // ─── Ports ────────────────────────────────────────
    const val DEFAULT_PORT = 9999
    const val DISCOVERY_PORT = 9998
    const val SIZE_TELEMETRY = 21     // 21B base frame (+2B optional FFB channels = 23B)

    data class TelemetryData(
        val speedKmh: Float = 0f,
        val rpm: Int = 0,
        val maxRpm: Int = 8500,
        val gear: Int = 1,          // 0=R, 1=N, 2=1st, 3=2nd...
        val flags: Int = 0,
        val lapTimeMs: Int = 0,
        val bestLapMs: Int = 0,
        val position: Int = 0,
        val ffbSlip: Int = 0,       // 0..255 rear/front tire slip & spin onset intensity
        val ffbKerb: Int = 0        // 0..255 kerb / suspension bump / gear shift pulse
    ) {
        val acActive: Boolean get() = (flags and TELEM_FLAG_ACTIVE) != 0
        val pitLimiter: Boolean get() = (flags and TELEM_FLAG_PIT_LIMITER) != 0
        val drsActive: Boolean get() = (flags and TELEM_FLAG_DRS) != 0
        val tcActive: Boolean get() = (flags and TELEM_FLAG_TC) != 0
        val absActive: Boolean get() = (flags and TELEM_FLAG_ABS) != 0
        val shiftCue: Boolean get() = (flags and TELEM_FLAG_SHIFT_CUE) != 0
    }

    // ─── Packet Builders ──────────────────────────────

    /**
     * Sensor packet (17 bytes) — used for steering + gas + brake
     * In racing mode: yaw=steering, pitch=gas, roll=brake
     */
    fun buildSensorPacket(steering: Float, gas: Float, brake: Float, timestampMs: Int): ByteArray {
        val buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(SENSOR)
        buf.putFloat(steering)
        buf.putFloat(gas)
        buf.putFloat(brake)
        buf.putInt(timestampMs)
        return buf.array()
    }

    /**
     * Button packet (3 bytes)
     */
    fun buildButtonPacket(pressed: Boolean, buttonId: Short): ByteArray {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(if (pressed) BTN_PRESS else BTN_RELEASE)
        buf.putShort(buttonId)
        return buf.array()
    }

    /**
     * Discovery ping (5 bytes)
     */
    fun buildDiscoveryPing(port: Int): ByteArray {
        val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(DISC_PING)
        buf.putInt(port)
        return buf.array()
    }

    /**
     * Parse discovery pong → hostname
     */
    fun parseDiscoveryPong(data: ByteArray): String? {
        if (data.isEmpty() || data[0] != DISC_PONG) return null
        return String(data, 1, data.size - 1, Charsets.UTF_8)
    }

    /**
     * Heartbeat (5 bytes)
     */
    fun buildHeartbeatPacket(timestampMs: Int): ByteArray {
        val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(HEARTBEAT)
        buf.putInt(timestampMs)
        return buf.array()
    }

    /**
     * Calibrate / Re-center packet (1 byte)
     */
    fun buildCalibratePacket(): ByteArray {
        return byteArrayOf(CALIBRATE)
    }

    /**
     * Parse 21- or 23-byte Telemetry packet (0x0A) from PC
     */
    fun parseTelemetryPacket(data: ByteArray, length: Int = data.size): TelemetryData? {
        if (length < SIZE_TELEMETRY || data[0] != TELEMETRY) return null
        return try {
            val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            buf.get() // skip type byte (0x0A)
            val speed = buf.float
            val rpm = buf.short.toInt() and 0xFFFF
            val maxRpm = buf.short.toInt() and 0xFFFF
            val gear = buf.get().toInt()
            val flags = buf.get().toInt() and 0xFF
            val lapMs = buf.int
            val bestMs = buf.int
            val pos = buf.short.toInt() and 0xFFFF
            val slip = if (length >= 23) (buf.get().toInt() and 0xFF) else 0
            val kerb = if (length >= 23) (buf.get().toInt() and 0xFF) else 0
            TelemetryData(speed, rpm, maxRpm, gear, flags, lapMs, bestMs, pos, slip, kerb)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Wrap packet with 2-byte length prefix for TCP transport
     */
    fun wrapForTcp(data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(2 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(data.size.toShort())
        buf.put(data)
        return buf.array()
    }
}
