package org.stypox.dicio.gar.followup.adapters

import cc.aaro.gar.router.followup.CorrectionKind
import cc.aaro.gar.router.followup.FollowUpForm
import cc.aaro.gar.router.followup.FollowUpForms
import org.stypox.dicio.gar.followup.GarAnswered
import org.stypox.dicio.gar.followup.GarFacts
import org.stypox.dicio.gar.followup.GarFollowUpAdapter
import org.stypox.dicio.gar.followup.GarFollowUpContext
import org.stypox.dicio.gar.followup.GarPlace
import org.stypox.dicio.gar.followup.GarRememberedTurn
import org.stypox.dicio.gar.followup.GarRewrite
import org.stypox.dicio.gar.followup.GarRewriteText
import org.stypox.dicio.gar.followup.GarTurn
import org.stypox.dicio.gar.followup.GarTurnKind
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.random.RandomRequest
import org.stypox.dicio.skills.weather.WeatherRequest
import org.stypox.dicio.skills.weather.WeatherTopic
import org.stypox.dicio.skills.weather.WeatherWhen
import org.stypox.dicio.skills.weather.WeatherWords
import org.stypox.dicio.skills.world_time.WorldTimeAnswer
import org.stypox.dicio.skills.world_time.WorldTimePhrases
import org.stypox.dicio.skills.world_time.WorldTimeRequest
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Answers about a place and a day: the weather, the time here and elsewhere, the date and the
 * holidays. "What about tomorrow", "and in paris", "no, boston" ask the newest answer again with
 * the day or the place changed: "what's the weather tomorrow in denver". Their turns are
 * [GarTurnKind.ANSWER]; the place is a fact that "what time is it there" reaches.
 */
object AnswerFollowUps : GarFollowUpAdapter {
    const val WEATHER = "weather"
    const val CURRENT_TIME = "current_time"
    const val WORLD_TIME = "world_time"
    const val HOLIDAY = "holiday"

    /** [GarTurn.slots] keys of a weather turn. */
    const val TOPIC = "topic"
    const val WHEN = "when"
    const val WHERE = "where"

    override val skillIds = setOf(
        WEATHER, CURRENT_TIME, WORLD_TIME, "date", HOLIDAY, "units", "currency", "translation",
    )

    // weather, world time and the date run on the lock screen, like their tools
    override val onLockScreen: Boolean = true

    /** The phone's date; tests replace it. */
    @Volatile
    internal var today: () -> LocalDate = { LocalDate.now() }

    override fun remember(answered: GarAnswered): GarTurn {
        val place: String?
        val slots = mutableMapOf<String, String>()
        when (answered.skillId) {
            WEATHER -> {
                val request = answered.inputData as? WeatherRequest
                    ?: WeatherWords.parse(answered.call?.utterance ?: answered.input, today())
                place = request?.place
                if (request != null) {
                    slots[TOPIC] = request.topic.name
                    whenWords(request.time, today())?.let { slots[WHEN] = it }
                }
            }
            WORLD_TIME -> place = when (val answer = answered.inputData) {
                is WorldTimeAnswer.Known -> when (val request = answer.request) {
                    is WorldTimeRequest.TimeIn -> request.place.name
                    is WorldTimeRequest.Difference -> request.place.name
                }
                is WorldTimeAnswer.UnknownPlace -> answer.name
                else -> null
            }
            else -> place = null
        }
        place?.let { slots[WHERE] = it }
        return GarTurn(
            skillId = answered.skillId,
            action = slots[TOPIC] ?: "",
            slots = slots,
            facts = GarFacts(place = place?.let { GarPlace(it, null, null, null) }),
            command = answered.input,
            kind = GarTurnKind.ANSWER,
        )
    }

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? {
        val (rest, preposition) = when (form) {
            is FollowUpForm.WhatAbout -> Pair(form.rest, form.preposition)
            is FollowUpForm.Correction ->
                if (form.kind == CorrectionKind.OTHER) return null else Pair(form.value ?: return null, null)
            else -> return null
        }
        val text = when (turn.turn.skillId) {
            WEATHER -> weather(turn.turn, rest, preposition, context.now.toLocalDate(), form is FollowUpForm.Correction)
            CURRENT_TIME, WORLD_TIME -> timeIn(rest, preposition)
            // "and the day after" names no holiday
            HOLIDAY -> if (preposition == null && GarRewriteText.safe(rest) && FollowUpForms.relativeDays(rest) == null) {
                "when is $rest"
            } else {
                null
            }
            else -> null
        } ?: return null
        return GarRewrite.Command(text)
    }

