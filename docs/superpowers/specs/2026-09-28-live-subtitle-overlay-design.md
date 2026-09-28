# Architecture Specification: SubBot - Real-Time On-Screen Audio Translation & Subtitle Overlay for Android

- **Date**: 2026-09-28
- **Project**: SubBot
- **Platform**: Android 10+ (API 29–35)
- **Language & Framework**: Kotlin, Jetpack Compose, Material 3, Coroutines & Flow
- **Primary Engines**: Sherpa-ONNX (Whisper STT), Google ML Kit (Offline Neural Machine Translation)

---

## 1. Executive Summary & Goals

### 1.1 Goal
SubBot is an Android utility application that captures audio played by other applications on the device (e.g., YouTube, TikTok, video players, web streams, games), transcribes the spoken dialogue in real-time, translates it offline to a user-selected target language, and renders a non-intrusive, click-through subtitle overlay at the bottom or top of the screen.

### 1.2 Key Requirements
1. **Internal Media Capture**: Capture device playback audio without using the external microphone, avoiding ambient room noise and degradation.
2. **Robust Speech Recognition**: Handle diverse accents, modern slang, colloquial speech, and background music using an on-device Whisper model executed via the optimized `sherpa-onnx` runtime.
3. **Multilingual Offline Translation**: Translate recognized text into 59+ target languages using Google ML Kit on-device translation without sending user data to the cloud.
4. **Pass-Through Overlay UX**: Render floating subtitles with `FLAG_NOT_TOUCHABLE` so users can interact with underlying video controls, scroll feeds, and type without interference.
5. **Battery & Resource Efficiency**: Implement voice activity detection (VAD) and RMS energy gating to avoid running inference on silent or music-only passages.

---

## 2. High-Level Architecture & Components

```
┌────────────────────────────────────────────────────────┐
│                   MainActivity (Compose)               │
│  - Permissions management & status checks              │
│  - Source / target language selection                  │
│  - Offline model download / storage manager            │
│  - Start / Stop service control                        │
└──────────────────────────┬─────────────────────────────┘
                           │ Starts with MediaProjection Intent Token
                           ▼
┌────────────────────────────────────────────────────────┐
│       AudioCaptureService (Foreground Service)         │
│     Foreground types: mediaProjection | microphone     │
│                                                        │
│  ┌───────────────────────┐   ┌───────────────────────┐ │
│  │  AudioCaptureManager   │──▶│ SpeechRecognition     │ │
│  │  (AudioPlaybackCapture│   │ Engine (Sherpa-ONNX)  │ │
│  │   PCM 16kHz, 16-bit)  │   │ (Whisper Offline)     │ │
│  └───────────────────────┘   └───────────┬───────────┘ │
│                                          │             │
│                                          ▼             │
│  ┌───────────────────────┐   ┌───────────────────────┐ │
│  │ SubtitleOverlayManager│◀──│ TranslationEngine     │ │
│  │ (WindowManager +      │   │ (Google ML Kit        │ │
│  │  Click-through Compose│   │  On-Device Translate) │ │
│  │  minimal bottom strip)│   │                       │ │
│  └───────────────────────┘   └───────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### Component Breakdown
1. **`MainActivity`**: Modern Jetpack Compose UI that guides users through granting permissions, managing offline language models, adjusting display preferences (font size, position, opacity), and launching the translation session.
2. **`AudioCaptureService`**: An Android Foreground Service that maintains the lifecycle of audio streaming, ML inference, and overlay display while the user is outside the app.
3. **`AudioCaptureManager`**: Uses the `MediaProjection` token and Android 10+ `AudioPlaybackCaptureConfiguration` to capture raw 16kHz 16-bit mono PCM audio from `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN`.
4. **`SpeechRecognitionEngine`**: Manages the Sherpa-ONNX offline Whisper recognizer (`whisper-tiny` or `whisper-base`). Evaluates audio chunks, computes RMS energy to filter silence, and outputs transcribed text segments.
5. **`TranslationEngine`**: Wraps Google ML Kit `OnDeviceTranslator`. Manages model downloads, checks language readiness, and translates transcribed text asynchronously.
6. **`SubtitleOverlayManager`**: Creates, updates, and destroys a system window (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`) embedding a reactive Jetpack `ComposeView` with `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`.

---

## 3. Detailed Subsystem Specifications

### 3.1 Audio Capture Subsystem (`AudioCaptureManager`)
* **Audio Format**:
  - Sample Rate: `16000` Hz (16 kHz is the native standard for Whisper).
  - Channel: `AudioFormat.CHANNEL_IN_MONO`.
  - Encoding: `AudioFormat.ENCODING_PCM_16BIT`.
* **Configuration**:
  ```kotlin
  val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
      .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
      .addMatchingUsage(AudioAttributes.USAGE_GAME)
      .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
      .build()

  val audioRecord = AudioRecord.Builder()
      .setAudioFormat(audioFormat)
      .setBufferSizeInBytes(bufferSize)
      .setAudioPlaybackCaptureConfig(captureConfig)
      .build()
  ```
* **Streaming Loop**:
  - A coroutine running on `Dispatchers.IO` continuously reads 1600 samples (100ms) into a buffer.
  - Normalizes 16-bit PCM bytes to `Float` values in range `[-1.0f, 1.0f]`.
  - Checks buffer Root Mean Square (RMS) energy. If below `SILENCE_THRESHOLD` (-45 dB) for more than 1.5 seconds, buffers are dropped to avoid idle inference.
  - Passes valid speech buffers into a Kotlin `Channel<FloatArray>(capacity = 64)`.

