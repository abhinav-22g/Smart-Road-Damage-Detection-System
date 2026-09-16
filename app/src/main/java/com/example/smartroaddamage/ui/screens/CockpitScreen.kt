package com.example.smartroaddamage.ui.screens

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.example.smartroaddamage.detector.AnomalyResult
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartroaddamage.ui.components.*
import com.example.smartroaddamage.ui.theme.*
import com.example.smartroaddamage.ui.theme.NeoBrutalTokens
import com.example.smartroaddamage.data.PendingJourneyUploadStore
import com.example.smartroaddamage.data.RouteAnalysis
import com.example.smartroaddamage.data.FirestoreHazardWriteRepository
import com.example.smartroaddamage.data.HazardWriteRepository
import com.mappls.sdk.maps.geometry.LatLng
import com.example.smartroaddamage.workers.HazardUploadScheduler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.camera.view.PreviewView

/** Live pilot identity pulled from Firebase Auth session. */
private data class PilotProfile(
    val name: String,
    val callsign: String,
    val uid: String,
    val avatarUrl: String?
)

// ─────────────────────────────────────────────────────────────────────────────
// CockpitMode — Architecture for Temporal Separation
// ─────────────────────────────────────────────────────────────────────────────
enum class CockpitMode {
    DEVICE_MONITOR,  // Pre-flight diagnostic phase
    ROUTE_SELECTION, // Choosing destination and scanning routes
    DRIVING,         // Active ride phase
    POST_RIDE_SUMMARY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CockpitScreen(
    onDisconnect: () -> Unit,
    isDarkMode: Boolean = false
) {
    var cockpitMode   by rememberSaveable { mutableStateOf(CockpitMode.DEVICE_MONITOR) }
    var selectedRoute by remember { mutableStateOf<RouteAnalysis?>(null) }
    val view         = LocalView.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val vm: CockpitViewModel = viewModel(
        factory = CockpitViewModel.Factory(context)
    )
    val uiState by vm.uiState.collectAsState()
    var visionAssistEnabled by rememberSaveable { mutableStateOf(false) }
    var showVisionDebugPanel by rememberSaveable { mutableStateOf(false) }
    var inlinePreviewView by remember { mutableStateOf<PreviewView?>(null) }
    var recenterMapTick by remember { mutableIntStateOf(0) }

    // ── Location Permission for Map SDK ──────────────────────────────────────
    val mapPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            vm.onLocationPermissionGranted()
        }
    }

    LaunchedEffect(Unit) {
        mapPermissionLauncher.launch(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(hasCameraPermission) {
        vm.setCameraPermissionGranted(hasCameraPermission)
    }

    LaunchedEffect(visionAssistEnabled) {
        vm.setVisionAssistEnabled(visionAssistEnabled)
    }

    LaunchedEffect(cockpitMode) {
        if (cockpitMode != CockpitMode.DRIVING) {
            showVisionDebugPanel = false
        }
    }

    LaunchedEffect(
        cockpitMode,
        hasCameraPermission,
        visionAssistEnabled,
        uiState.isJourneyPaused,
        lifecycleOwner,
        inlinePreviewView
    ) {
        if (cockpitMode == CockpitMode.DRIVING && visionAssistEnabled && !uiState.isJourneyPaused) {
            if (hasCameraPermission) {
                vm.startVision(
                    lifecycleOwner = lifecycleOwner,
                    previewView = inlinePreviewView
                )
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        } else {
            vm.stopVision()
        }
    }

    DisposableEffect(vm) {
        onDispose { vm.stopVision() }
    }

    // ── Live Firebase Pilot Identity & Stats ─────────────────────────────────
    val pilotProfile = remember {
        val user = FirebaseAuth.getInstance().currentUser
        user?.let {
            PilotProfile(
                name     = it.displayName ?: "Elite Pilot",
                callsign = "UNIT-${it.uid.take(5).uppercase()}",
                uid      = it.uid,
                avatarUrl = it.photoUrl?.toString()
            )
        } ?: PilotProfile("Ghost Pilot", "OFFLINE", "unknown", null)
    }

    // ── Write repository — the ONLY class that touches Firestore from this screen ─────
    val writeRepo: HazardWriteRepository = remember { FirestoreHazardWriteRepository() }
    val pendingUploadStore = remember { PendingJourneyUploadStore(context) }
    val coroutineScope = rememberCoroutineScope()

    var totalContributions by remember { mutableIntStateOf(0) }
    var totalSeverity      by remember { mutableIntStateOf(0) }
    var sessionAnomalies   by remember { mutableIntStateOf(0) }
    val sessionAnomaliesList = remember { mutableStateListOf<AnomalyResult>() }
    var showProfileDialog  by remember { mutableStateOf(false) }
    var isUploading        by remember { mutableStateOf(false) }
    var uploadError        by remember { mutableStateOf<String?>(null) }
    var activeJourneyId    by rememberSaveable { mutableStateOf<String?>(null) }
    val journeySummary = JourneySummaryCalculator.summarize(sessionAnomaliesList)

    // ── Load pilot stats from raw pothole_reports (not canonical potholes) ────
    LaunchedEffect(pilotProfile.uid) {
        if (pilotProfile.uid != "unknown") {
            try {
                val stats = writeRepo.loadPilotStats(pilotProfile.uid)
                totalContributions = stats.totalReports
                totalSeverity      = stats.totalSeverity
            } catch (e: Exception) {
                Log.w("CockpitScreen", "loadPilotStats failed: ${e.message}")
            }
        }
    }

    // ── CockpitViewModel — single source of truth for all ESP32 telemetry ──────
    LaunchedEffect(Unit) {
        vm.onLocationPermissionGranted()
    }

    val lifecycle = lifecycleOwner.lifecycle
    LaunchedEffect(vm, lifecycle) {
        vm.anomalyFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .collect { result ->
                sessionAnomalies++
                sessionAnomaliesList.add(result)
                Log.d("ANOMALY", "${result.label} ${result.confidence}% @ ${result.impactLat},${result.impactLng}")
            }
    }

    // ── Convenience aliases from the batched state object ─────────────────────
    val batteryPct  = uiState.batteryPct
    val voltage     = uiState.voltage
    val tempC       = uiState.tempC
    val gpsSats     = uiState.gpsSats
    val hdop        = uiState.hdop
    val isSdMounted = uiState.isSdMounted
    val sdUsedGb    = uiState.sdUsedGb
    val sdTotalGb   = uiState.sdTotalGb
    val pitch       = uiState.pitch
    val roll        = uiState.roll

    // ── Mutable local state (not from ESP32) ──────────────────────────────────
    // calState derived from VM — three-way mapping, no local mutable state.
    val calState = when {
        uiState.isCalibrating -> CalibrationState.CALIBRATING
        uiState.isCalibrated  -> CalibrationState.LOCKED
        else                  -> CalibrationState.IDLE
    }
    var noiseSeries   by remember { mutableStateOf(List(50) { 0.3f }) }
    val speed = uiState.speedKmph
    val vibrationData = uiState.liveVibrationSeries
    val journeyMinutes = uiState.journeyTimeSeconds / 60
    val journeySeconds = uiState.journeyTimeSeconds % 60
    val timeFmt = String.format("%02d:%02d", journeyMinutes, journeySeconds)
    val distFmt = String.format("%.1f km", uiState.journeyDistanceM / 1000.0)
    val etaLabel = remember(uiState.lat, uiState.lng, selectedRoute) {
        estimateRemainingEtaLabel(
            currentLat = uiState.lat,
            currentLng = uiState.lng,
            selectedRoute = selectedRoute
        )
    }

    // ── Live gyro sparkline — sliding window fed by real ESP32 data ─────────────────
    // Restarts on every gx/gy/gz change (every ~50ms C-packet).
    // Normalised by 0.5 rad/s — typical peak idle gyro noise on a motorcycle.
    LaunchedEffect(uiState.gx, uiState.gy, uiState.gz) {
        val gMag = kotlin.math.sqrt(
            (uiState.gx * uiState.gx +
             uiState.gy * uiState.gy +
             uiState.gz * uiState.gz).toDouble()
        ).toFloat()
        val normalized = (gMag / 0.5f).coerceIn(0f, 1f)
        noiseSeries = (noiseSeries.drop(1) + normalized)
    }

    // isSystemReady removed — use uiState.canStartJourney from the gate evaluator.

    // ── Auto-hiding HUD in DRIVING mode ──────────────────────────────────────
    var uiVisible           by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastInteractionTime) {
        uiVisible = true; delay(10000); uiVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))   // Chassis: Slate 900
            .pointerInput(Unit) {
                awaitEachGesture { awaitPointerEvent(); lastInteractionTime = System.currentTimeMillis() }
            }
    ) {
        // ── LAYER 1: Subtle background grid ──────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridStep = 40.dp.toPx()
            for (x in 0..(size.width / gridStep).toInt()) {
                drawLine(Color.White.copy(alpha = 0.03f), androidx.compose.ui.geometry.Offset(x * gridStep, 0f), androidx.compose.ui.geometry.Offset(x * gridStep, size.height), strokeWidth = 1.dp.toPx())
            }
            for (y in 0..(size.height / gridStep).toInt()) {
                drawLine(Color.White.copy(alpha = 0.03f), androidx.compose.ui.geometry.Offset(0f, y * gridStep), androidx.compose.ui.geometry.Offset(size.width, y * gridStep), strokeWidth = 1.dp.toPx())
            }
        }

        // ══ PRE-FLIGHT DEVICE MONITOR OVERLAY ════════════════════════════════
        AnimatedVisibility(
            visible = cockpitMode == CockpitMode.DEVICE_MONITOR,
            enter   = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit    = slideOutVertically(targetOffsetY = { it }) + shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Header — live pilot data from Firebase
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = onDisconnect,
                        modifier = Modifier
                            .size(44.dp)
                            .neoBrutalSurface(
                                backgroundColor = Color(0xFF172033),
                                shadowOffset = 3.dp,
                                borderColor = Color(0xFF334155)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back to hardware bridge",
                            tint = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "DEVICE MONITOR",
                            fontFamily = Outfit, fontWeight = FontWeight.Black,
                            fontSize   = 28.sp, color = Color.White
                        )
                        Text(
                            "PRE-JOURNEY CONFIGURATION",
                            fontFamily = JetBrainsMono, fontSize = 11.sp,
                            color      = Color(0xFF475569), letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Live pilot callsign pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.clickable { showProfileDialog = true }.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text       = "▶  ${pilotProfile.name}",
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                fontSize   = 13.sp,
                                color      = Color(0xFFADFF2F)   // ToxicLime — live data
                            )
                            Text(
                                text       = pilotProfile.callsign,
                                fontFamily = JetBrainsMono,
                                fontSize   = 11.sp,
                                color      = Color(0xFF475569)
                            )
                        }
                    }

                    // Pilot Profile Circle Image
                    AsyncImage(
                        model = pilotProfile.avatarUrl,
                        contentDescription = "Pilot Avatar",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color(0xFF38BDF8), CircleShape)
                            .clickable { showProfileDialog = true }
                    )
                }

                // ── ROW 1: Power Core (50%) + Data Integrity (50%) ───────────
                BentoEntranceRow(delayMs = 0) {
                    Row(
                        modifier            = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PowerCoreCard(
                            battery  = batteryPct,
                            voltage  = voltage,
                            tempC    = tempC,
                            modifier = Modifier.weight(1f)
                        )
                        DataIntegrityCard(
                            gpsSats     = gpsSats,
                            hdop        = hdop,
                            sdUsedGb    = sdUsedGb,
                            sdTotalGb   = sdTotalGb,
                            isSdMounted = isSdMounted,
                            modifier    = Modifier.weight(1f)
                        )
                    }
                }

                // ── ROW 2: Posture / Bubble Level ────────────────────────────
                BentoEntranceRow(delayMs = 50) {
                    PostureCard(
                        pitch    = pitch,
                        roll     = roll,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── ROW 3: Gyro Calibration (full width, clean) ──────────────
                BentoEntranceRow(delayMs = 100) {
                    CalibrationCard(
                        calibrationState = calState,
                        imuNoiseSeries   = noiseSeries,
                        calibProgress    = uiState.calibProgress,
                        onCalibrate      = { vm.triggerCalibration() },
                        modifier         = Modifier.fillMaxWidth()
                    )
                }

                // ── CALIBRATION ERROR STATUS LINE ────────────────────────────
                AnimatedVisibility(
                    visible = uiState.calibrationError != null,
                    enter   = fadeIn() + expandVertically(),
                    exit    = fadeOut() + shrinkVertically()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF7F1D1D), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFEF4444), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.calibrationError ?: "",
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFFFECACA),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                // RECALIBRATE — Neo-Brutalist secondary button, appears after first lock
                AnimatedVisibility(
                    visible = uiState.isCalibrated && !uiState.isCalibrating,
                    enter   = fadeIn(tween(400)) + expandVertically(),
                    exit    = fadeOut(tween(200)) + shrinkVertically()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.triggerCalibration() }
                            .neoBrutalSurface(
                                backgroundColor = Color(0xFF1E293B),
                                shadowOffset    = 3.dp,
                                borderColor     = Color(0xFF334155)
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "↺  RECALIBRATE",
                            fontFamily    = JetBrainsMono,
                            fontWeight    = FontWeight.Bold,
                            fontSize      = 11.sp,
                            letterSpacing = 1.5.sp,
                            color         = Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── FOOTER: Ignition Slider ───────────────────────────────────
                BentoEntranceRow(delayMs = 150) {
                    IgnitionSlider(
                        isSystemReady = uiState.canStartJourney,
                        onIgnite      = {
                            cockpitMode = CockpitMode.ROUTE_SELECTION
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        // ══ ROUTE SELECTION — REAL MAPPLS + FIRESTORE ═══════════════════════
        AnimatedVisibility(
            visible = cockpitMode == CockpitMode.ROUTE_SELECTION,
            enter   = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit    = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            RouteSelectionScreen(
                currentLat    = uiState.lat,
                currentLng    = uiState.lng,
                onBack        = { cockpitMode = CockpitMode.DEVICE_MONITOR },
                onStartJourney = { analysis ->
                    selectedRoute = analysis
                    activeJourneyId = "journey_${System.currentTimeMillis()}_${pilotProfile.uid.take(8)}"
                    uploadError = null
                    vm.onJourneyStarted()
                    sessionAnomalies = 0 // Reset anomalies tracker for new journey
                    sessionAnomaliesList.clear()
                    cockpitMode = CockpitMode.DRIVING
                }
            )
        }

        // ══ DRIVING MODE HUD ═════════════════════════════════════════════════
        AnimatedVisibility(
            visible = cockpitMode == CockpitMode.DRIVING,
            enter   = fadeIn(animationSpec = tween(1000)),
            exit    = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // ── Fullscreen Map with real route polyline ──
                MapplsMapWidget(
                    lat         = uiState.lat,
                    lng         = uiState.lng,
                    routePoints = selectedRoute?.route?.polyline,
                    potholePoints = selectedRoute?.potholes,
                    ttsManager = vm.hazardTtsManager,
                    recenterTrigger = recenterMapTick,
                    modifier    = Modifier.fillMaxSize().alpha(if (uiVisible) 0.8f else 1.0f)
                )

                DrivingCameraPreview(
                    enabled = visionAssistEnabled,
                    hasCameraPermission = hasCameraPermission,
                    visionStatus = uiState.visionStatus,
                    onToggleVision = {
                        val nextEnabled = !visionAssistEnabled
                        visionAssistEnabled = nextEnabled
                        if (nextEnabled && !hasCameraPermission) {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    onPreviewViewChanged = { previewView ->
                        if (inlinePreviewView !== previewView) {
                            inlinePreviewView = previewView
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 18.dp, top = 108.dp)
                )

                // ── Map Gradient Overlay ──
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xFF0F172A).copy(alpha = 0.95f)),
                                startY = 400f
                            )
                        )
                )

                VitalsPill(
                    speed             = speed,
                    batteryPercentage = batteryPct,
                    isDarkMode        = true,
                    modifier          = Modifier.align(Alignment.TopCenter).padding(top = 34.dp)
                )

                SessionAnomaliesPill(
                    count    = sessionAnomalies,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EtaPill(etaLabel = etaLabel)
                    RecenterMapButton(
                        onClick = { recenterMapTick++ }
                    )
                }

                SignalScopeWidget(
                    vibrationData = vibrationData,
                    isDarkMode    = true,
                    modifier      = Modifier.align(Alignment.BottomEnd).padding(bottom = 100.dp, end = 16.dp)
                )
                val handleAlpha by animateFloatAsState(
                    targetValue   = if (uiVisible) 1f else 0f,
                    animationSpec = tween(1000),
                    label         = "handle_alpha"
                )
                Glovebox(
                    onEndJourney = {
                        showVisionDebugPanel = false
                        vm.onJourneyEnded()
                        uploadError = null
                        cockpitMode = CockpitMode.POST_RIDE_SUMMARY
                    },
                    distanceLabel = distFmt,
                    potholeCountLabel = sessionAnomalies.toString(),
                    timeLabel = timeFmt,
                    isJourneyPaused = uiState.isJourneyPaused,
                    onPauseLoggingToggle = { vm.togglePauseState(!uiState.isJourneyPaused) },
                    isDarkMode   = true,
                    modifier     = Modifier.align(Alignment.BottomCenter).alpha(handleAlpha)
                )
            }
        }

        AnimatedVisibility(
            visible = cockpitMode == CockpitMode.POST_RIDE_SUMMARY,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 })
        ) {
            JourneySummaryScreen(
                summary = journeySummary,
                isUploading = isUploading,
                uploadError = uploadError,
                onUpload = {
                    isUploading = true
                    uploadError = null
                    val journeyId = activeJourneyId
                        ?: "journey_${System.currentTimeMillis()}_${pilotProfile.uid.take(8)}"
                    val anomaliesSnapshot = sessionAnomaliesList.toList()
                    coroutineScope.launch {
                        pendingUploadStore.upsertJourneyUpload(
                            journeyId = journeyId,
                            reporterUid = pilotProfile.uid,
                            anomalies = anomaliesSnapshot
                        )
                        HazardUploadScheduler.enqueue(context)
                        val result = writeRepo.uploadJourneyReports(
                            journeyId = journeyId,
                            reporterUid = pilotProfile.uid,
                            anomalies = anomaliesSnapshot
                        )
                        isUploading = false
                        if (result.error == null) {
                            pendingUploadStore.removeJourneyUpload(journeyId)
                            totalContributions += result.uploadedCount
                            totalSeverity += result.addedSeverityTotal
                            Log.i(
                                "CockpitScreen",
                                "Upload OK: ${result.uploadedCount} reports, ${result.skippedCount} skipped (no GPS)"
                            )
                        } else {
                            Log.w(
                                "CockpitScreen",
                                "Upload deferred to background worker: ${result.error}"
                            )
                        }
                        sessionAnomaliesList.clear()
                        sessionAnomalies = 0
                        activeJourneyId = null
                        uploadError = null
                        cockpitMode = CockpitMode.DEVICE_MONITOR
                    }
                },
                onDiscard = {
                    sessionAnomaliesList.clear()
                    sessionAnomalies = 0
                    activeJourneyId = null
                    uploadError = null
                    cockpitMode = CockpitMode.DEVICE_MONITOR
                }
            )
        }

        VisionDebugSheet(
            visible = showVisionDebugPanel,
            visionStatus = uiState.visionStatus,
            hasCameraPermission = hasCameraPermission,
            lastVisionPixelRatio = uiState.lastVisionPixelRatio,
            lastVisionHorizontalCenter = uiState.lastVisionHorizontalCenter,
            lastVisionLaneSide = uiState.lastVisionLaneSide,
            lastVisionFrameRotationDegrees = uiState.lastVisionFrameRotationDegrees,
            fusedCount = uiState.fusedConfirmationCount,
            onDismiss = { showVisionDebugPanel = false },
            onRequestPermission = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }
        )

        if (showProfileDialog) {
            AlertDialog(
                onDismissRequest = { showProfileDialog = false },
                containerColor = Color(0xFF1E293B),
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically, 
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AsyncImage(
                            model = pilotProfile.avatarUrl,
                            contentDescription = "Avatar",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color(0xFFADFF2F), CircleShape)
                        )
                        Column {
                            Text(pilotProfile.callsign, fontFamily = JetBrainsMono, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(pilotProfile.name, fontFamily = Outfit, fontSize = 12.sp, color = Color(0xFF94A3B8))
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "CAREER STATISTICS", 
                            fontFamily = JetBrainsMono, fontSize = 10.sp, 
                            color = Color(0xFF475569), letterSpacing = 1.5.sp
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Total Anomalies Detected", fontFamily = JetBrainsMono, fontSize = 13.sp, color = Color.White)
                            Text("$totalContributions", fontFamily = JetBrainsMono, fontSize = 16.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Black)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Combined Severity Score", fontFamily = JetBrainsMono, fontSize = 13.sp, color = Color.White)
                            Text("$totalSeverity", fontFamily = JetBrainsMono, fontSize = 16.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Black)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProfileDialog = false }) {
                        Text("CLOSE", color = Color(0xFF94A3B8), fontFamily = JetBrainsMono)
                    }
                }
            )
        }
    }
}

