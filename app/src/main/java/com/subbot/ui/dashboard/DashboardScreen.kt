package com.subbot.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subbot.ui.model.SubtitleFontSize
import com.subbot.ui.overlay.AudioListeningWave
import com.subbot.ui.overlay.SubtitleCard
import com.subbot.ui.theme.CyanContainer
import com.subbot.ui.theme.CyanPrimary
import com.subbot.ui.theme.CyanPrimaryLight
import com.subbot.ui.theme.DarkBorder
import com.subbot.ui.theme.DarkSurface
import com.subbot.ui.theme.DarkSurfaceElevated
import com.subbot.ui.theme.EmeraldTertiary
import com.subbot.ui.theme.GlassBorder
import com.subbot.ui.theme.IndigoSecondary
import com.subbot.ui.theme.RoseError
import com.subbot.ui.theme.TextPrimary
import com.subbot.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onRequestOverlayPermission: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(CyanPrimary, IndigoSecondary)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = "SubBot Logo",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "SubBot",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = TextPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = CyanContainer,
                                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "v${com.subbot.BuildConfig.VERSION_NAME}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanPrimaryLight,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "On-Device Real-Time Subtitles",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanPrimaryLight
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Visual Status Header (Glowing Pulse when running)
            StatusHeaderCard(
                isServiceRunning = state.isServiceRunning,
                isReady = state.isReadyToStart,
                sourceLang = state.sourceLanguage,
                targetLang = state.targetLanguage,
                onStop = onStopService
            )

            // Permissions Card
            PermissionsCard(
                state = state,
                onRequestOverlay = onRequestOverlayPermission,
                onRequestAudio = onRequestAudioPermission,
                onRequestNotification = onRequestNotificationPermission
            )

            // Quick Language Controls with Flip/Swap Button
            LanguageControlCard(
                sourceLang = state.sourceLanguage,
                targetLang = state.targetLanguage,
                selectedTier = state.selectedSpeechModelTier,
                isTinyDownloaded = state.isTinyModelDownloaded,
                isBaseDownloaded = state.isBaseModelDownloaded,
                isSmallDownloaded = state.isSmallModelDownloaded,
                isTurboDownloaded = state.isTurboModelDownloaded,
                translationMode = state.translationMode,
                isSpeechModelDownloaded = state.isSpeechModelDownloaded,
                isDownloadingSpeechModel = state.isDownloadingSpeechModel,
                speechModelProgress = state.speechModelProgress,
                areTranslationModelsDownloaded = state.areTranslationModelsDownloaded,
                isDownloadingModel = state.isDownloadingModel,
                statusMessage = state.statusMessage,
                onSourceSelect = { viewModel.setSourceLanguage(it) },
                onTargetSelect = { viewModel.setTargetLanguage(it) },
                onSwapLanguages = { viewModel.swapLanguages() },
                onSelectTier = { viewModel.selectSpeechModelTier(it) },
                onSelectTranslationMode = { viewModel.setTranslationMode(it) },
                onDownloadSpeechModel = { viewModel.downloadSpeechModel() },
                onDownloadTranslationModel = { viewModel.downloadTranslationModels() }
            )

            // Real-Time Subtitle Customizer & Live Preview Card
            SubtitleCustomizerCard(
                isTopPosition = state.isTopPosition,
                isBilingual = state.isBilingual,
                fontSize = state.fontSize,
                sourceLang = state.sourceLanguage,
                targetLang = state.targetLanguage,
                onPositionChange = { viewModel.setPosition(it) },
                onFontSizeChange = { viewModel.setFontSize(it) },
                onBilingualToggle = { viewModel.toggleBilingual() }
            )


            Spacer(modifier = Modifier.height(4.dp))

            // Main Action Button (Start / Stop)
            MainActionButton(
                isServiceRunning = state.isServiceRunning,
                isReadyToStart = state.isReadyToStart,
                onStart = onStartService,
                onStop = onStopService
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Animated visual status header showcasing active listening wave or standby readiness.
 */
@Composable
private fun StatusHeaderCard(
    isServiceRunning: Boolean,
    isReady: Boolean,
    sourceLang: String,
    targetLang: String,
    onStop: () -> Unit
) {
    val cardBorderColor by animateColorAsState(
        targetValue = if (isServiceRunning) CyanPrimary else GlassBorder,
        animationSpec = tween(300),
        label = "statusBorderColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        border = BorderStroke(1.2.dp, cardBorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isServiceRunning) {
                        GlowingPulseBeacon()
                    } else {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(
                                    if (isReady) EmeraldTertiary else Color(0xFFF59E0B),
                                    shape = CircleShape
                                )
                        )
                    }

                    Column {
                        Text(
                            text = if (isServiceRunning) "LIVE TRANSLATING" else if (isReady) "READY TO CAPTURE" else "SETUP REQUIRED",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isServiceRunning) CyanPrimaryLight else if (isReady) EmeraldTertiary else Color(0xFFF59E0B),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (isServiceRunning) {
                                "${sourceLang.uppercase()} → ${targetLang.uppercase()} • Floating overlay active"
                            } else if (isReady) {
                                "Tap Start below to launch floating subtitles"
                            } else {
                                "Grant permissions & download models to start"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                if (isServiceRunning) {
                    OutlinedButton(
                        onClick = onStop,
                        border = BorderStroke(1.dp, RoseError),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseError)
                    ) {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Animated concentric pulsing beacon for live translation active status.
 */
@Composable
private fun GlowingPulseBeacon() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring
        Box(
            modifier = Modifier
                .size(22.dp)
                .scale(pulseScale)
                .background(CyanPrimary.copy(alpha = pulseAlpha), shape = CircleShape)
        )
        // Inner glowing core
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(CyanPrimary, shape = CircleShape)
        )
    }
}

/**
 * Permissions status card with individual toggles and compact state.
 */
@Composable
private fun PermissionsCard(
    state: DashboardUiState,
    onRequestOverlay: () -> Unit,
    onRequestAudio: () -> Unit,
    onRequestNotification: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "System Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (state.areCorePermissionsGranted && state.isNotificationPermissionGranted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "All Granted",
                            tint = EmeraldTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "All Configured",
                            color = EmeraldTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PermissionRowItem(
                title = "Overlay Window",
                subtitle = "Render live subtitles over games and video apps",
                isGranted = state.isOverlayPermissionGranted,
                onGrant = onRequestOverlay
            )

            Spacer(modifier = Modifier.height(10.dp))

            PermissionRowItem(
                title = "Audio Capture",
                subtitle = "Capture internal media sound for speech recognition",
                isGranted = state.isAudioPermissionGranted,
                onGrant = onRequestAudio
            )

            Spacer(modifier = Modifier.height(10.dp))

            PermissionRowItem(
                title = "Notification Controls",
                subtitle = "Keep subtitle engine alive with quick controls",
                isGranted = state.isNotificationPermissionGranted,
                onGrant = onRequestNotification
            )
        }
    }
}