### 3.2 Speech Recognition Subsystem (`SpeechRecognitionEngine`)
* **Engine**: `com.k2fsa.sherpa.onnx:sherpa-onnx` Android AAR.
* **Model**: INT8-quantized Whisper (Tiny or Base).
  - `encoder.int8.onnx` (~40MB)
  - `decoder.int8.onnx` (~50MB)
  - `tokens.txt`
* **Segmentation & Processing**:
  - Uses audio accumulation buffer of 1.5s to 2.5s or silences detected by Voice Activity Detection (VAD).
  - Runs inference on worker thread via `recognizer.createStream()` or `recognizer.decode()`.
  - If target language is English and source is multilingual, Whisper direct translation mode can be utilized.
  - For all other language pairs, transcribes source text and passes to `TranslationEngine`.

### 3.3 Translation Subsystem (`TranslationEngine`)
* **Engine**: Google ML Kit On-Device Translation (`com.google.mlkit:translate`).
* **Capabilities**:
  - 59 supported languages.
  - 100% offline neural translation models (~30MB per language pair).
* **Model Download Management**:
  ```kotlin
  val conditions = DownloadConditions.Builder().build()
  translator.downloadModelIfNeeded(conditions)
      .addOnSuccessListener { /* Ready for translation */ }
      .addOnFailureListener { e -> /* Handle download failure */ }
  ```
* **Pipeline Execution**:
  - Caches frequent short phrases using an LRU cache (`LruCache<String, String>(100)`).
  - Translates recognized sentences asynchronously and emits state to `StateFlow<SubtitleUiState>`.

### 3.4 Subtitle Overlay Subsystem (`SubtitleOverlayManager`)
* **Window Configuration**:
  - `type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
  - `flags = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
  - `format = PixelFormat.TRANSLUCENT`
  - `gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL` (or `Gravity.TOP` via user preference)
* **UI Structure**:
  - Embedded Jetpack `ComposeView` with custom `ViewTreeLifecycleOwner` and `SavedStateRegistryOwner`.
  - Dark translucent pill background (`#80000000`, 12dp rounded corners, 16dp horizontal padding).
  - High-contrast text with subtle text shadow for readability over both dark and bright backgrounds.
  - Auto-fade timer: Subtitle text smoothly fades out after 3.5 seconds of inactivity.
  - Modes:
    1. *Translated only* (default).
    2. *Bilingual* (small original transcript on top, translated text on bottom).

### 3.5 User Controls & Lifecycle Management
* **Notification Controls**:
  - Persistent foreground notification with title "SubBot Active".
  - Actions: **Pause / Resume** and **Stop**.
* **MediaProjection Revocation**:
  - `MediaProjection.Callback` registered to detect when projection is stopped externally.
  - Gracefully stops `AudioRecord`, terminates foreground service, and tears down the overlay window.

---

## 4. Permissions & Security

| Permission | Protection Level | Purpose |
| :--- | :--- | :--- |
| `android.permission.RECORD_AUDIO` | Dangerous (Runtime) | Required to capture audio streams via `AudioRecord`. |
| `android.permission.SYSTEM_ALERT_WINDOW` | Special App Access | Required to draw floating overlay over other applications. |
| `android.permission.POST_NOTIFICATIONS` | Dangerous (Runtime, API 33+) | Required to display the foreground service status and controls. |
| `android.permission.FOREGROUND_SERVICE` | Normal | Base permission for foreground service. |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` | Normal (API 34+) | Declares foreground service type for screen/audio capture. |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Normal (API 34+) | Declares foreground service type for audio capture. |

---

## 5. Edge Cases & Resilience

1. **Apps Blocking Capture (`ALLOW_CAPTURE_BY_NONE`)**:
   - DRM-protected video apps (e.g. Netflix, Disney+) or phone calls disallow `AudioPlaybackCapture`.
   - *Behavior*: `AudioRecord` produces silent buffers (zeros). The RMS silence detector drops these buffers with zero CPU usage. The overlay remains hidden.
2. **Screen Orientation Changes**:
   - The overlay parameters apply max width bounds (85% of display width) and re-center automatically on configuration change.
3. **Missing Language Models**:
   - The UI disables the "Start Subtitles" button until the required Whisper and ML Kit models are downloaded and verified.
4. **App Termination / Task Swipe**:
   - `AudioCaptureService` runs with `START_NOT_STICKY` so it releases the projection token and cleans up native resources cleanly rather than respawning in a broken state without a valid token.

---

## 6. Testing & Quality Assurance

* **Unit Testing**:
  - `AudioUtilsTest`: Verifies PCM 16-bit to Float array conversion, RMS decibel calculation, and silence detection threshold.
  - `SubtitleQueueTest`: Verifies debounce timing, subtitle expiration timer, and bilingual formatting.
  - `TranslationCacheTest`: Verifies LRU cache hit/miss behavior and duplicate suppression.
* **Pipeline Integration Simulation**:
  - A mock audio feeder injects a bundled 16kHz test WAV file into `SpeechRecognitionEngine` and checks that the transcribed tokens match the expected text and flow into `TranslationEngine` and the overlay state flow.
