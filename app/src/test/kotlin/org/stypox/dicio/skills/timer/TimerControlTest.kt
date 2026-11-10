package org.stypox.dicio.skills.timer

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.gar.timer.Moment
import org.stypox.dicio.gar.timer.StartedTimer
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import java.time.Duration
import org.dicio.numbers.unit.Duration as DicioDuration

private const val MINUTE = 60_000L

/**
 * Timer controls that know the started [timers]; the clock app's timer rings when one of them has
 * ended ([ringing]).
 */
private fun controlSkill(timers: () -> List<StartedTimer>) = TimerControlSkill(
    TimerControlInfo, Sentences.TimerControl["en"]!!,
    startedTimers = timers,
    endedTimers = { timers().filter { it.end.elapsed <= 0 } },
    timerRinging = { timers().any { it.end.elapsed <= 0 } },
    alarmRinging = { false },
) { throw AssertionError("not run") }

/** Every English skill, with timer controls that know [timers], as a new install ranks them. */
private fun ranker(timers: List<StartedTimer> = listOf()) = SkillRanker(
    GarOnlineSkills.forRanking(
        allEnglishSkills().map { if (it is TimerControlSkill) controlSkill { timers } else it },
        UserSettingsSerializer.defaultValue.enabledSkillsMap,
    ),
    MockSkill(Specificity.LOW, 0.0f),
)

private fun winner(ranker: SkillRanker, input: String): Pair<String?, Any?> {
    val best = ranker.getBest(FakeSkillContext().forInput(input), input)
    return Pair(best?.skill?.correspondingSkillInfo?.id, best?.inputData)
}

/** A timer GAR started that still counts. */
private fun running(name: String?) =
    StartedTimer(name, 10 * MINUTE, Moment(10 * MINUTE, 10 * MINUTE, 1))

/** A timer GAR started that has ended: the clock app's timer rings. */
private fun ringing(name: String?) = StartedTimer(name, MINUTE, Moment(0, 0, 1))

