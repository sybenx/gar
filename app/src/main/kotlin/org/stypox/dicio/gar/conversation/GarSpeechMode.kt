package org.stypox.dicio.gar.conversation

import org.dicio.skill.context.SpeechOutputDevice

/**
 * Speaking vs showing (gar/DESIGN.md, "Speaking vs showing"): a spoken request gets its answer
 * said, a typed one or a card's button gets it on the card only, unless "Speak answers to typed
 * requests" is on. There is no global gate: every producer of speech passes the mode of its own
 * turn, also when it speaks late (the song skill's result after a recording), so that a later
 * turn of the other kind changes nothing. Stopping speech is never gated.
 */
object GarSpeechMode {
    /** "Speak answers to typed requests" (`speak_typed_answers`), set by the evaluator. */
    @Volatile
    var speakTypedAnswers = false

    /**
     * Whether [text], an answer to a turn of [mode], is said; null [mode] (no turn) speaks as
     * Dicio did.
     */
    fun speaks(mode: GarInputMode?, text: String): Boolean =
        text.isNotBlank() && (mode != GarInputMode.TYPED || speakTypedAnswers)

    /** Says [text] on [device] if a turn of [mode] speaks it ([speaks]); returns whether it did. */
    fun speak(device: SpeechOutputDevice, mode: GarInputMode?, text: String): Boolean {
        if (!speaks(mode, text)) {
            return false
        }
        device.speak(text)
        return true
    }
}
