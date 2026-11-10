package org.stypox.dicio.gar.conversation

import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.io.input.InputEvent
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** How a turn's input came in: said (Whisper, Vosk, another app's recognizer) or typed. */
enum class GarInputMode { SPOKEN, TYPED }

/**
 * One turn of the evaluator as GAR's conversation sees it (gar/DESIGN.md, "Speaking vs
 * showing", "Continued conversation"), carried through the evaluator's coroutine: how its input
 * came in ([mode]; card buttons are [GarInputMode.TYPED]), whether a continued-listening window
 * started it ([continued]), whether GAR was speaking when its capture started
 * ([startedWhileSpeaking]), whether the phone was locked when it started ([lockedAtStart]), and
 * the model's call when one ran ([call]).
 */
class GarTurnElement(
    val id: Long,
    val mode: GarInputMode,
    /** Started by a continued-listening window. */
    val continued: Boolean,
    /** GAR was talking when the capture started. */
    val startedWhileSpeaking: Boolean,
    val lockedAtStart: Boolean,
) : AbstractCoroutineContextElement(GarTurnElement) {
    /** The model's complete call, when one ran this turn (GarCallRunner.run sets it). */
    @Volatile
    var call: GarCall? = null

    companion object Key : CoroutineContext.Key<GarTurnElement> {
        private val ids = AtomicLong()

        /** Whether GAR was speaking when the last capture started, until a spoken turn takes it. */
        @Volatile
        private var captureStartedWhileSpeaking = false

        /** The turn of [event]; a spoken one takes what [markCaptureStart] recorded. */
        fun of(event: InputEvent.Final, continued: Boolean, locked: Boolean): GarTurnElement {
            val spoken = event.spoken
            val whileSpeaking = spoken && captureStartedWhileSpeaking
            if (spoken) {
                captureStartedWhileSpeaking = false
            }
            return GarTurnElement(
                id = ids.incrementAndGet(),
                mode = if (spoken) GarInputMode.SPOKEN else GarInputMode.TYPED,
                continued = continued,
                startedWhileSpeaking = whileSpeaking,
                lockedAtStart = locked,
            )
        }

        suspend fun current(): GarTurnElement? = coroutineContext[Key]

        /** GarBargeIn, when STT starts listening. */
        fun markCaptureStart(wasSpeaking: Boolean) {
            captureStartedWhileSpeaking = wasSpeaking
        }
    }
}
