package cc.aaro.gar.assist

import android.app.KeyguardManager
import android.app.VoiceInteractor
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.view.Window

/**
 * A session with three jobs:
 * - shown by the assist gesture (or [GarAssist.showAssistSession]): show the app's assistant
 *   sheet ([GarSessionUi]) in the session's own window, until it is dismissed or hidden;
 * - the same, with the sheet off ("Open the full app for the assistant", or the sheet could not
 *   be loaded): open MainActivity with ACTION_ASSIST and finish, the pattern of Home Assistant's
 *   companion app;
 * - shown by [GarAssist.startVoiceActivity]: start that voice activity, answer its complete or
 *   abort request, then hide (which also finishes the voice task) and finish.
 *
 * Every show creates a fresh session, since each job ends with [finish].
 */
class GarVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var voiceActivityRequest: VoiceActivityRequest? = null
    private var finishPosted = false

    // GAR sheet: set in onPrepareShow when this show gets the sheet
    private val ui: GarSessionUi? = GarAssist.sessionUi
    private var host: Host? = null
    private var sheetView: View? = null
    private var sheetThisShow = false
    private var sheetShown = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        val voiceActivity = (args?.getLong(GarAssist.EXTRA_VOICE_ACTIVITY_REQUEST) ?: 0L) != 0L
        val source = args?.getInt(GarAssist.EXTRA_SOURCE, GarAssist.SOURCE_ASSIST)
            ?: GarAssist.SOURCE_ASSIST
        val sheet = !voiceActivity && ui != null && try {
            ui.wantsSheet(context, source)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not ask the sheet whether to show", e)
            false
        }
        if (sheet) {
            // a show while the sheet is up (assist again) keeps this session's host
            host = host?.also { it.source = source } ?: Host(source)
        } else {
            // a voice activity started while the sheet is up takes over this session
            hideSheet()
        }
        sheetThisShow = sheet
        // MainActivity or the voice activity is the UI, a session window would only cover it
        setUiEnabled(sheet)
    }

    override fun onCreateContentView(): View? {
        val host = host ?: return null
        sheetView = try {
            ui?.createView(host)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not create the sheet", e)
            null
        }
        return sheetView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val requestId = args?.getLong(GarAssist.EXTRA_VOICE_ACTIVITY_REQUEST) ?: 0L
        if (requestId == 0L) {
            val host = host
            if (sheetThisShow && ui != null && host != null && sheetView != null) {
                sheetShown = true
                ui.onShow(host)
                return
            }
            if (sheetThisShow) {
                setUiEnabled(false) // the sheet could not be created
            }
            startMainActivity()
            finishSoon()
            return
        }

        val request = GarAssist.takeVoiceActivityRequest(requestId, this)
        if (request == null) {
            // the request timed out before the session showed up
            finishSoon()
            return
        }
        voiceActivityRequest = request
        try {
            startVoiceActivity(request.intent)
        } catch (e: RuntimeException) {
            // e.g. no activity, or the activity does not support voice interaction
            Log.w(TAG, "Could not start voice activity ${request.intent}", e)
            endVoiceActivity(VoiceActivityResult.Failed("could not start: $e"))
        }
    }

    override fun onHide() {
        super.onHide()
        if (hideSheet()) {
            // the sheet is done: dismissed, or hidden by home, the shade, recents or screen off
            finishSoon()
        }
        // back, home, the lock screen, or the voice task finished without sending a result
        endVoiceActivity(VoiceActivityResult.Failed("closed without a result"))
    }

    // Reached from the back key, and from the back gesture when the sheet's own back callback
    // isn't registered (predictive back off for the app: API 28-32, or 33+ without the opt-in).
    // The default hides the session.
    override fun onBackPressed() {
        val host = host
        if (sheetShown && ui != null && host != null && ui.onBackPressed(host)) {
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        hideSheet()
        voiceActivityRequest?.let {
            GarAssist.completeVoiceActivity(it, VoiceActivityResult.Failed("session destroyed"))
        }
        voiceActivityRequest = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestCompleteVoice(request: CompleteVoiceRequest) {
        request.sendCompleteResult(null)
        endVoiceActivity(VoiceActivityResult.Completed(request.voicePrompt.text()))
    }

    override fun onRequestAbortVoice(request: AbortVoiceRequest) {
        request.sendAbortResult(null)
        endVoiceActivity(VoiceActivityResult.Aborted(request.voicePrompt.text()))
    }

    // the session has no UI to ask the user with, so let the activity handle a cancellation
    override fun onRequestConfirmation(request: ConfirmationRequest) = request.cancel()
    override fun onRequestPickOption(request: PickOptionRequest) = request.cancel()
    override fun onRequestCommand(request: CommandRequest) = request.cancel()

    /** Reports [result] for the running voice activity, if any, and closes the session. */
    internal fun endVoiceActivity(result: VoiceActivityResult) {
        val request = voiceActivityRequest ?: return
        voiceActivityRequest = null
        hide()
        // post finish() before the result callback: a voice activity started from that callback
        // must get a new session, not this one, which the pending finish() would destroy
        finishSoon()
        GarAssist.completeVoiceActivity(request, result)
    }

    /** Tears the sheet down if it is up; true if it was. */
    private fun hideSheet(): Boolean {
        val host = host
        if (!sheetShown || ui == null || host == null) {
            return false
        }
        sheetShown = false
        try {
            ui.onHide(host)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not tear the sheet down", e)
        }
        return true
    }

    private fun startMainActivity() {
        val intent = GarAssist.assistIntent(context)
        try {
            // see GarAssist.assistIntent() for why this is not startAssistantActivity()
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start MainActivity, trying as assistant activity", e)
            try {
                startAssistantActivity(intent)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not start MainActivity", e)
            }
        }
    }

    // finish() inside onShow() would destroy the session while the framework's doShow() is still
    // running, so post it (Home Assistant hit BadTokenException without this)
    private fun finishSoon() {
        if (!finishPosted) {
            finishPosted = true
            handler.post { finish() }
        }
    }

    /** What the sheet gets from this session, see [GarSessionHost]. */
    private inner class Host(override var source: Int) : GarSessionHost {
        override val context: Context
            get() = this@GarVoiceInteractionSession.context

        override val window: Window
            get() = this@GarVoiceInteractionSession.window.window!!

        override val locked: Boolean = isDeviceLocked()

        override fun dismiss() {
            handler.post {
                try {
                    hide()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Could not hide the session", e)
                }
                // onHide() tears the sheet down, unless the system already hid the session
                hideSheet()
                finishSoon()
            }
        }

        override fun openApp(intent: Intent) {
            val start = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val keyguardLocked =
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            try {
                // the bound service exempts GAR from background activity start limits
                context.startActivity(
                    if (keyguardLocked) {
                        GarAssist.unlockIntent(context, arrayOf(start))
                    } else {
                        start
                    }
                )
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not open $intent", e)
            }
            dismiss()
        }
    }

    private fun isDeviceLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    companion object {
        private val TAG = GarVoiceInteractionSession::class.simpleName

        /** The spoken form if there is one, otherwise the visual one. */
        private fun VoiceInteractor.Prompt?.text(): String? {
            return when {
                this == null -> null
                countVoicePrompts() > 0 -> getVoicePromptAt(0)?.toString()
                else -> visualPrompt?.toString()
            }
        }
    }
}
