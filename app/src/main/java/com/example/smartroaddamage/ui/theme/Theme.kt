package com.example.smartroaddamage.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * MyApplicationTheme (Neo-Brutalism Engine)
 *
 * Enforces the "Dead Metal" dark semantic palette globally.
 * Background is strictly DeepSlate. Colored surfaces are forbidden.
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // Ignored, forced dark
    content: @Composable () -> Unit
) {
    // 1. Force dark mode, use DeepSlate for all page backgrounds
    val colorScheme = darkColorScheme(background = NeoBrutalTokens.DeepSlate)

    // 2. Enforce the two-font typography system
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // Uses Outfit and JetBrainsMono from Type.kt
        content = content
    )
}
