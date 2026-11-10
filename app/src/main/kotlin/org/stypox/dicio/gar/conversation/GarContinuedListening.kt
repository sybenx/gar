package org.stypox.dicio.gar.conversation

import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.dicio.skill.context.SpeechOutputDevice
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.MainActivity
import org.stypox.dicio.gar.media.GarMediaSessions
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.sheet.GarSheet
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.skills.calls.AndroidPhoneCalls
import org.stypox.dicio.skills.calls.PhoneCallState
import org.stypox.dicio.skills.fallback.text.TextFallbackOutput
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * What [GarContinuedListening.decide] needs to know about an answered turn and the phone, once
 * the answer was said.
 */
data class GarWindowFacts(
    /** "Keep listening after answers". */
    val settingOn: Boolean,
    /** The turn's input was spoken ([GarInputMode.SPOKEN]). */
    val spokenInput: Boolean,
    /** The answer's plan reopens the microphone itself (a question). */
    val planReopens: Boolean,
    /** The answer's speech was said: not blank, not shown only. */
    val spoke: Boolean,
    /** The answer ends the conversation ([GarContinuedListening.endsWindow]). */
    val conversationEnds: Boolean,
    /** The id of the skill that answered. */
    val skillId: String,
    /** GAR is on screen. */
    val onScreen: Boolean,
    val lockedAtStart: Boolean,
    val lockedNow: Boolean,
    /** A timer or an alarm rings ([GarRinging]). */
    val ringing: Boolean,
    /** A phone call rings or is on, or the audio is in a call. */
    val call: Boolean,
    /** Another app plays out loud, not into headphones. */
    val speakerMusic: Boolean,
    /** Whisper is the input device: only it has brief windows. */
    val whisper: Boolean,
    /** The speech input already listens or transcribes (the user tapped the mic). */
    val sttListening: Boolean,
)

/**
 * Continued listening (gar/DESIGN.md, "Continued conversation"): after a spoken answer has been
 * said, the microphone stays open [WINDOW_MILLIS] for a follow-up ("what about tomorrow"), with no
 * earcon. Silence ends it with no sound and no turn.
 *
 * A window is scheduled after the answer's speech ([afterAnswer]) with the sequence number of that
 * moment. Any new speech ([onSpeak]) or input ([onInput]) bumps the number and closes an open
 * window, so a window never records GAR's own voice and a stale one does nothing.
 */
object GarContinuedListening {
    private const val TAG = "GarContinued"

    /** From the window's opening to the start of speech; then the usual end-pointing. */
    const val WINDOW_MILLIS = 7_000L

    /** Less voiced audio than this in a window is silence. */
    const val MIN_SPEECH_MILLIS = 300

    /** A window's capture ends after this much speech: follow-ups are short. */
    const val MAX_SPEECH_MILLIS = 6_000L

    /**
     * The skills after whose answers a follow-up is likely. Not the ones that act outward, write,
     * ask their own questions or leave GAR: telephone, sms, messages, email, call_log, notes,
     * lists, reminders, share_location, navigation, open, media, song, camera, search, small_talk.
     */
    val OPENS_AFTER = setOf(
        "weather", "current_time", "world_time", "date", "holiday", "units", "currency",
        "translation", "offline_translator", "random", "joke", "knowledge", "dictionary",
        "calculator", "math", "battery", "spelling", "flashlight", "connectivity",
        "do_not_disturb", "device_settings", "volume", "alarm", "timer", "timer_control",
        "stopwatch", "calendar", "agenda", "contact_info", "follow_up",
    )

    /** "Keep listening after answers", set by the evaluator from [settingOn]. */
    @Volatile
    var enabled = true

    /**
     * Whether the input being matched now came from a window, for skills' `score`
     * (`ConversationSkill` takes a bare "thanks" then). Set by the evaluator while it matches,
     * under its match lock.
     */
    @Volatile
    var matchingContinued = false

