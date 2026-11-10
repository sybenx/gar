package org.stypox.dicio.skills.sms

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

enum class SmsState {
    /** Waiting for the user to say or tap send. */
    PENDING,
    SENDING,
    SENT,
    FAILED,

    /** Sent, but the network didn't report back in time. */
    NO_REPORT,

    /** Handed to the messages app, where the user taps send. */
    OPENED_APP,
    NO_SMS_APP,
    CANCELLED,

    /** The user changed the message: a new draft with the new text replaces this one. */
    REPLACED,
}

/**
 * A text waiting for the user's decision on its card. Only [send] and [sendAndWait] send: the
 * card's Send button and a spoken yes after the read-back (see [SmsCardAnswerSkill]). Each draft
 * is sent at most once, also when a tap and a spoken yes come at the same time (the changes of
 * [state] are synchronized: taps run on the main thread, voice answers on the evaluator's).
 */
class SmsDraft(
    val recipient: SmsRecipient,
    val message: String,
    /** android.util.Log doesn't run in JVM unit tests */
    private val logError: (String, Throwable) -> Unit = { text, e -> Log.w(TAG, text, e) },
) {
    var state by mutableStateOf(SmsState.PENDING)
        private set

    val isPending: Boolean get() = state == SmsState.PENDING

    /** Completed with the final state once the network reported on the text. */
    private val reported = CompletableDeferred<SmsState>()

    /**
     * Sends with [sender] if GAR may send texts, otherwise (or if sending can't start) opens the
     * messages app with the text filled in.
     */
    @Synchronized
    fun send(sender: SmsSender) {
        if (state != SmsState.PENDING) {
            return
        }
        if (!sender.canSendDirectly()) {
            openApp(sender)
            return
        }

        state = SmsState.SENDING
        try {
            sender.send(recipient.number, message) { result ->
                state = when (result) {
                    SendResult.SENT -> SmsState.SENT
                    SendResult.FAILED -> SmsState.FAILED
                    SendResult.NO_REPORT -> SmsState.NO_REPORT
                }
                reported.complete(state)
            }
        } catch (e: Exception) {
            logError("Can't send the text, opening the messages app instead", e)
            openApp(sender)
        }
    }

    /**
     * [send], then waits up to [timeoutMs] for the network's report, so that a spoken answer can
     * say whether the text went out.
     *
     * @return the state then: still [SmsState.SENDING] if the network took longer
     */
    suspend fun sendAndWait(sender: SmsSender, timeoutMs: Long): SmsState {
        send(sender)
        if (state == SmsState.SENDING) {
            withTimeoutOrNull(timeoutMs) { reported.await() }
        }
        return state
    }

    /**
     * Whether [edit] does something now: before sending, to retry a failed text by hand, or to
     * reopen the messages app after leaving it without sending.
     */
    val canEdit: Boolean
        get() = state == SmsState.PENDING || state == SmsState.FAILED || state == SmsState.OPENED_APP

    /** Opens the messages app with the text filled in, never sends. */
    @Synchronized
    fun edit(sender: SmsSender) {
        if (canEdit) {
            openApp(sender)
        }
    }

    @Synchronized
    fun cancel() {
        if (state == SmsState.PENDING) {
            state = SmsState.CANCELLED
        }
    }

    /** The user is changing the message: this draft can't be sent anymore. */
    @Synchronized
    fun replace() {
        if (state == SmsState.PENDING) {
            state = SmsState.REPLACED
        }
    }

    private fun openApp(sender: SmsSender) {
        state = if (sender.openSmsApp(recipient.number, message)) {
            SmsState.OPENED_APP
        } else {
            SmsState.NO_SMS_APP
        }
    }

    companion object {
        private const val TAG = "SmsDraft"
    }
}
