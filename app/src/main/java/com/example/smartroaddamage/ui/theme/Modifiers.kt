package com.example.smartroaddamage.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 1. Define the static Brush outside the composable to avoid re-instantiation
val DeepSpaceBrush = Brush.radialGradient(
    0.0f to Color(0xFF1E293B), // Slate-800
    0.4f to Color(0xFF0F172A), // Slate-900
    1.0f to Color(0xFF020617), // Obsidian-950
    center = Offset.Infinite // Will be calculated in draw scope
)

@Composable
fun NeoBentoScreenShell(
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 2. ONLY draw the background. Do not force layouts.
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1E293B),
                            Color(0xFF0F172A),
                            Color(0xFF020617)
                        ),
                        // Dynamically center the spotlight at the top-middle of the specific screen
                        center = Offset(size.width / 2f, size.height * 0.3f),
                        radius = size.width
                    )
                )
            }
    ) {
        // 3. UI Content sits safely on top, completely isolated from background draw calls
        content()
    }
}

fun Modifier.neoBrutalist(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp = 12.dp, // Strict SOP standard
    statusColor: Color = Color.Black // Default border color
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()

    // 1. Hardware-accelerated Spring Physics
    val pushOffset by animateFloatAsState(
        targetValue = if (isPressed) 0f else 6f, // 6dp hard shadow offset
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "neo_push_physics"
    )

    this
        // 2. Offload the translation to the GPU so it doesn't trigger layout changes
        .graphicsLayer {
            translationX = (6f - pushOffset).dp.toPx()
            translationY = (6f - pushOffset).dp.toPx()
        }
        // 3. Draw the hard shadow completely behind the element without adding padding
        .drawBehind {
            drawRoundRect(
                color = Color.Black,
                size = size,
                topLeft = androidx.compose.ui.geometry.Offset(pushOffset.dp.toPx(), pushOffset.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx())
            )
        }
        // 4. The physical card face
        .background(Color(0xFF141414), RoundedCornerShape(cornerRadius))
        .border(2.dp, statusColor, RoundedCornerShape(cornerRadius))
}

/**
 * Static surface variant for cards that don't need interactive physics.
 * Adheres to SOP by not using padding for shadows.
 */
fun Modifier.neoBrutalSurface(
    backgroundColor: Color = Color(0xFF141414),
    borderColor: Color = Color(0xFF000000),
    shadowColor: Color = Color(0xFF000000),
    cornerRadius: Dp = 12.dp,
    shadowOffset: Dp = 6.dp
): Modifier = this
    .drawBehind {
        drawRoundRect(
            color        = shadowColor,
            topLeft      = Offset(shadowOffset.toPx(), shadowOffset.toPx()),
            size         = Size(size.width, size.height),
            cornerRadius = CornerRadius(cornerRadius.toPx())
        )
    }
    .background(backgroundColor, RoundedCornerShape(cornerRadius))
    .border(2.dp, borderColor, RoundedCornerShape(cornerRadius))
    .clip(RoundedCornerShape(cornerRadius))
