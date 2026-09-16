package com.example.smartroaddamage.ui.screens

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smartroaddamage.ble.BluetoothService
import com.example.smartroaddamage.ble.ConnectionState
import com.example.smartroaddamage.detector.AnomalyDetector
import com.example.smartroaddamage.detector.AnomalyResult
import com.example.smartroaddamage.detector.ImuSample
import com.example.smartroaddamage.detector.InferenceEngine
import com.example.smartroaddamage.detector.SensorFusionManager
import com.example.smartroaddamage.detector.VisionInferenceEngine
import com.example.smartroaddamage.location.PhoneLocationTracker
import com.example.smartroaddamage.utils.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CockpitVM"
private const val CALIB_SAMPLES = 100

/** Must match InferenceEngine.WINDOW_SIZE_CONST. Keep in sync. */
private const val INFER_WINDOW = 20

private enum class EspMode {
    CAL,
    INFER
}

enum class VisionStatus {
    IDLE,
    PERMISSION_MISSING,
    STARTING,
    RUNNING,
    MODEL_INIT_FAILED,
    CAMERA_BIND_FAILED,
    PAUSED
}

data class CockpitUiState(
    val batteryPct: Int = 0,
    val voltage: Float = 0f,
    val tempC: Float = 0f,
    val gpsSats: Int = 0,
    val hdop: Float = 99f,
    val snr: Float = 0f,
    val sdUsedGb: Float = 0f,
    val sdTotalGb: Float = 0f,
    val isSdMounted: Boolean = false,
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val isCalibrating: Boolean = false,
    val calibProgress: Float = 0f,
    val isCalibrated: Boolean = false,
    val canStartJourney: Boolean = false,
    val calibrationError: String? = null,
    val visionStatus: VisionStatus = VisionStatus.IDLE,
    val isVisionRunning: Boolean = false,
    val lastVisionPixelRatio: Float = 0f,
    val lastVisionHorizontalCenter: Float? = null,
    val lastVisionLaneSide: String? = null,
    val lastVisionFrameRotationDegrees: Int? = null,
    val fusedConfirmationCount: Int = 0,
    val speedKmph: Int = 0,
    val journeyDistanceM: Int = 0,
    val journeyTimeSeconds: Long = 0L,
    val liveVibrationSeries: List<Float> = List(30) { 0.2f },
    val isJourneyPaused: Boolean = false
)

class CockpitViewModel(private val appContext: Context) : ViewModel() {

    private val _uiState = MutableStateFlow(CockpitUiState())
    val uiState: StateFlow<CockpitUiState> = _uiState.asStateFlow()

    private val _anomalyFlow = MutableSharedFlow<AnomalyResult>(extraBufferCapacity = 16)
    val anomalyFlow: SharedFlow<AnomalyResult> = _anomalyFlow.asSharedFlow()

    private val engine = InferenceEngine(appContext)
    private val packetRouter = AnomalyDetector(appContext)
    private val visionEngine = VisionInferenceEngine(appContext)
    private val fusionManager = SensorFusionManager()
    private val phoneLocationTracker = PhoneLocationTracker(appContext)
    val hazardTtsManager = TtsManager(appContext)
    private var journeyStartMs = 0L
    private var pausedAtMs: Long? = null
    private var pausedDurationMs = 0L
    private var lastGpsFix: Location? = null
    private var totalDistanceMeters = 0.0
    private val vibrationBuffer = ArrayDeque<Float>(30)
    // Buffer holds at most INFER_WINDOW + stride cushion samples.
    // Sized to INFER_WINDOW + 3 so the deque never holds stale old-window data.
    private val imuBuf = ArrayDeque<ImuSample>(INFER_WINDOW + 3)
    private var stride = 0
    private val cooldowns = mapOf("Pothole" to 500L, "Speedbreaker" to 2000L)
    private val lastHitMs = mutableMapOf<String, Long>()
    private var desiredEspMode = EspMode.CAL
    private var visionAssistEnabled = false
    private var cameraPermissionGranted = false
    private var journeyVisionRotationDegrees: Int? = null
    private var engineVisionStatus = VisionInferenceEngine.RuntimeStatus.IDLE

