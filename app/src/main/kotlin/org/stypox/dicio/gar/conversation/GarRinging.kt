package org.stypox.dicio.gar.conversation

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.gar.call.normalizeAnswer
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a timer or an alarm rings right now, so that "stop" goes to it (gar/DESIGN.md,
 * "Conversation"). While something rings, the conversation skill and GAR's cancel answers leave
 * the [isStopPhrase] inputs to the skill of what rings, which takes them from Dicio's default
 * batch, and GAR's answers to its questions also leave the other commands that skill takes
 * ([takes]).
 *
 * The timer and the alarm each register a provider under their skill id, once (registering again
 * replaces it), and the commands their skill takes while they ring. Both are asked while an input
 * is ranked, on any thread: they must answer at once, and one that throws counts as false.
 */
object GarRinging {
    private val providers = ConcurrentHashMap<String, () -> Boolean>()
    private val commands = ConcurrentHashMap<String, (SkillContext, String) -> Boolean>()

    /** Adds the provider of [id] ("timer", "alarm"), or replaces the one it had. */
    fun register(id: String, isRinging: () -> Boolean) {
        providers[id] = isRinging
    }

    /**
     * Adds what the skill of [id] takes while [id] rings ("turn it off", "dismiss", "snooze"), or
     * replaces what it had; the stop phrases count anyway.
     */
    fun registerCommands(id: String, takes: (SkillContext, String) -> Boolean) {
        commands[id] = takes
    }

    fun unregister(id: String) {
        providers.remove(id)
        commands.remove(id)
    }

    /**
     * Whether what rings takes [input]: one of the stop phrases while anything rings, or a
     * command the skill of what rings takes ([registerCommands]). GAR's answers to its questions
     * leave these to Dicio's default batch, so "turn it off" stops a ringing timer instead of
     * becoming a reminder's text.
     */
    fun takes(ctx: SkillContext, input: String): Boolean {
        val ringing = ringing()
        return ringing.isNotEmpty() && (isStopPhrase(input) || ringing.any { id ->
            try {
                commands[id]?.invoke(ctx, input) == true
            } catch (e: Exception) {
                false
            }
        })
    }

    /** The ids of the providers that ring now, sorted; empty when nothing rings. */
    fun ringing(): List<String> = providers.entries
        .filter { (_, isRinging) ->
            try {
                isRinging()
            } catch (e: Exception) {
                false
            }
        }
        .map { it.key }
        .sorted()

    fun isRinging(): Boolean = ringing().isNotEmpty()

    /**
     * Whether [input] tells what rings to stop: one of [STOP_PHRASES], maybe with a filler before
     * or after it ("ok stop", "can you stop", "stop please", see [conversationWords]). While
     * something rings, the skill of what rings should take every one of them, and it may take
     * more ("turn it off", "dismiss", "snooze"). "Never mind" and "that's all" stay with GAR's
     * conversation, and "cancel" with GAR's questions ([isCancelWhileRinging]). English, like
     * GAR's other answers.
     */
    fun isStopPhrase(input: String): Boolean =
        conversationWords(input).joinToString(" ") in STOP_PHRASES

    /** The words that silence what rings, without apostrophes, see [isStopPhrase]. */
    val STOP_PHRASES = setOf(
        "stop", "stop it", "stop that", "shut up", "be quiet", "keep quiet", "quiet", "silence",
        "hush", "enough", "thats enough", "that is enough",
    )

    /**
     * Whether [input] is one of [CANCEL_PHRASES], with the fillers of [isStopPhrase]. While
     * something rings, GAR's conversation leaves them to what rings too: a ringing timer and the
     * clock app's alarm take "cancel". Unlike the stop phrases, a pending GAR question still takes
     * them first (`GarCancelSkill`), since "cancel" is how a question is cancelled.
     */
    fun isCancelWhileRinging(input: String): Boolean =
        conversationWords(input).joinToString(" ") in CANCEL_PHRASES

    val CANCEL_PHRASES = setOf("cancel", "cancel it", "cancel that")
}

/**
 * They may come before a conversation phrase, one or more: "ok stop", "sorry, what did you say",
 * "ok please stop", "alright stop".
 */
private val LEADING_FILLERS = setOf("ok", "okay", "oh", "hey", "no", "please", "sorry", "alright")

/** Hesitations, dropped wherever they are, as GAR's answers drop them: "what did you just say". */
private val HESITATIONS = setOf("just", "uh", "um")

/** After [LEADING_FILLERS]: "can you stop", "could you repeat that". */
private val POLITE_REQUESTS = setOf("can you", "could you", "would you")

/** One of them may come after a conversation phrase: "stop please". */
private val TRAILING_FILLERS = setOf("please", "now")

/**
 * The words of [input] for matching the conversation phrases (GAR's conversation skill,
 * [GarRinging.isStopPhrase] and the alarm's stop phrases): normalized like GAR's answers
 * ([normalizeAnswer]) and without apostrophes ("that's" is "thats", as STT often writes it),
 * without [HESITATIONS], a word said twice once ("stop stop"), without [LEADING_FILLERS], then
 * one of [POLITE_REQUESTS], and one of [TRAILING_FILLERS] ("ok can you stop please" is "stop").
 * A filler alone stays ("ok"). The conversation sentences (`sentences/en/conversation.yml`) allow
 * the same fillers.
 */
internal fun conversationWords(input: String): List<String> {
    val said = normalizeAnswer(input).replace("'", "").split(" ").filter { it.isNotEmpty() }
    var words = said.filter { it !in HESITATIONS }.ifEmpty { said }
        .let { kept -> kept.filterIndexed { i, word -> i == 0 || word != kept[i - 1] } }
    while (words.size > 1 && words.first() in LEADING_FILLERS) {
        words = words.drop(1)
    }
    if (words.size > 2 && words.take(2).joinToString(" ") in POLITE_REQUESTS) {
        words = words.drop(2)
    }
    if (words.size > 1 && words.last() in TRAILING_FILLERS) {
        words = words.dropLast(1)
    }
    return words
}
