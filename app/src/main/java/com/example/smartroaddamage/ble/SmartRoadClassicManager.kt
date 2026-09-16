package com.example.smartroaddamage.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.smartroaddamage.models.CPacket
import com.example.smartroaddamage.ble.parseCPacket
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private const val TAG = "SmartRoadClassicManager"
private const val RAW_TAG = "RAW_ESP32"
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private const val INITIAL_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 5000L
private const val WATCHDOG_TIMEOUT_MS = 1500L
private const val WATCHDOG_POLL_MS = 250L

@SuppressLint("MissingPermission")
class SmartRoadClassicManager(private val serviceScope: CoroutineScope) {

    private val _potholeEvents = MutableSharedFlow<PotholeEvent>(extraBufferCapacity = 64)
    val potholeEvents = _potholeEvents.asSharedFlow()

    private val _cPackets = MutableSharedFlow<CPacket>(extraBufferCapacity = 64)
    val cPackets = _cPackets.asSharedFlow()

    private val _iPackets = MutableSharedFlow<com.example.smartroaddamage.detector.ImuSample>(extraBufferCapacity = 64)
    val iPackets = _iPackets.asSharedFlow()

    private val _rawPackets = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val rawPackets = _rawPackets.asSharedFlow()

    // ── Mount calibration state ────────────────────────────────────────────────
    // @Volatile: written by IO-thread read-loop, read by main-thread calibration call.
    @Volatile var lastRawCPacket: CPacket? = null
        private set

    @Volatile var isCalibrated: Boolean = false
        private set

    private var mountAxOffset = 0f
    private var mountAyOffset = 0f
    private var mountAzOffset = 0f
    private var mountGxOffset = 0f
    private var mountGyOffset = 0f
    private var mountGzOffset = 0f

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var autoReconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var targetDevice: BluetoothDevice? = null
    
    // Auto-reconnect flag, kept true until user intentionally ends ride
    private var shouldAutoReconnect = false

    @Volatile
    private var lastDataTimeMs = System.currentTimeMillis()

    fun connect(device: BluetoothDevice) {
        targetDevice = device
        shouldAutoReconnect = true
        startConnectionLoop()
    }

