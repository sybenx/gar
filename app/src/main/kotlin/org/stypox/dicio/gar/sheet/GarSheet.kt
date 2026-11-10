package org.stypox.dicio.gar.sheet

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import cc.aaro.gar.assist.GarAssist
import org.stypox.dicio.MainActivity
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.gar.perf.GarStartupCache

/**
 * The assistant sheet as the rest of GAR sees it (gar/DESIGN.md, "Assistant entry points"): the
 * sheet is the assistant session's own window ([GarSheetUi]), shown over the current app by the
 * assist gesture and, with the screen on, the wake word.
 */
object GarSheet {
    /** True while the sheet is on screen, from its show to its teardown. Any thread. */
    @Volatile
    var isShown: Boolean = false
        internal set

    // the shown sheet's dismiss, set by GarSheetController while it is shown (main thread)
    @Volatile
    internal var dismissShown: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Dismisses the sheet if it is shown. Any thread; posts to the main thread. */
    fun dismissIfShown() {
        mainHandler.post {
            if (isShown) {
                dismissShown?.invoke()
            }
        }
    }

    /**
     * Called by [GarLeavingContext] before a skill starts another app: the sheet goes away (its
     * teardown stops speech and listening), so the app comes up unobscured. Any thread.
     */
    fun leaving() {
        dismissIfShown()
    }

    /**
     * Skills whose answer usually opens another app. If an app came up some other way than
     * through [GarLeavingContext] (a stored context, a PendingIntent), the sheet still goes away
     * once it loses focus after such an answer.
     */
    val LEAVES_GAR: Set<String> = setOf(
        "navigation", "open", "camera", "website", "song", "places", "telephone", "email", "sms",
        "calendar", "notes", "media_play", "messages",
    )

    /** Activity extra: open the home screen's text field. */
    const val EXTRA_TYPE = "cc.aaro.gar.extra.TYPE"

    /** Activity extra (string array): ask for these permissions, see [GarPermissionHandOff]. */
    const val EXTRA_PERMISSIONS = "cc.aaro.gar.extra.PERMISSIONS"

    /**
     * "Open the full app for the assistant": MainActivity instead of the sheet. Read from
     * [GarStartupCache], since this is asked on the main thread at show; false (the sheet) until
     * the cache has read the settings.
     */
    internal fun fullAppWanted(context: Context): Boolean {
        GarStartupCache.start(context)
        return GarStartupCache.garSettings?.assistFullScreen ?: false
    }

    /**
     * Called by WakeService before the wake word's capture starts. Over the lock screen the turn
     * mustn't build on what was said while unlocked (a "reply" to a message read then), so the
     * conversation is forgotten here: a cold sheet fetches the evaluator only after its first
     * frame, and the turn can be answered by then. A sheet already up over the lock screen keeps
     * its own turns. Any thread.
     */
    fun beforeHotwordCapture(context: Context, skillEvaluator: SkillEvaluator) {
        if (!isShown &&
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        ) {
            skillEvaluator.forgetConversation()
        }
    }

    /**
     * The wake word's way in: shows the sheet over the current app when GAR's assistant service
     * is ready, the full app isn't wanted, the screen is on and GAR's home isn't in front (it
     * shows the turn itself). With the screen off the caller keeps opening MainActivity, which
     * turns the screen on. The caller has started the capture already. Any thread.
     * @return true if the sheet is being shown
     */
    fun showForHotword(context: Context): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        return GarAssist.isServiceReady &&
            interactive &&
            MainActivity.isInForeground == 0 &&
            !fullAppWanted(context) &&
            GarAssist.showAssistSession(GarAssist.SOURCE_HOTWORD)
    }
}
