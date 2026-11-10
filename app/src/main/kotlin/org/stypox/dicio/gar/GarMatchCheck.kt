package org.stypox.dicio.gar

import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.tools.ToolSpec
import org.dicio.numbers.ParserFormatter
import org.dicio.numbers.unit.Number
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.CalculatorOperators
import org.stypox.dicio.skills.alarm.AlarmInfo
import org.stypox.dicio.skills.alarm.AlarmSentenceMatch
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.date.DateInput
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.navigation.NavigationWords
import org.stypox.dicio.skills.timer.TimerInfo

/**
 * Checks matches of Dicio's own sentences before they run (gar/DESIGN.md, "Checking Dicio's own
 * matches"). Dicio's matcher still accepts a sentence with one word swapped ("switch off the
 * music" matches the flashlight's "switch off the light"), so for skills whose tools all have
 * [ToolSpec.keywords], a match whose input names none of them outside the sentence's captures
 * counts as no match and goes to the model instead (when the model is on). The keywords are the
 * ones the model's calls are checked with ([KeywordFilter]) plus [SENTENCE_KEYWORDS]; every
 * sentence of such a skill names one of them. The timer, the calculator and navigation have their
 * own checks.
 */
object GarMatchCheck {
    /**
     * What the checks of one input need.
     * @param tools every tool of the catalog
     * @param timerNames the names of the running timers, null for an unnamed one
     * @param calculatorOperators the calculator's operator words; English, the only language
     * whose matches GAR checks (`GarFallbackImpl.matchCheck`)
     */
    class State(
        val tools: List<ToolSpec>,
        val timerNames: List<String?> = listOf(),
        val parserFormatter: ParserFormatter? = null,
        val calculatorOperators: StandardRecognizerData<CalculatorOperators>? =
            CalculatorOperators[GarFallbackImpl.ENGLISH],
    )

    /** A match scored at least this is exact: the input is one of the skill's sentences. */
    private const val EXACT_SCORE = 0.999f

    /** Words scored at least this are an operator, as in `CalculatorSkill.getOperation`. */
    private const val OPERATOR_SCORE = 0.3f

    /**
     * Keywords of Dicio's sentences that the skill's tools don't have: the media skill's nouns
     * (its tool has no keywords, "next one please"), the alarm sentences' "show my clock" and the
     * listening sentences' "mute mike". Kept out of `tools.yml`, so that they don't widen the
     * check of the model's calls.
     */
    private val SENTENCE_KEYWORDS = mapOf(
        AlarmInfo.id to listOf("clock"),
        "listening" to listOf("mike"),
        "media" to listOf(
            "song", "media", "track", "music", "audio", "video", "movie", "stream", "film",
            "player", "playing", "queue", "one", "podcast", "episode", "audiobook", "playlist",
            "radio",
        ),
        // gar:settings: the connectivity sentences' words, which were the keywords of the wifi
        // and bluetooth tools before device_setting took their calls; the device settings
        // sentences' ringer words ("mute my phone"), the pages that need no "settings" after
        // their name ("open developer options"), and "screen" and "display" ("when does the
        // screen turn off"), which device_setting doesn't have alone ("my screen is cracked")
        "connectivity" to listOf(
            "wifi", "wi fi", "wireless", "internet", "network", "online", "offline", "bluetooth",
            "blue tooth", "pair", "headphone", "headset", "earbud", "earphone", "airpod", "buds",
            "speaker",
        ),
        "device_settings" to listOf(
            "mute", "unmute", "unsilence", "ring", "option", "about phone", "about this phone",
            "about my phone", "about device", "device info", "phone info", "default app",
            "accessibility", "night light", "blue light", "eye comfort", "do not disturb", "dnd",
            "d n d", "priority mode", "contactless", "tap to pay", "screen", "display",
        ),
    )

    /**
     * Keywords that only count in an exact match ("next", "go back to the last song"): the media
     * sentences make the noun optional, so with one word swapped "go to the next page" or "play
     * the last voicemail" matched with only these left.
     */
    private val EXACT_ONLY_KEYWORDS = mapOf(
        "media" to listOf("next", "following", "previous", "last", "rewind"),
    )

    /**
     * Words that, in a match that isn't exact, name another thing next to the skill's keyword:
     * "turn on light mode", "turn on wifi calling", "read my notification settings", "start the
     * video call".
     */
    private val OTHER_THING_WORDS = mapOf(
        // gar:settings: "turn on the night light" is Android's night light, not the torch
        "flashlight" to listOf(
            "mode", "theme", "filter", "sensor", "notification", "setting", "night light",
            "blue light",
        ),
        "connectivity" to listOf("charging", "call", "debugging", "mouse", "keyboard", "hotspot"),
        "notify" to listOf("setting"),
        "media" to listOf("call"),
    )

