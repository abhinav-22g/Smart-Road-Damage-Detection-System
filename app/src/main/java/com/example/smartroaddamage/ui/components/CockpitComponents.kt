package com.example.smartroaddamage.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartroaddamage.ui.theme.JetBrainsMono
import com.example.smartroaddamage.ui.theme.Outfit
import com.example.smartroaddamage.ui.theme.neoBrutalist
import com.example.smartroaddamage.ui.theme.neoBrutalSurface
import kotlin.math.abs
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// DESIGN TOKENS
// ─────────────────────────────────────────────────────────────────────────────
private val Chassis      = Color(0xFF0F172A)   // Slate 900 — background
private val HardBlack    = Color(0xFF000000)
private val IndYellow    = Color(0xFFFFD600)   // Industrial Yellow — action / active
private val SystemReady  = Color(0xFF2DD4BF)   // Teal — locked / ready
private val SystemFault  = Color(0xFFEF4444)   // Harsh Red — fault
private val DimGray      = Color(0xFF334155)   // Inactive / offline
private val SlateText    = Color(0xFF94A3B8)   // Secondary text
private val CardBg       = Color(0xFF1E293B)   // Slate 800 — card face

// ─────────────────────────────────────────────────────────────────────────────
// MODULE 1 — POWER CORE CARD (50% width)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PowerCoreCard(
    battery: Int,       // 0–100 SOC
    voltage: Float,     // e.g. 7.4f
    tempC: Float,       // box temperature from MPU6050
    modifier: Modifier = Modifier
) {
    // Danger state: red border + pulsing background above 65°C
    val isThermalFault = tempC > 65f
    val infiniteTransition = rememberInfiniteTransition(label = "fault_pulse")
    val faultPulse by infiniteTransition.animateFloat(
        initialValue  = 0.5f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fault_alpha"
    )
    val borderColor = if (isThermalFault) SystemFault else HardBlack
    val faceColor   = if (isThermalFault)
        SystemFault.copy(alpha = faultPulse * 0.25f + 0.1f)
    else
        CardBg

    // Use derivedStateOf — only recompose when the integer percent changes, not every raw float
    val displayBattery by remember(battery) { derivedStateOf { battery.coerceIn(0, 100) } }

    Column(
        modifier = modifier
            .neoBrutalSurface(
                backgroundColor = CardBg, // Fixed base color
                shadowOffset    = 5.dp,
                borderColor     = borderColor
            )
            .drawBehind {
                if (isThermalFault) {
                    drawRoundRect(
                        color = SystemFault.copy(alpha = faultPulse * 0.25f + 0.1f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
                    )
                }
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Label
        Text(
            "POWER CORE",
            fontFamily    = Outfit,
            fontWeight    = FontWeight.ExtraBold,
            fontSize      = 10.sp,
            letterSpacing = 1.5.sp,
            color         = SlateText
        )

        // Segmented battery blocks (5 segments)
        val filledCount = (displayBattery / 20).coerceIn(0, 5)
        val segColor = when {
            isThermalFault      -> SystemFault
            displayBattery > 40 -> SystemReady
            displayBattery > 20 -> IndYellow
            else                -> SystemFault
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(5) { idx ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .background(
                            if (idx < filledCount) segColor else DimGray,
                            RoundedCornerShape(3.dp)
                        )
                        .border(1.dp, HardBlack, RoundedCornerShape(3.dp))
                )
            }
        }

        // Large SOC readout — AnimatedContent for the "odometer roll" effect
        AnimatedContent(
            targetState   = displayBattery,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            },
            label = "soc_counter"
        ) { soc ->
            Text(
                text       = "$soc%",
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize   = 36.sp,
                color      = if (isThermalFault) SystemFault else Color.White
            )
        }

        // Data tags row
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataTag(label = "${voltage}V",  color = SlateText)
            DataTag(
                label = "${tempC.toInt()}°C",
                color = if (isThermalFault) SystemFault else SlateText
            )
        }
    }
}