// ── NEW: Small HUD pill for Anomalies ──────────────────────────────────────
@Composable
fun SessionAnomaliesPill(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .neoBrutalSurface(
                backgroundColor = Color(0xFF172033),
                borderColor = if (count > 0) Color(0xFFEF4444) else Color(0xFF334155),
                shadowOffset = 2.dp
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Warning,
            contentDescription = "Anomalies",
            tint = if (count > 0) Color(0xFFEF4444) else Color(0xFF475569),
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(
                "SESSION ANOMALIES",
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = if (count > 0) Color(0xFFFCA5A5) else Color(0xFF475569),
                letterSpacing = 0.5.sp
            )
            Text(
                text = count.toString(),
                fontFamily = Outfit,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                color = if (count > 0) Color.White else Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
fun VisionAssistCard(
    visionStatus: VisionStatus,
    isVisionAssistEnabled: Boolean,
    isVisionRunning: Boolean,
    lastVisionPixelRatio: Float,
    lastVisionLaneSide: String?,
    fusedCount: Int,
    hasCameraPermission: Boolean,
    onToggleVision: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        fusedCount > 0 -> Color(0xFFADFF2F)
        visionStatus == VisionStatus.MODEL_INIT_FAILED || visionStatus == VisionStatus.CAMERA_BIND_FAILED -> Color(0xFFF97316)
        visionStatus == VisionStatus.PERMISSION_MISSING -> Color(0xFFFBBF24)
        isVisionRunning -> Color(0xFF38BDF8)
        isVisionAssistEnabled -> Color(0xFF475569)
        else -> Color(0xFF334155)
    }
    val accentColor = when {
        fusedCount > 0 -> Color(0xFFADFF2F)
        visionStatus == VisionStatus.MODEL_INIT_FAILED || visionStatus == VisionStatus.CAMERA_BIND_FAILED -> Color(0xFFF97316)
        visionStatus == VisionStatus.PERMISSION_MISSING -> Color(0xFFFBBF24)
        isVisionRunning -> Color(0xFF7DD3FC)
        isVisionAssistEnabled -> Color(0xFFFBBF24)
        else -> Color(0xFF64748B)
    }
    val detailText = if (!isVisionAssistEnabled) {
        "Scans the road ahead with the live camera and confirms hazards before fusion."
    } else if (visionStatus == VisionStatus.PERMISSION_MISSING) {
        "Camera permission is needed to start the live road scan."
    } else if (visionStatus == VisionStatus.STARTING) {
        "Initializing live camera pipeline."
    } else if (visionStatus == VisionStatus.MODEL_INIT_FAILED) {
        "Vision model could not start."
    } else if (visionStatus == VisionStatus.CAMERA_BIND_FAILED) {
        "Camera could not bind to preview."
    } else if (visionStatus == VisionStatus.PAUSED) {
        "Vision is paused while the journey is paused."
    } else if (lastVisionPixelRatio > 0f) {
        buildString {
            append("Mask ")
            append((lastVisionPixelRatio * 100f).roundToInt())
            append("%")
            lastVisionLaneSide?.let {
                append(" • ")
                append(it)
            }
        }
    } else {
        if (isVisionRunning) "Road scan live and watching the lane ahead." else "Camera idle until the road scan starts."
    }

    Column(
        modifier = modifier
            .neoBrutalSurface(
                backgroundColor = Color(0xFF172033),
                borderColor = borderColor,
                shadowOffset = 2.dp
            )
            .widthIn(min = 180.dp, max = 220.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Vision fusion status",
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "VISION FUSION",
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    color = accentColor,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = when {
                        fusedCount > 0 -> "$fusedCount CONFIRMED"
                        isVisionRunning -> "ROAD SCAN LIVE"
                        isVisionAssistEnabled -> "VISION READY"
                        else -> "VISION OFF"
                    },
                    fontFamily = Outfit,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
        }
        Text(
            text = detailText,
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            color = Color(0xFFCBD5E1),
            lineHeight = 14.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .neoBrutalSurface(
                    backgroundColor = Color(0xFF0F172A),
                    borderColor = if (isVisionAssistEnabled) accentColor else Color(0xFF334155),
                    shadowOffset = 1.dp
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "VISION SWITCH",
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    color = accentColor,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = if (isVisionAssistEnabled) "ON" else "OFF",
                    fontFamily = Outfit,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
            NeoBentoSwitch(
                checked = isVisionAssistEnabled,
                onCheckedChange = { onToggleVision() }
            )
        }
        TextButton(
            onClick = onOpenDebug,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(
                text = if (hasCameraPermission) "OPEN DEBUG" else "PERMISSION HELP",
                fontFamily = JetBrainsMono,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun EtaPill(
    etaLabel: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .neoBrutalSurface(
                backgroundColor = Color(0xFF172033),
                borderColor = Color(0xFF38BDF8),
                shadowOffset = 2.dp
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "ETA",
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                color = Color(0xFF7DD3FC),
                letterSpacing = 0.5.sp
            )
            Text(
                text = etaLabel,
                fontFamily = Outfit,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                color = Color.White
            )
        }
    }
}

@Composable
fun RecenterMapButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(56.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF172033),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, Color(0xFF38BDF8), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = "Recenter map",
                tint = Color(0xFF7DD3FC),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrivingCameraPreview(
    enabled: Boolean,
    hasCameraPermission: Boolean,
    visionStatus: VisionStatus,
    onToggleVision: () -> Unit,
    onPreviewViewChanged: (PreviewView?) -> Unit,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        onDispose { onPreviewViewChanged(null) }
    }

    Surface(
        modifier = modifier
            .width(156.dp)
            .height(264.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.72f),
        tonalElevation = 10.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(22.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (enabled && hasCameraPermission) {
                    AndroidView(
                        factory = { context ->
                            PreviewView(context).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                onPreviewViewChanged(this)
                            }
                        },
                        update = { onPreviewViewChanged(it) },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (!enabled) "VISION OFF" else "CAMERA LOCKED",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(6.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.66f)
                ) {
                    Text(
                        text = "CAM ${visionStatusLabel(visionStatus)}",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF172033))
                    .border(
                        1.5.dp,
                        if (enabled) Color(0xFF38BDF8) else Color(0xFF334155),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Camera toggle",
                        tint = if (enabled) Color(0xFF7DD3FC) else Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (enabled) "CAM ON" else "CAM OFF",
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
                NeoBentoSwitch(
                    checked = enabled,
                    onCheckedChange = { onToggleVision() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisionDebugSheet(
    visible: Boolean,
    visionStatus: VisionStatus,
    hasCameraPermission: Boolean,
    lastVisionPixelRatio: Float,
    lastVisionHorizontalCenter: Float?,
    lastVisionLaneSide: String?,
    lastVisionFrameRotationDegrees: Int?,
    fusedCount: Int,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NeoBrutalTokens.DeepSlate,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "VISION DEBUG PANEL",
                fontFamily = Outfit,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                color = Color.White
            )

            if (!hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color(0xFFFBBF24))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Camera permission is required for the live preview and vision assist.",
                        fontFamily = JetBrainsMono,
                        fontSize = 12.sp,
                        color = Color(0xFFF8FAFC)
                    )
                    Button(onClick = onRequestPermission) {
                        Text("GRANT CAMERA")
                    }
                }
            }

            DebugStatRow("STATUS", visionStatusLabel(visionStatus))
            DebugStatRow("PREVIEW", "INLINE HUD")
            DebugStatRow("MASK", "${(lastVisionPixelRatio * 100f).roundToInt()}%")
            DebugStatRow(
                "H CENTER",
                lastVisionHorizontalCenter?.let { String.format("%.3f", it) } ?: "N/A"
            )
            DebugStatRow("LANE", lastVisionLaneSide ?: "N/A")
            DebugStatRow("ROTATION", lastVisionFrameRotationDegrees?.toString() ?: "N/A")
            DebugStatRow("FUSED", fusedCount.toString())
        }
    }
}

@Composable
private fun DebugStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            color = Color(0xFF94A3B8)
        )
        Text(
            text = value,
            fontFamily = Outfit,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Color.White
        )
    }
}

@Composable
private fun JourneySummaryScreen(
    summary: JourneySummary,
    isUploading: Boolean,
    uploadError: String?,
    onUpload: () -> Unit,
    onDiscard: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF081120))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "RIDE SUMMARY",
                fontFamily = Outfit,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp,
                color = Color.White
            )
            Text(
                text = "Review your labeled ride results before uploading them to the network.",
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )

            SummaryHero(summary = summary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryStatCard("POTHOLES", summary.potholeCount.toString(), Color(0xFFEF4444), Modifier.weight(1f))
                SummaryStatCard("SPEEDBREAKERS", summary.speedbreakerCount.toString(), Color(0xFFF59E0B), Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryStatCard("BUMPS", summary.bumpCount.toString(), Color(0xFF38BDF8), Modifier.weight(1f))
                SummaryStatCard("FUSED", summary.fusedCount.toString(), Color(0xFFADFF2F), Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryStatCard("UPLOAD READY", summary.uploadEligibleCount.toString(), Color(0xFF4ADE80), Modifier.weight(1f))
                SummaryStatCard("SKIPPED", summary.skippedCount.toString(), Color(0xFF64748B), Modifier.weight(1f))
            }

            uploadError?.let { message ->
                Text(
                    text = message,
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp,
                    color = Color(0xFFFCA5A5)
                )
            }

            if (isUploading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF4ADE80)
                )
                Text(
                    text = "Uploading raw reports…",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onUpload,
                enabled = !isUploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("UPLOAD DATA")
            }
            OutlinedButton(
                onClick = onDiscard,
                enabled = !isUploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("DISCARD")
            }
        }
    }
}

