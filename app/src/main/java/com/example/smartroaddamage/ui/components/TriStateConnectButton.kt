package com.example.smartroaddamage.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.smartroaddamage.ui.theme.DarkColors
import com.example.smartroaddamage.ui.theme.JetBrainsMono
import com.example.smartroaddamage.ui.theme.LightColors
import com.example.smartroaddamage.ui.theme.Outfit

/**
 * Connection states for the Hardware Bridge
 */
enum class ConnectionState {
    IDLE,       // Red - Standby/Disconnected
    SEARCHING,  // Yellow - Actively scanning for devices
    CONNECTING, // Blue - Connecting to selected device
    CONNECTED   // Teal - Successfully connected
}

/**
 * TriStateConnectButton - Premium hardware connection button
 * 
 * Features:
 * - Three distinct visual states (Idle/Searching/Connected)
 * - Heartbeat pulse animation when searching
 * - Smooth color transitions (300ms)
 * - Gentle haptic feedback on press
 * - 3D gradient effect for depth
 * - Recessed shadow when idle, glowing border when connected
 * 
 * @param state Current connection state
 * @param onClick Callback when button is pressed
 * @param modifier Modifier for the button
 * @param isDarkMode Whether app is in dark mode
 */
@Composable
fun TriStateConnectButton(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false
) {
    val view = LocalView.current
    val shape = RoundedCornerShape(8.dp)

    // ── Background color (state-driven) ──────────────────────────────────────
    val buttonColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.IDLE       -> Color(0xFFFFD700) // Safety Yellow
            ConnectionState.SEARCHING  -> Color(0xFFFFD700) // Keep yellow
            ConnectionState.CONNECTING -> Color(0xFF3B82F6) // Royal Blue
            ConnectionState.CONNECTED  -> Color(0xFF18D322) // Industrial Green
        },
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "neo_button_color"
    )

    // ── Press interaction → physical push-down offset ─────────────────────────
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressOffsetX by animateDpAsState(
        targetValue   = if (isPressed) 6.dp else 0.dp,
        animationSpec = tween(durationMillis = 100),
        label = "press_offset_x"
    )
    val pressOffsetY by animateDpAsState(
        targetValue   = if (isPressed) 6.dp else 0.dp,
        animationSpec = tween(durationMillis = 100),
        label = "press_offset_y"
    )

    // ── Shimmer for SCANNING border ───────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "neo_infinite")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    // ── Layout: Static shadow box behind + animated offset button box ─────────
    Box(modifier = modifier) {
        // Static hard shadow (always 6dp behind)
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 6.dp, y = 6.dp)
                .background(Color.Black, shape)
        )

        // Animated button face (moves toward shadow on press)
        Box(
            modifier = Modifier
                .offset(x = (-6).dp + pressOffsetX, y = (-6).dp + pressOffsetY)
                .background(buttonColor, shape)
                .then(
                    if (state == ConnectionState.SEARCHING || state == ConnectionState.CONNECTING) {
                        Modifier.drawWithContent {
                            drawContent()
                            // Shimmer: LinearGradient traveling across the border
                            val shimmerX = size.width * shimmerOffset
                            drawRect(
                                brush       = Brush.linearGradient(
                                    colors  = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.6f),
                                        Color.Transparent
                                    ),
                                    start   = Offset(shimmerX - size.width * 0.4f, 0f),
                                    end     = Offset(shimmerX + size.width * 0.4f, size.height)
                                ),
                                size        = this.size
                            )
                            // Border stroke on top of shimmer
                            drawRoundRect(
                                color       = Color.Black,
                                style       = Stroke(width = 3.dp.toPx()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                            )
                        }
                    } else {
                        Modifier.border(3.dp, Color.Black, shape)
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication        = null
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onClick()
                }
                .padding(horizontal = 28.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier             = Modifier.animateContentSize(
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                ),
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Icon: Crossfade between Bluetooth (idle/scanning) and Check (connected)
                AnimatedContent(
                    targetState   = state,
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    },
                    label = "icon_morph"
                ) { s ->
                    val icon = when (s) {
                        ConnectionState.CONNECTED  -> Icons.Default.Check
                        ConnectionState.CONNECTING -> Icons.Default.Link
                        else                       -> Icons.Default.Bluetooth
                    }
                    Icon(
                        imageVector        = icon,
                        contentDescription = null,
                        tint               = Color.Black,
                        modifier           = Modifier.size(20.dp)
                    )
                }

                // Label: slides/morphs via animateContentSize on parent Row
                Text(
                    text = when (state) {
                        ConnectionState.IDLE       -> "INITIALIZE BRIDGE"
                        ConnectionState.SEARCHING  -> "SCANNING..."
                        ConnectionState.CONNECTING -> "LINKING..."
                        ConnectionState.CONNECTED  -> "SYSTEM LINKED"
                    },
                    style  = TextStyle(
                        fontFamily   = Outfit,
                        fontWeight   = if (state == ConnectionState.CONNECTED)
                                           FontWeight.ExtraBold else FontWeight.Bold,
                        fontSize     = 15.sp,
                        color        = Color.Black,
                        letterSpacing = 0.08.em
                    )
                )
            }
        }
    }

    // Haptics on CONNECTED
    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTED) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }
}

/**
 * Status text to display below the connect button
 */
@Composable
fun ConnectionStatusText(
    rssi: Int?,
    deviceName: String?,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (deviceName != null) {
            Text(
                text = deviceName,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = if (isDarkMode) DarkColors.PrimaryText else LightColors.PrimaryText
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        
        if (rssi != null) {
            Text(
                text = "RSSI: ${rssi}dBm",
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                color = if (isDarkMode) DarkColors.MonospaceGray else LightColors.MonospaceGray
            )
        }
    }
}
