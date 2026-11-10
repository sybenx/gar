package org.stypox.dicio.skills.calls

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.delay
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.conversation.conversationWords
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences.CallControl

/** What GAR knows of the phone's calls. */
enum class PhoneCallState {
    /** No call. */
    IDLE,

    /** A call rings, and no other call is on. */
    RINGING,

    /** A call rings while another one is on (call waiting). */
    WAITING,

    /** A call is on, dialing or on hold, and none rings. */
    IN_CALL,

    /** GAR may not read the call state (`READ_PHONE_STATE` was taken away). */
    UNKNOWN;

    companion object {
        /**
         * From `TelephonyManager`'s call state, which says "ringing" for a waiting call too, and
         * the audio mode, which stays in call while the other call is on.
         */
        fun of(callState: Int, audioMode: Int): PhoneCallState = when (callState) {
            TelephonyManager.CALL_STATE_RINGING ->
                if (audioMode == AudioManager.MODE_IN_CALL ||
                    audioMode == AudioManager.MODE_IN_COMMUNICATION
                ) WAITING else RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> IN_CALL
            else -> IDLE
        }
    }
}

/** The phone's calls, as GAR may handle them without being the phone app. */
interface PhoneCalls {
    fun state(): PhoneCallState

    /** Answers the ringing call (a waiting call puts the other one on hold). */
    fun answer()

    /**
     * Ends the call that is on, or declines the ringing one when none is on. False when there is
     * none, or when Android won't end it (emergency calls, calls of apps like Signal).
     */
    fun end(): Boolean

    /** Brings up the phone app's call screen. */
    fun showCallScreen()
}

/**
 * Telecom's calls through the public APIs that work for an app that isn't the phone app, checked
 * on AOSP's TelecomServiceImpl (main, 2026): `acceptRingingCall` (API 26) and `endCall` (API 28)
 * need `ANSWER_PHONE_CALLS`, are deprecated since API 29 but not limited to the phone app, skip
 * self-managed calls (Signal, WhatsApp) and never end emergency calls. `isRinging` needs
 * `MODIFY_PHONE_STATE` outside the phone app, so the state comes from `TelephonyManager`
 * (`READ_PHONE_STATE`).
 */
class AndroidPhoneCalls(private val context: Context) : PhoneCalls {
    private val telecom get() = context.getSystemService(TelecomManager::class.java)

    @Suppress("DEPRECATION") // getCallStateForSubscription is one SIM's; this one is the phone's
    @SuppressLint("MissingPermission") // CallControlInfo.neededPermissions
    override fun state(): PhoneCallState {
        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?: return PhoneCallState.IDLE
        val callState = try {
            telephony.callState
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't read the call state", e)
            return PhoneCallState.UNKNOWN
        }
        val mode = context.getSystemService(AudioManager::class.java)?.mode
            ?: AudioManager.MODE_NORMAL
        return PhoneCallState.of(callState, mode)
    }

    @Suppress("DEPRECATION") // the replacement, InCallService, is only for the phone app
    @SuppressLint("MissingPermission") // CallControlInfo.neededPermissions
    override fun answer() {
        telecom?.acceptRingingCall()
    }

    @Suppress("DEPRECATION") // the replacement, InCallService, is only for the phone app
    @SuppressLint("MissingPermission") // CallControlInfo.neededPermissions
    override fun end(): Boolean = telecom?.endCall() == true

    @SuppressLint("MissingPermission") // CallControlInfo.neededPermissions
    override fun showCallScreen() {
        try {
            telecom?.showInCallScreen(false)
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't show the call screen", e)
        }
    }

    companion object {
        private const val TAG = "AndroidPhoneCalls"
    }
}

/**
 * "Answer", "decline", "hang up" and "speaker on" while a call rings or is on (gar/PARITY.md,
 * batch 4), English only, no tool, said to GAR's assistant (the wake word, the assist gesture, or
 * typed). Dicio's matcher takes a sentence with a word left out, so a match only counts when the
 * whole input is one of [CallControlWords]' phrasings ("end the meeting" and "pick up milk" are
 * not). No confirmation: the user hears the call ring or is on it, and every action is one the
 * phone's own lock screen allows too.
 *
 * @param pause waits while Telecom answers the call
 */
class CallControlSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<CallControl>,
    private val calls: (SkillContext) -> PhoneCalls = { AndroidPhoneCalls(it.android) },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : Skill<CallControl?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, CallControl?> {
        val (score, sentence) = data.score(ctx, input)
        return if (CallControlWords.command(input) == sentence) {
            Pair(score, sentence)
        } else {
            Pair(AlwaysWorstScore, null)
        }
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: CallControl?): SkillOutput {
        val calls = calls(ctx)
        val state = calls.state()
        if (state == PhoneCallState.UNKNOWN) {
            return MissingPermissionsSkillOutput(correspondingSkillInfo)
        }
        return try {
            when (inputData) {
                CallControl.Answer -> answer(calls, state)
                CallControl.Decline -> when (state) {
                    PhoneCallState.RINGING ->
                        if (calls.end()) CallControlOutput.Declined else CallControlOutput.NothingRinging
                    // endCall would end the call that is on, not the waiting one
                    PhoneCallState.WAITING -> CallControlOutput.CantDeclineWaiting(calls)
                    else -> CallControlOutput.NothingRinging
                }
                CallControl.HangUp -> when (state) {
                    PhoneCallState.IN_CALL, PhoneCallState.WAITING ->
                        if (calls.end()) CallControlOutput.Ended else CallControlOutput.CantEnd(calls)
                    PhoneCallState.RINGING ->
                        if (calls.end()) CallControlOutput.Declined else CallControlOutput.NotInCall
                    else -> CallControlOutput.NotInCall
                }
                CallControl.SpeakerOn, CallControl.SpeakerOff, null ->
                    if (state == PhoneCallState.IDLE) {
                        CallControlOutput.NotInCall
                    } else {
                        CallControlOutput.Speaker(calls)
                    }
            }
        } catch (e: SecurityException) {
            // ANSWER_PHONE_CALLS taken away while GAR ran
            MissingPermissionsSkillOutput(correspondingSkillInfo)
        }
    }

    private suspend fun answer(calls: PhoneCalls, state: PhoneCallState): SkillOutput {
        if (state != PhoneCallState.RINGING && state != PhoneCallState.WAITING) {
            return CallControlOutput.NothingRinging
        }
        calls.answer()
        // Telecom answers asynchronously; then the call screen shows mute, speaker and keypad
        if (!waitFor { calls.state() == PhoneCallState.IN_CALL }) {
            return CallControlOutput.StillRinging(calls)
        }
        calls.showCallScreen()
        return CallControlOutput.Answered
    }

    private suspend fun waitFor(done: () -> Boolean): Boolean {
        repeat((WAIT_MILLIS / WAIT_STEP_MILLIS).toInt()) {
            if (done()) {
                return true
            }
            pause(WAIT_STEP_MILLIS)
        }
        return done()
    }

    companion object {
        const val WAIT_MILLIS = 2000L
        const val WAIT_STEP_MILLIS = 100L
    }
}

/**
 * The call commands, read from the whole input: [conversationWords] (no apostrophes, fillers like
 * "ok" and "please" dropped), then one of the phrasings below. English.
 */
object CallControlWords {
    fun command(input: String): CallControl? {
        val text = conversationWords(input).joinToString(" ")
        return when {
            ANSWER.matches(text) -> CallControl.Answer
            DECLINE.matches(text) -> CallControl.Decline
            HANG_UP.matches(text) -> CallControl.HangUp
            SPEAKER_ON.matches(text) -> CallControl.SpeakerOn
            SPEAKER_OFF.matches(text) -> CallControl.SpeakerOff
            else -> null
        }
    }

    private const val CALL = "(the |this |that |my )?(phone )?call"
    private const val IT = "(it|that|this|the phone|my phone|$CALL|the incoming call)"
    private const val SPEAKER_WORD = "(speaker|speakerphone|speaker phone|loudspeaker)"
    private const val SPEAKER = "(the )?$SPEAKER_WORD"
    private const val WHO = "(it|this|that|me|him|her|them|$CALL)"

    private val ANSWER = Regex(
        "answer( $IT)?|pick up( $IT)?|pick (it|that|this|the phone|$CALL) up" +
            "|(accept|take) $CALL|accept it"
    )

    private val DECLINE = Regex(
        "(decline|reject)( $IT)?|ignore $CALL|(dont|do not) (answer|pick up)( $IT)?" +
            "|send ($WHO )?to voicemail"
    )

    private val HANG_UP = Regex(
        "hang up( $IT)?|hang (it|that|this|the phone|$CALL) up|hangup" +
            "|(end|stop|finish|disconnect|terminate) $CALL|end call|disconnect"
    )

    private val SPEAKER_ON = Regex(
        "(turn|switch) on $SPEAKER|(turn|switch) $SPEAKER on|$SPEAKER_WORD( on)?" +
            "|put ($WHO )?on $SPEAKER|(switch|go|change) to $SPEAKER|use $SPEAKER"
    )

    private val SPEAKER_OFF = Regex(
        "(turn|switch) off $SPEAKER|(turn|switch) $SPEAKER off|$SPEAKER_WORD off" +
            "|take ($WHO )?off $SPEAKER|(go|get) off $SPEAKER" +
            "|(switch|go) (back )?to (the )?(earpiece|handset)"
    )
}
