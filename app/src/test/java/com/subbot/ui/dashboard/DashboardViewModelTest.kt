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

        // All ready
        val stateReady = DashboardUiState(
            isOverlayPermissionGranted = true,
            isAudioPermissionGranted = true,
            isTargetModelDownloaded = true
        )
        assertTrue("Service should be ready when permissions and model are ready", stateReady.isReadyToStart)
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
}
