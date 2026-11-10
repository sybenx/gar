package cc.aaro.gar.assist

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * What the rest of GAR can do with its [VoiceInteractionService]. GAR only holds the assistant
 * privileges (background activity starts, voice activities, shortcut host, while-in-use mic and
 * audio control) while the user has picked it as the digital assistant app, see
 * [isActiveAssistant] and `gar/assist/README.md`.
 */
object GarAssist {
    /** Dicio's activity that assist requests go to without the sheet. Same APK, so just a name. */
    const val MAIN_ACTIVITY_CLASS = "org.stypox.dicio.MainActivity"

    /** How long a voice activity may run before its session is closed with a failure. */
    const val VOICE_ACTIVITY_TIMEOUT_MILLIS = 15_000L

    internal const val EXTRA_VOICE_ACTIVITY_REQUEST = "cc.aaro.gar.assist.VOICE_ACTIVITY_REQUEST"

    /** What showed a session ([showAssistSession]); no extra means [SOURCE_ASSIST]. */
    const val EXTRA_SOURCE = "cc.aaro.gar.assist.SOURCE"
    /** The assist gesture, the power button or the corner swipe. */
    const val SOURCE_ASSIST = 1
    /** The wake word, whose capture already runs when the session shows. */
    const val SOURCE_HOTWORD = 2
    /** The Quick Settings tile. */
    const val SOURCE_TILE = 3

    /** The app's [GarSessionUi], the assistant sheet. Same APK, so just a name. */
    const val SHEET_UI_CLASS = "org.stypox.dicio.gar.sheet.GarSheetUi"

    /** The app's trampoline that asks to unlock and then starts [EXTRA_UNLOCK_TARGETS]. */
    const val UNLOCK_ACTIVITY_CLASS = "org.stypox.dicio.gar.sheet.GarUnlockActivity"

    /** The intents [UNLOCK_ACTIVITY_CLASS] starts once the phone is unlocked (Intent array). */
    const val EXTRA_UNLOCK_TARGETS = "cc.aaro.gar.extra.UNLOCK_TARGETS"

    private val TAG = GarAssist::class.simpleName
    private val mainHandler = Handler(Looper.getMainLooper())

    // set by the service between onReady() and onShutdown(); it runs in the app's process
    @SuppressLint("StaticFieldLeak")
    @Volatile
    internal var service: GarVoiceInteractionService? = null

    // both guarded by `this`
    private var pendingRequest: VoiceActivityRequest? = null
    private var nextRequestId = 1L // 0 means "no request" in the session's show args

    /**
     * Whether GAR's [VoiceInteractionService] is the active one, i.e. the user picked GAR as the
     * digital assistant app and the system accepted the service's metadata. Holding the assistant
     * role alone is not enough: the privileges come with the active service.
     */
    fun isActiveAssistant(context: Context): Boolean {
        return VoiceInteractionService.isActiveService(
            context, ComponentName(context, GarVoiceInteractionService::class.java)
        )
    }

    /**
     * Whether the system has bound GAR's service in this process and it can show sessions, which
     * [showAssistSession] and [startVoiceActivity] need. It follows [isActiveAssistant], with a
     * short delay after boot or after the user picks GAR.
     */
    val isServiceReady: Boolean
        get() = service != null

    /**
     * The app's assistant sheet, loaded once by [SHEET_UI_CLASS]; null (sessions then open
     * MainActivity as before) if it can't be loaded.
     */
    internal val sessionUi: GarSessionUi? by lazy {
        try {
            Class.forName(SHEET_UI_CLASS).getDeclaredConstructor().newInstance() as GarSessionUi
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Could not load $SHEET_UI_CLASS, sessions open MainActivity", e)
            null
        } catch (e: ClassCastException) {
            Log.e(TAG, "$SHEET_UI_CLASS is not a GarSessionUi, sessions open MainActivity", e)
            null
        } catch (e: LinkageError) {
            Log.e(TAG, "Could not load $SHEET_UI_CLASS, sessions open MainActivity", e)
            null
        }
    }