@Composable
private fun SummaryHero(summary: JourneySummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neoBrutalSurface(
                backgroundColor = Color(0xFF172033),
                borderColor = Color(0xFF38BDF8),
                shadowOffset = 3.dp
            )
            .padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "TOTAL ANOMALIES",
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = summary.totalAnomalies.toString(),
                fontFamily = Outfit,
                fontWeight = FontWeight.Black,
                fontSize = 34.sp,
                color = Color.White
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "SESSION SEVERITY",
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = summary.totalSeverity.toString(),
                fontFamily = Outfit,
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                color = Color(0xFFEF4444)
            )
        }
    }
}

@Composable
private fun SummaryStatCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .neoBrutalSurface(
                backgroundColor = Color(0xFF101827),
                borderColor = accent,
                shadowOffset = 2.dp
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            color = accent
        )
        Text(
            text = value,
            fontFamily = Outfit,
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
            color = Color.White
        )
    }
}

private fun visionStatusLabel(status: VisionStatus): String = when (status) {
    VisionStatus.IDLE -> "CAMERA IDLE"
    VisionStatus.PERMISSION_MISSING -> "PERMISSION MISSING"
    VisionStatus.STARTING -> "STARTING"
    VisionStatus.RUNNING -> "RUNNING"
    VisionStatus.MODEL_INIT_FAILED -> "MODEL INIT FAILED"
    VisionStatus.CAMERA_BIND_FAILED -> "CAMERA BIND FAILED"
    VisionStatus.PAUSED -> "PAUSED"
}