@Composable
private fun PermissionRowItem(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (isGranted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Granted",
                        tint = EmeraldTertiary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        if (!isGranted) {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("Grant", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Dual language selector with animated center swap / flip button and offline model status.
 */
@Composable
private fun LanguageControlCard(
    sourceLang: String,
    targetLang: String,
    selectedTier: com.subbot.stt.WhisperModelTier,
    isTinyDownloaded: Boolean,
    isBaseDownloaded: Boolean,
    isSmallDownloaded: Boolean,
    isTurboDownloaded: Boolean,
    translationMode: com.subbot.translation.TranslationMode,
    isSpeechModelDownloaded: Boolean,
    isDownloadingSpeechModel: Boolean,
    speechModelProgress: Int,
    areTranslationModelsDownloaded: Boolean,
    isDownloadingModel: Boolean,
    statusMessage: String,
    onSourceSelect: (String) -> Unit,
    onTargetSelect: (String) -> Unit,
    onSwapLanguages: () -> Unit,
    onSelectTier: (com.subbot.stt.WhisperModelTier) -> Unit,
    onSelectTranslationMode: (com.subbot.translation.TranslationMode) -> Unit,
    onDownloadSpeechModel: () -> Unit,
    onDownloadTranslationModel: () -> Unit
) {
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    val animatedRotation by animateFloatAsState(
        targetValue = rotationAngle,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "swapRotation"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Language Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Dual Language Row with Swap Button in between
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Source Language
                Box(modifier = Modifier.weight(1f)) {
                    LanguageDropdown(
                        label = "Spoken Audio",
                        selectedCode = sourceLang,
                        onSelect = onSourceSelect
                    )
                }

                // Swap Button
                IconButton(
                    onClick = {
                        rotationAngle += 180f
                        onSwapLanguages()
                    },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(CyanContainer)
                        .border(1.dp, CyanPrimary.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Swap Languages",
                        tint = CyanPrimaryLight,
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(animatedRotation)
                    )
                }

                // Target Language
                Box(modifier = Modifier.weight(1f)) {
                    LanguageDropdown(
                        label = "Translate To",
                        selectedCode = targetLang,
                        onSelect = onTargetSelect
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Speech Model Tier Selector Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Speech Model Tier",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (sourceLang.equals("ja", ignoreCase = true)) {
                    Text(
                        text = "★ Base or Small recommended",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldTertiary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Tier Selector Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.subbot.stt.WhisperModelTier.values().forEach { tier ->
                    val isSelected = tier == selectedTier
                    val isTierDownloaded = when (tier) {
                        com.subbot.stt.WhisperModelTier.TINY -> isTinyDownloaded
                        com.subbot.stt.WhisperModelTier.BASE -> isBaseDownloaded
                        com.subbot.stt.WhisperModelTier.SMALL -> isSmallDownloaded
                        com.subbot.stt.WhisperModelTier.TURBO -> isTurboDownloaded
                    }
                    Surface(
                        onClick = { onSelectTier(tier) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) CyanContainer else DarkSurfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) CyanPrimary else DarkBorder
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = tier.displayName.removePrefix("Whisper ").substringBefore(" ("),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) CyanPrimaryLight else TextPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isTierDownloaded) "Ready ✓" else "~${tier.approxSizeMb}MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isTierDownloaded) EmeraldTertiary else TextSecondary
                            )
                        }
                    }
                }
            }

            // Translation Mode Selector (Google NER vs Whisper Direct) when translating non-English
            val canChooseTranslationMode = !sourceLang.equals("en", ignoreCase = true) && !sourceLang.equals(targetLang, ignoreCase = true)
            if (canChooseTranslationMode) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Translation Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    if (translationMode == com.subbot.translation.TranslationMode.GOOGLE_NER) {
                        Text(
                            text = "★ Preserves names & grammar",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldTertiary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.subbot.translation.TranslationMode.values().forEach { mode ->
                        val isModeSelected = mode == translationMode
                        Surface(
                            onClick = { onSelectTranslationMode(mode) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isModeSelected) CyanContainer else DarkSurfaceElevated,
                            border = BorderStroke(
                                1.dp,
                                if (isModeSelected) CyanPrimary else DarkBorder
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isModeSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isModeSelected) CyanPrimaryLight else TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (mode == com.subbot.translation.TranslationMode.GOOGLE_NER) "Names & Honorifics" else "Direct Acoustic",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isModeSelected) CyanPrimaryLight else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Whisper STT Model Status
            val isCurrentTierDownloaded = when (selectedTier) {
                com.subbot.stt.WhisperModelTier.TINY -> isTinyDownloaded
                com.subbot.stt.WhisperModelTier.BASE -> isBaseDownloaded
                com.subbot.stt.WhisperModelTier.SMALL -> isSmallDownloaded
                com.subbot.stt.WhisperModelTier.TURBO -> isTurboDownloaded
            }
            ModelStatusRow(
                title = "Whisper Engine (${selectedTier.displayName})",
                isDownloaded = isCurrentTierDownloaded,
                isDownloading = isDownloadingSpeechModel,
                progress = speechModelProgress,
                downloadButtonLabel = "Download (~${selectedTier.approxSizeMb}MB)",
                onDownload = onDownloadSpeechModel
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Translation Model Status
            val isDirectWhisper = !sourceLang.equals("en", ignoreCase = true) && !sourceLang.equals(targetLang, ignoreCase = true) && translationMode == com.subbot.translation.TranslationMode.WHISPER_DIRECT
            val isDirectToEnglish = isDirectWhisper && targetLang.equals("en", ignoreCase = true)
            val isSameLanguage = sourceLang.equals(targetLang, ignoreCase = true)

            if (isDirectToEnglish) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceElevated)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Direct Speech-to-English Translation",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Acoustic translation via Whisper • 0ms ML Kit latency ✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldTertiary
                        )
                    }
                }
            } else if (isSameLanguage) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceElevated)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Transcription Mode (${sourceLang.uppercase()})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Direct transcription without translation ✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldTertiary
                        )
                    }
                }
            } else {
                val transEstimate = "~30MB"
                ModelStatusRow(
                    title = "Offline Translation (${if (isDirectWhisper) "EN" else sourceLang.uppercase()} → ${targetLang.uppercase()})",
                    isDownloaded = areTranslationModelsDownloaded,
                    isDownloading = isDownloadingModel,
                    progress = 0,
                    downloadButtonLabel = "Download ($transEstimate)",
                    onDownload = onDownloadTranslationModel
                )
            }

            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanPrimaryLight
                )
            }
        }
    }
}