class TimerControlTest : StringSpec({
    "the timer controls' sentences win about a running timer" {
        val ranker = ranker(listOf(running("pasta")))
        for ((input, command) in listOf(
            "pause the timer" to TimerCommand.Pause(null),
            "pause my timer" to TimerCommand.Pause(null),
            "pause the pasta timer" to TimerCommand.Pause("pasta"),
            "pause the pasta" to TimerCommand.Pause("pasta"),
            // the plural means every timer, without asking which
            "pause the timers" to TimerCommand.Pause("all"),
            "add a minute to the timers" to TimerCommand.AddTime(MINUTE, "all"),
            "resume the timer" to TimerCommand.Resume(null),
            "continue the pasta timer" to TimerCommand.Resume("pasta"),
            "unpause the timer" to TimerCommand.Resume(null),
            "add two minutes to the pasta timer" to TimerCommand.AddTime(2 * MINUTE, "pasta"),
            "add 1 minute to the timer" to TimerCommand.AddTime(MINUTE, null),
            "add five minutes" to TimerCommand.AddTime(5 * MINUTE, null),
            "take two minutes off the timer" to TimerCommand.AddTime(-2 * MINUTE, null),
            "remove thirty seconds from the pasta timer" to TimerCommand.AddTime(-30_000, "pasta"),
            "take off a minute" to TimerCommand.AddTime(-MINUTE, null),
            "add a minute" to TimerCommand.AddTime(MINUTE, null),
            "show my timers" to TimerCommand.Show,
            "show me all my timers" to TimerCommand.Show,
            "what timers do i have" to TimerCommand.Show,
            "do i have any timers running" to TimerCommand.Show,
            "delete all timers" to TimerCommand.CancelAll,
            "clear all of my timers" to TimerCommand.CancelAll,
            // one timer, with the words Dicio's cancel sentences don't have
            "delete the timer" to TimerCommand.Cancel(null),
            "remove my timer" to TimerCommand.Cancel(null),
            "clear the pasta timer" to TimerCommand.Cancel("pasta"),
            "switch off the timer" to TimerCommand.Cancel(null),
            "turn the timer off" to TimerCommand.Cancel(null),
            "delete the timers" to TimerCommand.Cancel("all"),
        )) {
            withClue(input) { winner(ranker, input) shouldBe Pair(TimerControlInfo.id, command) }
        }
    }

    "Dicio's timer sentences keep set, cancel and how long is left" {
        val ranker = ranker(listOf(running("pasta")))
        for (input in listOf(
            "set a timer for ten minutes", "start a pasta timer for eight minutes",
            "cancel the pasta timer", "stop the timer", "how much time is left on the timer",
            "when will the pasta timer expire",
        )) {
            withClue(input) { winner(ranker, input).first shouldBe TimerInfo.id }
        }
        // both cancel every timer
        winner(ranker, "cancel all timers").first shouldBeIn listOf(TimerInfo.id, TimerControlInfo.id)
    }

    "the timer controls take nothing else" {
        val withoutTimers = ranker()
        val withPasta = ranker(listOf(running("pasta")))
        val inputs = listOf(
            "pause", "pause the music", "resume the song", "continue", "keep going",
            "show my alarms", "show my notifications", "add two minutes", "add an event",
            "add milk to the list", "take a picture", "remove the alarm", "stop", "ok",
            "dismiss", "turn it off", "snooze", "what time is it", "pause the video",
            "what s my battery", "show me the time", "cancel all alarms",
        )
        for (ranker in listOf(withoutTimers, withPasta)) {
            val stolen = inputs.associateWith { winner(ranker, it).first }
                .filterValues { it == TimerControlInfo.id }
                // with a timer, "add two minutes" adds them to it
                .filterKeys { !(ranker === withPasta && it == "add two minutes") }
            stolen shouldBe mapOf()
        }
    }

    "while a timer rings, its stop words win over every other skill" {
        val ranker = ranker(listOf(ringing("pasta"), running("eggs")))
        for ((input, command) in listOf(
            "stop" to TimerCommand.StopRinging(null),
            "stop it" to TimerCommand.StopRinging(null),
            "ok" to TimerCommand.StopRinging(null),
            "okay stop" to TimerCommand.StopRinging(null),
            "dismiss" to TimerCommand.StopRinging(null),
            "cancel" to TimerCommand.StopRinging(null),
            "turn it off" to TimerCommand.StopRinging(null),
            "shut up" to TimerCommand.StopRinging(null),
            "be quiet" to TimerCommand.StopRinging(null),
            "that's enough" to TimerCommand.StopRinging(null),
            "stop the timer" to TimerCommand.StopRinging(null),
            "stop the alarm" to TimerCommand.StopRinging(null),
            "silence the timer please" to TimerCommand.StopRinging(null),
            "stop the pasta timer" to TimerCommand.StopRinging("pasta"),
            "ok thanks" to TimerCommand.StopRinging(null),
            "okay thank you" to TimerCommand.StopRinging(null),
            "can you turn it off" to TimerCommand.StopRinging(null),
            "just stop" to TimerCommand.StopRinging(null),
            "snooze" to TimerCommand.AddTime(MINUTE, null),
            "snooze for five minutes" to TimerCommand.AddTime(5 * MINUTE, null),
            "snooze it ten minutes" to TimerCommand.AddTime(10 * MINUTE, null),
            "snooze the pasta timer for another two minutes" to TimerCommand.AddTime(2 * MINUTE, "pasta"),
            "snooze again" to TimerCommand.AddTime(MINUTE, null),
        )) {
            withClue(input) { winner(ranker, input) shouldBe Pair(TimerControlInfo.id, command) }
        }

        // other commands, the other timer and "never mind" aren't stop words
        for (input in listOf(
            "stop the music", "never mind", "cancel all timers", "stop the eggs timer",
            "what time is it", "stop listening", "oh thanks", "snooze for five minutes and call mom",
        )) {
            withClue(input) {
                (winner(ranker, input).second is TimerCommand.StopRinging) shouldBe false
                (winner(ranker, input).second is TimerCommand.AddTime) shouldBe false
            }
        }
    }

    "a delete without all is no cancel of every timer" {
        val ranker = ranker(listOf(running("pasta"), running(null)))
        for (input in listOf(
            "delete the timer", "reset my timer", "remove my timer", "clear timer", "delete timer",
            "reset the timer",
        )) {
            withClue(input) { winner(ranker, input).second shouldNotBe TimerCommand.CancelAll }
        }
    }

    "a sentence only counts for an input about a timer" {
        val skill = controlSkill { listOf() }
        for (input in listOf("pause the music", "show my alarms", "add two minutes")) {
            withClue(input) {
                skill.score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
        val pasta = controlSkill { listOf(running("pasta")) }
        pasta.score(FakeSkillContext().forInput("pause the pasta"), "pause the pasta").first shouldNotBe
            AlwaysWorstScore
        pasta.score(FakeSkillContext().forInput("pause the music"), "pause the music").first shouldBe
            AlwaysWorstScore
        pasta.score(FakeSkillContext().forInput("stop"), "stop").first shouldBe AlwaysWorstScore

        val ringingPasta = controlSkill { listOf(ringing("pasta")) }
        ringingPasta.score(FakeSkillContext().forInput("stop"), "stop") shouldBe
            Pair(AlwaysBestScore, TimerCommand.StopRinging(null))
    }
})
