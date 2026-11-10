package org.stypox.dicio.skills.song

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import cc.aaro.gar.assist.GarAssist
import org.stypox.dicio.MainActivity

/** A way to start Audile, kept as data so that tests can check what GAR starts. */
sealed interface AudileStart {
    val packageName: String

    /**
     * Audile's "recognize in the background" dynamic shortcut: recognition without Audile's UI,
     * the result in Audile's notification. Only a shortcut host can start it, which GAR is while
     * it is the active digital assistant. Audile publishes the shortcut once it has run.
     */
    data class BackgroundShortcut(override val packageName: String) : AudileStart

    /** Audile's "Open & Recognize": its main screen, which starts listening at once. */
    data class Recognize(override val packageName: String) : AudileStart

    /** Audile's launcher activity, where the user taps to recognize. */
    data class Launch(override val packageName: String) : AudileStart
}

/** An explicit activity intent as plain data (Android's Intent isn't available in JVM tests). */
data class ComponentIntent(val action: String, val packageName: String, val className: String)

/**
 * Audile (com.mrsep.musicrecognizer, GPL-3.0-or-later), the open source song recognizer: GAR's
 * tier 3 when no service is set up. Its entry points were read from Audile's source
 * (`~/src/gar-notes/recon/audile.md`): none is a documented API, so each start falls back to the
 * next one.
 */
object AudileIntents {
    const val PACKAGE = "com.mrsep.musicrecognizer"
    const val DEBUG_PACKAGE = "com.mrsep.musicrecognizer.debug"
    const val MAIN_ACTIVITY = "com.mrsep.musicrecognizer.presentation.MainActivity"
    const val ACTION_RECOGNIZE = "com.mrsep.musicrecognizer.intent.action.RECOGNIZE"
    const val BACKGROUND_SHORTCUT_ID = "RecognizeInBackgroundShortcutId"

    /** The release build first, then the debug build (same activity class name). */
    val PACKAGES = listOf(PACKAGE, DEBUG_PACKAGE)

    fun installed(isInstalled: (String) -> Boolean): String? = PACKAGES.firstOrNull(isInstalled)

    /** The ways to start [packageName], in the order GAR tries them. */
    fun attempts(packageName: String, shortcutHost: Boolean): List<AudileStart> =
        listOfNotNull(
            AudileStart.BackgroundShortcut(packageName).takeIf { shortcutHost },
            AudileStart.Recognize(packageName),
            AudileStart.Launch(packageName),
        )

    /** MainActivity is exported, and its RECOGNIZE action is accepted without a filter. */
    fun recognizeIntent(packageName: String) =
        ComponentIntent(ACTION_RECOGNIZE, packageName, MAIN_ACTIVITY)
}

/** How handing off to Audile went, which picks the answer. */
enum class AudileResult {
    LISTENING_IN_BACKGROUND,
    OPENED,

    /** Opened behind the lock screen: Audile's screen doesn't show over it. */
    OPENED_LOCKED,

    /** Only Audile's main screen opened: the user has to tap. */
    OPENED_APP,

    /** GAR isn't visible and isn't the assistant, so Android blocks activity starts. */
    BLOCKED_IN_BACKGROUND,
    FAILED,
}

/** What starting Audile needs from the device, faked in tests. */
interface AudileLauncher {
    /** Whether GAR's own UI is visible or GAR is the active assistant (background starts). */
    fun canStartActivities(): Boolean
    fun isLocked(): Boolean
    fun isShortcutHost(): Boolean

    /** @return false if [start] didn't work */
    fun start(start: AudileStart): Boolean
}

object AudileHandOff {
    fun run(packageName: String, launcher: AudileLauncher): AudileResult {
        for (attempt in AudileIntents.attempts(packageName, launcher.isShortcutHost())) {
            if (attempt !is AudileStart.BackgroundShortcut && !launcher.canStartActivities()) {
                // a start would be blocked silently
                return AudileResult.BLOCKED_IN_BACKGROUND
            }
            if (launcher.start(attempt)) {
                return when (attempt) {
                    is AudileStart.BackgroundShortcut -> AudileResult.LISTENING_IN_BACKGROUND
                    is AudileStart.Recognize, is AudileStart.Launch -> when {
                        launcher.isLocked() -> AudileResult.OPENED_LOCKED
                        attempt is AudileStart.Recognize -> AudileResult.OPENED
                        else -> AudileResult.OPENED_APP
                    }
                }
            }
        }
        return AudileResult.FAILED
    }
}

class AndroidAudileLauncher(private val context: Context) : AudileLauncher {
    override fun canStartActivities(): Boolean =
        MainActivity.isInForeground > 0 || try {
            GarAssist.isActiveAssistant(context)
        } catch (e: RuntimeException) {
            false
        }

    override fun isLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun isShortcutHost(): Boolean = try {
        context.getSystemService(LauncherApps::class.java)?.hasShortcutHostPermission() == true
    } catch (e: RuntimeException) { // IllegalStateException while the user is locked
        false
    }

    override fun start(start: AudileStart): Boolean = try {
        when (start) {
            is AudileStart.BackgroundShortcut -> {
                val launcherApps = context.getSystemService(LauncherApps::class.java)
                launcherApps?.startShortcut(
                    start.packageName,
                    AudileIntents.BACKGROUND_SHORTCUT_ID,
                    null,
                    null,
                    Process.myUserHandle(),
                )
                launcherApps != null
            }
            is AudileStart.Recognize -> {
                val component = AudileIntents.recognizeIntent(start.packageName)
                context.startActivity(
                    Intent(component.action)
                        .setClassName(component.packageName, component.className)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }
            is AudileStart.Launch -> {
                val intent = context.packageManager.getLaunchIntentForPackage(start.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
                intent != null
            }
        }
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Can't start $start", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to start $start", e)
        false
    } catch (e: IllegalStateException) { // the shortcut is disabled, or the user is locked
        Log.w(TAG, "Can't start $start", e)
        false
    }

    companion object {
        private const val TAG = "AudileLauncher"

        fun installedAudile(context: Context): String? = AudileIntents.installed { name ->
            try {
                context.packageManager.getApplicationInfo(name, 0).enabled
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
