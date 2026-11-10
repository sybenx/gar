package cc.aaro.gar.assist

import android.os.Bundle
import android.os.Process
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * GAR's [VoiceInteractionService]. While GAR is the digital assistant app the system keeps it
 * bound, which is what grants the assistant privileges, so it stays tiny and does no work of its
 * own: [GarVoiceInteractionSession] shows the app's assistant sheet or hands the request to
 * MainActivity. [GarAssist] is the API for the rest of the app.
 */
class GarVoiceInteractionService : VoiceInteractionService() {

    private var destroyed = false

    override fun onReady() {
        super.onReady()
        if (destroyed) {
            // the system may send a last onReady() after onDestroy() while it shuts the service
            // down (see VoiceInteractionService.onReady() docs), don't publish a dead service
            return
        }
        try {
            // GAR never looks at the screen, so don't let the system collect the foreground app's
            // assist data or take a screenshot each time the assistant is invoked
            setDisabledShowContext(
                VoiceInteractionSession.SHOW_WITH_ASSIST or
                    VoiceInteractionSession.SHOW_WITH_SCREENSHOT
            )
        } catch (e: SecurityException) {
            // thrown if this is not the active service anymore, e.g. the user just picked
            // another assistant; this service is about to be shut down
            Log.w(TAG, "Not the active voice interaction service", e)
            return
        }
        GarAssist.service = this
        Log.d(TAG, "Ready")
        prewarmSessionUi()
    }

    // the sheet's cheap warm-up (cached settings, no skills, STT, TTS or model), off the main
    // thread and at the lowest priority, so that the first show draws sooner
    private fun prewarmSessionUi() {
        val context = applicationContext
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            try {
                GarAssist.sessionUi?.prewarm(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not prewarm the assistant sheet", t)
            }
        }, "GarSheetPrewarm").start()
    }

    override fun onShutdown() {
        clearService()
        super.onShutdown()
    }

    override fun onDestroy() {
        destroyed = true
        clearService()
        super.onDestroy()
    }

    // API 34+. The failure args only carry the system's show session id, not the args passed to
    // showSession(), so onPrepareToShowSession() maps that id to the voice activity request.
    override fun onPrepareToShowSession(args: Bundle, flags: Int) {
        super.onPrepareToShowSession(args, flags)
        GarAssist.onPrepareToShowSession(args)
    }

    override fun onShowSessionFailed(args: Bundle) {
        super.onShowSessionFailed(args)
        GarAssist.onShowSessionFailed(args)
    }

    private fun clearService() {
        if (GarAssist.service === this) {
            GarAssist.service = null
        }
    }

    companion object {
        private val TAG = GarVoiceInteractionService::class.simpleName
    }
}
