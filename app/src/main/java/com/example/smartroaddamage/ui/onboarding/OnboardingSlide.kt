package com.example.smartroaddamage.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.example.smartroaddamage.R

/**
 * Data model for each onboarding slide
 * 
 * @param title The main headline for this slide
 * @param description Short description (max 2 lines for "Hype" feel)
 * @param imageRes Resource ID for the 3D render (must be in drawable-nodpi)
 * @param themeColor Background color for this slide (extracted from image palette)
 * @param accentColor Color for buttons, indicators, and interactive elements
 */
data class OnboardingSlide(
    val title: String,
    val description: String,
    @param:DrawableRes val imageRes: Int,
    val themeColor: Color,
    val accentColor: Color
)

/**
 * The 5 onboarding slides for Smart Road Monitor
 * Colors are carefully selected to match the "Titanium" aesthetic
 */
val onboardingSlides = listOf(
    OnboardingSlide(
        title = "Detect Hazards",
        description = "Identify potholes in real-time using high-precision IMU sensors.",
        imageRes = R.drawable.intro_1,
        themeColor = Color(0xFF0F172A), // Slate 900 - Deep Space
        accentColor = Color(0xFF3B82F6)  // Blue 500 - Primary
    ),
    OnboardingSlide(
        title = "Geospatial Mapping",
        description = "Every impact is tagged with 2.5m GPS precision for authorities.",
        imageRes = R.drawable.intro_2,
        themeColor = Color(0xFF1E293B), // Slate 800 - Midnight
        accentColor = Color(0xFF2DD4BF)  // Teal 400 - Accent
    ),
    OnboardingSlide(
        title = "Live Monitoring",
        description = "Real-time visualization of road conditions and sensor data.",
        imageRes = R.drawable.intro_3,
        themeColor = Color(0xFF0F172A), // Slate 900 - Deep Space
        accentColor = Color(0xFF60A5FA)  // Blue 400 - Light Primary
    ),
    OnboardingSlide(
        title = "Data Analytics",
        description = "Advanced DSP filtering and black box recording for analysis.",
        imageRes = R.drawable.intro_4,
        themeColor = Color(0xFF1E293B), // Slate 800 - Midnight
        accentColor = Color(0xFFF97316)  // Orange 500 - Hazard
    ),
    OnboardingSlide(
        title = "Smart Infrastructure",
        description = "Building safer roads through intelligent sensor networks.",
        imageRes = R.drawable.intro_5,
        themeColor = Color(0xFF0F172A), // Slate 900 - Deep Space
        accentColor = Color(0xFF10B981)  // Green 500 - Success
    )
)
