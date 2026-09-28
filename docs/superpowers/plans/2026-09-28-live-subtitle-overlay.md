# SubBot Live Audio Translation & Subtitle Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build SubBot, a native Android application that captures internal device audio playback, performs on-device speech-to-text with Sherpa-ONNX Whisper, translates the speech into a user-selected target language with Google ML Kit offline translation, and renders a floating, touch-pass-through subtitle overlay.

**Architecture:** A foreground service coordinates `AudioPlaybackCapture` via `MediaProjection`, feeds raw 16kHz mono PCM chunks into an on-device Sherpa-ONNX Whisper recognizer, streams recognized speech to Google ML Kit offline translation, and updates a Jetpack Compose system overlay (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`) configured with `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, AndroidX Lifecycle, Kotlin Coroutines & Flow, Sherpa-ONNX (`com.k2fsa.sherpa.onnx:sherpa-onnx`), Google ML Kit Translate (`com.google.mlkit:translate`), JUnit 4, Kotlinx Coroutines Test.

**Spec:** [`docs/superpowers/specs/2026-09-28-live-subtitle-overlay-design.md`](file:///home/trluong/projects/subbot/docs/superpowers/specs/2026-09-28-live-subtitle-overlay-design.md)

## Global Constraints

- Android Min SDK: `29` (Android 10 - required for `AudioPlaybackCaptureConfiguration`).
- Android Target / Compile SDK: `35` (Android 15).
- Audio format: `16000` Hz sample rate, `ENCODING_PCM_16BIT`, `CHANNEL_IN_MONO`.
- Overlay flags: `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`.
- Speech recognition: Sherpa-ONNX Whisper INT8 quantized offline engine.
- Translation: Google ML Kit On-Device Translation (`com.google.mlkit:translate:17.0.3`).
- Service declaration: `foregroundServiceType="mediaProjection|microphone"`.

## Review Focus

1. **Zero-Audio / DRM-Protected Media Silence**: Apps blocking capture (`ALLOW_CAPTURE_BY_NONE`) produce buffers filled with zeros; RMS silence detection must drop them before triggering inference.
2. **Overlay Non-Interference**: Overlay window must never consume touch events, gestures, or steal soft keyboard focus from underlying apps.
3. **MediaProjection External Revocation**: Stopping casting/projection from Android system Quick Settings must trigger `MediaProjection.Callback` and cleanly terminate the service without leaking resources.
4. **Offline Model Availability Guard**: The service must not start until target language models for STT and ML Kit translation are verified and available on device.
5. **Subtitle Auto-Fade & Inactivity Timeout**: Subtitles must smoothly disappear after 3.5 seconds of silence so old subtitles do not linger over subsequent video scenes.

---

### Task 1: Project Scaffolding & Gradle Setup

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: None (root configuration)
- Produces: Runnable Android Gradle project with Compose, Sherpa-ONNX, ML Kit, and AndroidX dependencies.

- [ ] **Step 1: Create `settings.gradle.kts` and `gradle/libs.versions.toml`**
  Define repositories (`google()`, `mavenCentral()`), project name `"SubBot"`, and version catalog with Compose (BOM 2024.09.00), ML Kit Translate `17.0.3`, Sherpa-ONNX `1.10.37` (or current stable AAR), Coroutines `1.9.0`.

- [ ] **Step 2: Create root `build.gradle.kts` and `app/build.gradle.kts`**
  Configure Android Application plugin, Kotlin Android, Compose compiler. Set `minSdk = 29`, `targetSdk = 35`, `compileSdk = 35`. Add dependencies for Compose Material3, Lifecycle, Navigation, Coroutines, ML Kit Translate, Sherpa-ONNX, and JUnit/Test libraries.

- [ ] **Step 3: Create `app/src/main/AndroidManifest.xml`**
  Declare permissions:
  - `android.permission.RECORD_AUDIO`
  - `android.permission.SYSTEM_ALERT_WINDOW`
  - `android.permission.POST_NOTIFICATIONS`
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`
  - `android.permission.FOREGROUND_SERVICE_MICROPHONE`
  Declare `AudioCaptureService` with `foregroundServiceType="mediaProjection|microphone"`. Declare `MainActivity` as Launcher.

- [ ] **Step 4: Verify Gradle configuration**
  Run: `./gradlew help` or verify build scripts syntax.
  Expected: Gradle completes build configuration successfully.

- [ ] **Step 5: Commit**
  ```bash
  git add settings.gradle.kts build.gradle.kts gradle/ app/
  git commit -m "chore: scaffold Android project with Jetpack Compose, Sherpa-ONNX, and ML Kit"
  ```

---

### Task 2: Audio Utilities Core (PCM Conversion & RMS Silence Detector)

**Files:**
- Create: `app/src/main/java/com/subbot/audio/AudioUtils.kt`
- Test: `app/src/test/java/com/subbot/audio/AudioUtilsTest.kt`

**Interfaces:**
- Consumes: Raw 16-bit PCM bytes from `AudioRecord`
- Produces:
  - `AudioUtils.pcm16ToNormalizedFloats(pcmBytes: ByteArray, sampleCount: Int): FloatArray`
  - `AudioUtils.calculateRmsDb(samples: FloatArray): Float`
  - `AudioUtils.isSilence(rmsDb: Float, thresholdDb: Float = -45.0f): Boolean`

- [ ] **Step 1: Write the failing tests in `AudioUtilsTest.kt`**
  ```kotlin
  @Test
  fun testPcm16ToNormalizedFloats_convertsCorrectly() {
      // 0, 32767 (max positive), -32768 (min negative)
      val bytes = byteArrayOf(0, 0, 0xFF.toByte(), 0x7F.toByte(), 0x00, 0x80.toByte())
      val floats = AudioUtils.pcm16ToNormalizedFloats(bytes, 3)
      assertEquals(0.0f, floats[0], 0.001f)
      assertEquals(1.0f, floats[1], 0.001f)
      assertEquals(-1.0f, floats[2], 0.001f)
  }

  @Test
  fun testCalculateRmsDb_detectsSilence() {
      val silentSamples = FloatArray(1600) { 0.0f }
      val rms = AudioUtils.calculateRmsDb(silentSamples)
      assertTrue(AudioUtils.isSilence(rms, -45.0f))
  }
  ```

- [ ] **Step 2: Run tests to verify failure**
  Run: `./gradlew testDebugUnitTest --tests com.subbot.audio.AudioUtilsTest`
  Expected: FAIL with unresolved references `AudioUtils`.

- [ ] **Step 3: Implement `AudioUtils` in `AudioUtils.kt`**
  Implement little-endian 16-bit PCM to float conversion (`sample / 32768.0f`), RMS calculation (`20 * log10(sqrt(mean(samples^2)))`), and silence threshold evaluator (`rmsDb < thresholdDb || rmsDb.isNaN()`).

- [ ] **Step 4: Run tests to verify pass**
  Run: `./gradlew testDebugUnitTest --tests com.subbot.audio.AudioUtilsTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/com/subbot/audio/AudioUtils.kt app/src/test/java/com/subbot/audio/AudioUtilsTest.kt
  git commit -m "feat(audio): implement PCM16 conversion and RMS silence detection"
  ```

---

### Task 3: Subtitle UI State & Inactivity Queue Manager

**Files:**
- Create: `app/src/main/java/com/subbot/ui/model/SubtitleUiState.kt`
- Create: `app/src/main/java/com/subbot/ui/overlay/SubtitleQueue.kt`
- Test: `app/src/test/java/com/subbot/ui/overlay/SubtitleQueueTest.kt`

**Interfaces:**
- Consumes: Transcribed text (`String`), translated text (`String`)
- Produces:
  - `SubtitleUiState(val originalText: String, val translatedText: String, val isVisible: Boolean, val isBilingual: Boolean)`
  - `SubtitleQueue.postSubtitle(original: String, translated: String)`
  - `SubtitleQueue.uiState: StateFlow<SubtitleUiState>`
  - `SubtitleQueue.clear()`

- [ ] **Step 1: Write the failing tests in `SubtitleQueueTest.kt`**
  ```kotlin
  @Test
  fun testPostSubtitle_updatesStateAndAutoFadesAfterTimeout() = runTest {
      val queue = SubtitleQueue(scope = this, autoFadeTimeoutMs = 1000L)
      queue.postSubtitle(original = "Hello world", translated = "Xin chào")
      assertEquals("Xin chào", queue.uiState.value.translatedText)
      assertTrue(queue.uiState.value.isVisible)

      advanceTimeBy(1100L)
      assertFalse(queue.uiState.value.isVisible)
  }
  ```

- [ ] **Step 2: Run test to verify failure**
  Run: `./gradlew testDebugUnitTest --tests com.subbot.ui.overlay.SubtitleQueueTest`
  Expected: FAIL

- [ ] **Step 3: Implement `SubtitleUiState` and `SubtitleQueue`**
  Create data class `SubtitleUiState`. In `SubtitleQueue`, manage a mutable `StateFlow`. When `postSubtitle` is called, emit state with `isVisible = true` and launch a cancellable delay job that sets `isVisible = false` after `autoFadeTimeoutMs` (default `3500L`).

- [ ] **Step 4: Run tests to verify pass**
  Run: `./gradlew testDebugUnitTest --tests com.subbot.ui.overlay.SubtitleQueueTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/com/subbot/ui/model/ app/src/main/java/com/subbot/ui/overlay/ app/src/test/
  git commit -m "feat(ui): implement SubtitleUiState and SubtitleQueue with auto-fade"
  ```

---

### Task 4: Translation Engine (Google ML Kit Client & Cache)

**Files:**
- Create: `app/src/main/java/com/subbot/translation/TranslationCache.kt`
- Create: `app/src/main/java/com/subbot/translation/TranslationEngine.kt`
- Test: `app/src/test/java/com/subbot/translation/TranslationCacheTest.kt`

**Interfaces:**
- Consumes: Source text (`String`), Source & Target language codes (e.g., `"en"`, `"vi"`, `"es"`)
- Produces:
  - `TranslationEngine.isModelDownloaded(langCode: String): Flow<Boolean>`
  - `TranslationEngine.downloadModel(langCode: String): Flow<DownloadStatus>`
  - `TranslationEngine.translate(text: String, sourceLang: String, targetLang: String): String`

- [ ] **Step 1: Write failing tests in `TranslationCacheTest.kt`**
  ```kotlin
  @Test
  fun testTranslationCache_hitsAndEvicts() {
      val cache = TranslationCache(maxEntries = 2)
      cache.put("en", "es", "Hello", "Hola")
      assertEquals("Hola", cache.get("en", "es", "Hello"))
      assertNull(cache.get("en", "es", "Goodbye"))
  }
  ```

- [ ] **Step 2: Run test to verify failure**
  Run: `./gradlew testDebugUnitTest --tests com.subbot.translation.TranslationCacheTest`
  Expected: FAIL

- [ ] **Step 3: Implement `TranslationCache` and `TranslationEngine`**
  Create `TranslationCache` using `android.util.LruCache` or an in-memory `LinkedHashMap` keyed by `"$sourceLang->$targetLang:$text"`. Implement `TranslationEngine` wrapping `com.google.mlkit.nl.translate.Translation` and `TranslatorOptions`.

- [ ] **Step 4: Run test to verify pass**
  Run: `./gradlew testDebugUnitTest --tests com.subbot.translation.TranslationCacheTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/com/subbot/translation/ app/src/test/java/com/subbot/translation/
  git commit -m "feat(translation): implement TranslationCache and ML Kit TranslationEngine"
  ```

---

### Task 5: Speech Recognition Subsystem (Sherpa-ONNX Whisper Integration)

**Files:**
- Create: `app/src/main/java/com/subbot/stt/SpeechRecognitionConfig.kt`
- Create: `app/src/main/java/com/subbot/stt/SpeechRecognitionEngine.kt`
- Test: `app/src/test/java/com/subbot/stt/SpeechRecognitionConfigTest.kt`

**Interfaces:**
- Consumes: Audio float samples stream (`Channel<FloatArray>`)
- Produces:
  - `SpeechRecognitionEngine.initialize(modelDir: File)`
  - `SpeechRecognitionEngine.processStream(audioChannel: ReceiveChannel<FloatArray>, onUtterance: suspend (String) -> Unit)`
  - `SpeechRecognitionEngine.release()`

- [ ] **Step 1: Write the failing tests in `SpeechRecognitionConfigTest.kt`**
  ```kotlin
  @Test
  fun testSpeechRecognitionConfig_validatesModelFiles() {
      val config = SpeechRecognitionConfig(
          encoderPath = "whisper/encoder.int8.onnx",
          decoderPath = "whisper/decoder.int8.onnx",
          tokensPath = "whisper/tokens.txt"
      )
      assertTrue(config.isValid())
  }
  ```

- [ ] **Step 2: Run test to verify failure**
  Run: `./gradlew testDebugUnitTest --tests com.subbot.stt.SpeechRecognitionConfigTest`
  Expected: FAIL

- [ ] **Step 3: Implement `SpeechRecognitionConfig` and `SpeechRecognitionEngine`**
  Implement configuration dataclass and `SpeechRecognitionEngine` that initializes `com.k2fsa.sherpa.onnx.OfflineRecognizer` / `OnlineRecognizer` with INT8 Whisper models, reads 16kHz float chunks from the channel, checks silence via `AudioUtils`, executes decode, and invokes `onUtterance(text)` when utterances finish.

- [ ] **Step 4: Run test to verify pass**
  Run: `./gradlew testDebugUnitTest --tests com.subbot.stt.SpeechRecognitionConfigTest`
  Expected: PASS

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/com/subbot/stt/ app/src/test/java/com/subbot/stt/
  git commit -m "feat(stt): implement Sherpa-ONNX Whisper recognizer wrapper"
  ```

---

### Task 6: Audio Capture & MediaProjection Manager

**Files:**
- Create: `app/src/main/java/com/subbot/audio/AudioCaptureManager.kt`
- Create: `app/src/main/java/com/subbot/service/AudioCaptureService.kt`
- Create: `app/src/main/java/com/subbot/service/NotificationHelper.kt`

**Interfaces:**
- Consumes: `MediaProjection` token from `MainActivity`
- Produces:
  - `AudioCaptureManager.startCapture(mediaProjection: MediaProjection): ReceiveChannel<FloatArray>`
  - `AudioCaptureManager.stopCapture()`
  - `AudioCaptureService`: Foreground service lifecycle managing notification, projection callback, STT, and translation loop.

- [ ] **Step 1: Implement `NotificationHelper.kt`**
  Create persistent notification channel `"subbot_capture"`. Build notification with:
  - Title: "SubBot Active"
  - Text: "Translating live screen audio"
  - Action buttons: "Pause / Resume" and "Stop" (PendingIntents directed to `AudioCaptureService`).

- [ ] **Step 2: Implement `AudioCaptureManager.kt`**
  Configure `AudioPlaybackCaptureConfiguration` with `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`. Initialize `AudioRecord` at 16kHz mono 16-bit PCM. Launch reader coroutine on `Dispatchers.IO` emitting `FloatArray` chunks to a Kotlin `Channel<FloatArray>`.

- [ ] **Step 3: Implement `AudioCaptureService.kt`**
  Handle `ACTION_START`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_STOP`.
  On start:
  - Call `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | FOREGROUND_SERVICE_TYPE_MICROPHONE)`.
  - Register `MediaProjection.Callback` to auto-stop on external revocation.
  - Wire `AudioCaptureManager` -> `SpeechRecognitionEngine` -> `TranslationEngine` -> `SubtitleQueue`.

- [ ] **Step 4: Verify build compilation**
  Run: `./gradlew compileDebugKotlin`
  Expected: Compilation succeeds.

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/com/subbot/audio/AudioCaptureManager.kt app/src/main/java/com/subbot/service/
  git commit -m "feat(service): implement AudioCaptureService and MediaProjection pipeline"
  ```

---

### Task 7: Subtitle Overlay System Window (Jetpack Compose)

**Files:**
- Create: `app/src/main/java/com/subbot/ui/overlay/SubtitleOverlayView.kt`
- Create: `app/src/main/java/com/subbot/ui/overlay/SubtitleOverlayManager.kt`

**Interfaces:**
- Consumes: `SubtitleQueue.uiState: StateFlow<SubtitleUiState>`
- Produces:
  - `SubtitleOverlayManager.showOverlay()`
  - `SubtitleOverlayManager.hideOverlay()`
  - `SubtitleOverlayManager.updatePosition(isTop: Boolean)`

- [ ] **Step 1: Implement `SubtitleOverlayView.kt`**
  Compose UI component:
  - Collects `SubtitleUiState`.
  - Animated visibility (`AnimatedVisibility(visible = uiState.isVisible, enter = fadeIn(), exit = fadeOut())`).
  - Dark translucent pill background (`Color(0x88000000)`, shape = `RoundedCornerShape(12.dp)`).
  - High-contrast white typography with drop shadow for clarity over varied video brightness.
  - Bilingual toggle support: original transcript in smaller subdued font above translated text.

- [ ] **Step 2: Implement `SubtitleOverlayManager.kt`**
  Configure `WindowManager.LayoutParams`:
  - `type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
  - `flags = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
  - `format = PixelFormat.TRANSLUCENT`
  - `gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL` (customizable to `TOP`).
  Embed `ComposeView` with mock lifecycle owner. Add `showOverlay()` and `hideOverlay()` methods.

- [ ] **Step 3: Verify build compilation**
  Run: `./gradlew compileDebugKotlin`
  Expected: Compilation succeeds.

- [ ] **Step 4: Commit**
  ```bash
  git add app/src/main/java/com/subbot/ui/overlay/
  git commit -m "feat(overlay): implement non-blocking touch-pass-through Compose subtitle overlay"
  ```

---

### Task 8: Main Activity & Dashboard UI

**Files:**
- Create: `app/src/main/java/com/subbot/ui/dashboard/DashboardViewModel.kt`
- Create: `app/src/main/java/com/subbot/ui/dashboard/DashboardScreen.kt`
- Create: `app/src/main/java/com/subbot/MainActivity.kt`

**Interfaces:**
- Consumes: User inputs (permission clicks, language selections, start/stop toggle)
- Produces: Launching `MediaProjection` intent and binding to `AudioCaptureService`.

- [ ] **Step 1: Implement `DashboardViewModel.kt`**
  Track permission states (`canDrawOverlays`, `recordAudioGranted`, `notificationGranted`), selected source & target languages, model download progress, and service running state.

- [ ] **Step 2: Implement `DashboardScreen.kt`**
  Modern Material 3 Compose screen:
  - Permission status card with one-click direct request buttons.
  - Source and Target language dropdown selectors (e.g. English, Vietnamese, Spanish, Japanese, etc.).
  - Offline model download button with progress indicator.
  - Overlay preference toggles (position: Top/Bottom, Bilingual mode: On/Off, font size slider).
  - Prominent "Start Subtitles" / "Stop Subtitles" button.

- [ ] **Step 3: Implement `MainActivity.kt`**
  Wire `registerForActivityResult(StartActivityForResult())` for `MediaProjectionManager.createScreenCaptureIntent()`. When granted, launch `AudioCaptureService` with the intent data and selected language options.

- [ ] **Step 4: Verify build compilation**
  Run: `./gradlew assembleDebug`
  Expected: App APK builds successfully.

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/com/subbot/ui/dashboard/ app/src/main/java/com/subbot/MainActivity.kt
  git commit -m "feat(ui): implement DashboardScreen and MainActivity with permissions workflow"
  ```

---

### Task 9: End-to-End Pipeline Verification & Integration Test

**Files:**
- Create: `app/src/test/java/com/subbot/pipeline/PipelineIntegrationTest.kt`
- Create: `app/src/test/resources/sample_speech_16k.wav` (or synthetic PCM generator)

**Interfaces:**
- Consumes: Synthetic 16kHz audio buffer
- Produces: Verified end-to-end flow from audio input through VAD, STT text emission, translation, and `SubtitleQueue` state updates.

- [ ] **Step 1: Implement `PipelineIntegrationTest.kt`**
  Inject a 16kHz speech sample buffer into `AudioCaptureManager` stream mock.
  Verify:
  1. `AudioUtils.calculateRmsDb` registers speech energy above silence threshold.
  2. STT receives audio chunks without dropped frames.
  3. `TranslationEngine` translates emitted text.
  4. `SubtitleQueue.uiState` transitions to `isVisible = true` with translated text populated.
  5. Auto-fade timer transitions `isVisible = false` after silence.

- [ ] **Step 2: Run all unit and integration tests**
  Run: `./gradlew test`
  Expected: All tests pass.

- [ ] **Step 3: Build debug APK**
  Run: `./gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL with `app-debug.apk` generated.

- [ ] **Step 4: Commit**
  ```bash
  git add app/src/test/
  git commit -m "test: add end-to-end pipeline integration verification test"
  ```