    /**
     * Shows a session as the assist gesture would: the assistant sheet, or MainActivity with
     * [Intent.ACTION_ASSIST] when the sheet is off. For paths that have no activity yet, e.g. the
     * wake word ([SOURCE_HOTWORD]) or the Quick Settings tile ([SOURCE_TILE]). Any thread.
     * @return false if GAR's service is not running (see [isServiceReady])
     */
    fun showAssistSession(source: Int = SOURCE_ASSIST): Boolean {
        return showSession(Bundle().apply { putInt(EXTRA_SOURCE, source) })
    }

    /**
     * The intent of the app's unlock trampoline ([UNLOCK_ACTIVITY_CLASS]): it shows over the lock
     * screen, asks the user to unlock, then starts [targets] in order; if the user cancels it
     * just closes. Start it with a plain startActivity from the app's process.
     */
    fun unlockIntent(context: Context, targets: Array<Intent>): Intent {
        return Intent()
            .setClassName(context.packageName, UNLOCK_ACTIVITY_CLASS)
            .putExtra(EXTRA_UNLOCK_TARGETS, targets)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Starts [intent] as a voice activity (the system adds category VOICE) from a session without
     * UI, then answers the activity's complete or abort request and closes the session. Some
     * system activities only act when started this way, e.g. turning off Do Not Disturb, which
     * apps targeting API 35+ can't do through NotificationManager:
     * ```
     * Intent(Settings.ACTION_VOICE_CONTROL_DO_NOT_DISTURB_MODE)
     *     .putExtra(Settings.EXTRA_DO_NOT_DISTURB_MODE_ENABLED, false)
     * ```
     * Only one voice activity runs at a time. The session also closes, with
     * [VoiceActivityResult.Failed], when the activity ends without a result, on back/home/lock
     * screen, or after [VOICE_ACTIVITY_TIMEOUT_MILLIS]. Confirmation, pick-option and command
     * requests are cancelled, since the session has no UI to ask the user.
     *
     * @param onResult called exactly once on the main thread, only if this returns true; it may
     * start the next voice activity
     * @return false if GAR's service is not running (see [isServiceReady]) or another voice
     * activity is still pending
     */
    fun startVoiceActivity(
        intent: Intent,
        onResult: (VoiceActivityResult) -> Unit = {},
    ): Boolean {
        val request = synchronized(this) {
            if (service == null || pendingRequest != null) {
                return false
            }
            VoiceActivityRequest(nextRequestId++, Intent(intent), onResult)
                .also { pendingRequest = it }
        }

        mainHandler.postDelayed(
            { onVoiceActivityTimeout(request) }, request, VOICE_ACTIVITY_TIMEOUT_MILLIS
        )
        val args = Bundle().apply { putLong(EXTRA_VOICE_ACTIVITY_REQUEST, request.id) }
        if (!showSession(args)) {
            synchronized(this) {
                if (pendingRequest === request) {
                    pendingRequest = null
                }
            }
            mainHandler.removeCallbacksAndMessages(request)
            return false
        }
        return true
    }

    /** Builds the intent the session opens MainActivity with. */
    internal fun assistIntent(context: Context): Intent {
        // Started with a plain startActivity() rather than VoiceInteractionSession
        // .startAssistantActivity(): the latter gives the activity the "assistant" activity type,
        // so it would get a second task and a second MainActivity next to the one opened from the
        // launcher, and MainActivity.onDestroy() releases the STT device they share. A plain start
        // is also what SystemUI does when the assistant is an ACTION_ASSIST activity. The service's
        // binding exempts GAR from background activity start limits. CLEAR_TOP|SINGLE_TOP make an
        // existing MainActivity receive onNewIntent() instead of stacking a new one.
        return Intent(Intent.ACTION_ASSIST)
            .setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
    }

    /**
     * Called by a session in onShow() with the request id from its show args. Returns the request
     * and binds it to [session], or null if the request is not pending anymore (e.g. timed out).
     */
    internal fun takeVoiceActivityRequest(
        id: Long,
        session: GarVoiceInteractionSession,
    ): VoiceActivityRequest? {
        synchronized(this) {
            val request = pendingRequest
            if (request == null || request.id != id || request.session != null) {
                return null
            }
            request.session = session
            return request
        }
    }

    /** Delivers [result] for [request], unless a result was already delivered. Any thread. */
    internal fun completeVoiceActivity(request: VoiceActivityRequest, result: VoiceActivityResult) {
        synchronized(this) {
            if (pendingRequest !== request) {
                return
            }
            pendingRequest = null
        }
        mainHandler.removeCallbacksAndMessages(request)
        Log.d(TAG, "Voice activity ${request.intent.action} ended: $result")
        mainHandler.post { request.onResult(result) }
    }

    /**
     * Called by the service on API 34+ before a session is shown, with the args passed to
     * showSession() plus the system's show session id, which is all [onShowSessionFailed] gets.
     */
    internal fun onPrepareToShowSession(args: Bundle) {
        val id = args.getLong(EXTRA_VOICE_ACTIVITY_REQUEST)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || id == 0L ||
            !args.containsKey(VoiceInteractionSession.KEY_SHOW_SESSION_ID)
        ) {
            return
        }
        synchronized(this) {
            pendingRequest?.takeIf { it.id == id }?.showSessionId =
                args.getInt(VoiceInteractionSession.KEY_SHOW_SESSION_ID)
        }
    }

