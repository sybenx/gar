package org.stypox.dicio.skills.stopwatch

import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.sentences.Sentences.Stopwatch

/**
 * The stopwatch: start, stop, resume, reset, lap and elapsed time. The model reaches it through
 * GAR's LLM-only `stopwatch` tool, whose resolved action [inputFromSlots] passes on; the tool
 * doesn't need sentences. English also has sentences: without them, Dicio's timer sentences took
 * "stop the stopwatch" and "cancel the stopwatch" (as a timer called "stopwatch") before the
 * model saw them. A sentence only matches an input that names the stopwatch (or a lap, for the lap
 * sentences), see [mentionsStopwatch].
 *
 * @param data null for languages without stopwatch sentences: then only GAR reaches the skill
 * @param controller the app's stopwatch, only looked up when the skill runs
 */
class StopwatchSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Stopwatch>?,
    private val controller: () -> StopwatchController,
) : Skill<StopwatchAction>(correspondingSkillInfo, data?.specificity ?: Specificity.LOW), GarToolSkill {

    override fun score(ctx: SkillContext, input: String): Pair<Score, StopwatchAction> {
        if (data == null) {
            return Pair(AlwaysWorstScore, StopwatchAction.ELAPSED)
        }
        val (score, sentence) = data.score(ctx, input)
        val action = actionOf(sentence)
        return Pair(if (mentionsStopwatch(input, action)) score else AlwaysWorstScore, action)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: StopwatchAction): SkillOutput {
        val controller = controller()
        val event = controller.perform(inputData)
        return StopwatchOutput(event, controller.state, controller.clock)
    }

    override fun inputFromSlots(slots: Map<String, Any?>): StopwatchAction {
        val value = slots[ACTION_SLOT] as? String
        return StopwatchAction.fromSlotValue(value)
            ?: throw IllegalArgumentException("Unknown stopwatch action $value")
    }

    companion object {
        const val ACTION_SLOT = "action"

        /**
         * Whether [input] names what the sentence of [action] is about. Dicio's matcher also scores
         * sentences with a word left out, and without the stopwatch the rest scores above Dicio's
         * thresholds: "keep going" and "keep the music going" resumed the stopwatch, "set the timer
         * to zero" reset it, "how long has the timer been running" told its time.
         */
        fun mentionsStopwatch(input: String, action: StopwatchAction): Boolean {
            val words = Utterances.words(Utterances.normalize(input))
            return words.any { it in STOPWATCH_WORDS } ||
                words.zipWithNext().any { (a, b) -> a == "stop" && b in WATCH_WORDS } ||
                (action == StopwatchAction.LAP && words.any { it in LAP_WORDS })
        }

        private val STOPWATCH_WORDS = setOf("stopwatch", "stopwatches", "stopwatch's")
        private val WATCH_WORDS = setOf("watch", "watches", "watch's")
        private val LAP_WORDS = setOf("lap", "laps")

        fun actionOf(sentence: Stopwatch): StopwatchAction = when (sentence) {
            Stopwatch.Start -> StopwatchAction.START
            Stopwatch.Stop -> StopwatchAction.STOP
            Stopwatch.Resume -> StopwatchAction.RESUME
            Stopwatch.Reset -> StopwatchAction.RESET
            Stopwatch.Lap -> StopwatchAction.LAP
            Stopwatch.Elapsed -> StopwatchAction.ELAPSED
        }
    }
}
