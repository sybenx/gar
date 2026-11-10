package org.stypox.dicio.skills.conversation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.conversation.GarContinuedListening
import org.stypox.dicio.gar.conversation.GarConversationSkill
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.conversation.conversationWords
import org.stypox.dicio.sentences.Sentences.Conversation
import org.stypox.dicio.skills.alarm.AlarmOutput
import org.stypox.dicio.skills.alarm.AndroidClockApp
import org.stypox.dicio.skills.alarm.FiringAlarmCheck
import org.stypox.dicio.skills.alarm.FiringAlarms

/**
 * A match of [ConversationSkill]: its [sentence], and whether the input [silences] what rings
 * ([GarRinging.isStopPhrase]).
 */
data class ConversationMatch(val sentence: Conversation, val silences: Boolean = false)

/**
 * "Stop" and "never mind" end GAR's speech and its conversation, and say nothing; "repeat that"
 * says GAR's last answer again and leaves a question open (gar/DESIGN.md, "Conversation").
 *
 * @param stopSpeaking stops what GAR says (listening has already stopped it, a typed "stop" hasn't)
 * @param isRinging whether a timer or an alarm rings ([GarRinging]): then the stop words go to it
 * @param alarmSounds whether an alarm sound plays (the clock app's, which GAR can't see without
 * notification access, see `ClockApp.isAlarmRinging`)
 * @param firingAlarm what GAR sees of the clock app's ringing alarm
 * @param continued whether the input came from a continued-listening window: then a bare "thanks"
 * ends the conversation too, without small talk's "You're welcome!" (gar/DESIGN.md, "Continued
 * conversation"; a bare "thank you" never gets here, the window drops it as silence)
 */
class ConversationSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Conversation>,
    private val stopSpeaking: suspend (SkillContext) -> Unit = { ctx ->
        withContext(Dispatchers.Main) { ctx.speechOutputDevice.stopSpeaking() }
    },
    private val isRinging: () -> Boolean = GarRinging::isRinging,
    private val alarmSounds: (SkillContext) -> Boolean = { AndroidClockApp(it.android).isAlarmRinging() },
    private val firingAlarm: () -> FiringAlarmCheck = FiringAlarms::current,
    private val continued: () -> Boolean = { GarContinuedListening.matchingContinued }, // GAR
) : Skill<ConversationMatch>(correspondingSkillInfo, data.specificity), GarConversationSkill {

    // Only an input that is one of the phrases (see [ConversationWords]): Dicio scores a sentence
    // with a word left out above its thresholds, so "stop the music" would match "stop it" and
    // "say that again in french" "say that again". While a timer or an alarm rings, its own skill
    // gets the words that stop it, and "cancel".
    override fun score(ctx: SkillContext, input: String): Pair<Score, ConversationMatch> {
        if (continued() && ConversationWords.isThanks(input)) {
            return Pair(AlwaysBestScore, ConversationMatch(Conversation.Stop))
        }
        val (score, sentence) = data.score(ctx, input)
        val silences = GarRinging.isStopPhrase(input)
        val takes = when (sentence) {
            Conversation.Stop -> ConversationWords.isStop(input) &&
                !((silences || GarRinging.isCancelWhileRinging(input)) && isRinging())
            Conversation.Repeat -> ConversationWords.isRepeat(input)
        }
        return Pair(if (takes) score else AlwaysWorstScore, ConversationMatch(sentence, silences))
    }

    override fun keepsQuestion(inputData: Any?): Boolean =
        (inputData as? ConversationMatch)?.sentence == Conversation.Repeat

    override suspend fun generateOutput(ctx: SkillContext, inputData: ConversationMatch): SkillOutput {
        return when (inputData.sentence) {
            Conversation.Stop -> {
                stopSpeaking(ctx)
                // "stop" to an alarm that GAR can't see (no notification access) or can't stop:
                // say so, instead of a silent "OK" while it goes on ringing. A clock app timer or
                // alarm that GAR sees would have taken the input.
                if (inputData.silences && !isRinging() && alarmSounds(ctx)) {
                    AlarmOutput.Ringing(needsAccess = firingAlarm() == FiringAlarmCheck.NoAccess)
                } else {
                    ConversationStopOutput(before = ctx.previousOutput)
                }
            }
            Conversation.Repeat -> ConversationRepeatOutput.of(ctx, ctx.previousOutput)
        }
    }
}

/**
 * The phrases of [ConversationSkill], without apostrophes, as [conversationWords] reads an input:
 * with fillers before ("ok", "no", "sorry", ...), "can you" or "could you", and "please" or "now"
 * after. English, like its sentences, which allow the same.
 */