    /**
     * The weather of [turn] asked again with what [rest] says instead: a day, a place or both. A
     * [correction]'s bare name is a place ("no, boston").
     */
    private fun weather(turn: GarTurn, rest: String, preposition: String?, today: LocalDate, correction: Boolean): String? {
        // "the next day", "the day after that": from the day the turn asked about
        FollowUpForms.relativeDays(rest)?.takeIf { !correction && preposition in RELATIVE_PREPOSITIONS }
            ?.let { return weatherDaysFrom(turn, it, today) }
        var said = listOfNotNull(preposition, rest).joinToString(" ")
        var asked = WeatherWords.parse("what's the weather $said", today) ?: return null
        if (correction && preposition == null && asked.time == WeatherWhen.Now && asked.place == null &&
            rest.split(" ").none { it in FollowUpForms.META_WORDS }
        ) {
            // "no, boston": a bare name is a place ("no, wait" is none)
            said = "in $rest"
            asked = WeatherWords.parse("what's the weather $said", today) ?: return null
        }
        val newDay = asked.time != WeatherWhen.Now
        val newPlace = asked.place != null
        val oldPlace = turn.slots[WHERE]?.let { GarRewriteText.words(it) ?: return null }
        val topic = topicWords(turn.slots[TOPIC])
        return when {
            newDay && newPlace -> "$topic $said"
            // the day as the sentences say it ("this weekend" for "the weekend")
            newDay -> listOfNotNull(topic, whenWords(asked.time, today) ?: said, oldPlace?.let { "in $it" })
                .joinToString(" ")
            newPlace -> {
                val place = GarRewriteText.words(asked.place) ?: return null
                listOfNotNull(topic, turn.slots[WHEN], "in $place").joinToString(" ")
            }
            else -> null
        }
    }

    /** "What about for the next day": no preposition, or one of a day. */
    private val RELATIVE_PREPOSITIONS = setOf(null, "on", "for")

    /**
     * The weather of [turn] asked again [days] days from the day it asked about ("what's the
     * weather tomorrow", then "the next day": "what's the weather on wednesday"). Now counts as
     * today and a night as its day; a weekend or a week has no next day, and a day the weather
     * sentences can't name (past, or a week away) is no rewrite.
     */
    private fun weatherDaysFrom(turn: GarTurn, days: Int, today: LocalDate): String? {
        val asked = turn.slots[WHEN]?.let { WeatherWords.parse("what's the weather $it", today)?.time }
            ?: WeatherWhen.Now
        val from = when (asked) {
            WeatherWhen.Now, WeatherWhen.Hours -> today
            is WeatherWhen.Day -> asked.date
            is WeatherWhen.Night -> asked.date
            is WeatherWhen.Weekend, is WeatherWhen.Week -> return null
        }
        val day = whenWords(WeatherWhen.Day(from.plusDays(days.toLong())), today) ?: return null
        val oldPlace = turn.slots[WHERE]?.let { GarRewriteText.words(it) ?: return null }
        return listOfNotNull(topicWords(turn.slots[TOPIC]), day, oldPlace?.let { "in $it" }).joinToString(" ")
    }

