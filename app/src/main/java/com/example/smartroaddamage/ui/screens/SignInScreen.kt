package com.example.smartroaddamage.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartroaddamage.ui.theme.*
import kotlinx.coroutines.delay

/**
 * SignInScreen — Carbon Stealth + Neo-Brutalist Authentication Page
 *
 * Wired to [SignInViewModel] which calls the real Google/Firebase
 * Credential Manager handshake via [AuthRepository].
 *
 * Auth flow:
 *  1. User taps "Sign in with Google"
 *  2. ViewModel sets isAuthenticating → PilotAuthLoader overlay appears
 *  3. System bottom-sheet opens (Google account picker)
 *  4. On success  → isAuthenticated = true → onSignInSuccess() called
 *  5. On failure  → errorMessage shown under the button
 */
@Composable
fun SignInScreen(
    onSignInSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: SignInViewModel = viewModel(
        factory = SignInViewModelFactory(context)
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate away once authenticated
    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onSignInSuccess()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    0.0f to Color(0xFF1E293B),  // Slate-800: subtle centre glow
                    0.6f to Color(0xFF0F172A),  // Slate-900: mid-tone
                    1.0f to Color(0xFF020617)   // Obsidian-950: deep edges
                )
            )
    ) {
        // ── Main Content ──────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo — Neo-Brutalist industrial box
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .neoBrutalSurface(cornerRadius = 12.dp, shadowOffset = 4.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "PILOT LOGIN",
                fontFamily = Outfit,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Authorize geospatial data access",
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = NeoBrutalTokens.SlateText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(64.dp))

            // Neo-Brutalist Google Sign-In Button
            NeoSignInButton(
                onClick = { viewModel.onPilotLoginClicked() },
                enabled = !uiState.isAuthenticating
            )

            // Error readout — only visible on failure
            val errorMessage = uiState.errorMessage
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "⚠ $errorMessage",
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = NeoBrutalTokens.CriticalRose,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { viewModel.clearError() }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "By signing in, you agree to our Terms of Service",
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = NeoBrutalTokens.SlateText.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }

        // ── Tactical Auth Overlay (replaces generic spinner) ─────────────────
        if (uiState.isAuthenticating) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF080808).copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                PilotAuthLoader()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PilotAuthLoader — Tactical Hardware Uplink Animation
// Replaces the generic CircularProgressIndicator with a Carbon Stealth
// scanning block that moves on a physical track, paired with a monospace
// telemetry readout cycling through real handshake step labels.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PilotAuthLoader(modifier: Modifier = Modifier) {
    val carbon950   = Color(0xFF080808)
    val concrete800 = Color(0xFF1E1E1E)
    val toxicLime   = Color(0xFFADFF2F)

    // Telemetry step sequencer
    var authStep by remember { mutableStateOf("INITIALIZING SECURE LINK...") }
    LaunchedEffect(Unit) {
        delay(600)
        authStep = "VERIFYING GOOGLE OAUTH..."
        delay(800)
        authStep = "SYNCING PILOT TELEMETRY..."
        delay(800)
        authStep = "ACCESS GRANTED."
    }

    // Infinite scanner sweep
    val transition = rememberInfiniteTransition(label = "auth_scanner")
    val scanPosition by transition.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_bar"
    )

    // Neo-Bento hard-shadow card (8dp primary modal offset)
    Box(
        modifier = modifier
            .width(320.dp)
            .drawBehind {
                drawRoundRect(
                    color       = Color.Black,
                    topLeft     = Offset(8.dp.toPx(), 8.dp.toPx()),
                    size        = size,
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            }
            .background(concrete800, RoundedCornerShape(12.dp))
            .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
            .padding(24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Text(
                text  = "AUTHENTICATION",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 20.sp),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Hardware progress track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(carbon950, RoundedCornerShape(8.dp))
                    .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
            ) {
                // ToxicLime scanner block
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = 0.3f)
                        .offset(x = (200 * scanPosition).dp)
                        .background(toxicLime, RoundedCornerShape(6.dp))
                        .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Monospace telemetry readout
            Text(
                text  = authStep,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = toxicLime
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeoSignInButton — Neo-Brutalist press-offset button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NeoSignInButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val view  = LocalView.current
    val shape = RoundedCornerShape(12.dp)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset by animateDpAsState(
        targetValue   = if (isPressed) 4.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessHigh
        ),
        label = "signin_press"
    )

    Box(modifier = modifier.fillMaxWidth()) {
        // Static hard shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 4.dp, y = 4.dp)
                .background(Color.Black, shape)
        )

        // Animated button face
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = (-4).dp + pressOffset, y = (-4).dp + pressOffset)
                .background(NeoBrutalTokens.ActionYellow, shape)
                .border(2.dp, Color.Black, shape)
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
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Google "G" — industrial monogram
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Black, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "G",
                        fontFamily  = Outfit,
                        fontWeight  = FontWeight.ExtraBold,
                        fontSize    = 14.sp,
                        color       = Color.Black
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text       = if (enabled) "Sign in with Google" else "Authenticating...",
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp,
                    color      = Color.Black
                )
            }
        }
    }
}