object ConversationWords {
    /** Stop GAR's speech and end its conversation, besides [GarRinging.STOP_PHRASES]. */
    private val STOP_PHRASES = setOf("stop talking", "stop speaking") + GarRinging.CANCEL_PHRASES

    /** End the conversation, also with "thanks" or "thank you" before or after them. */
    private val DONE_PHRASES = setOf(
        "never mind", "nevermind", "forget it", "forget that", "forget about it",
        "thats it", "thats all", "that is it", "that is all", "thatll be all", "that will be all",
        "im done", "i am done", "were done", "we are done", "all done",
        // no thanks, to "What can I do for you?" and the like
        "nothing", "nope", "nah", "im good", "i am good",
    )

    /**
     * Declines that start with a word [conversationWords] drops ("no") or ends with ("now"),
     * checked on the words as said, without the fillers that aren't part of them.
     */
    private val DECLINE_PHRASES = setOf(
        "no thanks", "no thank you", "nah thanks", "nah thank you", "not now", "not right now",
        "no not now", "no not right now",
    )

    /** Dropped before [DECLINE_PHRASES] are compared ("ok no thanks"). */
    private val DECLINE_FILLERS = setOf("ok", "okay", "oh", "alright", "just", "uh", "um", "sorry")

    private val THANKS = listOf(listOf("thank", "you"), listOf("thanks"))

    /** What [isThanks] takes. */
    private val THANKS_PHRASES = setOf(
        "thanks", "thank you", "thanks a lot", "thanks so much", "thank you so much",
        "thank you very much", "many thanks", "cheers",
    )

    private val REPEAT_PHRASES = listOf("that", "it", "yourself").flatMap { what ->
        listOf("repeat $what", "repeat $what again")
    } + listOf("that", "it").flatMap { listOf("say $it again", "say $it one more time") } + listOf(
        "say again", "what did you say", "what was that", "what was that again", "come again",
        "pardon", "pardon me", "i missed that", "repeat what you said",
    ) + listOf("didnt", "did not").flatMap { did ->
        listOf("hear", "catch", "get").flatMap { verb ->
            listOf("that", "you", "it").map { what -> "i $did $verb $what" }
        }
    } + listOf("couldnt", "could not").flatMap { could ->
        listOf("that", "you", "it").map { what -> "i $could hear $what" }
    } + listOf("cant", "cannot", "can not").map { can ->
        // not "i can't hear it": the music is too quiet, more likely
        "i $can hear you"
    }

    /** "Can you repeat": a bare "repeat" is left out, since media's repeat mode may want it. */
    private val POLITE_REPEAT = Regex("\\b(can|could|would) you repeat\\b")

    /** "Stop", "be quiet", "never mind", "thanks, that's all", "no thanks", ... */
    fun isStop(input: String): Boolean {
        val words = conversationWords(input)
        val phrase = words.joinToString(" ")
        return phrase in GarRinging.STOP_PHRASES || phrase in STOP_PHRASES ||
            withoutThanks(words).joinToString(" ") in DONE_PHRASES || isDecline(input)
    }

    /**
     * A bare "thanks", "thanks a lot": the end of a continued conversation. A bare "thank you"
     * never gets here from a window, which drops it as a Whisper hallucination first
     * (`GarWhisperHallucinations`).
     */
    fun isThanks(input: String): Boolean =
        conversationWords(input).joinToString(" ") in THANKS_PHRASES

    /** "Repeat that", "say that again", "what did you say", "can you repeat", ... */
    fun isRepeat(input: String): Boolean {
        val words = conversationWords(input)
        return words.joinToString(" ") in REPEAT_PHRASES ||
            (words == listOf("repeat") && POLITE_REPEAT.containsMatchIn(normalizeAnswer(input)))
    }

    private fun isDecline(input: String): Boolean = normalizeAnswer(input).replace("'", "")
        .split(" ")
        .filter { it.isNotEmpty() && it !in DECLINE_FILLERS }
        .joinToString(" ") in DECLINE_PHRASES

    /** [words] without "thanks" or "thank you" before and after them. */
    private fun withoutThanks(words: List<String>): List<String> {
        var rest = words
        THANKS.firstOrNull { rest.size > it.size && rest.take(it.size) == it }
            ?.let { rest = rest.drop(it.size) }
        THANKS.firstOrNull { rest.size > it.size && rest.takeLast(it.size) == it }
            ?.let { rest = rest.dropLast(it.size) }
        return rest
    }
}
