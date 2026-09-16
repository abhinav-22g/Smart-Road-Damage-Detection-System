package com.example.smartroaddamage.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * SonarRadar — "Neo-Brutalist Phosphor HUD" edition.
 *
 * Three-canvas architecture:
 *
 * [Canvas 0] Ghost Signal atmosphere + circular elevation glow ring.
 *   Randomised blips that cycle: fade-in 800ms → hold 500ms → fade-out 1200ms.
 *   Count scales with device discovery (2 idle → 6 when devices found).
 *   Soft radial glow ring at radar edge provides depth/elevation without a square border.
 *
 * [Canvas 1] GPU-rotated radar layer (graphicsLayer).
 *   Dashed rings @ 10% alpha, compass ticks, TWIN phosphor sweep arcs (180° apart),
 *   4-layer bloom leading edge + white cutting spike, titanium core hub.
 *
 * [Canvas 2] Static blip overlay — screen-space, not rotated.
 *   Rests at 30% alpha; spikes 100%→0% over 200ms when sweep crosses it.
 */

// ── Ghost Signal particle data ───────────────────────────────────────────────
private data class GhostBlip(
    val normX: Float,   // -1..1 normalised within radar radius
    val normY: Float,
    val alpha: Animatable<Float, AnimationVector1D>
)

