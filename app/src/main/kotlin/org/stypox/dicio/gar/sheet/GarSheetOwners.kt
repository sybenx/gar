package org.stypox.dicio.gar.sheet

import android.os.Build
import android.view.View
import android.view.Window
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.runtime.MutableFloatState
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

/**
 * What a ComposeView outside an Activity needs: a lifecycle (CREATED at createView, RESUMED in
 * onShow, DESTROYED in onHide), saved state (never saved: each show is a new session) and a
 * ViewModelStore (cleared at the end). Main thread.
 */
internal class GarSheetOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Makes these the owners of [view] and its children. */
    fun attach(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun resume() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun destroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

/**
 * Back on API 33+: a callback registered straight on the session window's dispatcher with
 * PRIORITY_DEFAULT, above the Dialog's own one (registered at system priority, which only hides
 * the session). On 34+ it is an [OnBackAnimationCallback] whose progress goes to [progress] for
 * the sheet's shrink and slide. The dispatcher only takes it while predictive back is on for the
 * app (Android 16 for apps targeting 36, or the manifest opt-in); otherwise, and on API 28-32,
 * back reaches the session's onBackPressed (GarSessionUi.onBackPressed). Main thread.
 */
internal class GarSheetBack(
    private val window: Window,
    private val progress: MutableFloatState,
    private val onBack: () -> Unit,
) {
    private var registered: Any? = null

    fun register() {
        if (registered != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34.register(window, progress, onBack)
        } else {
            Api33.register(window, onBack)
        }
    }

    fun unregister() {
        val callback = registered ?: return
        registered = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.unregister(window, callback)
        }
        progress.floatValue = 0f
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun register(window: Window, onBack: () -> Unit): Any {
            val callback = OnBackInvokedCallback { onBack() }
            window.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
            return callback
        }

        fun unregister(window: Window, callback: Any) {
            window.onBackInvokedDispatcher
                .unregisterOnBackInvokedCallback(callback as OnBackInvokedCallback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        fun register(window: Window, progress: MutableFloatState, onBack: () -> Unit): Any {
            val callback = object : OnBackAnimationCallback {
                override fun onBackStarted(backEvent: BackEvent) {
                    progress.floatValue = 0f
                }

                override fun onBackProgressed(backEvent: BackEvent) {
                    progress.floatValue = backEvent.progress
                }

                override fun onBackCancelled() {
                    progress.floatValue = 0f
                }

                override fun onBackInvoked() {
                    onBack()
                }
            }
            window.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
            return callback
        }
    }
}
