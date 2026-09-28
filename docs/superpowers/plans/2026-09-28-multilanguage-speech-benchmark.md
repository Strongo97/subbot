# Multi-Language Speech Benchmark & Performance Rating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package readily-available 16kHz speech audio samples for English, Japanese, and Korean, implement a comprehensive multi-language benchmark & performance rating engine, integrate an in-app verification dashboard, create automated test verification, and bump version to `v0.2.3` (versionCode 5).

**Architecture:**
1. Package real 16kHz mono speech waveforms (`en.wav`, `ja.wav`, `ko.wav`) into `app/src/main/assets/benchmark/` and `app/src/test/resources/speech/` with ground-truth transcripts and reference translations.
2. Implement `com.subbot.benchmark.BenchmarkEngine` and `BenchmarkModels` to evaluate:
   - Acoustic Ingestion & VAD latency (< 5ms per 100ms chunk)
   - Preroll buffer onset preservation & boundary detection
   - Real-Time Factor (RTF)
   - Translation engine latency and cache throughput
   - Text fidelity & score calculation (0 - 100, Letter Grades S, A+, A, B, C)
3. Integrate an interactive Benchmark UI card in `DashboardScreen` so users can run tests directly inside the built application on their phone.
4. Add automated test `MultiLanguageSpeechBenchmarkTest` running under `./gradlew testDebugUnitTest`.
5. Bump version to `v0.2.3` (versionCode 5) and build `subbot-v0.2.3-debug.apk`.

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines, Sherpa-ONNX, Google ML Kit, Android Assets.

---

### Task 1: Package Multi-Language Speech Assets and Manifest

**Files:**
- Create: `app/src/main/assets/benchmark/en.wav`
- Create: `app/src/main/assets/benchmark/ja.wav`
- Create: `app/src/main/assets/benchmark/ko.wav`
- Create: `app/src/main/assets/benchmark/benchmark_manifest.json`
- Copy to: `app/src/test/resources/speech/`

**Interfaces:**
- Produces: 16kHz mono 16-bit PCM wav files and json manifest with ground truth and expected translations.

- [ ] **Step 1: Copy downloaded audio wavs to `app/src/main/assets/benchmark/`**
- [ ] **Step 2: Create `benchmark_manifest.json` with metadata and ground truths**
- [ ] **Step 3: Verify assets in build**

---

### Task 2: Implement Benchmark Models & BenchmarkEngine

**Files:**
- Create: `app/src/main/java/com/subbot/benchmark/BenchmarkModels.kt`
- Create: `app/src/main/java/com/subbot/benchmark/BenchmarkEngine.kt`
- Test: `app/src/test/java/com/subbot/benchmark/MultiLanguageSpeechBenchmarkTest.kt`

**Interfaces:**
- Produces: `BenchmarkEngine.runBenchmark()` returning `BenchmarkReport` with overall grade, latency, RTF, and per-language metrics.

- [ ] **Step 1: Write unit tests in `MultiLanguageSpeechBenchmarkTest.kt`**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `BenchmarkModels.kt` and `BenchmarkEngine.kt`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 3: Integrate Benchmark Runner into DashboardViewModel and UI

**Files:**
- Modify: `app/src/main/java/com/subbot/ui/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/subbot/ui/dashboard/DashboardScreen.kt`

**Interfaces:**
- Exposes: `runBenchmark()` in `DashboardViewModel` with `benchmarkState: StateFlow<BenchmarkUiState>`
- Renders: Benchmark Card with Live Progress, Scorecard, and Performance Rating Badge.

- [ ] **Step 1: Update `DashboardViewModel.kt` with benchmark execution state**
- [ ] **Step 2: Add `BenchmarkCard` component in `DashboardScreen.kt`**
- [ ] **Step 3: Test and compile Compose UI**
- [ ] **Step 4: Commit**

---

### Task 4: Version Bump to v0.2.3, Full Verification and APK Assembly

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- `versionCode = 5`
- `versionName = "0.2.3"`
- Build artifact: `app/build/outputs/apk/debug/subbot-v0.2.3-debug.apk`

- [ ] **Step 1: Bump version in `app/build.gradle.kts`**
- [ ] **Step 2: Run `./gradlew testDebugUnitTest --rerun-tasks`**
- [ ] **Step 3: Run `./gradlew assembleDebug`**
- [ ] **Step 4: Commit and tag `v0.2.3`**
