package com.example.smartroaddamage.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.smartroaddamage.ui.theme.JetBrainsMono
import com.example.smartroaddamage.ui.theme.NeoBrutalTokens
import com.example.smartroaddamage.ui.theme.neoBrutalSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * OpeningScreen — The "Lobby" of the Smart Road Monitor Application
 *
 * Neo-Brutalist edition:
 * - Flat DeepSlate background (no radial gradient — dead metal)
 * - Hard-shadow industrial logo box
 * - Spring-based entry animation for weighted feel
 * - Continuous "breathing" pulse effect
 * - Metallic text gradient for industrial look
 * - Automatic transition to Dashboard after brand exposure
 */
@Composable
fun OpeningScreen(
    onAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 1. Entry Animations — Landing with spring physics
    val scale = remember { Animatable(0.8f) }
    val alpha = remember { Animatable(0f) }

    // 2. The "Breathing" Loop — Continuous pulse to show system is alive
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_pulse"
    )

    // 3. Trigger Entry & Transition Logic
    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(800, easing = FastOutSlowInEasing)
            )
        }

        // Wait for 2.5 seconds (Brand Exposure) then trigger transition
        delay(2500)
        onAnimationFinished()
    }

    // 4. The Fullscreen Container — Flat DeepSlate (no gradient, no glass)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeoBrutalTokens.DeepSlate),
        contentAlignment = Alignment.Center
    ) {
        // 5. The Logo & Title Group with Combined Animations
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer(
                scaleX = scale.value * breathingScale,
                scaleY = scale.value * breathingScale,
                alpha = alpha.value
            )
        ) {
            // THE LOGO BOX — Neo-Brutalist hard-shadow industrial block
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .neoBrutalSurface(cornerRadius = 12.dp, shadowOffset = 6.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // THE SYSTEM TITLE — Industrial monospace
            Text(
                text = "SMART ROAD MONITOR",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 0.2.em,
                    color = Color.White
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // SUBTITLE — System Status Indicator
            Text(
                text = "INITIALIZING SYSTEM",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Normal,
                    fontSize = 11.sp,
                    letterSpacing = 0.15.em,
                    color = NeoBrutalTokens.SlateText
                )
            )
        }
    }
}
