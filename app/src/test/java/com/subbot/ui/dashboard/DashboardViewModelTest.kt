package com.subbot.ui.dashboard

import com.subbot.service.AudioCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AudioCaptureService.setServiceActive(false)
    }

    @Test
    fun testIsReadyToStart_requiresBothPermissionsAndModelDownloaded() {
        // Permissions granted but model not downloaded
        val stateWithoutModel = DashboardUiState(
            isOverlayPermissionGranted = true,
            isAudioPermissionGranted = true,
            isTargetModelDownloaded = false
        )
        assertFalse("Service must not be ready to start if model is not downloaded", stateWithoutModel.isReadyToStart)

        // Model downloaded but audio permission missing
        val stateWithoutAudio = DashboardUiState(
            isOverlayPermissionGranted = true,
            isAudioPermissionGranted = false,
            isTargetModelDownloaded = true
        )
        assertFalse("Service must not be ready to start if audio permission is missing", stateWithoutAudio.isReadyToStart)

        // Speech model missing
        val stateWithoutSpeechModel = DashboardUiState(
            isOverlayPermissionGranted = true,
            isAudioPermissionGranted = true,
            isTargetModelDownloaded = true,
            isSpeechModelDownloaded = false
        )
        assertFalse("Service must not be ready to start if speech model is missing", stateWithoutSpeechModel.isReadyToStart)

        // Source translation model missing (e.g. Japanese -> Vietnamese)
        val stateWithoutSourceModel = DashboardUiState(
            isOverlayPermissionGranted = true,
            isAudioPermissionGranted = true,
            isSourceModelDownloaded = false,
            isTargetModelDownloaded = true,
            isSpeechModelDownloaded = true
        )
        assertFalse("Service must not be ready to start if source translation model is missing", stateWithoutSourceModel.isReadyToStart)

        // All ready
        val stateReady = DashboardUiState(
            isOverlayPermissionGranted = true,
            isAudioPermissionGranted = true,
            isSourceModelDownloaded = true,
            isTargetModelDownloaded = true,
            isSpeechModelDownloaded = true
        )
        assertTrue("Service should be ready when permissions and all models are ready", stateReady.isReadyToStart)
    }

    @Test
    fun testServiceRunningState_syncsWithServiceActiveFlow() = runTest {
        val viewModel = DashboardViewModel()
        assertFalse(viewModel.uiState.value.isServiceRunning)

        AudioCaptureService.setServiceActive(true)
        viewModel.syncServiceState()
        assertTrue(viewModel.uiState.value.isServiceRunning)

        AudioCaptureService.setServiceActive(false)
        viewModel.syncServiceState()
        assertFalse(viewModel.uiState.value.isServiceRunning)
    }

    @Test
    fun testSwapLanguages_swapsSourceAndTarget() = runTest {
        val viewModel = DashboardViewModel()
        org.junit.Assert.assertEquals("en", viewModel.uiState.value.sourceLanguage)
        org.junit.Assert.assertEquals("vi", viewModel.uiState.value.targetLanguage)

        viewModel.swapLanguages()
        org.junit.Assert.assertEquals("vi", viewModel.uiState.value.sourceLanguage)
        org.junit.Assert.assertEquals("en", viewModel.uiState.value.targetLanguage)

        viewModel.swapLanguages()
        org.junit.Assert.assertEquals("en", viewModel.uiState.value.sourceLanguage)
        org.junit.Assert.assertEquals("vi", viewModel.uiState.value.targetLanguage)
    }

    @Test
    fun testSetFontSize_updatesState() = runTest {
        val viewModel = DashboardViewModel()
        org.junit.Assert.assertEquals(com.subbot.ui.model.SubtitleFontSize.NORMAL, viewModel.uiState.value.fontSize)

        viewModel.setFontSize(com.subbot.ui.model.SubtitleFontSize.LARGE)
        org.junit.Assert.assertEquals(com.subbot.ui.model.SubtitleFontSize.LARGE, viewModel.uiState.value.fontSize)

        viewModel.setFontSize(com.subbot.ui.model.SubtitleFontSize.EXTRA_LARGE)
        org.junit.Assert.assertEquals(com.subbot.ui.model.SubtitleFontSize.EXTRA_LARGE, viewModel.uiState.value.fontSize)
    }

    @Test
    fun testPosition_toggleAndSetUpdatesState() = runTest {
        val viewModel = DashboardViewModel()
        assertFalse(viewModel.uiState.value.isTopPosition)

        viewModel.togglePosition()
        assertTrue(viewModel.uiState.value.isTopPosition)

        viewModel.setPosition(false)
        assertFalse(viewModel.uiState.value.isTopPosition)

        viewModel.setPosition(true)
        assertTrue(viewModel.uiState.value.isTopPosition)
    }

    @Test
    fun testSubtitleFontSize_fromName() {
        org.junit.Assert.assertEquals(
            com.subbot.ui.model.SubtitleFontSize.LARGE,
            com.subbot.ui.model.SubtitleFontSize.fromName("LARGE")
        )
        org.junit.Assert.assertEquals(
            com.subbot.ui.model.SubtitleFontSize.EXTRA_LARGE,
            com.subbot.ui.model.SubtitleFontSize.fromName("extra_large")
        )
        org.junit.Assert.assertEquals(
            com.subbot.ui.model.SubtitleFontSize.NORMAL,
            com.subbot.ui.model.SubtitleFontSize.fromName("non_existent")
        )
        org.junit.Assert.assertEquals(
            com.subbot.ui.model.SubtitleFontSize.NORMAL,
            com.subbot.ui.model.SubtitleFontSize.fromName(null)
        )
    }

    @Test
    fun testSpeechModelTier_selection() = runTest {
        val viewModel = DashboardViewModel()
        org.junit.Assert.assertEquals(
            com.subbot.stt.WhisperModelTier.TINY,
            viewModel.uiState.value.selectedSpeechModelTier
        )

        viewModel.selectSpeechModelTier(com.subbot.stt.WhisperModelTier.BASE)
        org.junit.Assert.assertEquals(
            com.subbot.stt.WhisperModelTier.BASE,
            viewModel.uiState.value.selectedSpeechModelTier
        )

        viewModel.selectSpeechModelTier(com.subbot.stt.WhisperModelTier.SMALL)
        org.junit.Assert.assertEquals(
            com.subbot.stt.WhisperModelTier.SMALL,
            viewModel.uiState.value.selectedSpeechModelTier
        )
    }

    @Test
    fun testTranslationMode_toggleAndDefaults() = runTest {
        val viewModel = DashboardViewModel()
        org.junit.Assert.assertEquals(
            com.subbot.translation.TranslationMode.GOOGLE_NER,
            viewModel.uiState.value.translationMode
        )

        viewModel.setTranslationMode(com.subbot.translation.TranslationMode.WHISPER_DIRECT)
        org.junit.Assert.assertEquals(
            com.subbot.translation.TranslationMode.WHISPER_DIRECT,
            viewModel.uiState.value.translationMode
        )

        org.junit.Assert.assertEquals(
            com.subbot.translation.TranslationMode.GOOGLE_NER,
            com.subbot.translation.TranslationMode.fromId("google_ner")
        )
        org.junit.Assert.assertEquals(
            com.subbot.translation.TranslationMode.WHISPER_DIRECT,
            com.subbot.translation.TranslationMode.fromId("whisper_direct")
        )
        org.junit.Assert.assertEquals(
            com.subbot.translation.TranslationMode.GOOGLE_NER,
            com.subbot.translation.TranslationMode.fromId("unknown")
        )
    }

    @Test
    fun testSetSourceLanguage_ja_defaultsToGoogleNer() = runTest {
        val viewModel = DashboardViewModel()
        viewModel.setTranslationMode(com.subbot.translation.TranslationMode.WHISPER_DIRECT)
        viewModel.setSourceLanguage("ja")

        org.junit.Assert.assertEquals(
            com.subbot.translation.TranslationMode.GOOGLE_NER,
            viewModel.uiState.value.translationMode
        )
    }

    @Test
    fun testDirectTranslation_exemptsFromMLKitSourceModel() = runTest {
        // Japanese -> English with direct Whisper speech translation
        val stateJapaneseToEnglish = DashboardUiState(
            isOverlayPermissionGranted = true,
            isAudioPermissionGranted = true,
            sourceLanguage = "ja",
            targetLanguage = "en",
            translationMode = com.subbot.translation.TranslationMode.WHISPER_DIRECT,
            isSourceModelDownloaded = true,
            isTargetModelDownloaded = true,
            isSpeechModelDownloaded = true
        )
        assertTrue(stateJapaneseToEnglish.isReadyToStart)
    }

    @Test
    fun testSenseVoiceTier_forcesGoogleNerAndHidesWhisperDirect() = runTest {
        val viewModel = DashboardViewModel()
        viewModel.setSourceLanguage("ko")
        viewModel.setTranslationMode(com.subbot.translation.TranslationMode.WHISPER_DIRECT)

        viewModel.selectSpeechModelTier(com.subbot.stt.WhisperModelTier.SENSE_VOICE)
        val state = viewModel.uiState.value
        org.junit.Assert.assertEquals(com.subbot.stt.WhisperModelTier.SENSE_VOICE, state.selectedSpeechModelTier)
        org.junit.Assert.assertEquals(com.subbot.translation.TranslationMode.GOOGLE_NER, state.translationMode)
        assertFalse(state.isWhisperDirectAvailable)

        // Whisper Direct cannot be re-selected while SenseVoice is active
        viewModel.setTranslationMode(com.subbot.translation.TranslationMode.WHISPER_DIRECT)
        org.junit.Assert.assertEquals(com.subbot.translation.TranslationMode.GOOGLE_NER, viewModel.uiState.value.translationMode)
    }

    @Test
    fun testSenseVoiceTier_rejectedForUnsupportedSourceLanguage() = runTest {
        val viewModel = DashboardViewModel()
        viewModel.setSourceLanguage("vi")
        viewModel.selectSpeechModelTier(com.subbot.stt.WhisperModelTier.SENSE_VOICE)
        org.junit.Assert.assertEquals(com.subbot.stt.WhisperModelTier.TINY, viewModel.uiState.value.selectedSpeechModelTier)
        assertTrue(viewModel.uiState.value.statusMessage.contains("does not support"))
    }

    @Test
    fun testSenseVoiceTier_fallsBackWhenSourceLanguageBecomesUnsupported() = runTest {
        val viewModel = DashboardViewModel()
        viewModel.setSourceLanguage("en")
        viewModel.selectSpeechModelTier(com.subbot.stt.WhisperModelTier.SENSE_VOICE)

        // Japanese is supported: SenseVoice stays selected (no auto-switch to Whisper Small)
        viewModel.setSourceLanguage("ja")
        org.junit.Assert.assertEquals(com.subbot.stt.WhisperModelTier.SENSE_VOICE, viewModel.uiState.value.selectedSpeechModelTier)

        // Spanish is not: fall back to a Whisper tier (TINY when nothing is downloaded)
        viewModel.setSourceLanguage("es")
        org.junit.Assert.assertEquals(com.subbot.stt.WhisperModelTier.TINY, viewModel.uiState.value.selectedSpeechModelTier)
        assertTrue(viewModel.uiState.value.isWhisperDirectAvailable)

        // Swapping so that an unsupported language becomes the source also falls back
        viewModel.setSourceLanguage("ja")
        viewModel.selectSpeechModelTier(com.subbot.stt.WhisperModelTier.SENSE_VOICE)
        viewModel.setTargetLanguage("vi")
        viewModel.swapLanguages()
        org.junit.Assert.assertEquals("vi", viewModel.uiState.value.sourceLanguage)
        org.junit.Assert.assertEquals(com.subbot.stt.WhisperModelTier.TINY, viewModel.uiState.value.selectedSpeechModelTier)
    }
}
