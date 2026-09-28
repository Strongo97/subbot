package com.subbot.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subbot.ui.model.SubtitleFontSize
import com.subbot.ui.model.SubtitleUiState
import com.subbot.ui.theme.CyanPrimary
import com.subbot.ui.theme.CyanPrimaryLight
import com.subbot.ui.theme.IndigoSecondary
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SubtitleOverlayView(
    uiStateFlow: StateFlow<SubtitleUiState>
) {
    val state by uiStateFlow.collectAsState()

    AnimatedVisibility(
        visible = state.isVisible && (state.translatedText.isNotBlank() || state.originalText.isNotBlank()),
        enter = fadeIn(animationSpec = tween(260, easing = LinearOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    initialOffsetY = { fullHeight -> if (state.isTopPosition) -fullHeight / 2 else fullHeight / 2 }
                ),
        exit = fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)) +
                slideOutVertically(
                    animationSpec = tween(220),
                    targetOffsetY = { fullHeight -> if (state.isTopPosition) -fullHeight / 2 else fullHeight / 2 }
                )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            SubtitleCard(
                originalText = state.originalText,
                translatedText = state.translatedText,
                isBilingual = state.isBilingual,
                fontSize = state.fontSize,
                isListening = state.isListening
            )
        }
    }
}

/**
 * Reusable glassmorphic subtitle card with responsive layout, glowing border,
 * listening visualizer, and crisp bilingual typographic hierarchy.
 */
@Composable
fun SubtitleCard(
    originalText: String,
    translatedText: String,
    isBilingual: Boolean,
    fontSize: SubtitleFontSize,
    isListening: Boolean = true,
    modifier: Modifier = Modifier
) {
    val glassShape = RoundedCornerShape(16.dp)

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xE6111827), // 90% obsidian
            Color(0xF2090D16)  // 95% deep space
        )
    )

    val borderBrush = Brush.linearGradient(
        colors = listOf(
            Color(0x6600E5FF), // Cyan glow
            Color(0x33FFFFFF), // Subtle translucent highlight
            Color(0x4D818CF8)  // Indigo soft sheen
        )
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = glassShape,
                ambientColor = Color(0x66000000),
                spotColor = Color(0x6600E5FF)
            )
            .clip(glassShape)
            .background(brush = backgroundBrush)
            .border(BorderStroke(1.2.dp, borderBrush), shape = glassShape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.wrapContentSize()
        ) {
            // Live Status & Listening Wave Header Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                AudioListeningWave(
                    isListening = isListening,
                    barColor = CyanPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF10B981), shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "LIVE",
                    color = CyanPrimaryLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            // Bilingual Original Utterance (if enabled and present)
            if (isBilingual && originalText.isNotBlank()) {
                Text(
                    text = originalText,
                    color = Color(0xCCE2E8F0),
                    fontSize = fontSize.originalSp.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    lineHeight = (fontSize.originalSp * 1.35f).sp,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color(0xDD000000),
                            offset = Offset(1f, 1f),
                            blurRadius = 3f
                        )
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // High-Contrast Primary Translated Text
            Text(
                text = translatedText.ifBlank { originalText },
                color = Color.White,
                fontSize = fontSize.textSp.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = (fontSize.textSp * 1.3f).sp,
                letterSpacing = 0.25.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0xEE000000),
                        offset = Offset(1.5f, 1.5f),
                        blurRadius = 5f
                    )
                ),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * Animated 4-bar equalizer wave visualizer indicating real-time audio listening.
 */
@Composable
fun AudioListeningWave(
    modifier: Modifier = Modifier,
    isListening: Boolean = true,
    barColor: Color = CyanPrimary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audioWaveTransition")

    val h1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 13f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveBar1"
    )

    val h2 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveBar2"
    )

    val h3 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveBar3"
    )

    val h4 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 390, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveBar4"
    )

    val barHeights = listOf(h1, h2, h3, h4)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        barHeights.forEach { heightVal ->
            val effectiveHeight = if (isListening) heightVal.dp else 4.dp
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(effectiveHeight)
                    .background(barColor, shape = RoundedCornerShape(1.5.dp))
            )
        }
    }
}
