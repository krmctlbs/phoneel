package com.phoneel

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Bidirectional network client for UDP (Wi-Fi) and length-prefixed TCP (USB ADB reverse).
 *
 * - Sensor packets use latest-state conflation (`AtomicReference`) so the transmitter thread
 *   always sends the most recent orientation sample without queue accumulation.
 * - Button and calibration packets use a FIFO queue (`ConcurrentLinkedQueue`).
 * - Receives 23-byte telemetry packets (`0x0A`) at 60 Hz from the PC server.
 */
class UdpSender {

    enum class Mode { WIFI_UDP, USB_TCP }

    @Volatile private var mode = Mode.WIFI_UDP
    @Volatile private var targetHost: String = "127.0.0.1"
    @Volatile private var targetPort: Int = Protocol.DEFAULT_PORT

    // UDP (WiFi)
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var targetAddress: InetAddress? = null

    // TCP (USB via ADB reverse)
    @Volatile private var tcpSocket: Socket? = null
    @Volatile private var tcpOutput: OutputStream? = null
    @Volatile private var tcpInput: InputStream? = null

    // State
    private val isConnected = AtomicBoolean(false)
    private val wantConnected = AtomicBoolean(false)
    val packetsSent = AtomicLong(0)

    // Latest-state conflation slot for sensor data (zero queue buildup)
    private val latestSensorPacket = AtomicReference<ByteArray?>(null)

    // Reliable queue for discrete events (buttons, calibrate, heartbeat)
    private val reliableQueue = ConcurrentLinkedQueue<ByteArray>()

    // Dedicated high-priority writer thread
    @Volatile private var writerThread: Thread? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var watchdogThread: Thread? = null