    /**
     * The timer's own words. Its tools have no keywords ("beep in two minutes"), and "time" is
     * left out: it is in the timer sentences (STT drops the r of "timer"), but most inputs that
     * matched with it were about something else: "the time" cancelled the running timer, "turn
     * off screen time" or "how much time is left on the battery".
     */
    private val TIMER_WORDS = listOf(
        "timer", "countdown", "count down", "ping", "bell", "alert", "sound", "ringtone", "beep",
        "buzz",
    )

    /**
     * Also enough for a new timer whose duration Dicio understood: "set a time for five minutes"
     * (STT), "set a five minute alarm" (an alarm can't ring in five minutes).
     */
    private val TIMER_SET_WORDS = listOf("time", "second alarm", "minute alarm", "hour alarm")

    /**
     * The alarm cancel sentences' verbs: without one, an alarm cancel that isn't exact ("next
     * alarm") is a question about the alarm, and a cancel from the sentences is never confirmed.
     */
    private val ALARM_CANCEL_WORDS = listOf(
        "cancel", "delete", "remove", "dismiss", "disable", "skip", "clear", "off", "stop",
    )

    /**
     * The keywords a match of [skillId] needs, or null if its matches aren't checked this way: the
     * skill has no tools, or one of its tools has no keywords (its requests don't always name it),
     * and it has no [SENTENCE_KEYWORDS].
     * @param exact whether the match is exact (see [EXACT_ONLY_KEYWORDS])
     */
    fun keywordsFor(tools: List<ToolSpec>, skillId: String, exact: Boolean = true): List<String>? {
        val skillTools = tools.filter { it.skillId == skillId }
        val toolKeywords = if (skillTools.isEmpty() || skillTools.any { it.keywords.isEmpty() }) {
            null
        } else {
            skillTools.flatMap { it.keywords }
        }
        val sentenceKeywords = SENTENCE_KEYWORDS[skillId]
        if (toolKeywords == null && sentenceKeywords == null) {
            return null
        }
        val exactOnly = if (exact) EXACT_ONLY_KEYWORDS[skillId].orEmpty() else listOf()
        return (toolKeywords.orEmpty() + sentenceKeywords.orEmpty() + exactOnly).distinct()
    }

    /** Whether [match], Dicio's match for [input], should be treated as no match. */
    fun rejects(state: State, match: SkillWithResult<*>, input: String): Boolean = rejects(
        state, match.skill.correspondingSkillInfo.id, match.inputData,
        match.score.scoreIn01Range(), input,
    )

