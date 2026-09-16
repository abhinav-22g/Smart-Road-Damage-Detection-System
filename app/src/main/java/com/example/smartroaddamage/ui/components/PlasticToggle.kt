package com.example.smartroaddamage.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartroaddamage.ui.theme.neoBrutalist

private val ToxicLime = Color(0xFFADFF2F)

@Composable
fun NeoBentoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val trackWidth = 52.dp
    val trackHeight = 28.dp
    val thumbSize = 20.dp
    val padding = 4.dp

    // Spring physics for the physical sliding motion
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - padding else padding,
        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioLowBouncy),
        label = "switch_slide"
    )

    // Hardware active color transition
    val thumbBorderColor by animateColorAsState(
        targetValue = if (checked) ToxicLime else Color.Black,
        tween(200), label = "switch_color"
    )

    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .background(Color(0xFF0A0A0A), RoundedCornerShape(14.dp)) // Stealth track
            .border(2.dp, Color.Black, RoundedCornerShape(14.dp)), // Recessed look
        contentAlignment = Alignment.CenterStart
    ) {
        // The Physical Thumb
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                // Draw a mini hard shadow for the thumb
                .drawBehind {
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset(4f, 4f),
                        size = size,
                        cornerRadius = CornerRadius(10.dp.toPx())
                    )
                }
                .background(Color(0xFF2A2A2A), RoundedCornerShape(10.dp)) // Carbon face
                .border(2.dp, thumbBorderColor, RoundedCornerShape(10.dp))
        )
    }
}

/**
 * NeoBrutalistLever — "Slab-Lever" industrial toggle.
 *
 * Replaces the round thumb-track switch with a hard-edged physical lever:
 *   - Rectangular knob pivots ±25° from a base slab
 *   - High-tension spring (stiffness=800, damping=0.6) for instant mechanical snap
 *   - LongPress haptic fires on every toggle
 *   - Green (ON) / Red (OFF) knob color transition in 150ms
 *
 * Use ONLY for Voice Alerts and Black Box toggles per the Neo-Brutalist SOP.
 */
@Composable
fun NeoBrutalistLever(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptics = LocalHapticFeedback.current

    // High-tension pivot spring — immediate mechanical snap at 800 stiffness
    val leverRotation by animateFloatAsState(
        targetValue   = if (checked) 25f else -25f,
        animationSpec = spring(
            dampingRatio = 0.6f,   // Energetic bounce
            stiffness    = 800f    // High tension — no lag
        ),
        label = "LeverPivot"
    )

    // Knob color: CriticalRose (OFF) → ToxicLime (ON)
    val knobColor by animateColorAsState(
        targetValue   = if (checked) Color(0xFFADFF2F) else Color(0xFFF43F5E),
        animationSpec = tween(150),
        label         = "LeverColor"
    )

    Box(
        modifier = Modifier
            .size(width = 80.dp, height = 100.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // The Base Slab — hard offset shadow, zero blur, 4dp corner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .drawBehind {
                    drawRoundRect(
                        color        = Color.Black,
                        topLeft      = Offset(6.dp.toPx(), 6.dp.toPx()),
                        size         = size,
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                }
                .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
                .border(3.dp, Color.Black, RoundedCornerShape(4.dp))
        )

        // The Lever Arm — pivots at the base (transformOrigin Y=0.85)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(26.dp)
                .graphicsLayer {
                    rotationZ       = leverRotation
                    transformOrigin = TransformOrigin(0.5f, 0.85f)
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Machined rectangular knob
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(knobColor, RoundedCornerShape(4.dp))
                    .border(3.dp, Color.Black, RoundedCornerShape(4.dp))
            )
            // Rigid steel shaft
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight(0.65f)
                    .background(Color(0xFF2A2A2A))
            )
        }
    }
}

@Composable
fun VoiceIdentitySegmentedControl(isMaleSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(2.dp, Color.Black, RoundedCornerShape(8.dp)) // Hard outer shell
            .background(Color(0xFF141414), RoundedCornerShape(8.dp))
    ) {
        // Male Button
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    if (isMaleSelected) ToxicLime else Color.Transparent,
                    RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                )
                .clickable { onSelect(true) },
            contentAlignment = Alignment.Center
        ) {
            Text("MALE", style = MaterialTheme.typography.bodyLarge, color = if (isMaleSelected) Color.Black else Color.White)
        }

        // Hard divider
        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(Color.Black))

        // Female Button
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    if (!isMaleSelected) ToxicLime else Color.Transparent,
                    RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                )
                .clickable { onSelect(false) },
            contentAlignment = Alignment.Center
        ) {
            Text("FEMALE", style = MaterialTheme.typography.bodyLarge, color = if (!isMaleSelected) Color.Black else Color.White)
        }
    }
}

@Composable
fun CockpitSettingsDrawer(
    pilotName:     String = "Elite Pilot",
    callsign:      String = "OFFLINE",
    avatarInitial: String = "EP"
) {
    var voiceAlertsEnabled by remember { mutableStateOf(true) }
    var isMaleVoice        by remember { mutableStateOf(true) }
    var blackBoxEnabled    by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Color(0xFF141414))
            .drawBehind {
                drawLine(
                    color       = Color.Black,
                    start       = Offset(size.width, 0f),
                    end         = Offset(size.width, size.height),
                    strokeWidth = 12f
                )
            }
            .padding(24.dp)
    ) {
        Text(
            text     = "COCKPIT SETTINGS",
            style    = MaterialTheme.typography.displayLarge.copy(fontSize = 20.sp),
            color    = Color.White,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // --- BENTO 1: LIVE PROFILE ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neoBrutalist(remember { MutableInteractionSource() })
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar squircle — ToxicLime with first initial from Google account
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(ToxicLime, RoundedCornerShape(12.dp))
                        .border(2.dp, Color.Black, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = avatarInitial,
                        color = Color.Black,
                        style = MaterialTheme.typography.displayLarge
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    // Live display name from Firebase
                    Text(
                        text  = pilotName.uppercase(),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 15.sp),
                        color = Color.White
                    )
                    // Live UID callsign
                    Text(
                        text  = callsign,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp),
                        color = Color.Gray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- BENTO 2: VOICE TELEMETRY ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neoBrutalist(remember { MutableInteractionSource() })
                .padding(16.dp)
        ) {
            Column {
                // Row 1: Voice Alerts — Slab-Lever
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("Voice Alerts", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    NeoBrutalistLever(
                        checked         = voiceAlertsEnabled,
                        onCheckedChange = { voiceAlertsEnabled = it }
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Row 2: The Segmented Control (Replacing the blue pills)
                Text("Voice Identity", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp), color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                VoiceIdentitySegmentedControl(
                    isMaleSelected = isMaleVoice,
                    onSelect = { isMaleVoice = it }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- BENTO 3: BLACK BOX ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .neoBrutalist(remember { MutableInteractionSource() })
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("BLACK BOX", style = MaterialTheme.typography.displayLarge.copy(fontSize = 16.sp), color = Color.White)
                    Text("MicroSD Logging", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp), color = Color.Gray)
                }
                // Black Box — Slab-Lever
                NeoBrutalistLever(
                    checked         = blackBoxEnabled,
                    onCheckedChange = { blackBoxEnabled = it }
                )
            }
        }
    }
}