    /** Whether "Keep listening after answers" is on: stored the other way round, on by default. */
    fun settingOn(settings: GarSettings): Boolean = !settings.continuedListeningOff

    /** Why no window opens after a turn with [facts], or null when one does. */
    fun reason(facts: GarWindowFacts): String? = when {
        !facts.settingOn -> "setting"
        !facts.spokenInput -> "typed"
        facts.planReopens -> "question"
        !facts.spoke -> "silent"
        facts.conversationEnds -> "ended"
        facts.skillId !in OPENS_AFTER -> "skill"
        !facts.onScreen -> "off_screen"
        facts.lockedNow && !facts.lockedAtStart -> "locked"
        facts.ringing -> "ringing"
        facts.call -> "call"
        facts.speakerMusic -> "music"
        !facts.whisper -> "not_whisper"
        facts.sttListening -> "listening"
        else -> null
    }

    fun decide(facts: GarWindowFacts): Boolean = reason(facts) == null

    /**
     * Whether [output] ends the conversation: stop, small talk's thanks, locked answers
     * ([GarEndsConversation]), an error, the permissions card or the text fallback.
     */
    fun endsWindow(output: SkillOutput): Boolean =
        output is GarEndsConversation || output is ErrorSkillOutput ||
            output is MissingPermissionsSkillOutput || output is TextFallbackOutput

    /**
     * Whether [output], the answer to a continued turn, is shown only: the text fallback, since
     * the window may have heard the TV or someone nearby. It opens no window either.
     */
    fun silentInContinued(output: SkillOutput): Boolean = output is TextFallbackOutput

    // ---- the window's sequence ----

    private val sequence = AtomicLong()
    private val lock = Any()

    /** The open window's sequence number and how to close it; guarded by [lock]. */
    private var open: Pair<Long, () -> Unit>? = null

    /** The current sequence number. */
    fun sequenceNow(): Long = sequence.get()

    /**
     * GAR speaks (`SpeechOutputDeviceWrapper.speak`): a scheduled window is stale, an open one
     * closes.
     */
    fun onSpeak() = bump()

    /** The evaluator got an input: the same as [onSpeak]. */
    fun onInput() = bump()

    private fun bump() {
        sequence.incrementAndGet()
        val close = synchronized(lock) { open.also { open = null } }
        close?.second?.invoke()
    }

    /**
     * After an answer was said on [device]: once it has finished speaking, and if nothing spoke
     * or came in since and [facts] still allow it, [open] opens the window with the sequence
     * number it was scheduled at.
     */
    fun afterAnswer(device: SpeechOutputDevice, facts: () -> GarWindowFacts, open: (Long) -> Unit) {
        val scheduled = sequence.get()
        if (!safely { decide(facts()) }) {
            return
        }
        device.runWhenFinishedSpeaking {
            // on the text to speech's thread: nothing may throw there
            safely {
                if (sequence.get() == scheduled && decide(facts())) {
                    open(scheduled)
                }
                true
            }
        }
    }

