# Japanese STT Accuracy, Named Entity Recognition & Whisper Small Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate audio onset clipping of Japanese consonants, add Google Named Entity Recognition (NER) translation mode to accurately preserve Japanese names and honorifics, introduce the studio-grade Whisper Small model tier (~374MB), and bump version to `v0.2.2` (versionCode 4).

**Architecture:**
1. Implement a 300ms audio preroll circular buffer in `SpeechRecognitionEngine` so initial consonant onsets (k, t, s, w, h) are never dropped before VAD gating confirms speech.
2. Introduce a configurable `TranslationMode` (`GOOGLE_NER` vs `WHISPER_DIRECT`). In `GOOGLE_NER` mode, Whisper transcribes native Japanese (`task = "transcribe"`) and Google ML Kit translates to English/Vietnamese, preserving Japanese surnames (Tanaka, Yamada, Honda, Sakura, etc.) and grammar particles.
3. Extend `WhisperModelTier` with `SMALL` (244M parameters, ~374MB int8 quantized) for studio-grade transcription.
4. Update UI in `DashboardScreen` with a Translation Mode toggle and 3-tier speech model selector (Tiny, Base, Small).
5. Bump version to `0.2.2` (versionCode 4).

**Tech Stack:** Kotlin, Jetpack Compose, Sherpa-ONNX Whisper (int8 ONNX), Google ML Kit Digital Natural Language Translation, Android MediaProjection / AudioRecord.

**Spec:** Fix Japanese audio onset clipping, Japanese name recognition failure, add Whisper Small, and bump to v0.2.2.

## Global Constraints
- Preserve documentation integrity and existing comments.
- 100% test pass rate with `./gradlew testDebugUnitTest`.
- Version bump: `versionCode = 4`, `versionName = "0.2.2"` in `app/build.gradle.kts`.
- Produce debug APK at `app/build/outputs/apk/debug/subbot-v0.2.2-debug.apk`.

## Review Focus
1. Initial Speech Consonant Preservation: Initial phonemes (first 100-200ms) of utterances must be buffered in preroll and included in the decode buffer, never discarded.
2. Japanese Name Preservation: Transcribing in Japanese followed by Google ML Kit translation must preserve proper nouns (e.g. "田中" -> "Tanaka", not "in the middle of a field").
3. Bilingual Overlay Integrity: In `GOOGLE_NER` mode, the original text must show native Japanese kanji/kana, while the translated text displays English or Vietnamese.
4. Model Tier Resolution: If a user selects `SMALL` or `BASE`, the app must load the selected tier when downloaded, with graceful fallback to any downloaded tier.
5. Backward Compatibility: English-to-Vietnamese and existing configurations must continue to function seamlessly without regression.

---

### Task 1: Fix Speech Audio Preroll & Boundary Splitting in SpeechRecognitionEngine

**Files:**
- Modify: `app/src/main/java/com/subbot/stt/SpeechRecognitionEngine.kt:130-175`
- Test: `app/src/test/java/com/subbot/stt/SpeechRecognitionEngineTest.kt`

**Interfaces:**
- Produces: `SpeechRecognitionEngine` with preroll buffer preserving acoustic onsets and optimized VAD silence thresholds.

- [ ] **Step 1: Write unit tests for SpeechRecognitionEngine audio preroll logic and cleanUtterance**
- [ ] **Step 2: Run test to verify it fails or compiles**
- [ ] **Step 3: Implement 300ms preroll buffer and adjusted pause thresholds in `SpeechRecognitionEngine.kt`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 2: Add Whisper Small Model Tier to WhisperModelManager

**Files:**
- Modify: `app/src/main/java/com/subbot/stt/WhisperModelManager.kt:20-60, 160-180`
- Test: `app/src/test/java/com/subbot/stt/WhisperModelManagerTest.kt`

**Interfaces:**
- Produces: `WhisperModelTier.SMALL` with HuggingFace int8 paths (`small-tokens.txt`, `small-encoder.int8.onnx`, `small-decoder.int8.onnx`).

- [ ] **Step 1: Write unit tests for WhisperModelTier.SMALL availability and file resolution**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Add `SMALL` to `WhisperModelTier` in `WhisperModelManager.kt`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 3: Implement TranslationMode (Google NER vs Whisper Direct)

**Files:**
- Create: `app/src/main/java/com/subbot/translation/TranslationMode.kt`
- Modify: `app/src/main/java/com/subbot/service/AudioCaptureService.kt:39-60, 160-240`
- Modify: `app/src/main/java/com/subbot/MainActivity.kt:52-68`
- Modify: `app/src/main/java/com/subbot/ui/dashboard/DashboardViewModel.kt:20-55, 260-350`
- Test: `app/src/test/java/com/subbot/ui/dashboard/DashboardViewModelTest.kt`

**Interfaces:**
- Produces: `enum class TranslationMode { GOOGLE_NER, WHISPER_DIRECT }`
- Updates `DashboardUiState` with `translationMode` and model check logic.
- Updates `AudioCaptureService` to pass native source transcript through Google ML Kit when in `GOOGLE_NER` mode.

- [ ] **Step 1: Write unit tests for TranslationMode selection and model requirement checking**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `TranslationMode` enum and wire into `AudioCaptureService`, `MainActivity`, and `DashboardViewModel`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 4: Update Dashboard UI with Small Tier and Translation Mode Toggle

**Files:**
- Modify: `app/src/main/java/com/subbot/ui/dashboard/DashboardScreen.kt:610-750`

**Interfaces:**
- Renders:
  - 3-tier chips for Whisper (Tiny, Base, Small).
  - Translation Mode switch card (Google NER vs Whisper Direct) when Japanese or foreign language is selected.

- [ ] **Step 1: Update `DashboardScreen.kt` with Translation Mode toggle and 3 model tier chips**
- [ ] **Step 2: Verify Compose compilation and layout preview compatibility**
- [ ] **Step 3: Commit**

---

### Task 5: Bump Version to 0.2.2, Full Verification and APK Assembly

**Files:**
- Modify: `app/build.gradle.kts:22-26`

**Interfaces:**
- `versionCode = 4`
- `versionName = "0.2.2"`
- Build artifact: `app/build/outputs/apk/debug/subbot-v0.2.2-debug.apk`

- [ ] **Step 1: Update `app/build.gradle.kts` to versionCode 4, versionName "0.2.2"**
- [ ] **Step 2: Run `./gradlew testDebugUnitTest` and verify all tests pass**
- [ ] **Step 3: Run `./gradlew assembleDebug` and verify APK generation**
- [ ] **Step 4: Commit and tag `v0.2.2`**