    private fun startConnectionLoop() {
        autoReconnectJob?.cancel()
        autoReconnectJob = serviceScope.launch(Dispatchers.IO) {
            var backoffMs = INITIAL_RECONNECT_DELAY_MS
            
            while (shouldAutoReconnect && isActive) {
                try {
                    _connectionState.value = ConnectionState.CONNECTING
                    val device = targetDevice ?: break
                    Log.d(TAG, "Creating RFCOMM socket to ${device.address}...")
                    
                    val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket = sock
                    sock.connect()

                    Log.d(TAG, "Successfully connected to ${device.address} via SPP.")
                    _connectionState.value = ConnectionState.CONNECTED
                    backoffMs = INITIAL_RECONNECT_DELAY_MS // Reset backoff on success
                    
                    lastDataTimeMs = System.currentTimeMillis()
                    startWatchdog()
                    
                    // Blocking read loop on this coroutine
                    val buffer = ByteArray(1024)
                    var stringBuilder = StringBuilder()
                    val inputStream = sock.inputStream
                    
                    while (isActive) {
                        val bytes = inputStream.read(buffer)
                        if (bytes > 0) {
                            lastDataTimeMs = System.currentTimeMillis()
                            val chunk = String(buffer, 0, bytes, Charsets.UTF_8)
                            stringBuilder.append(chunk)

                            var idx = stringBuilder.indexOf("\n")
                            while (idx != -1) {
                                val line = stringBuilder.substring(0, idx).trimEnd('\r')
                                stringBuilder.delete(0, idx + 1)
                                if (line.isNotEmpty()) {
                                    parseAndEmitEvent(line)
                                }
                                idx = stringBuilder.indexOf("\n")
                            }
                            if (stringBuilder.length > 4096) {
                                Log.w(TAG, "Stream buffer exceeded 4KB without newline. Flushing.")
                                stringBuilder.clear()
                            }
                        } else if (bytes == -1) {
                            Log.w(TAG, "End of stream reached. Triggering reconnect...")
                            break
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Socket read/connect failed: ${e.message}")
                } finally {
                    cleanSocketSilently()
                }
                
                if (shouldAutoReconnect && isActive) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    Log.d(TAG, "ESP32 Disconnected. Retrying in ${backoffMs/1000}s...")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch(Dispatchers.Default) {
             while (isActive) {
                 delay(WATCHDOG_POLL_MS)
                 if (System.currentTimeMillis() - lastDataTimeMs > WATCHDOG_TIMEOUT_MS) {
                     Log.w(TAG, "Watchdog timeout! No data received for ${WATCHDOG_TIMEOUT_MS}ms. Triggering reconnect...")
                     cleanSocketSilently() // Forces inputStream.read() to throw IOException in the connection loop
                     break
                 }
             }
        }
    }

    private fun parseAndEmitEvent(dataString: String) {
        try {
            Log.d(RAW_TAG, dataString)

            // Forward all packets to raw stream for downstream routing (e.g. G and SYNCED packets)
            serviceScope.launch {
                _rawPackets.emit(dataString)
            }

            // Header/metadata packets are valid transport data, but not numeric payloads.
            if (dataString.startsWith("H:") || dataString == "CALIB_DONE") {
                return
            }

            // G-packets and SYNCED are handled downstream entirely, no further BT-level parsing needed
            if (dataString.startsWith("G,") || dataString.startsWith("SYNCED,")) {
                return
            }

            // Route I-packets directly, apply mount calibration (subtract offsets, restore +9.81f gravity on Z)
            if (dataString.startsWith("I,")) {
                if (!isCalibrated) return // Defensive: discard uncalibrated inputs
                val sample = com.example.smartroaddamage.ble.IPacketParser.parse(dataString)
                if (sample != null) {
                    val corrected = sample.copy(
                        ax = sample.ax - mountAxOffset,
                        ay = sample.ay - mountAyOffset,
                        az = sample.az - mountAzOffset + 9.81f,
                        gx = sample.gx - mountGxOffset,
                        gy = sample.gy - mountGyOffset,
                        gz = sample.gz - mountGzOffset
                    )
                    serviceScope.launch { _iPackets.emit(corrected) }
                }
                return
            }

            // Route C-packets to the UI state flow
            if (dataString.startsWith("C,")) {
                val packet = parseCPacket(dataString)
                if (packet != null) {
                    lastRawCPacket = packet               // ← @Volatile cache for calibration
                    serviceScope.launch {
                        _cPackets.emit(packet)
                    }
                }
                return
            }

            // Legacy pothole event parsing (lat,lng,mag format)
            val parts = dataString.split(",")
            if (parts.size >= 3) {
                val lat = parts[0].toDoubleOrNull()
                val lng = parts[1].toDoubleOrNull()
                val mag = parts[2].toFloatOrNull()
                
                if (lat != null && lng != null && mag != null) {
                    val event = PotholeEvent(
                        timestamp = System.currentTimeMillis(),
                        lat = lat,
                        lng = lng,
                        magnitude = mag,
                        eventType = if (parts.size >= 4) parts[3].trim() else "POTHOLE"
                    )
                    Log.d(TAG, "Parsed event: $event")
                    
                    // Fire-and-forget emit
                    serviceScope.launch {
                        _potholeEvents.emit(event)
                    }
                } else {
                    Log.w(TAG, "Invalid numbers in payload: '$dataString'")
                }
            } else {
                Log.w(TAG, "Malformed Classic BT packet (${parts.size} fields): '$dataString'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse BT packet: '$dataString'", e)
        }
    }

    private fun cleanSocketSilently() {
        watchdogJob?.cancel()
        watchdogJob = null
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    // Call this when the Ride Activity is destroyed or "End Ride" is clicked
    fun stopAllOperations() {
        shouldAutoReconnect = false
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        isCalibrated = false // Reset calibration when entire session ends
        cleanSocketSilently()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // Send a command to the ESP32 socket
    fun sendCommand(cmd: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                socket?.outputStream?.let { stream ->
                    stream.write(cmd.toByteArray(Charsets.UTF_8))
                    stream.flush()
                    Log.d(TAG, "Sent command: $cmd")
                } ?: Log.w(TAG, "sendCommand: socket not connected")
            } catch (e: IOException) {
                Log.e(TAG, "sendCommand failed: ${e.message}")
            }
        }
    }

    // ── Calibration API ────────────────────────────────────────────────────────

    /**
     * Snapshot the current raw C-packet IMU values as mont-angle offsets.
     * Call this when the user taps "RUN GYRO CALIBRATION" with the bike stationary.
     * Must have at least one C-packet in [lastRawCPacket] before calling.
     */
    fun lockMountCalibration() {
        val raw = lastRawCPacket ?: run {
            Log.w(TAG, "lockMountCalibration: no C-packet received yet — is Bluetooth connected?")
            return
        }
        mountAxOffset = raw.ax
        mountAyOffset = raw.ay
        mountAzOffset = raw.az
        mountGxOffset = raw.gx
        mountGyOffset = raw.gy
        mountGzOffset = raw.gz
        isCalibrated  = true
        Log.i(TAG, "Mount calibration LOCKED: " +
              "ax=$mountAxOffset ay=$mountAyOffset az=$mountAzOffset " +
              "gx=$mountGxOffset gy=$mountGyOffset gz=$mountGzOffset")
    }

    /**
     * Lock pre-computed noise-averaged offsets from a multi-sample calibration window.
     * Called by [CockpitViewModel.triggerCalibration] after collecting 100 C-packets
     * and averaging out micro-vibrations.
     */
    fun lockMountCalibrationFromAverages(
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float
    ) {
        mountAxOffset = ax
        mountAyOffset = ay
        mountAzOffset = az
        mountGxOffset = gx
        mountGyOffset = gy
        mountGzOffset = gz
        isCalibrated  = true
        Log.i(TAG, "Mount cal LOCKED (100-sample avg): ax=$ax ay=$ay az=$az gx=$gx gy=$gy gz=$gz")
    }

    /**
     * Apply mount-angle offsets to raw 6-DoF IMU values before ML inference.
     * Returns [ax, ay, az, gx, gy, gz] with:
     *  - mounting angle removed
     *  - gravity restored on az (+9.81f): the model was trained with az ≈ +9.81 at rest,
     *    so (raw.az - offset) would give ~0, and (+9.81f) re-anchors to Earth's frame.
     *
     * Wired in Sprint 2 before ImuSamples are pushed to AnomalyDetector's ring buffer.
     * Returns raw values unchanged if [isCalibrated] is false.
     */
    fun applyMountOffsets(
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float
    ): FloatArray {
        if (!isCalibrated) return floatArrayOf(ax, ay, az, gx, gy, gz)
        return floatArrayOf(
            ax - mountAxOffset,
            ay - mountAyOffset,
            (az - mountAzOffset) + 9.81f,   // restore gravity to corrected Z-axis
            gx - mountGxOffset,
            gy - mountGyOffset,
            gz - mountGzOffset
        )
    }
}