    /** "What time is it in london", when [rest] is a place GAR knows the time of. */
    private fun timeIn(rest: String, preposition: String?): String? {
        if (preposition != null && preposition != "in") {
            return null
        }
        return rest.takeIf { WorldTimePhrases.timeIn(it) != null && GarRewriteText.safe(it) }
            ?.let { "what time is it in $it" }
    }

    /** How the weather sentences ask about [topic] (a [WeatherTopic] name). */
    private fun topicWords(topic: String?): String = when (topic?.let { name -> WeatherTopic.entries.firstOrNull { it.name == name } }) {
        WeatherTopic.TEMPERATURE -> "what's the temperature"
        WeatherTopic.HIGH -> "what's the high"
        WeatherTopic.LOW -> "what's the low"
        WeatherTopic.RAIN -> "will it rain"
        WeatherTopic.SNOW -> "will it snow"
        WeatherTopic.UMBRELLA -> "do i need an umbrella"
        WeatherTopic.WIND -> "how windy is it"
        else -> "what's the weather"
    }

    /** [time] as the weather sentences say it, or null for now or a day they can't name. */
    fun whenWords(time: WeatherWhen, today: LocalDate): String? = when (time) {
        WeatherWhen.Now, WeatherWhen.Hours -> null
        is WeatherWhen.Day -> when (time.date.toEpochDay() - today.toEpochDay()) {
            0L -> "today"
            1L -> "tomorrow"
            in 2L..6L -> "on " + time.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
            else -> null
        }
        is WeatherWhen.Night -> when (time.date.toEpochDay() - today.toEpochDay()) {
            0L -> "tonight"
            1L -> "tomorrow night"
            else -> null
        }
        // as said, when the sentences read it back as the same days
        is WeatherWhen.Weekend, is WeatherWhen.Week -> listOf("this", "next")
            .map { "$it " + if (time is WeatherWhen.Week) "week" else "weekend" }
            .firstOrNull { WeatherWords.parse("what's the weather $it", today)?.time == time }
    }
}

/**
 * Answers worth another one: a joke, a coin, dice and a random number. "Another one" and "again"
 * ask the same again ("roll 2d6" rolls two dice again).
 */
object RepeatFollowUps : GarFollowUpAdapter {
    const val RANDOM = "random"

    /** The [GarTurn.slots] key of the command that asks again. */
    const val AGAIN = "again"

    override val skillIds = setOf(RANDOM, JokeInfo.id)

    override val onLockScreen: Boolean = true

    override val repeatable: Boolean = true

    override fun remember(answered: GarAnswered): GarTurn {
        val again = when (answered.skillId) {
            JokeInfo.id -> "tell me a joke"
            else -> when (val request = answered.inputData as? RandomRequest) {
                RandomRequest.Coin -> "flip a coin"
                is RandomRequest.Dice ->
                    if (request.count == 1 && request.sides == 6) "roll a die" else "roll ${request.count}d${request.sides}"
                is RandomRequest.Pick -> "pick a random number between ${request.low} and ${request.high}"
                null -> null
            }
        }
        return GarTurn(
            skillId = answered.skillId,
            action = answered.skillId,
            slots = listOfNotNull(again?.let { AGAIN to it }).toMap(),
            facts = GarFacts(),
            command = answered.input,
            kind = GarTurnKind.ANSWER,
        )
    }

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? {
        if (form !is FollowUpForm.Another) {
            return null
        }
        // "another joke" after a joke; "another one" after anything of these
        val nouns = if (turn.turn.skillId == JokeInfo.id) JOKE_WORDS else RANDOM_WORDS
        val what = form.what
        if (what != null && what !in nouns) {
            return null
        }
        return turn.turn.slots[AGAIN]?.let { GarRewrite.Command(it) }
    }

    private val JOKE_WORDS = setOf("one", "joke", "pun", "funny one", "good one")
    private val RANDOM_WORDS = setOf("one", "number", "roll", "flip", "coin", "die", "dice", "random number", "go", "try")
}
