package com.subbot.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.subbot.ui.model.SubtitleUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the floating WindowManager system overlay for rendering subtitles
 * on top of other running applications with touch pass-through.
 */
class SubtitleOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isTopPosition = false
    private var isAttached = false

    private val lifecycleOwner = OverlayLifecycleOwner()

    fun showOverlay(uiStateFlow: StateFlow<SubtitleUiState>, isTop: Boolean = false) {
        if (isAttached) {
            updatePosition(isTop)
            return
        }
        isTopPosition = isTop

        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                com.subbot.ui.theme.SubBotTheme {
                    SubtitleOverlayView(uiStateFlow = uiStateFlow)
                }
            }
        }
        composeView = view

        val params = createLayoutParams(isTop)
        try {
            windowManager.addView(view, params)
            isAttached = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updatePosition(isTop: Boolean) {
        isTopPosition = isTop
        composeView?.let { view ->
            if (isAttached) {
                val params = createLayoutParams(isTop)
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun hideOverlay() {
        if (isAttached && composeView != null) {
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            composeView = null
            isAttached = false
        }
    }

    private fun createLayoutParams(isTop: Boolean): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (isTop) {
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            } else {
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            y = 70 // Offset in pixels from navigation / status bar
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        private var isRestored = false

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }

        fun performRestore(savedState: android.os.Bundle?) {
            if (!isRestored) {
                savedStateRegistryController.performRestore(savedState)
                isRestored = true
            }
        }
    }
}