@Composable
private fun ModelStatusRow(
    title: String,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: Int,
    downloadButtonLabel: String,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Text(
                text = if (isDownloaded) "Ready on device ✓" else if (isDownloading) "Downloading..." else "Required for offline translation",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDownloaded) EmeraldTertiary else if (isDownloading) CyanPrimaryLight else Color(0xFFF59E0B)
            )
        }

        if (!isDownloaded) {
            if (isDownloading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = CyanPrimary,
                        strokeWidth = 2.dp
                    )
                    if (progress > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("$progress%", style = MaterialTheme.typography.labelSmall, color = CyanPrimaryLight)
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onDownload,
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CyanPrimary)
                ) {
                    Text(downloadButtonLabel, fontSize = 11.sp, color = CyanPrimaryLight, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Real-Time Subtitle Customizer & Interactive Live Preview Card.
 */
@Composable
private fun SubtitleCustomizerCard(
    isTopPosition: Boolean,
    isBilingual: Boolean,
    fontSize: SubtitleFontSize,
    sourceLang: String,
    targetLang: String,
    onPositionChange: (Boolean) -> Unit,
    onFontSizeChange: (SubtitleFontSize) -> Unit,
    onBilingualToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Subtitle Overlay Customizer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Position visual toggle (Top / Bottom)
            Text(
                text = "Screen Position",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PositionOptionPill(
                    title = "Top of Screen",
                    icon = Icons.Default.VerticalAlignTop,
                    isSelected = isTopPosition,
                    onClick = { onPositionChange(true) },
                    modifier = Modifier.weight(1f)
                )
                PositionOptionPill(
                    title = "Bottom of Screen",
                    icon = Icons.Default.VerticalAlignBottom,
                    isSelected = !isTopPosition,
                    onClick = { onPositionChange(false) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Font size selector
            Text(
                text = "Font Sizing",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubtitleFontSize.entries.forEach { sizeOption ->
                    val isSelected = fontSize == sizeOption
                    val backgroundColor = if (isSelected) CyanContainer else DarkSurfaceElevated
                    val borderColor = if (isSelected) CyanPrimary else DarkBorder
                    val textColor = if (isSelected) CyanPrimaryLight else TextSecondary

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(backgroundColor)
                            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                            .clickable { onFontSizeChange(sizeOption) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = sizeOption.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = textColor
                            )
                            Text(
                                text = "${sizeOption.textSp.toInt()}sp",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) CyanPrimary else Color(0xFF64748B)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bilingual switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bilingual Display",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Show recognized speech above translation",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = isBilingual,
                    onCheckedChange = { onBilingualToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyanPrimary,
                        checkedTrackColor = CyanContainer,
                        uncheckedTrackColor = DarkBorder
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Interactive Preview
            Text(
                text = "Live Interactive Preview",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = CyanPrimaryLight
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Screen simulation mockup
            val sampleOriginal = when (sourceLang) {
                "ja" -> "リアルタイムで音声を認識しています"
                "es" -> "Reconociendo voz en tiempo real"
                "fr" -> "Reconnaissance vocale en temps réel"
                "de" -> "Spracherkennung in Echtzeit"
                "zh" -> "正在实时识别语音"
                "ko" -> "실시간으로 음성을 인식하고 있습니다"
                "vi" -> "Đang nhận dạng giọng nói theo thời gian thực"
                else -> "SubBot is actively listening to speech"
            }

            val sampleTranslated = when (targetLang) {
                "vi" -> "SubBot dịch phụ đề theo thời gian thực"
                "en" -> "SubBot translates audio in real time"
                "ja" -> "SubBotが音声をリアルタイムで翻訳中"
                "es" -> "SubBot traduce audio en tiempo real"
                "fr" -> "SubBot traduit l'audio en temps réel"
                "de" -> "SubBot übersetzt Audio in Echtzeit"
                "zh" -> "SubBot 正在实时翻译音频"
                "ko" -> "SubBot이 오디오를 실시간으로 번역 중"
                else -> "SubBot is translating into live subtitles"
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF0F172A),
                                Color(0xFF020617)
                            )
                        )
                    )
                    .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                // Background video placeholder hint
                Text(
                    text = "Target App Screen (e.g. YouTube, Meeting, Game)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF475569),
                    modifier = Modifier.align(Alignment.Center)
                )

                // Subtitle Card placed at Top or Bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(if (isTopPosition) Alignment.TopCenter else Alignment.BottomCenter),
                    contentAlignment = Alignment.Center
                ) {
                    SubtitleCard(
                        originalText = sampleOriginal,
                        translatedText = sampleTranslated,
                        isBilingual = isBilingual,
                        fontSize = fontSize,
                        isListening = true
                    )
                }
            }
        }
    }
}

@Composable
private fun PositionOptionPill(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) CyanContainer else DarkSurfaceElevated
    val borderColor = if (isSelected) CyanPrimary else DarkBorder
    val contentColor = if (isSelected) CyanPrimaryLight else TextSecondary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            fontSize = 12.sp
        )
    }
}

