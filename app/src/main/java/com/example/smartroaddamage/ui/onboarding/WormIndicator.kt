package com.example.smartroaddamage.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * WormIndicator - A premium page indicator that "stretches" during transitions
 * 
 * This creates a fluid, organic feel as the user swipes between onboarding slides.
 * The indicator expands into a "worm" shape during the transition, then contracts
 * back to a dot when settled.
 * 
 * @param pagerState The state of the HorizontalPager
 * @param slideCount Total number of slides
 * @param activeColor Color for the active indicator
 * @param inactiveColor Color for inactive dots
 */
@Composable
fun WormIndicator(
    pagerState: PagerState,
    slideCount: Int,
    activeColor: Color,
    inactiveColor: Color = Color.White.copy(alpha = 0.3f),
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .height(8.dp)
            .width((slideCount * 20).dp)
    ) {
        val spacing = 20.dp.toPx()
        val dotRadius = 4.dp.toPx()
        val centerY = size.height / 2
        
        // Draw inactive dots for all pages
        repeat(slideCount) { index ->
            val xPos = index * spacing + dotRadius
            drawCircle(
                color = inactiveColor,
                radius = dotRadius,
                center = Offset(xPos, centerY)
            )
        }
        
        // Calculate worm position and size
        val currentPage = pagerState.currentPage
        val pageOffset = pagerState.currentPageOffsetFraction
        
        // Determine if we're scrolling forward or backward
        val targetPage = if (pageOffset > 0) {
            (currentPage + 1).coerceAtMost(slideCount - 1)
        } else {
            (currentPage - 1).coerceAtLeast(0)
        }
        
        // Calculate worm metrics
        val absOffset = pageOffset.absoluteValue
        val startX = currentPage * spacing + dotRadius
        val endX = targetPage * spacing + dotRadius
        
        // The worm stretches as it moves
        // At 0% offset: normal dot at current position
        // At 50% offset: maximum stretch between current and target
        // At 100% offset: normal dot at target position
        val stretchFactor = if (absOffset < 0.5f) {
            // First half: stretch from current position
            absOffset * 2
        } else {
            // Second half: contract to target position
            (1 - absOffset) * 2
        }
        
        val wormLeft = if (pageOffset > 0) {
            startX + (endX - startX) * (absOffset - stretchFactor * 0.5f)
        } else {
            endX + (startX - endX) * (absOffset - stretchFactor * 0.5f)
        }
        
        val wormWidth = dotRadius * 2 + (spacing * stretchFactor)
        
        // Draw the worm
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(wormLeft - dotRadius, centerY - dotRadius),
            size = Size(wormWidth, dotRadius * 2),
            cornerRadius = CornerRadius(dotRadius, dotRadius)
        )
    }
}
