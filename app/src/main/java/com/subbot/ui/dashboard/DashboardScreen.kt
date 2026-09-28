package com.subbot.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                    Text(
                        text = "SubBot",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permissions Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Required Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    PermissionItem(
                        title = "Draw Over Other Apps",
                        isGranted = state.isOverlayPermissionGranted,
                        onGrant = onRequestOverlayPermission
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    PermissionItem(
                        title = "Audio Recording Permission",
                        isGranted = state.isAudioPermissionGranted,
                        onGrant = onRequestAudioPermission
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    PermissionItem(
                        title = "Notification Controls",
                        isGranted = state.isNotificationPermissionGranted,
                        onGrant = onRequestNotificationPermission
                    )
                }
            }

            // Language Configuration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Language Translation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Source language selector
                    LanguageDropdown(
                        label = "Spoken Audio Language",
                        selectedCode = state.sourceLanguage,
                        onSelect = { viewModel.setSourceLanguage(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Target language selector
                    LanguageDropdown(
                        label = "Subtitle Target Language",
                        selectedCode = state.targetLanguage,
                        onSelect = { viewModel.setTargetLanguage(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Speech Recognition Model Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val speechLabel = if (state.isSpeechModelDownloaded) {
                            "Speech Model (${state.sourceLanguage.uppercase()}): Ready ✓"
                        } else {
                            if (state.sourceLanguage != "en") "Multilingual Model: Needed" else "Speech Model: Not Downloaded"
                        }
                        Text(
                            text = speechLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.isSpeechModelDownloaded) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!state.isSpeechModelDownloaded) {
                            if (state.isDownloadingSpeechModel) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("${state.speechModelProgress}%", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.downloadSpeechModel() }) {
                                    Text("Download (~104MB)")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Offline Translation Model Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val translationLabel = if (state.areTranslationModelsDownloaded) {
                            "Translation (${state.sourceLanguage.uppercase()}→${state.targetLanguage.uppercase()}): Ready ✓"
                        } else {
                            "Translation Model: Not Downloaded"
                        }
                        Text(
                            text = translationLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.areTranslationModelsDownloaded) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!state.areTranslationModelsDownloaded) {
                            if (state.isDownloadingModel) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                OutlinedButton(onClick = { viewModel.downloadTranslationModels() }) {
                                    val sizeEst = if (state.sourceLanguage != "en" && !state.isSourceModelDownloaded) "~60MB" else "~30MB"
                                    Text("Download ($sizeEst)")
                                }
                            }
                        }
                    }

                    if (state.statusMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // Display Preferences Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Subtitle Overlay Style",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Position at Top of Screen", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = state.isTopPosition,
                            onCheckedChange = { viewModel.togglePosition() }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Bilingual (Source + Translated)", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = state.isBilingual,
                            onCheckedChange = { viewModel.toggleBilingual() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // Main Action Button
            Button(
                onClick = {
                    if (state.isServiceRunning) {
                        onStopService()
                    } else {
                        onStartService()
                    }
                },
                enabled = if (state.isServiceRunning) true else state.isReadyToStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (state.isServiceRunning) "Stop Subtitles" else "Start Live Subtitles",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (isGranted) "Granted ✓" else "Required",
                style = MaterialTheme.typography.bodySmall,
                color = if (isGranted) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }
        if (!isGranted) {
            Button(onClick = onGrant) {
                Text("Grant")
            }
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
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DashboardViewModel.SUPPORTED_LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.displayName) },
                    onClick = {
                        onSelect(lang.code)
                        expanded = false
                    }
                )
            }
        }
    }
}
