package com.example.smartroaddamage.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartroaddamage.ui.theme.*

/**
 * NeoButton — Unified Semantic Neo-Brutalist button.
 *
 * Architecture: Two-Box approach from TriStateConnectButton.
 *   Box 0: Static hard shadow (always at full offset)
 *   Box 1: Animated face that pushes toward shadow on press
 *
 * Features:
 *   - Semantic border/shadow color via [NeoStatus]
 *   - Physical push-down offset (4dp) with spring physics
 *   - `CLOCK_TICK` haptic on every press
 *   - 12dp industrial squircle corners (Hardware Bridge standard)
 *   - Text in `JetBrainsMono Bold` (uppercase enforced)
 *
 * @param text       Button label — automatically uppercased
 * @param status     Semantic state that controls border/shadow color
 * @param onClick    Press callback
 * @param modifier   External modifier (sizing, padding, alignment)
 * @param enabled    Whether the button is interactive
 * @param textColor  Label color — defaults to Black for Action/Warning, White for rest
 */
@Composable
fun NeoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: NeoStatus = NeoStatus.IDLE,
    enabled: Boolean = true,
    textColor: Color = when (status) {
        NeoStatus.IDLE     -> Color.Black
        NeoStatus.ACTIVE   -> Color.Black
        NeoStatus.WARNING  -> Color.Black
        NeoStatus.CRITICAL -> Color.White
    },
    backgroundColor: Color = when (status) {
        NeoStatus.IDLE     -> NeoBrutalTokens.ActionYellow
        NeoStatus.ACTIVE   -> NeoBrutalTokens.SystemTeal
        NeoStatus.WARNING  -> NeoBrutalTokens.WarningAmber
        NeoStatus.CRITICAL -> NeoBrutalTokens.CriticalRose
    }
) {
    val view  = LocalView.current
    val shape = RoundedCornerShape(12.dp)

    // ── Semantic border color with 400ms "warming filament" transition ────────
    val borderColor by animateColorAsState(
        targetValue   = Color.Black,
        animationSpec = tween(400, easing = LinearOutSlowInEasing),
        label         = "neo_btn_border"
    )

    // ── Animated background color ────────────────────────────────────────────
    val animatedBg by animateColorAsState(
        targetValue   = backgroundColor,
        animationSpec = tween(400, easing = LinearOutSlowInEasing),
        label         = "neo_btn_bg"
    )

    // ── Press interaction ────────────────────────────────────────────────────
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressOffset by animateDpAsState(
        targetValue   = if (isPressed) 4.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessHigh
        ),
        label = "neo_btn_press"
    )

    Box(modifier = modifier) {
        // Box 0: Static hard shadow — always at 4dp offset
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 4.dp, y = 4.dp)
                .background(Color.Black, shape)
        )

        // Box 1: Animated face — pushes toward shadow on press
        Box(
            modifier = Modifier
                .offset(x = (-4).dp + pressOffset, y = (-4).dp + pressOffset)
                .background(animatedBg, shape)
                .border(2.dp, borderColor, shape)
                .clip(shape)
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication        = null
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onClick()
                        }
                    } else Modifier
                )
                .padding(horizontal = 24.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = text.uppercase(),
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize   = 13.sp,
                color      = textColor
            )
        }
    }
}