    // Callbacks
    var onTelemetry: ((Protocol.TelemetryData) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    private val startTime = System.currentTimeMillis()

    val connected: Boolean get() = isConnected.get()
    val currentMode: Mode get() = mode

    // ─── Connect ──────────────────────────────────────

    fun connectWifi(host: String, port: Int = Protocol.DEFAULT_PORT) {
        stopSession(clearIntent = false)
        mode = Mode.WIFI_UDP
        targetHost = host
        targetPort = port
        wantConnected.set(true)
        establishConnection()
        ensureWatchdog()
    }

    fun connectUsb(port: Int = Protocol.DEFAULT_PORT) {
        stopSession(clearIntent = false)
        mode = Mode.USB_TCP
        targetHost = "127.0.0.1"
        targetPort = port
        wantConnected.set(true)
        establishConnection()
        ensureWatchdog()
    }

    fun disconnect() {
        wantConnected.set(false)
        stopSession(clearIntent = true)
    }

    private fun stopSession(clearIntent: Boolean) {
        if (clearIntent) {
            wantConnected.set(false)
        }
        val wasConnected = isConnected.getAndSet(false)

        latestSensorPacket.set(null)
        reliableQueue.clear()

        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        targetAddress = null

        try { tcpInput?.close() } catch (_: Exception) {}
        try { tcpOutput?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        tcpInput = null
        tcpOutput = null
        tcpSocket = null

        writerThread?.interrupt()
        readerThread?.interrupt()
        writerThread = null
        readerThread = null

        if (wasConnected) {
            onConnectionStateChanged?.invoke(false)
        }
    }

    @Synchronized
    private fun establishConnection(): Boolean {
        if (!wantConnected.get()) return false
        return try {
            if (mode == Mode.WIFI_UDP) {
                targetAddress = InetAddress.getByName(targetHost)
                udpSocket = DatagramSocket().apply {
                    soTimeout = 1500
                    sendBufferSize = 65536
                    receiveBufferSize = 65536
                    try { trafficClass = 0x10 } catch (_: Exception) {} // IPTOS_LOWDELAY for WMM Wi-Fi priority
                }
            } else {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress("127.0.0.1", targetPort), 1500)
                sock.soTimeout = 4000
                tcpSocket = sock
                tcpOutput = sock.getOutputStream()
                tcpInput = sock.getInputStream()
            }

            isConnected.set(true)
            startWorkerThreads()
            onConnectionStateChanged?.invoke(true)
            Log.i("phoneel", "Connected via $mode to $targetHost:$targetPort")
            true
        } catch (e: Exception) {
            Log.w("phoneel", "Connect attempt ($mode) failed: ${e.message}")
            isConnected.set(false)
            false
        }
    }

    // ─── Worker Threads ───────────────────────────────

    private fun startWorkerThreads() {
        writerThread?.interrupt()
        readerThread?.interrupt()

        // 1. Ultra-low-latency writer thread
        val wt = Thread({
            // Immediately announce connection with a heartbeat packet
            try {
                val initTs = ((System.currentTimeMillis() - startTime) and 0xFFFFFFFFL).toInt()
                writeRaw(Protocol.buildHeartbeatPacket(initTs))
            } catch (_: Exception) {}

            var lastHeartbeat = System.currentTimeMillis()
            while (isConnected.get() && !Thread.currentThread().isInterrupted) {
                var didWork = false

                try {
                    // Priority 1: Drain discrete button/control packets first (FIFO)
                    while (true) {
                        val eventPkt = reliableQueue.poll() ?: break
                        writeRaw(eventPkt)
                        packetsSent.incrementAndGet()
                        didWork = true
                    }

                    // Priority 2: Send ONLY the latest sensor state (Zero-Queue Conflation)
                    val sensorPkt = latestSensorPacket.getAndSet(null)
                    if (sensorPkt != null) {
                        writeRaw(sensorPkt)
                        packetsSent.incrementAndGet()
                        didWork = true
                    }

                    // Periodic heartbeat every 1000ms
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat >= 1000L) {
                        lastHeartbeat = now
                        val ts = ((now - startTime) and 0xFFFFFFFFL).toInt()
                        writeRaw(Protocol.buildHeartbeatPacket(ts))
                    }

                    if (!didWork) {
                        // Park until unparked by sendDriveState/sendButton (avoids Thread.sleep(1) 16ms timer slack!)
                        LockSupport.parkNanos(2_000_000L)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (mode == Mode.USB_TCP) {
                        Log.w("phoneel", "TCP write broken: ${e.message}")
                        handleTransportDrop()
                        break
                    }
                }
            }
        }, "phoneel-tx-loop").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        writerThread = wt

        // 2. Telemetry reader thread (PC → Phone @ 60Hz)
        val rt = Thread({
            val buf = ByteArray(64)
            while (isConnected.get() && !Thread.currentThread().isInterrupted) {
                try {
                    if (mode == Mode.WIFI_UDP) {
                        val sock = udpSocket ?: break
                        val dp = DatagramPacket(buf, buf.size)
                        sock.receive(dp)
                        if (dp.length >= Protocol.SIZE_TELEMETRY && buf[0] == Protocol.TELEMETRY) {
                            Protocol.parseTelemetryPacket(buf, dp.length)?.let {
                                onTelemetry?.invoke(it)
                            }
                        }
                    } else {
                        val inp = tcpInput ?: break
                        try {
                            if (!readExact(inp, buf, 2)) {
                                handleTransportDrop()
                                break
                            }
                            val len = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
                            if (len <= 0 || len > 64) {
                                handleTransportDrop()
                                break
                            }
                            if (!readExact(inp, buf, len)) {
                                handleTransportDrop()
                                break
                            }
                            if (len >= Protocol.SIZE_TELEMETRY && buf[0] == Protocol.TELEMETRY) {
                                Protocol.parseTelemetryPacket(buf, len)?.let {
                                    onTelemetry?.invoke(it)
                                }
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Normal when PC is not streaming telemetry; keep USB connection alive!
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal when PC is idle
                } catch (e: Exception) {
                    if (mode == Mode.USB_TCP && isConnected.get()) {
                        handleTransportDrop()
                        break
                    }
                }
            }
        }, "phoneel-rx-telem").apply {
            isDaemon = true
            start()
        }
        readerThread = rt
    }

    private fun handleTransportDrop() {
        if (isConnected.getAndSet(false)) {
            try { tcpSocket?.close() } catch (_: Exception) {}
            tcpSocket = null
            onConnectionStateChanged?.invoke(false)
        }
    }

    private fun ensureWatchdog() {
        if (watchdogThread?.isAlive == true) return
        watchdogThread = Thread({
            while (wantConnected.get()) {
                try {
                    Thread.sleep(1500)
                    if (wantConnected.get() && !isConnected.get()) {
                        Log.i("phoneel", "Auto-reconnecting ($mode)...")
                        establishConnection()
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "phoneel-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun readExact(inp: InputStream, buf: ByteArray, n: Int): Boolean {
        var offset = 0
        while (offset < n) {
            val r = inp.read(buf, offset, n - offset)
            if (r <= 0) return false
            offset += r
        }
        return true
    }

    private fun writeRaw(data: ByteArray) {
        if (mode == Mode.WIFI_UDP) {
            val addr = targetAddress ?: return
            val sock = udpSocket ?: return
            sock.send(DatagramPacket(data, data.size, addr, targetPort))
        } else {
            val out = tcpOutput ?: return
            val wrapped = Protocol.wrapForTcp(data)
            out.write(wrapped)
        }
    }

    // ─── Public Send API ──────────────────────────────

    /**
     * Send driving state (steering + gas + brake).
     * Uses Lock-Free Latest-State Conflation: never queues stale frames!
     */
    fun sendDriveState(steering: Float, gas: Float, brake: Float) {
        if (!isConnected.get()) return
        val ts = ((System.currentTimeMillis() - startTime) and 0xFFFFFFFFL).toInt()
        latestSensorPacket.set(Protocol.buildSensorPacket(steering, gas, brake, ts))
        writerThread?.let { LockSupport.unpark(it) }
    }

    /**
     * Send discrete button press/release (guaranteed delivery via FIFO queue).
     */
    fun sendButton(pressed: Boolean, buttonId: Short) {
        if (!isConnected.get()) return
        reliableQueue.offer(Protocol.buildButtonPacket(pressed, buttonId))
        writerThread?.let { LockSupport.unpark(it) }
    }

    /**
     * Send calibrate/re-center command.
     */
    fun sendCalibrate() {
        if (!isConnected.get()) return
        reliableQueue.offer(Protocol.buildCalibratePacket())
        writerThread?.let { LockSupport.unpark(it) }
    }
}