/**
 * Main floating action button to start/stop the service.
 */
@Composable
private fun MainActionButton(
    isServiceRunning: Boolean,
    isReadyToStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Button(
        onClick = {
            if (isServiceRunning) {
                onStop()
            } else {
                onStart()
            }
        },
        enabled = if (isServiceRunning) true else isReadyToStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isServiceRunning) RoseError else CyanPrimary,
            contentColor = if (isServiceRunning) Color.White else Color.Black,
            disabledContainerColor = DarkSurfaceElevated,
            disabledContentColor = Color(0xFF64748B)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = if (isServiceRunning) "Stop Live Subtitles" else "Start Live Subtitles",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    label: String,
    selectedCode: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = DashboardViewModel.SUPPORTED_LANGUAGES.find { it.code == selectedCode }
        ?: DashboardViewModel.SUPPORTED_LANGUAGES.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedOption.displayName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            maxLines = 1,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            label = { Text(label, fontSize = 10.sp, maxLines = 1) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkSurfaceElevated,
                unfocusedContainerColor = DarkSurfaceElevated,
                focusedBorderColor = CyanPrimary,
                unfocusedBorderColor = DarkBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedLabelColor = CyanPrimaryLight,
                unfocusedLabelColor = TextSecondary
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(DarkSurfaceElevated)
        ) {
            DashboardViewModel.SUPPORTED_LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = lang.displayName,
                            color = if (lang.code == selectedCode) CyanPrimaryLight else TextPrimary,
                            fontWeight = if (lang.code == selectedCode) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onSelect(lang.code)
                        expanded = false
                    }
                )
            }
        }
    }
}


