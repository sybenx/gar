package cc.aaro.gar.assist

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.Window

/**
 * The window a session shows: the app's assistant sheet (`org.stypox.dicio.gar.sheet.GarSheetUi`,
 * loaded by name in [GarAssist.sessionUi] so that this module stays free of Compose and Hilt).
 * All calls come on the main thread except [prewarm].
 */
interface GarSessionUi {
    /** Background thread from the service's onReady(): cheap warm-up only (theme, locale cache). */
    fun prewarm(context: Context) {}

    /** Whether to show the sheet for this show; false keeps today's MainActivity hand-off. */
    fun wantsSheet(context: Context, source: Int): Boolean

    fun createView(host: GarSessionHost): View

    fun onShow(host: GarSessionHost)

    /** Any hide: our dismiss, home, shade, recents, screen off. Tears down capture and speech. */
    fun onHide(host: GarSessionHost)

    /** The back key or gesture when no back callback took it; true if handled. */
    fun onBackPressed(host: GarSessionHost): Boolean = false
}

/** What a session gives its [GarSessionUi]. One host per session, so per show. */
interface GarSessionHost {
    /** The session's context. */
    val context: Context

    /** The session's Dialog window. */
    val window: Window

    /** [GarAssist.SOURCE_ASSIST], [GarAssist.SOURCE_HOTWORD] or [GarAssist.SOURCE_TILE]. */
    val source: Int

    /** KeyguardManager.isDeviceLocked at show. */
    val locked: Boolean

    /** Hides the session and then finishes it, posted. Any thread. */
    fun dismiss()

    /**
     * Starts [intent] (NEW_TASK is added) and then [dismiss]es. While the phone is locked the
     * intent goes through the app's unlock trampoline ([GarAssist.unlockIntent]), so the user
     * unlocks first and then lands in it.
     */
    fun openApp(intent: Intent)
}