    /** Called by the service on API 34+ when a session could not be shown. */
    internal fun onShowSessionFailed(args: Bundle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !args.containsKey(VoiceInteractionSession.KEY_SHOW_SESSION_ID)
        ) {
            return
        }
        val showSessionId = args.getInt(VoiceInteractionSession.KEY_SHOW_SESSION_ID)
        val request = synchronized(this) {
            pendingRequest?.takeIf { it.showSessionId == showSessionId && it.session == null }
        } ?: return
        completeVoiceActivity(request, VoiceActivityResult.Failed("session could not be shown"))
    }

    private fun onVoiceActivityTimeout(request: VoiceActivityRequest) {
        val result = VoiceActivityResult.Failed("timed out")
        request.session?.endVoiceActivity(result) // closes the session too
        completeVoiceActivity(request, result) // no-op if the session already reported it
    }

    private fun showSession(args: Bundle): Boolean {
        val vis = service ?: return false
        return try {
            vis.showSession(args, 0)
            true
        } catch (e: IllegalStateException) {
            // thrown if the service is not ready (yet or anymore)
            Log.w(TAG, "Could not show a session", e)
            false
        } catch (e: SecurityException) {
            // thrown by the system if this is not the active service anymore (the user picked
            // another assistant and onShutdown() has not arrived yet)
            Log.w(TAG, "Could not show a session", e)
            false
        }
    }
}

/** Outcome of [GarAssist.startVoiceActivity]. */
sealed interface VoiceActivityResult {
    /** The activity did what it was asked; [prompt] is what it wants said or shown, if anything. */
    data class Completed(val prompt: String?) : VoiceActivityResult

    /** The activity could not do what it was asked; [prompt] says why, if anything. */
    data class Aborted(val prompt: String?) : VoiceActivityResult

    /** The activity could not be started, or it ended or timed out without a result. */
    data class Failed(val reason: String) : VoiceActivityResult
}

internal class VoiceActivityRequest(
    val id: Long,
    val intent: Intent,
    val onResult: (VoiceActivityResult) -> Unit,
) {
    // the session running this request, set (under GarAssist's lock) when it takes the request
    var session: GarVoiceInteractionSession? = null

    // the system's id for the show of this request's session (API 34+), under GarAssist's lock
    var showSessionId: Int? = null
}
