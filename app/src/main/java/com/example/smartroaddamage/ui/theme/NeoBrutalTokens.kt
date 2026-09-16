package com.example.smartroaddamage.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * NeoBrutalTokens — The "Surgical Pop" Palette
 *
 * Color is applied to BORDERS and SHADOWS only, never surface backgrounds.
 * Surfaces stay flat Slate (CardSlate / DeepSlate) to maintain the
 * machined-equipment aesthetic. High-saturation tokens exist as 2px
 * semantic accents — the "LED indicators" on a dead-metal chassis.
 */
object NeoBrutalTokens {
    val DeepSlate   = Color(0xFF0F172A)   // Page background — "The Void"
    val CardSlate   = Color(0xFF1E293B)   // Card face — flat, no transparency
    val DimGray     = Color(0xFF334155)   // Inactive / offline elements
    val SlateText   = Color(0xFF94A3B8)   // Secondary monospace text

    val ActionYellow  = Color(0xFFFFD600)  // Primary CTA — ignition, connect
    val SystemTeal    = Color(0xFF2DD4BF)  // Active state — data flowing, connected
    val WarningAmber  = Color(0xFFF59E0B)  // Caution — side-stand, low battery
    val CriticalRose  = Color(0xFFF43F5E)  // Fault — thermal, disconnect, error

    val HardShadow  = Color(0xFF000000)   // Canonical black offset shadow
    val HardBorder  = Color(0xFF000000)   // Default 2px border — idle state
}

/**
 * Semantic state for Neo-Brutalist components.
 * Determines the border color, shadow color, and animation behavior.
 *
 * - IDLE:     Black border, black shadow (the default — dead metal)
 * - ACTIVE:   Teal border, teal shadow glow (data flowing)
 * - WARNING:  Amber border, amber shadow (needs attention)
 * - CRITICAL: Rose border, rose shadow + pulsing spread (fault alarm)
 */
enum class NeoStatus {
    IDLE,
    ACTIVE,
    WARNING,
    CRITICAL
}