    /**
     * Whether a match of [skillId] with [inputData] and [score] (in 0..1) for [input] should be
     * treated as no match.
     */
    fun rejects(
        state: State,
        skillId: String,
        inputData: Any?,
        score: Float,
        input: String,
    ): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        // a capture takes any words: "what will the weather be tomorrow" matched "what will the
        // date be .day." with the day's "tomorrow"
        val frame = withoutCaptures(words, capturesOf(inputData)).joinToString(" ")
        val exact = score >= EXACT_SCORE
        if (isRingingAlarmCommand(inputData)) {
            // "stop" and "snooze" name no alarm: AlarmSkill.score has checked them
            return false
        }
        return when (skillId) {
            TimerInfo.id -> rejectsTimer(state, inputData, frame)
            CalculatorInfo.id -> rejectsCalculation(state, inputData)
            // "when should I go to bed" matched "how should I go to .where." without its "how"
            NavigationInfo.id -> !exact && NavigationWords.skipsHow(input)
            else -> {
                val keywords = keywordsFor(state.tools, skillId, exact) ?: return false
                val all = words.joinToString(" ")
                val otherThings = OTHER_THING_WORDS[skillId].orEmpty()
                !KeywordFilter.containsAny(frame, keywords) ||
                    (!exact && KeywordFilter.containsAny(all, otherThings)) ||
                    (!exact && isAlarmCancel(inputData) &&
                        !KeywordFilter.containsAny(all, ALARM_CANCEL_WORDS))
            }
        }
    }

    /**
     * The timer's words, a running timer's name ("cancel the pasta" while a pasta timer runs), or
     * a question about the running timers ("how long is left"). Without timers, a cancel or a
     * question that doesn't say "timer" goes to the model: Dicio would only answer that there is
     * no timer.
     */
    private fun rejectsTimer(state: State, inputData: Any?, frame: String): Boolean {
        if (KeywordFilter.containsAny(frame, TIMER_WORDS)) {
            return false
        }
        return when (inputData) {
            is Sentences.Timer.Set ->
                inputData.duration == null || !KeywordFilter.containsAny(frame, TIMER_SET_WORDS)
            is Sentences.Timer.Cancel -> !namesRunningTimer(inputData.name, state.timerNames)
            is Sentences.Timer.Query -> if (Utterances.normalize(inputData.name ?: "").isEmpty()) {
                state.timerNames.isEmpty()
            } else {
                !namesRunningTimer(inputData.name, state.timerNames)
            }
            else -> false
        }
    }

    /** Whether [said], a timer name as captured, names one of [timerNames] or a part of one. */
    private fun namesRunningTimer(said: String?, timerNames: List<String?>): Boolean {
        val saidName = Utterances.normalize(said ?: return false)
        if (saidName.isEmpty()) {
            return false
        }
        return timerNames.any { timer ->
            val timerName = Utterances.normalize(timer ?: return@any false)
            timerName.isNotEmpty() && (KeywordFilter.containsAny(saidName, listOf(timerName)) ||
                KeywordFilter.containsAny(timerName, listOf(saidName)))
        }
    }

    /**
     * A calculation without a number, or with one number and other words: "what is .calculation."
     * matches any question ("what's the time in london", "what's the weather on the 5th"), and the
     * calculator only computes between numbers. With one number it says that number back ("5"),
     * also for "the square root of sixty four" (GAR's rewrite of the model's call computes it). A
     * number alone is fine: the number parser already divides "12 divided by 4" into 3.
     *
     * With more numbers, the calculator ignores words after the last one and adds two numbers
     * whose words in between hold no operator, so those go to the model too: "the score of the
     * 49ers game" would be 20 times 49 ("score" is twenty to the number parser, "of" means times
     * and "ers game" is left over), "two and three" or "2 x 3" would be added. No words between
     * two numbers is fine: the parser takes "plus" and "minus" into the next number ("seven plus
     * nine" is 7 and +9).
     */
    private fun rejectsCalculation(state: State, inputData: Any?): Boolean {
        val match = inputData as? Sentences.Calculator.Calculate ?: return false
        val calculation = match.calculation ?: return true
        val parserFormatter = state.parserFormatter ?: return false
        val parts = parserFormatter.extractNumber(calculation).parseMixedWithText()
        val numbers = parts.count { it is Number }
        if (numbers <= 1) {
            return numbers == 0 || parts.any { it is String && it.isNotBlank() }
        }

        val lastNumber = parts.indexOfLast { it is Number }
        val after = parts.subList(lastNumber + 1, parts.size).filterIsInstance<String>()
        val between = parts.subList(parts.indexOfFirst { it is Number }, lastNumber)
            .filterIsInstance<String>()
        return after.any { Utterances.normalize(it).isNotEmpty() } ||
            between.any { it.isNotBlank() && !isOperator(state, parserFormatter, it) }
    }

    /**
     * Whether the calculator reads [words] as an operator, scored like `CalculatorSkill` does;
     * true without the operator sentences, so that nothing is rejected then.
     */
    private fun isOperator(state: State, parserFormatter: ParserFormatter, words: String): Boolean {
        val operators = state.calculatorOperators ?: return true
        val (score, _) = operators.score(MatchHelper(parserFormatter, words), words)
        return score.scoreIn01Range() >= OPERATOR_SCORE
    }

    /**
     * The alarm skill's "stop" (only while the clock app's alarm rings) and "snooze" (only an
     * input made of the snooze sentences' words), see `AlarmSkill.score`.
     */
    private fun isRingingAlarmCommand(inputData: Any?): Boolean =
        (inputData as? AlarmSentenceMatch)?.sentence.let {
            it is Sentences.Alarm.Stop || it is Sentences.Alarm.Snooze
        }

    private fun isAlarmCancel(inputData: Any?): Boolean =
        (inputData as? AlarmSentenceMatch)?.sentence.let {
            it is Sentences.Alarm.Cancel || it is Sentences.Alarm.CancelAll
        }

    private fun capturesOf(inputData: Any?): List<String?> = when (inputData) {
        is Sentences.Timer.Set -> listOf(inputData.name)
        is Sentences.Timer.Cancel -> listOf(inputData.name)
        is Sentences.Timer.Query -> listOf(inputData.name)
        is DateInput.Said -> listOf(inputData.day)
        is AlarmSentenceMatch -> when (val sentence = inputData.sentence) {
            is Sentences.Alarm.Set -> listOf(sentence.time, sentence.label)
            is Sentences.Alarm.Cancel -> listOf(sentence.time)
            is Sentences.Alarm.Change -> listOf(sentence.from, sentence.to)
            else -> listOf()
        }
        else -> listOf()
    }

    /**
     * [words] without the first occurrence of each capture's words (normalized like [words]).
     * Also used for the online skills' inputs (`GarOnlineInputs`).
     */
    fun withoutCaptures(words: List<String>, captures: List<String?>): List<String> =
        captures.fold(words) { remaining, capture ->
            val captureWords = Utterances.words(Utterances.normalize(capture ?: ""))
            val start = (0..remaining.size - captureWords.size).firstOrNull { start ->
                captureWords.isNotEmpty() &&
                    captureWords.indices.all { remaining[start + it] == captureWords[it] }
            }
            if (start == null) {
                remaining
            } else {
                remaining.subList(0, start) +
                    remaining.subList(start + captureWords.size, remaining.size)
            }
        }
}