    private var bluetoothService: BluetoothService? = null
    private var collectJob: Job? = null
    private var connectionStateJob: Job? = null
    private var calibrationJob: Job? = null
    private var timerJob: Job? = null
    private var isServiceBound = false

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val inferenceContext = Dispatchers.Default.limitedParallelism(1)

    @SuppressLint("MissingPermission")
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder is BluetoothService.LocalBinder) {
                isServiceBound = true
                bluetoothService = binder.getService()
                Log.d(TAG, "BluetoothService bound - starting packet collection.")

                connectionStateJob?.cancel()
                connectionStateJob = bluetoothService?.connectionState
                    ?.onEach { state ->
                        when (state) {
                            ConnectionState.CONNECTED -> {
                                val syncCmd = packetRouter.buildSyncCommand()
                                bluetoothService?.sendCommand(syncCmd)
                            }
                            ConnectionState.CONNECTING -> Unit
                            ConnectionState.DISCONNECTED,
                            ConnectionState.DISCONNECTING,
                            ConnectionState.ERROR -> {
                                if (_uiState.value.isCalibrating) {
                                    Log.w(TAG, "Calibration aborted because Bluetooth disconnected mid-window.")
                                    calibrationJob?.cancel(
                                        CancellationException("Bluetooth disconnected during calibration")
                                    )
                                    _uiState.update { current ->
                                        current.copy(
                                            isCalibrating = false,
                                            calibProgress = 0f,
                                            isCalibrated = false,
                                            canStartJourney = false,
                                            calibrationError = "Calibration interrupted - Bluetooth disconnected"
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ?.launchIn(viewModelScope)

                startCollecting()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            Log.w(TAG, "BluetoothService disconnected.")
            bluetoothService = null
            connectionStateJob?.cancel()
            connectionStateJob = null
            collectJob?.cancel()
            calibrationJob?.cancel()
        }
    }

    init {
        val intent = Intent(appContext, BluetoothService::class.java)
        try {
            appContext.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Not allowed to start service in background, relying on BIND_AUTO_CREATE", e)
        }
        isServiceBound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!isServiceBound) {
            Log.e(TAG, "Failed to bind BluetoothService.")
        }

        visionEngine.onRunningStateChanged = { running ->
            _uiState.update { current ->
                current.copy(isVisionRunning = running)
            }
        }
        visionEngine.onStatusChanged = { status ->
            engineVisionStatus = status
            refreshVisionStatus()
        }
        visionEngine.onDetection = { detection ->
            when (detection) {
                VisionInferenceEngine.VisionDetection.Clear -> {
                    _uiState.update { current ->
                        current.copy(
                            lastVisionPixelRatio = 0f,
                            lastVisionHorizontalCenter = null,
                            lastVisionLaneSide = null
                        )
                    }
                }
                is VisionInferenceEngine.VisionDetection.PotholeDetected -> {
                    if (journeyVisionRotationDegrees == null) {
                        journeyVisionRotationDegrees = detection.frameRotationDegrees
                    }
                    val isOrientationCompatible = journeyVisionRotationDegrees == detection.frameRotationDegrees
                    val trustedHorizontalCenter = detection.horizontalCenter.takeIf { isOrientationCompatible }
                    val trustedLaneSide = trustedHorizontalCenter?.let(::laneSideForHorizontalCenter)
                    fusionManager.recordVisionHit(
                        pixelRatio = detection.pixelRatio,
                        horizontalCenter = trustedHorizontalCenter,
                        frameRotationDegrees = detection.frameRotationDegrees,
                        timestampMs = detection.timestampMs
                    )
                    _uiState.update { current ->
                        current.copy(
                            lastVisionPixelRatio = detection.pixelRatio,
                            lastVisionHorizontalCenter = trustedHorizontalCenter,
                            lastVisionLaneSide = trustedLaneSide,
                            lastVisionFrameRotationDegrees = detection.frameRotationDegrees
                        )
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.warmup()
                Log.d(TAG, "Inference engine warmed up successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to warm up inference engine", e)
            }
        }

        packetRouter.onSynced = {
            viewModelScope.launch { sendDesiredModeCommand() }
        }

        maybeStartPhoneLocationTracking()
    }

    fun onJourneyStarted() {
        desiredEspMode = EspMode.INFER
        journeyStartMs = System.currentTimeMillis()
        pausedAtMs = null
        pausedDurationMs = 0L
        totalDistanceMeters = 0.0
        lastGpsFix = null
        journeyVisionRotationDegrees = null
        imuBuf.clear()
        vibrationBuffer.clear()
        fusionManager.reset()
        _uiState.update { current ->
            current.copy(
                visionStatus = resolveVisionStatus(),
                lastVisionPixelRatio = 0f,
                lastVisionHorizontalCenter = null,
                lastVisionLaneSide = null,
                lastVisionFrameRotationDegrees = null,
                fusedConfirmationCount = 0,
                speedKmph = 0,
                journeyDistanceM = 0,
                journeyTimeSeconds = 0L,
                liveVibrationSeries = List(30) { 0.2f },
                isJourneyPaused = false
            )
        }
        startTimerLoop()
        viewModelScope.launch { sendDesiredModeCommand() }
    }

    fun onJourneyEnded() {
        desiredEspMode = EspMode.CAL
        timerJob?.cancel()
        timerJob = null
        journeyVisionRotationDegrees = null
        stopVision()
        imuBuf.clear()
        vibrationBuffer.clear()
        pausedAtMs = null
        fusionManager.reset()
        _uiState.update { current ->
            current.copy(
                lastVisionPixelRatio = 0f,
                lastVisionHorizontalCenter = null,
                lastVisionLaneSide = null,
                lastVisionFrameRotationDegrees = null,
                isVisionRunning = false,
                speedKmph = 0,
                journeyDistanceM = 0,
                journeyTimeSeconds = 0L,
                liveVibrationSeries = List(30) { 0.2f },
                isJourneyPaused = false
            )
        }
        viewModelScope.launch { sendDesiredModeCommand() }
    }

    fun togglePauseState(isPaused: Boolean) {
        if (_uiState.value.isJourneyPaused == isPaused) return

        if (isPaused) {
            pausedAtMs = System.currentTimeMillis()
            imuBuf.clear()
            vibrationBuffer.clear()
            fusionManager.reset()
            stopVision()
        } else {
            pausedAtMs?.let { pausedStart ->
                pausedDurationMs += (System.currentTimeMillis() - pausedStart)
            }
            pausedAtMs = null
        }

        _uiState.update { current ->
            current.copy(
                isJourneyPaused = isPaused,
                speedKmph = if (isPaused) 0 else current.speedKmph,
                liveVibrationSeries = if (isPaused) List(30) { 0.2f } else current.liveVibrationSeries
            )
        }
        refreshVisionStatus()
    }

    fun setVisionAssistEnabled(enabled: Boolean) {
        visionAssistEnabled = enabled
        refreshVisionStatus(clearMetrics = !enabled)
    }

    fun setCameraPermissionGranted(granted: Boolean) {
        cameraPermissionGranted = granted
        refreshVisionStatus()
    }

    fun startVision(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null
    ) {
        engineVisionStatus = VisionInferenceEngine.RuntimeStatus.STARTING
        refreshVisionStatus()
        visionEngine.start(lifecycleOwner, previewView)
    }

    fun stopVision() {
        visionEngine.stop()
    }

    private suspend fun sendDesiredModeCommand() {
        val command = when (desiredEspMode) {
            EspMode.CAL -> "MODE:C\n"
            EspMode.INFER -> "MODE:I\n"
        }
        bluetoothService?.sendCommand(command)
        Log.d(TAG, "Applied desired ESP32 mode: $desiredEspMode")
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (desiredEspMode == EspMode.INFER) {
                if (!_uiState.value.isJourneyPaused) {
                    val pauseOffset = pausedAtMs?.let { System.currentTimeMillis() - it } ?: 0L
                    val elapsed = ((System.currentTimeMillis() - journeyStartMs - pausedDurationMs - pauseOffset) / 1000L)
                        .coerceAtLeast(0L)
                    _uiState.update { current ->
                        current.copy(journeyTimeSeconds = elapsed)
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            val svc = bluetoothService ?: return@launch

            launch {
                svc.cPackets.collect { p ->
                    if (_uiState.value.isJourneyPaused) return@collect

                    val stationary = estimateStationary(
                        ax = p.ax,
                        ay = p.ay,
                        az = p.az,
                        gx = p.gx,
                        gy = p.gy,
                        gz = p.gz
                    )
                    packetRouter.updateMotionState(stationary)
                    val (mapLat, mapLng) = packetRouter.currentMapLocation()
                    _uiState.update { cur ->
                        cur.copy(
                            batteryPct = p.batPct.toInt(),
                            voltage = p.volts,
                            tempC = p.tempC,
                            gpsSats = p.sats,
                            hdop = p.hdop,
                            snr = p.snr,
                            sdUsedGb = p.sdUsedGb,
                            sdTotalGb = p.sdTotGb,
                            isSdMounted = p.sdTotGb > 0f,
                            roll = p.roll,
                            pitch = p.pitch,
                            ax = p.ax,
                            ay = p.ay,
                            az = p.az,
                            gx = p.gx,
                            gy = p.gy,
                            gz = p.gz,
                            lat = mapLat ?: p.lat,
                            lng = mapLng ?: p.lng
                        )
                    }
                    evaluateJourneyGate()
                }
            }

            launch {
                svc.rawPackets.collect { rawLine ->
                    if (
                        rawLine.startsWith("C,") ||
                        rawLine.startsWith("G,") ||
                        rawLine.startsWith("SYNCED,") ||
                        rawLine == "CALIB_DONE" ||
                        rawLine.startsWith("H:")
                    ) {
                        packetRouter.parseLine(rawLine)
                    }
                }
            }

            launch(Dispatchers.IO) {
                svc.iPackets.collect { sample ->
                    if (_uiState.value.isJourneyPaused) return@collect

                    val accelMag = kotlin.math.sqrt(
                        (sample.ax * sample.ax + sample.ay * sample.ay + sample.az * sample.az).toDouble()
                    ).toFloat()
                    val normalizedJolt = ((accelMag - 9.8f) / 10f).coerceIn(0f, 1f)
                    vibrationBuffer.addLast(normalizedJolt)
                    while (vibrationBuffer.size > 30) {
                        vibrationBuffer.removeFirst()
                    }
                    _uiState.update { current ->
                        current.copy(liveVibrationSeries = vibrationBuffer.toList())
                    }

                    imuBuf.addLast(sample)
                    // Keep buffer trimmed: discard oldest when we exceed window + stride cushion
                    if (imuBuf.size > INFER_WINDOW + 3) imuBuf.removeFirst()

                    // Stride = 3: run inference once every 3 new I-packets after warm-up.
                    // Model requires exactly INFER_WINDOW (20) samples per window.
                    if (++stride >= 3 && imuBuf.size >= INFER_WINDOW) {
                        stride = 0
                        val window = imuBuf.takeLast(INFER_WINDOW)

                        launch(inferenceContext) {
                            val raw = engine.infer(window)
                            Log.d(
                                "ANOMALY",
                                "Infer @ ${raw.impactEspMs}ms -> [${raw.label}] " +
                                    "Conf=${String.format("%.1f", raw.confidence)}%, " +
                                    "AnomalyP=${String.format("%.2f", raw.anomalyProbability)}"
                            )

                            if (!raw.isAnomaly) return@launch

                            val now = raw.impactEspMs
                            val cooldownMs = cooldowns[raw.label] ?: 500L
                            val last = lastHitMs[raw.label] ?: 0L
                            if (now - last < cooldownMs) return@launch
                            lastHitMs[raw.label] = now

                            val enriched = packetRouter.enrich(raw)
                            val fusionEvent = fusionManager.fuse(enriched)
                            val finalEnriched = enriched.copy(
                                laneSide = fusionEvent?.visionLaneSide ?: enriched.laneSide,
                                isFused = fusionEvent?.isFused ?: false,
                                visionPixelRatio = fusionEvent?.visionPixelRatio ?: 0f
                            )
                            if (fusionEvent?.isFused == true) {
                                _uiState.update { current ->
                                    current.copy(
                                        fusedConfirmationCount = current.fusedConfirmationCount + 1,
                                        lastVisionPixelRatio = fusionEvent.visionPixelRatio
                                    )
                                }
                            }
                            _anomalyFlow.emit(finalEnriched)
                        }
                    }
                }
            }
        }
    }

    fun triggerCalibration() {
        val svc = bluetoothService ?: run {
            Log.w(TAG, "triggerCalibration: service not bound yet.")
            return
        }
        if (_uiState.value.isCalibrating) {
            Log.w(TAG, "triggerCalibration: already in progress - ignoring tap.")
            return
        }

        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            if (svc.connectionState.value != ConnectionState.CONNECTED) {
                Log.w(TAG, "triggerCalibration: Bluetooth is not connected.")
                _uiState.update {
                    it.copy(
                        isCalibrating = false,
                        calibProgress = 0f,
                        isCalibrated = false,
                        calibrationError = "Calibration failed - Bluetooth is not connected"
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isCalibrating = true,
                    calibProgress = 0f,
                    isCalibrated = false,
                    calibrationError = null
                )
            }
            Log.i(TAG, "Calibration started - collecting $CALIB_SAMPLES samples (5s window).")

            var sumAx = 0f
            var sumAy = 0f
            var sumAz = 0f
            var sumGx = 0f
            var sumGy = 0f
            var sumGz = 0f
            var count = 0

            try {
                val result = withTimeoutOrNull(30_000L) {
                    svc.cPackets.take(CALIB_SAMPLES).collect { p ->
                        sumAx += p.ax
                        sumAy += p.ay
                        sumAz += p.az
                        sumGx += p.gx
                        sumGy += p.gy
                        sumGz += p.gz
                        count++
                        _uiState.update { it.copy(calibProgress = count.toFloat() / CALIB_SAMPLES) }
                    }
                    true
                }

                if (result == null) {
                    Log.e(TAG, "Calibration timed out!")
                    _uiState.update {
                        it.copy(
                            isCalibrating = false,
                            calibProgress = 0f,
                            calibrationError = "Calibration timed out - check Bluetooth connection"
                        )
                    }
                    return@launch
                }

                if (count == 0) {
                    Log.e(TAG, "Calibration failed: no packets received during window!")
                    _uiState.update {
                        it.copy(
                            isCalibrating = false,
                            calibProgress = 0f,
                            calibrationError = "Calibration failed - no calibration packets were received"
                        )
                    }
                    return@launch
                }

                svc.lockMountCalibrationFromAverages(
                    ax = sumAx / count,
                    ay = sumAy / count,
                    az = sumAz / count,
                    gx = sumGx / count,
                    gy = sumGy / count,
                    gz = sumGz / count
                )

                _uiState.update {
                    it.copy(
                        isCalibrating = false,
                        calibProgress = 1f,
                        isCalibrated = true,
                        calibrationError = null
                    )
                }
                evaluateJourneyGate()
                Log.i(
                    TAG,
                    "Calibration LOCKED ($count samples) - " +
                        "axOff=${sumAx / count} ayOff=${sumAy / count} azOff=${sumAz / count} " +
                        "gxOff=${sumGx / count} gyOff=${sumGy / count} gzOff=${sumGz / count}"
                )
            } catch (_: CancellationException) {
                Log.w(TAG, "Calibration collection cancelled before completion.")
            } finally {
                calibrationJob = null
            }
        }
    }

    private fun evaluateJourneyGate() {
        _uiState.update { state ->
            state.copy(
                canStartJourney = state.gpsSats >= 1 && state.isCalibrated && !state.isCalibrating
            )
        }
    }

    fun onLocationPermissionGranted() {
        maybeStartPhoneLocationTracking()
    }

    private fun maybeStartPhoneLocationTracking() {
        if (!phoneLocationTracker.hasPermission()) return
        phoneLocationTracker.start { location ->
            packetRouter.updatePhoneLocation(location)
            val (mapLat, mapLng) = packetRouter.currentMapLocation()

            if (desiredEspMode == EspMode.INFER) {
                if (_uiState.value.isJourneyPaused) {
                    lastGpsFix = location
                    _uiState.update { current ->
                        current.copy(
                            speedKmph = 0,
                            lat = mapLat ?: current.lat,
                            lng = mapLng ?: current.lng
                        )
                    }
                    return@start
                }

                val speedKmph = (location.speed.coerceAtLeast(0f) * 3.6f).toInt()
                lastGpsFix?.let { previous ->
                    totalDistanceMeters += previous.distanceTo(location)
                }
                lastGpsFix = location

                _uiState.update { current ->
                    current.copy(
                        speedKmph = speedKmph,
                        journeyDistanceM = totalDistanceMeters.toInt(),
                        lat = mapLat ?: current.lat,
                        lng = mapLng ?: current.lng
                    )
                }
            } else if (mapLat != null && mapLng != null) {
                _uiState.update { current ->
                    current.copy(lat = mapLat, lng = mapLng)
                }
            }
        }
    }

    private fun estimateStationary(
        ax: Float,
        ay: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float
    ): Boolean {
        val accelMag = kotlin.math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        val gyroMag = kotlin.math.sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
        return gyroMag < 0.08f && kotlin.math.abs(accelMag - 9.81f) < 0.75f
    }

    override fun onCleared() {
        super.onCleared()
        stopVision()
        visionEngine.destroy()
        hazardTtsManager.destroy()
        engine.destroy()
        phoneLocationTracker.stop()
        timerJob?.cancel()
        calibrationJob?.cancel()
        connectionStateJob?.cancel()
        collectJob?.cancel()
        try {
            if (isServiceBound) {
                appContext.unbindService(serviceConnection)
            }
        } catch (_: Exception) {
        } finally {
            isServiceBound = false
        }
        Log.d(TAG, "CockpitViewModel cleared.")
    }

    private fun refreshVisionStatus(clearMetrics: Boolean = false) {
        val resolvedStatus = resolveVisionStatus()
        _uiState.update { current ->
            current.copy(
                visionStatus = resolvedStatus,
                isVisionRunning = resolvedStatus == VisionStatus.RUNNING,
                lastVisionPixelRatio = if (clearMetrics) 0f else current.lastVisionPixelRatio,
                lastVisionHorizontalCenter = if (clearMetrics) null else current.lastVisionHorizontalCenter,
                lastVisionLaneSide = if (clearMetrics) null else current.lastVisionLaneSide,
                lastVisionFrameRotationDegrees = if (clearMetrics) null else current.lastVisionFrameRotationDegrees
            )
        }
    }

    private fun resolveVisionStatus(): VisionStatus {
        return when {
            !visionAssistEnabled -> VisionStatus.IDLE
            _uiState.value.isJourneyPaused -> VisionStatus.PAUSED
            desiredEspMode != EspMode.INFER -> when (engineVisionStatus) {
                VisionInferenceEngine.RuntimeStatus.MODEL_INIT_FAILED -> VisionStatus.MODEL_INIT_FAILED
                else -> VisionStatus.IDLE
            }
            !cameraPermissionGranted -> VisionStatus.PERMISSION_MISSING
            else -> when (engineVisionStatus) {
                VisionInferenceEngine.RuntimeStatus.IDLE -> VisionStatus.IDLE
                VisionInferenceEngine.RuntimeStatus.STARTING -> VisionStatus.STARTING
                VisionInferenceEngine.RuntimeStatus.RUNNING -> VisionStatus.RUNNING
                VisionInferenceEngine.RuntimeStatus.MODEL_INIT_FAILED -> VisionStatus.MODEL_INIT_FAILED
                VisionInferenceEngine.RuntimeStatus.CAMERA_BIND_FAILED -> VisionStatus.CAMERA_BIND_FAILED
            }
        }
    }

    private fun laneSideForHorizontalCenter(horizontalCenter: Float): String {
        return when {
            horizontalCenter < 0.35f -> "LEFT"
            horizontalCenter < 0.65f -> "CENTER"
            else -> "RIGHT"
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CockpitViewModel(context.applicationContext) as T
        }
    }
}
