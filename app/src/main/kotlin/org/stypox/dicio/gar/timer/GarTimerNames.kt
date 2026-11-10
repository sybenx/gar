package org.stypox.dicio.gar.timer

import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.Utterances
import org.stypox.dicio.gar.call.GarSlotRewrites
import org.stypox.dicio.util.StringUtils
import kotlin.math.abs

/**
 * Which timer the user means, by its name as said ("the pasta timer", "pasta") or, for one
 * without a name, by how long it was set for ("the ten minute timer"). English, like the rest of
 * GAR's answers.
 */
object GarTimerNames {
    /** Leading and filler words around a timer's name, besides [GarSlotRewrites.timerName]'s. */
    private val FILLER_WORDS = setOf("one", "please", "now")

    /** Words that mean every timer: "cancel all timers", "pause both timers". */
    private val ALL_WORDS = setOf("all", "both", "every", "everything", "each")

    /** Words that may come with [ALL_WORDS]: "all of my timers", "all the timers", "both of them". */
    private val ALL_FILLER_WORDS = setOf("of", "the", "my", "our", "your", "these", "those", "them")

    /** The name as said, without "the", "timer", "one" and the like: "the pasta one" -> "pasta". */
    fun clean(said: String): String =
        Utterances.words(Utterances.normalize(GarSlotRewrites.timerName(said)))
            .filter { it !in FILLER_WORDS }
            .joinToString(" ")

    /**
     * [name] as captured, or null if it names nothing: "my" in "cancel my timer", "the" in "how
     * long is left on the the timer" (Dicio's sentences capture any words there).
     */
    fun said(name: String?): String? =
        name?.takeIf { clean(it).isNotEmpty() || meansAll(it) }

    /** Whether [said], a timer's name as said, means every timer ("all", "all of my", "both"). */
    fun meansAll(said: String?): Boolean {
        val words = Utterances.words(Utterances.normalize(said ?: return false))
            .filter { it != "timer" && it != "timers" }
        return words.any { it in ALL_WORDS } && words.all { it in ALL_WORDS || it in ALL_FILLER_WORDS }
    }

    /**
     * The timer of [timers] that [said] names: the one with that name, the one whose name contains
     * what was said or is part of it ("pasta" for "pasta sauce"), else the timer that was set for
     * the duration [durationOf] reads in [said] ("ten minute"), preferring one without a name, else
     * the closest name with a typo or so ("egs"). Among equally good matches, the last one set.
     *
     * @param durationOf the duration in [said], in milliseconds, or null if it holds none
     */
    fun find(
        timers: List<StartedTimer>,
        said: String,
        durationOf: (String) -> Long? = { null },
    ): StartedTimer? {
        val name = clean(said)
        if (name.isEmpty()) {
            return null
        }
        val named = timers.filter { it.name != null }
        named.lastOrNull { Utterances.normalize(it.name!!) == name }?.let { return it }
        named.lastOrNull {
            val timerName = Utterances.normalize(it.name!!)
            timerName.isNotEmpty() && (KeywordFilter.containsAny(name, listOf(timerName)) ||
                KeywordFilter.containsAny(timerName, listOf(name)))
        }?.let { return it }

        val duration = durationOf(said)
        if (duration != null) {
            val byDuration = timers.filter {
                abs(it.durationMillis - duration) < DURATION_TOLERANCE_MILLIS
            }
            (byDuration.lastOrNull { it.name == null } ?: byDuration.lastOrNull())?.let { return it }
        }

        // a name said a little wrong ("egs"); Dicio's timer skill took any name within a string
        // distance that "tea" was within for "eggs"
        return named.reversed()
            .map { Pair(it, StringUtils.levenshteinDistance(name, it.name!!)) }
            .filter { (timer, distance) -> distance <= maxTypos(timer.name!!) }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    /** One letter wrong in a short name, one in four in a longer one. */
    private fun maxTypos(name: String): Int = maxOf(1, name.length / 4)

    /**
     * Whether [said] is the name of one of [timers], or a part of one, or holds one: stricter than
     * [find], for telling whether an input is about a timer at all ("pause the pasta" while a
     * pasta timer runs, not "pause the music").
     */
    fun namesOneOf(timers: List<StartedTimer>, said: String?): Boolean {
        val name = clean(said ?: return false)
        return name.isNotEmpty() && timers.any { timer ->
            val timerName = Utterances.normalize(timer.name ?: return@any false)
            timerName.isNotEmpty() && (KeywordFilter.containsAny(name, listOf(timerName)) ||
                KeywordFilter.containsAny(timerName, listOf(name)))
        }
    }

    /** Durations are said in whole seconds at most. */
    private const val DURATION_TOLERANCE_MILLIS = 1_000L
}