@Composable
private fun DataTag(label: String, color: Color) {
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, color = color)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MODULE 2 — DATA INTEGRITY CARD (50% width)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DataIntegrityCard(
    gpsSats: Int,         // 0 = no fix
    hdop: Float,          // horizontal dilution of precision
    sdUsedGb: Float,      // used storage in GB
    sdTotalGb: Float,     // total capacity in GB
    isSdMounted: Boolean,
    modifier: Modifier = Modifier
) {
    val hasGpsFix = gpsSats >= 4
    val gpsColor  = if (hasGpsFix) SystemReady else DimGray
    val sdColor   = if (isSdMounted) IndYellow else DimGray

    Column(
        modifier = modifier
            .neoBrutalSurface(backgroundColor = CardBg, shadowOffset = 5.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "DATA INTEGRITY",
            fontFamily    = Outfit,
            fontWeight    = FontWeight.ExtraBold,
            fontSize      = 10.sp,
            letterSpacing = 1.5.sp,
            color         = SlateText
        )

        // GPS block
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GPS", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = SlateText.copy(alpha = 0.6f))
            AnimatedContent(
                targetState   = if (hasGpsFix) "SAT: $gpsSats | HDOP: $hdop" else "SEARCHING...",
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "gps_text"
            ) { text ->
                Text(text, fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, color = gpsColor, maxLines = 1)
            }
        }

        // Divider
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DimGray.copy(alpha = 0.4f)))

        // MicroSD block
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("MICROSD", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = SlateText.copy(alpha = 0.6f))
            if (isSdMounted) {
                // Blocky storage bar
                val usedFraction = (sdUsedGb / sdTotalGb.coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(DimGray, RoundedCornerShape(2.dp))
                        .border(1.dp, HardBlack, RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(usedFraction)
                            .fillMaxHeight()
                            .background(IndYellow, RoundedCornerShape(2.dp))
                    )
                }
                Text(
                    "${sdUsedGb.toInt()} GB / ${sdTotalGb.toInt()} GB",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = sdColor
                )
            } else {
                Text("NOT MOUNTED", fontFamily = JetBrainsMono, fontSize = 10.sp, color = SystemFault)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MODULE 3 — POSTURE CARD (full width, Bubble Level)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PostureCard(
    pitch: Float,   // degrees forward/backward
    roll: Float,    // degrees left/right (side-stand lean)
    modifier: Modifier = Modifier
) {
    val isSideStand  = abs(roll) > 10f
    val statusText   = if (isSideStand)
        "SIDE STAND  ${"%.1f".format(roll)}° LEAN"
    else
        "CENTER STAND"
    val statusColor  = if (isSideStand) IndYellow else SystemReady

    Row(
        modifier = modifier
            .neoBrutalSurface(backgroundColor = CardBg, shadowOffset = 5.dp)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        // Animated bubble level canvas — pre-allocated in remember
        val bubbleX by animateFloatAsState(
            targetValue   = (roll / 45f).coerceIn(-1f, 1f),
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "bubble_x"
        )
        val bubbleY by animateFloatAsState(
            targetValue   = (pitch / 45f).coerceIn(-1f, 1f),
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "bubble_y"
        )

        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFF0F172A), CircleShape)
                .border(2.dp, HardBlack, CircleShape)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r    = size.minDimension / 2f
                val cx   = size.width / 2f
                val cy   = size.height / 2f

                // Grid crosshair
                drawLine(color = SystemReady.copy(alpha = 0.2f), start = Offset(0f, cy), end = Offset(size.width, cy), strokeWidth = 1.dp.toPx())
                drawLine(color = SystemReady.copy(alpha = 0.2f), start = Offset(cx, 0f), end = Offset(cx, size.height), strokeWidth = 1.dp.toPx())
                // Center ring
                drawCircle(color = SystemReady.copy(alpha = 0.15f), radius = r * 0.35f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

                // Bubble dot — positioned by IMU
                val bx = cx + bubbleX * r * 0.6f
                val by = cy + bubbleY * r * 0.6f
                val dotColor = if (isSideStand) IndYellow else SystemReady
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(dotColor, dotColor.copy(alpha = 0.3f), Color.Transparent),
                        center = Offset(bx, by),
                        radius = 10.dp.toPx()
                    ),
                    radius = 10.dp.toPx(),
                    center = Offset(bx, by)
                )
                drawCircle(color = dotColor, radius = 5.dp.toPx(), center = Offset(bx, by))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("VEHICLE POSTURE", fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, letterSpacing = 1.5.sp, color = SlateText)
            Text(statusText, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = statusColor)
            Text(
                "R: ${"%.1f".format(roll)}°   P: ${"%.1f".format(pitch)}°",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = SlateText
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MODULE 4 — CALIBRATION CARD (full width, sparkline + Neo-Brutalist button)
// ─────────────────────────────────────────────────────────────────────────────
enum class CalibrationState { IDLE, CALIBRATING, LOCKED }

@Composable
fun CalibrationCard(
    calibrationState: CalibrationState,
    imuNoiseSeries: List<Float>,   // raw noise values for sparkline, normalised 0..1
    calibProgress: Float = 0f,     // 0.0 → 1.0 — fills while collecting 100 samples
    onCalibrate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view  = LocalView.current
    val scope = rememberCoroutineScope()

    // Shimmer animation while calibrating
    val infiniteTransition = rememberInfiniteTransition(label = "cal_shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    // Button Neo-Brutalist state
    val isCalibrating = calibrationState == CalibrationState.CALIBRATING
    val isLocked      = calibrationState == CalibrationState.LOCKED

    val btnColor = when (calibrationState) {
        CalibrationState.IDLE       -> IndYellow
        CalibrationState.CALIBRATING -> DimGray
        CalibrationState.LOCKED     -> SystemReady
    }
    val btnText = when (calibrationState) {
        CalibrationState.IDLE       -> "RUN GYRO CALIBRATION"
        CalibrationState.CALIBRATING -> "CALCULATING OFFSETS..."
        CalibrationState.LOCKED     -> "CALIBRATION LOCKED ✓"
    }
    // Button shadow stays pressed (0dp) while calibrating
    val btnShadow = if (isCalibrating) 0.dp else 4.dp

    // Pre-allocate path objects outside draw scope (Surgeon's rule)
    val noisePath = remember { androidx.compose.ui.graphics.Path() }

    Column(
        modifier = modifier
            .neoBrutalSurface(backgroundColor = CardBg, shadowOffset = 5.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("GYRO CALIBRATION", fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, letterSpacing = 1.5.sp, color = SlateText)

        // Sparkline — single Canvas with drawPath (Surgeon's rule: never N Boxes for a graph)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                .border(1.dp, DimGray, RoundedCornerShape(4.dp))
        ) {
            val data   = if (isLocked || isCalibrating) imuNoiseSeries.map { it * (if (isLocked) 0f else 0.2f) } else imuNoiseSeries
            val w      = size.width
            val h      = size.height
            val step   = w / (data.size.coerceAtLeast(2) - 1)
            val lineColor = if (isLocked) SystemReady else IndYellow

            noisePath.reset()
            data.forEachIndexed { i, v ->
                val x = i * step
                val y = h - (v * h * 0.85f) - h * 0.075f
                if (i == 0) noisePath.moveTo(x, y) else noisePath.lineTo(x, y)
            }
            drawPath(noisePath, lineColor, style = Stroke(width = 1.5.dp.toPx()))
        }

        // ── Progress bar (visible only during CALIBRATING) ────────────────────
        AnimatedVisibility(visible = isCalibrating) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(DimGray, RoundedCornerShape(3.dp))
                        .border(1.dp, HardBlack, RoundedCornerShape(3.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(calibProgress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(IndYellow, IndYellow.copy(alpha = 0.7f))
                                ),
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                }
                Text(
                    "SAMPLING: ${(calibProgress * 100).toInt()}%  (${(calibProgress * 5).toInt()}s / 5s)",
                    fontFamily = JetBrainsMono,
                    fontSize   = 9.sp,
                    color      = SlateText
                )
            }
        }

        // Neo-Brutalist calibration button with hardware acceleration
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val btnEnabled = !isCalibrating
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isCalibrating) {
                        Modifier.drawBehind {
                            val sx = size.width * shimmerOffset
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.15f), Color.Transparent),
                                    start  = Offset(sx - size.width * 0.3f, 0f),
                                    end    = Offset(sx + size.width * 0.3f, size.height)
                                ),
                                size = this.size
                            )
                        }
                    } else Modifier
                )
                .then(
                    if (btnEnabled) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onCalibrate()
                        }
                    } else Modifier
                )
                .neoBrutalist(
                    interactionSource = interactionSource,
                    cornerRadius = 8.dp,
                    statusColor = Color.Black
                )
                .background(btnColor, RoundedCornerShape(8.dp)) // Face color over the shadow
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                btnText,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize   = 11.sp,
                color      = if (isCalibrating) SlateText else Color.Black
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MODULE 5 — IGNITION SLIDER (footer, gated by isSystemReady)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun IgnitionSlider(
    isSystemReady: Boolean,
    onIgnite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view    = LocalView.current
    val density = LocalDensity.current

    val trackWidth = 320.dp
    val keySize    = 60.dp
    val maxPx      = with(density) { (trackWidth - keySize).toPx() }

    var offset  by remember { mutableStateOf(0f) }
    val animOff by animateFloatAsState(
        targetValue   = offset,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label         = "slider_offset"
    )

    // Key color animates from DimGray (locked) to IndYellow (armed)
    val keyColor by animateColorAsState(
        targetValue   = if (isSystemReady) IndYellow else DimGray,
        animationSpec = tween(400),
        label         = "key_color"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Pre-flight status pills
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("GPS", isSystemReady)
            StatusPill("SD", isSystemReady)
            StatusPill("IMU", isSystemReady)
        }

        // Track — inset shadow look
        Box(
            modifier = Modifier
                .width(trackWidth + 6.dp)   // extra for shadow
                .height(keySize + 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Inset track background (dark recess)
            Box(
                modifier = Modifier
                    .width(trackWidth)
                    .height(keySize)
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF0A1020), RoundedCornerShape(8.dp))
                    .border(2.dp, HardBlack, RoundedCornerShape(8.dp))
            ) {
                // Track label
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text      = if (isSystemReady) "SLIDE TO INITIATE RIDE ▶" else "AWAITING DIAGNOSTICS",
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize  = 10.sp,
                        color     = Color.White.copy(alpha = if (isSystemReady) 1f - (animOff / maxPx) else 0.4f),
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth().padding(horizontal = keySize + 8.dp)
                    )
                }
            }

            // The Key (thumb)
            Box(
                modifier = Modifier
                    .size(keySize)
                    .offset { IntOffset(animOff.roundToInt(), 0) }
                    .drawBehind {
                        drawRoundRect(
                            color        = Color.Black,
                            topLeft      = Offset(3.dp.toPx(), 3.dp.toPx()),
                            size         = Size(size.width, size.height),
                            cornerRadius = CornerRadius(8.dp.toPx())
                        )
                    }
                    .background(keyColor, RoundedCornerShape(8.dp))
                    .border(2.dp, HardBlack, RoundedCornerShape(8.dp))
                    .then(
                        if (isSystemReady) {
                            Modifier.draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta ->
                                    offset = (offset + delta).coerceIn(0f, maxPx)
                                },
                                onDragStopped = {
                                    if (offset > maxPx * 0.8f) {
                                        offset = maxPx
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        onIgnite()
                                    } else {
                                        offset = 0f  // spring-snap back
                                    }
                                }
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Grip texture — three vertical black lines
                Canvas(modifier = Modifier.size(20.dp, 24.dp)) {
                    val lineColor = if (isSystemReady) Color.Black else Color.White.copy(alpha = 0.3f)
                    listOf(0.25f, 0.5f, 0.75f).forEach { x ->
                        drawLine(
                            color       = lineColor,
                            start       = Offset(size.width * x, 4.dp.toPx()),
                            end         = Offset(size.width * x, size.height - 4.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, isReady: Boolean) {
    val color = if (isReady) SystemReady else DimGray
    Box(
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(5.dp).background(color, CircleShape))
            Text(label, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = color)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DRIVING MODE HUD COMPOSABLES — Neo-Brutalist
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun VitalsPill(
    speed: Int,
    batteryPercentage: Int,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val batColor = if (batteryPercentage > 30) SystemReady else SystemFault

    Box(
        modifier = modifier
            .width(160.dp)
            .height(40.dp)
            .neoBrutalSurface(backgroundColor = CardBg, cornerRadius = 8.dp, shadowOffset = 3.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxSize(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$speed km/h", fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
            Box(modifier = Modifier.width(1.dp).height(16.dp).background(DimGray))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(height = 10.dp, width = 18.dp).background(DimGray, RoundedCornerShape(2.dp)).border(1.dp, HardBlack, RoundedCornerShape(2.dp))) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(batteryPercentage / 100f).background(batColor, RoundedCornerShape(1.dp)))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("$batteryPercentage%", fontFamily = JetBrainsMono, fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

@Composable
fun SignalScopeWidget(
    vibrationData: List<Float>,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val path = remember { androidx.compose.ui.graphics.Path() }
    Box(
        modifier = modifier
            .width(140.dp).height(70.dp)
            .neoBrutalSurface(backgroundColor = if (isDarkMode) Color(0xFF1E293B) else Color.White, shadowOffset = 3.dp)
            .padding(8.dp)
    ) {
        Column {
            Text("G-FORCE SCOPE", fontFamily = JetBrainsMono, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = SlateText)
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w    = size.width
                val h    = size.height
                val step = w / (vibrationData.size.coerceAtLeast(2) - 1)
                path.reset()
                vibrationData.forEachIndexed { i, v ->
                    val x = i * step; val y = h - (v * h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF3B82F6), style = Stroke(2.dp.toPx()))
            }
        }
    }
}
