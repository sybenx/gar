package org.stypox.dicio.skills.screen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * GAR (gar:screen). An optional accessibility service that only takes screenshots when the user
 * asks GAR for one ("take a screenshot"), with [AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT]
 * (Android 9+). It is off until the user turns it on in Accessibility settings, from the card the
 * screenshot skill shows the first time. Its configuration (`res/xml/gar_screenshot_service.xml`)
 * asks for no events and no window content, so it never sees what is on the screen; Android saves
 * the screenshot itself, as with the buttons.
 */
class GarScreenshotService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // no event types are requested, so nothing arrives here
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Takes a screenshot after [delayMillis], once GAR's own window is gone. */
    fun takeScreenshot(delayMillis: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }, delayMillis)
    }

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        /** The running service, or null while it is off (or not bound yet). */
        @Volatile
        var instance: GarScreenshotService? = null
            private set

        /** Whether the user turned the service on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val ours = ComponentName(context, GarScreenshotService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
        }
    }
}