    /** [block], or false when it throws: a window that can't open is no error. */
    private inline fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "No continued-listening window", e)
        false
    }

    /**
     * The window of [scheduled] opened and [close] ends it. False, after closing it, when it is
     * already stale.
     */
    fun opened(scheduled: Long, close: () -> Unit): Boolean {
        val current = synchronized(lock) {
            (sequence.get() == scheduled).also { if (it) open = Pair(scheduled, close) }
        }
        if (!current) {
            close()
        }
        return current
    }

    /** The window of [scheduled] ended by itself (speech, silence). */
    fun closed(scheduled: Long) {
        synchronized(lock) {
            if (open?.first == scheduled) {
                open = null
            }
        }
    }

    // ---- ending words in other languages ----

    /**
     * "Thanks", "that's all", "nothing" in the languages other than English, whose conversation
     * skill takes them; the evaluator answers them with the silent "OK" in continued turns.
     */
    private val ENDING_WORDS = mapOf(
        "de" to listOf("danke", "danke schön", "danke schoen", "das wars", "das war es", "nichts"),
        "fr" to listOf("merci", "cest tout", "rien"),
        "es" to listOf("gracias", "eso es todo", "nada"),
        "it" to listOf("grazie", "è tutto", "e tutto", "niente"),
        "pt" to listOf("obrigado", "obrigada", "é tudo", "e tudo", "nada"),
        "nl" to listOf("dank je", "bedankt", "dat was het", "niets"),
    )

    /** Whether [text] in [locale] (not English) only ends the conversation. */
    fun endsConversation(locale: Locale, text: String): Boolean =
        ENDING_WORDS[locale.language]?.contains(endingWords(locale, text)) == true

    /** [text] lowercase, without apostrophes and other punctuation, one space between words. */
    private fun endingWords(locale: Locale, text: String): String = buildString {
        for (c in text.lowercase(locale)) {
            when {
                c == '\'' || c == '’' -> {}
                Character.isLetterOrDigit(c) -> append(c)
                else -> append(' ')
            }
        }
    }.split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    // ---- the phone, for the facts ----

    /** Whether GAR is on screen: its home, or the assistant sheet over another app. */
    fun onScreen(): Boolean = MainActivity.isInForeground > 0 || GarSheet.isShown

    fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    /** A call rings or is on (also another app's: the audio mode), as far as GAR can tell. */
    fun inCall(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
        ) {
            return true
        }
        return try {
            when (AndroidPhoneCalls(context).state()) {
                PhoneCallState.IDLE, PhoneCallState.UNKNOWN -> false
                else -> true
            }
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Whether another app plays out loud: a playing local session while media isn't routed to an
     * output only the user hears ([mediaIsPrivate]). Without notification access the sessions
     * can't be seen; then any music the phone plays counts (`isMusicActive`, read after GAR's own
     * speech ended).
     */
    fun speakerMusic(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val controllers = GarMediaSessions.controllers(context)
            ?: return audio?.isMusicActive == true && !mediaIsPrivate(context, audio)
        val playing = controllers.any {
            it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                it.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
        }
        if (!playing) {
            return false
        }
        return audio == null || !mediaIsPrivate(context, audio)
    }

    /**
     * Whether media plays on outputs only the user hears: the outputs media is routed to (API 33;
     * before, the connected outputs), all of them headphones. A Bluetooth output counts only when
     * its device class says headphones or a headset, since a speaker or a car is just as loud
     * (without the Bluetooth permission it doesn't).
     */
    private fun mediaIsPrivate(context: Context, audio: AudioManager): Boolean {
        val outputs = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                audio.getAudioDevicesForAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                )
            } else {
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
        } catch (e: RuntimeException) {
            return false
        }
        return outputs.isNotEmpty() && outputs.all { output ->
            when (output.type) {
                in WIRED_OUTPUTS -> true
                in BLUETOOTH_OUTPUTS -> isHeadphones(context, output.address)
                else -> false
            }
        }
    }

    /** Whether the Bluetooth device at [address] says it is headphones or a headset. */
    private fun isHeadphones(context: Context, address: String?): Boolean = try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val deviceClass = address?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
            ?.let { adapter?.getRemoteDevice(it)?.bluetoothClass?.deviceClass }
        deviceClass != null && deviceClass in HEADPHONE_CLASSES
    } catch (e: SecurityException) {
        false // no Bluetooth permission
    } catch (e: RuntimeException) {
        false
    }

    /** Wired and USB outputs only the user hears. */
    private val WIRED_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** Bluetooth outputs, headphones or not (`TYPE_BLE_HEADSET` 26 is API 31). */
    private val BLUETOOTH_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        26,
    )

    private val HEADPHONE_CLASSES = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
    )

    /**
     * Calls [onScreenOff] when the screen turns off, until the returned function is called.
     * Registered only while a window is open.
     */
    fun watchScreenOff(context: Context, onScreenOff: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onScreenOff()
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't watch the screen", e)
            return {}
        }
        return {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
        }
    }
}
