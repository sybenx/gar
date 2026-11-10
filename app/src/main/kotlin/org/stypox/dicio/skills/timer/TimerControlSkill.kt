package org.stypox.dicio.skills.timer

import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.SlotResolvers
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.timer.GarStartedTimers
import org.stypox.dicio.gar.timer.StartedTimer
import org.stypox.dicio.sentences.Sentences.TimerControl
import org.stypox.dicio.skills.alarm.AlarmInfo
import org.stypox.dicio.skills.alarm.FiringAlarmCheck
import org.stypox.dicio.skills.alarm.FiringTimers

/** What GAR's timer controls were asked to do. Names are as said, durations in milliseconds. */
sealed interface TimerCommand {
    /** Stop the clock app's ringing timers ([name] as said: the dismiss stops all of them). */
    data class StopRinging(val name: String?) : TimerCommand
    data class Pause(val name: String?) : TimerCommand
    data class Resume(val name: String?) : TimerCommand

    /** Add [millis] to the timer named [name], or take it away if negative. */
    data class AddTime(val millis: Long, val name: String?) : TimerCommand
    data object Show : TimerCommand

    /** "Delete the timer": the one named [name], or asks which when there are several. */
    data class Cancel(val name: String?) : TimerCommand
    data object CancelAll : TimerCommand
}

/**
 * GAR's timer controls (gar/DESIGN.md, "Timers"): stop the clock app's ringing timer, and pause,
 * resume, add or take away time, list the timers, delete one ("delete the timer", which Dicio's
 * cancel sentences don't say), cancel them all. Dicio's timer sentences still set, cancel and
 * query timers ([TimerSkill]); both act on the clock app's timers ([TimerActions]), and what
 * `AlarmClock` has no intent for opens the clock app's timers.
 *
 * While the clock app's timer rings, the words that stop it ([TimerWords.whileRinging]) score
 * best, before every other skill of Dicio's default batch: "stop" means the ringing timer then.
 * Otherwise a sentence only counts for an input about a timer ([TimerWords.isAboutTimer]), and
 * cancelling every timer only for one that says so ([TimerWords.saysAll]).
 *
 * @param startedTimers the timers GAR started, as this process knows them without loading them:
 * [score] mustn't touch the disk
 * @param endedTimers those of them that have ended: their names stop the ringing timer
 * @param timerRinging whether the clock app's timer rings ([FiringTimers])
 * @param alarmRinging whether the clock app's alarm rings too ([GarRinging]): then "stop the
 * alarm" is the alarm skill's, see [TimerWords.whileRinging]
 * @param actions what runs the commands, only looked up when the skill runs
 */
class TimerControlSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<TimerControl>,
    private val startedTimers: () -> List<StartedTimer> = {
        GarStartedTimers.peek()?.current().orEmpty()
    },
    private val endedTimers: () -> List<StartedTimer> = {
        GarStartedTimers.peek()?.ended().orEmpty()
    },
    private val timerRinging: () -> Boolean = { FiringTimers.current() == FiringAlarmCheck.Firing },
    private val alarmRinging: () -> Boolean = { AlarmInfo.id in GarRinging.ringing() },
    private val actions: (SkillContext) -> TimerActions,
) : Skill<TimerCommand?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, TimerCommand?> {
        val timers = startedTimers()
        TimerWords.whileRinging(
            input, timerRinging(), endedTimers().map { it.name }, alarmRinging, wholeDurationOf(ctx),
        )?.let { return Pair(AlwaysBestScore, it) }

        val (score, sentence) = data.score(ctx, input)
        val command = commandOf(sentence)?.let { TimerWords.allWhenPlural(input, it) }
        // "delete the timer" matches "delete all timers" with a word left out
        val takes = command != null && TimerWords.isAboutTimer(input, command, timers) &&
            (command != TimerCommand.CancelAll || TimerWords.saysAll(input))
        return Pair(if (takes) score else AlwaysWorstScore, command)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: TimerCommand?): SkillOutput {
        val command = inputData
            // impossible, since AlwaysWorstScore was used above
            ?: throw IllegalArgumentException("AlwaysWorstScore still triggered generateOutput")
        return actions(ctx).run(command)
    }

    companion object {
        /** The command of [sentence], or null for one without a duration it needs. */
        fun commandOf(sentence: TimerControl): TimerCommand? = when (sentence) {
            is TimerControl.Pause -> TimerCommand.Pause(nameOf(sentence.name))
            is TimerControl.Resume -> TimerCommand.Resume(nameOf(sentence.name))
            is TimerControl.Add -> millisOf(sentence.duration)
                ?.let { TimerCommand.AddTime(it, nameOf(sentence.name)) }
            is TimerControl.Subtract -> millisOf(sentence.duration)
                ?.let { TimerCommand.AddTime(-it, nameOf(sentence.name)) }
            TimerControl.Show -> TimerCommand.Show
            is TimerControl.Cancel -> TimerCommand.Cancel(nameOf(sentence.name))
            TimerControl.CancelAll -> TimerCommand.CancelAll
        }

        /**
         * The duration a whole text is ("for five more minutes"), in milliseconds, or null if it
         * is something else too.
         */
        fun wholeDurationOf(ctx: SkillContext): (String) -> Long? = { text ->
            (SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage).duration(text)
                as? Resolution.Resolved)?.value?.toMillis()
        }

        private fun nameOf(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() }

        private fun millisOf(duration: org.dicio.numbers.unit.Duration?): Long? =
            duration?.toJavaDuration()?.toMillis()?.takeIf { it > 0 }
    }
}
