package com.example.smartroaddamage.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================
// DUAL-MODE COLOR PALETTE (Light & Dark)
// ============================================

// Light Mode - Neo-Clean Aesthetic
object LightColors {
    val AppBackground = Color(0xFFF8FAFC)      // Cool Gray
    val BentoSurface = Color(0xFFFFFFFF)       // Pure White
    val CardBorder = Color(0xFFE2E8F0)         // Slate 200
    val PrimaryText = Color(0xFF1E293B)        // Slate 800
    val MonospaceGray = Color(0xFF64748B)      // Muted Blue
    
    // Tri-State Button Colors
    val IdleState = Color(0xFFB91C1C)          // Deep Ruby
    val SearchingState = Color(0xFFD97706)     // Amber Gold
    val ConnectedState = Color(0xFF059669)     // Emerald
}

// Dark Mode - Titanium Aesthetic
object DarkColors {
    val AppBackground = Color(0xFF0F172A)      // Deep Slate
    val BentoSurface = Color(0xFF1E293B)       // Slate 800
    val CardBorder = Color(0xFF334155)         // Slate 700
    val PrimaryText = Color(0xFFF1F5F9)        // Slate 100
    val MonospaceGray = Color(0xFF94A3B8)      // Sky Gray
    
    // Tri-State Button Colors
    val IdleState = Color(0xFFEF4444)          // Crimson Glow
    val SearchingState = Color(0xFFFBBF24)     // Safety Yellow
    val ConnectedState = Color(0xFF2DD4BF)     // Cyber Teal
}

// Slate palette — referenced by some legacy components
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)