@Composable
fun SonarRadar(
    isActive: Boolean,
    foundDevice: Boolean,
    modifier: Modifier = Modifier
) {
    // ── Rotation: 2400ms — Machined sweep (Subtle & slow) ─────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "sonar_sweep")
    val rotation by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sonar_rotation"
    )

    // ── Ghost Signal particles ────────────────────────────────────────────────
    // 8 particles total — alpha budget scales with device discovery
    val particleCount  = 8
    val coroutineScope = rememberCoroutineScope()
    val ghostBlips = remember {
        val list = mutableListOf<GhostBlip>()
        val rng  = Random(seed = 42)
        repeat(particleCount) {
            var nx: Float; var ny: Float
            do {
                nx = rng.nextFloat() * 2f - 1f
                ny = rng.nextFloat() * 2f - 1f
            } while (nx * nx + ny * ny > 0.85f * 0.85f)
            list.add(GhostBlip(nx, ny, Animatable(0f)))
        }
        list
    }

    // density multiplier: 0.25 when idle, 1.0 when device found
    val atmosphereDensity = if (foundDevice) 1.0f else 0.25f

    LaunchedEffect(Unit) {
        ghostBlips.forEachIndexed { idx, blip ->
            coroutineScope.launch {
                delay(idx * 300L)
                while (true) {
                    blip.alpha.animateTo(0.28f, tween(800, easing = FastOutSlowInEasing))
                    delay(500)
                    blip.alpha.animateTo(0f, tween(1200, easing = LinearEasing))
                    delay(Random.nextLong(800, 2400))
                }
            }
        }
    }

    // ── Blip sync: rests at 30%, spikes on sweep crossover ───────────────────
    val blipScreenAngle = 315f
    val sweepWindow     = 20f

    val blipBaseAlpha = remember { Animatable(0f) }
    LaunchedEffect(foundDevice) {
        blipBaseAlpha.animateTo(
            targetValue   = if (foundDevice) 0.30f else 0f,
            animationSpec = tween(600)
        )
    }

    val sonarGreen    = Color(0xFF00FF41)  // Pure aerospace phosphor green
    val ringOpacities = remember { listOf(0.10f, 0.10f, 0.10f, 0.10f) }

    Box(
        modifier = modifier
            .size(280.dp)
            // Circular Neo-Brutalist shadow — offset 4dp, no blur
            .drawBehind {
                drawCircle(
                    color  = Color.Black.copy(alpha = 0.45f),
                    radius = size.minDimension / 2f + 6.dp.toPx(),
                    center = center + Offset(4.dp.toPx(), 4.dp.toPx())
                )
            }
            // Stealth circular background
            .background(Color(0xFF0A0A0A), CircleShape)
            // Subtle phosphor border ring
            .border(2.dp, sonarGreen.copy(alpha = 0.20f), CircleShape)
    ) {

        // ══ CANVAS 0: Circular glow ring + Ghost Signal atmosphere ═══════════
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx   = center.x
            val cy   = center.y
            val maxR = size.minDimension / 2f

            // ── Circular elevation glow ───────────────────────────────────────
            // Soft ring at the radar perimeter — creates depth without a square border
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.72f to Color.Transparent,
                        0.88f to sonarGreen.copy(alpha = if (isActive) 0.08f else 0.03f),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = maxR
                ),
                radius = maxR,
                center = Offset(cx, cy)
            )

            // ── Ghost Signal atmosphere ───────────────────────────────────────
            ghostBlips.forEach { blip ->
                val alpha = blip.alpha.value * atmosphereDensity
                if (alpha > 0.005f) {
                    val bx = cx + blip.normX * maxR
                    val by = cy + blip.normY * maxR
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                sonarGreen.copy(alpha = alpha),
                                sonarGreen.copy(alpha = alpha * 0.3f),
                                Color.Transparent
                            ),
                            center = Offset(bx, by),
                            radius = 10.dp.toPx()
                        ),
                        radius = 10.dp.toPx(),
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color  = sonarGreen.copy(alpha = alpha),
                        radius = 1.8.dp.toPx(),
                        center = Offset(bx, by)
                    )
                }
            }
        }

        // ══ CANVAS 1: GPU-rotated radar layer ════════════════════════════════
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = if (isActive) rotation else 0f }
        ) {
            val cx       = center.x
            val cy       = center.y
            val maxR     = size.minDimension / 2f
            val strokePx = 1.dp.toPx()

            // ── 1. Dashed rings @ 10% alpha ───────────────────────────────────
            ringOpacities.forEachIndexed { idx, opacity ->
                val ringRadius = maxR * ((idx + 1).toFloat() / ringOpacities.size)
                drawCircle(
                    color  = sonarGreen.copy(alpha = opacity),
                    radius = ringRadius,
                    center = Offset(cx, cy),
                    style  = Stroke(
                        width      = strokePx,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 20f), 0f)
                    )
                )
            }

            // ── 2. Compass ticks at 0°, 90°, 180°, 270° ─────────────────────
            val tickLen = 7.dp.toPx()
            listOf(0.0, 90.0, 180.0, 270.0).forEach { angleDeg ->
                val rad      = Math.toRadians(angleDeg - 90.0)
                val outerTip = Offset(cx + (maxR * cos(rad)).toFloat(),             cy + (maxR * sin(rad)).toFloat())
                val innerTip = Offset(cx + ((maxR - tickLen) * cos(rad)).toFloat(), cy + ((maxR - tickLen) * sin(rad)).toFloat())
                drawLine(sonarGreen.copy(alpha = 0.40f), innerTip, outerTip, strokeWidth = 1.5.dp.toPx())
            }

            // ── 3. Crosshair ──────────────────────────────────────────────────
            drawLine(sonarGreen.copy(alpha = 0.07f), Offset(cx - maxR, cy), Offset(cx + maxR, cy), strokePx)
            drawLine(sonarGreen.copy(alpha = 0.07f), Offset(cx, cy - maxR), Offset(cx, cy + maxR), strokePx)

            if (isActive) {
                // ── 4. Pure Green Phosphor sweep — Subtle 290° tail ───────────
                drawArc(
                    brush = Brush.sweepGradient(
                        0.00f to Color.Transparent,
                        0.19f to Color.Transparent,
                        0.65f to sonarGreen.copy(alpha = 0.02f),
                        0.90f to sonarGreen.copy(alpha = 0.15f),
                        1.00f to sonarGreen.copy(alpha = 0.60f) 
                    ),
                    startAngle  = 0f,
                    sweepAngle  = 360f,
                    useCenter   = true
                )
                // Clean phosphor leading-edge line — subtle
                drawLine(
                    color       = sonarGreen.copy(alpha = 0.50f),
                    start       = Offset(cx, cy),
                    end         = Offset(cx, cy - maxR),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // ── 5. Pure green core hub — subtle ───────────────────────────
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(sonarGreen.copy(alpha = 0.6f), sonarGreen.copy(alpha = 0.2f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = 6.dp.toPx()
                ),
                radius = 4.dp.toPx(),
                center = Offset(cx, cy)
            )
        }

        // ══ CANVAS 2: Static blip overlay (screen-space, not rotated) ════════
        if (foundDevice) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                var dynamicSpike = 0f
                if (isActive) {
                    val raw  = (rotation - blipScreenAngle + 360f) % 360f
                    val diff = if (raw > 180f) 360f - raw else raw
                    if (diff < sweepWindow) dynamicSpike = 1f - (diff / sweepWindow)
                }
                val effectiveBlipAlpha = (blipBaseAlpha.value + dynamicSpike).coerceIn(0f, 1f)
                if (effectiveBlipAlpha > 0.01f) {
                    val cx   = center.x
                    val cy   = center.y
                    val maxR = size.minDimension / 2f
                    val blipAngleRad = Math.toRadians(blipScreenAngle - 90.0)
                    val blipRadius   = maxR * 0.62f
                    val blipX        = cx + (blipRadius * cos(blipAngleRad)).toFloat()
                    val blipY        = cy + (blipRadius * sin(blipAngleRad)).toFloat()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF18D322).copy(alpha = effectiveBlipAlpha),
                                Color(0xFF18D322).copy(alpha = effectiveBlipAlpha * 0.35f),
                                Color.Transparent
                            ),
                            center = Offset(blipX, blipY),
                            radius = 18.dp.toPx()
                        ),
                        radius = 18.dp.toPx(),
                        center = Offset(blipX, blipY)
                    )
                    drawCircle(
                        color  = Color(0xFF18D322).copy(alpha = effectiveBlipAlpha),
                        radius = 3.dp.toPx(),
                        center = Offset(blipX, blipY)
                    )
                }
            }
        }
    }
}

// Shim so anything referencing TacticalRadarModule still compiles
@Composable
fun TacticalRadarModule(
    isScanning: Boolean,
    realDevices: List<BleDevice>,
    modifier: Modifier = Modifier
) {
    SonarRadar(isActive = isScanning, foundDevice = realDevices.isNotEmpty(), modifier = modifier)
}