/**
 * Staggered scale-in wrapper for each Bento row.
 * Uses StiffnessLow spring starting from scale 0.85 so each module "lands" with weight.
 */
@Composable
private fun BentoEntranceRow(
    delayMs: Long,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        visible = true
    }
    AnimatedVisibility(
        visible        = visible,
        enter          = scaleIn(
            initialScale = 0.85f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
        ) + fadeIn(tween(300))
    ) {
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GLOVEBOX — Neo-Brutalist Session Controls
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Glovebox(
    onEndJourney: () -> Unit,
    distanceLabel: String,
    potholeCountLabel: String,
    timeLabel: String,
    isJourneyPaused: Boolean,
    onPauseLoggingToggle: () -> Unit,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()
    var showSheet  by remember { mutableStateOf(false) }

    Box(
        modifier       = modifier.fillMaxWidth().height(60.dp).clickable { showSheet = true },
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(modifier = Modifier.width(60.dp).height(4.dp).padding(top = 8.dp), color = Color.White.copy(alpha = 0.2f), shape = CircleShape) {}
        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Open Glovebox", tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(top = 12.dp))
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState       = sheetState,
            containerColor   = NeoBrutalTokens.DeepSlate,
            dragHandle       = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment   = Alignment.CenterHorizontally,
                verticalArrangement   = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "SESSION CONTROLS",
                    fontFamily = Outfit,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = 20.sp,
                    color      = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    GloveboxStat("DIST", distanceLabel)
                    GloveboxStat("EVENTS", potholeCountLabel)
                    GloveboxStat("TIME", timeLabel)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // PAUSE LOGGING — Neo-Brutalist button with toggle state
                NeoButton(
                    text   = if (isJourneyPaused) "RESUME LOGGING" else "PAUSE LOGGING",
                    status = if (isJourneyPaused) NeoStatus.ACTIVE else NeoStatus.WARNING,
                    onClick = onPauseLoggingToggle,
                    modifier = Modifier.fillMaxWidth()
                )

                NeoButton(
                    text   = "END JOURNEY",
                    status = NeoStatus.CRITICAL,
                    onClick = { showSheet = false; onEndJourney() },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GloveboxStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color(0xFF64748B))
        Text(value, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
    }
}

private fun estimateRemainingEtaLabel(
    currentLat: Double,
    currentLng: Double,
    selectedRoute: RouteAnalysis?
): String {
    val route = selectedRoute?.route ?: return "--"
    val points = route.polyline
    if (points.size < 2 || currentLat == 0.0 || currentLng == 0.0) {
        return "${route.durationMin} min"
    }

    val totalDistance = routeDistanceMeters(points)
    if (totalDistance <= 1.0) return "${route.durationMin} min"

    val closestIndex = points.indices.minByOrNull { index ->
        distanceMeters(currentLat, currentLng, points[index].latitude, points[index].longitude)
    } ?: return "${route.durationMin} min"

    val coveredDistance = routeDistanceMeters(points.take(max(closestIndex + 1, 2)))
    val remainingRatio = ((totalDistance - coveredDistance) / totalDistance).coerceIn(0.05, 1.0)
    val remainingMinutes = max(1, (route.durationMin * remainingRatio).roundToInt())
    return "$remainingMinutes min"
}

private fun routeDistanceMeters(points: List<LatLng>): Double {
    if (points.size < 2) return 0.0
    return points.zipWithNext().sumOf { (start, end) ->
        distanceMeters(start.latitude, start.longitude, end.latitude, end.longitude)
    }
}

private fun distanceMeters(
    lat1: Double,
    lng1: Double,
    lat2: Double,
    lng2: Double
): Double {
    val earthRadius = 6_371_000.0
    val deltaLat = Math.toRadians(lat2 - lat1)
    val deltaLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(deltaLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(deltaLng / 2).let { it * it }
    return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
