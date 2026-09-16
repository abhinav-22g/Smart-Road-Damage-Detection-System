package com.example.smartroaddamage.ui.onboarding

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.smartroaddamage.ui.theme.JetBrainsMono
import com.example.smartroaddamage.ui.theme.Outfit
import com.example.smartroaddamage.ui.theme.NeoBrutalTokens
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * OnboardingScreen - Premium "Titanium" onboarding experience
 * 
 * Features:
 * - Fluid background color transitions based on scroll position
 * - 3D depth effect with image scaling during swipes
 * - Worm indicator for premium feel
 * - Memory-efficient image loading from drawable-nodpi
 * - Skip button that fades out on final slide
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { onboardingSlides.size })
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    
    // Fluid background color interpolation
    val backgroundColor by remember {
        derivedStateOf {
            val currentPage = pagerState.currentPage
            val offset = pagerState.currentPageOffsetFraction
            
            if (offset == 0f) {
                // No scrolling, use current page color
                onboardingSlides[currentPage].themeColor
            } else {
                // Interpolate between current and next/previous page
                val targetPage = if (offset > 0) {
                    (currentPage + 1).coerceAtMost(onboardingSlides.size - 1)
                } else {
                    (currentPage - 1).coerceAtLeast(0)
                }
                
                // Linear interpolation between colors
                androidx.compose.ui.graphics.lerp(
                    onboardingSlides[currentPage].themeColor,
                    onboardingSlides[targetPage].themeColor,
                    offset.absoluteValue
                )
            }
        }
    }
    
    // Current accent color for buttons and indicator
    val accentColor by remember {
        derivedStateOf {
            onboardingSlides[pagerState.currentPage].accentColor
        }
    }
    
    // Skip button fades out on final slide
    val skipAlpha by animateFloatAsState(
        targetValue = if (pagerState.currentPage == onboardingSlides.size - 1) 0f else 1f,
        animationSpec = tween(300),
        label = "skip_fade"
    )
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Skip Button (Top Right)
        TextButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onComplete()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .alpha(skipAlpha),
            enabled = skipAlpha > 0f
        ) {
            Text(
                text = "SKIP",
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                letterSpacing = 0.1.em,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        
        // Main Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(80.dp))
            
            // HorizontalPager with slides
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                beyondViewportPageCount = 1 // Preload adjacent pages for smooth scrolling
            ) { page ->
                OnboardingSlideContent(
                    slide = onboardingSlides[page],
                    pageOffset = pagerState.currentPageOffsetFraction,
                    isCurrentPage = pagerState.currentPage == page
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Worm Indicator
            WormIndicator(
                pagerState = pagerState,
                slideCount = onboardingSlides.size,
                activeColor = accentColor,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            
            // Action Button
            if (pagerState.currentPage == onboardingSlides.size - 1) {
                // Final slide: "Get Started" button
                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onComplete()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeoBrutalTokens.ActionYellow
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "GET STARTED",
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 0.15.em,
                        color = Color.Black
                    )
                }
            } else {
                // Other slides: "Next" button with gradient
                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = 0.8f),
                                        accentColor
                                    )
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NEXT",
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            letterSpacing = 0.15.em,
                            color = Color.White
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

/**
 * Individual slide content with 3D depth effect
 */
@Composable
private fun OnboardingSlideContent(
    slide: OnboardingSlide,
    pageOffset: Float,
    isCurrentPage: Boolean
) {
    // Calculate scale based on scroll offset for 3D depth effect
    val scale by animateFloatAsState(
        targetValue = if (isCurrentPage) {
            1f - (pageOffset.absoluteValue * 0.2f)
        } else {
            0.8f
        },
        animationSpec = tween(300),
        label = "slide_scale"
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 3D Render Image
        Image(
            painter = painterResource(id = slide.imageRes),
            contentDescription = slide.title,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f),
            contentScale = ContentScale.Fit
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Title
        Text(
            text = slide.title,
            fontFamily = Outfit,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Description
        Text(
            text = slide.description,
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}
