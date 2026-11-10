package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.ControlVerb
import cc.aaro.gar.router.followup.CorrectionKind
import cc.aaro.gar.router.followup.FollowUpForm
import cc.aaro.gar.router.resolve.ResolvedTime
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.TEST_TODAY
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.gar.followup.adapters.ClockFollowUps
import org.stypox.dicio.gar.shouldBeTakenBy
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.alarm.AlarmOutput
import org.stypox.dicio.skills.timer.TimerAnswer
import org.stypox.dicio.skills.timer.TimerAnswerOutput
import org.stypox.dicio.skills.timer.TimerCommand
import java.time.DayOfWeek
import java.time.LocalTime

private const val MINUTE = 60_000L

private fun alarm(time: LocalTime, days: Set<DayOfWeek> = setOf(), label: String? = null) = rememberedBy(
    ClockFollowUps,
    answered(
        "alarm", "set an alarm", null,
        AlarmOutput.Set(ResolvedTime(time, days), label, null, TEST_TODAY, englishFormatter()),
    ),
)

private fun timer(name: String?, minutes: Long, ageMillis: Long = 1_000L) = rememberedBy(
    ClockFollowUps,
    answered("timer", "set a timer", null, TimerAnswerOutput(TimerAnswer.Started(name, minutes * MINUTE))),
    ageMillis,
)

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

class ClockFollowUpsTest : StringSpec({
    "an alarm is modifiable and remembers its time, days and label" {
        val turn = alarm(LocalTime.of(7, 0), WEEKDAYS, "gym")
        turn.turn.kind shouldBe GarTurnKind.MODIFIABLE
        turn.turn.facts.alarm shouldBe GarAlarmFact(LocalTime.of(7, 0), WEEKDAYS, "gym")
    }

    "cancel it and turn it off cancel the alarm by its time" {
        val turn = alarm(LocalTime.of(7, 0))
        rewriteOf(ClockFollowUps, FollowUpForm.Remove(delete = false), turn).command shouldBe "cancel my 7 am alarm"
        rewriteOf(ClockFollowUps, FollowUpForm.Remove(delete = true), turn).command shouldBe "cancel my 7 am alarm"
        rewriteOf(ClockFollowUps, FollowUpForm.Switch(on = false, back = false), turn).command shouldBe
            "cancel my 7 am alarm"
        rewriteOf(ClockFollowUps, FollowUpForm.Switch(on = true, back = false), turn).shouldBeNull()
        shouldBeTakenBy("cancel my 7 am alarm", "alarm", mapOf("time" to "7 am"))
        shouldBeTakenBy("cancel my 7:30 pm alarm", "alarm", mapOf("time" to "7:30 pm"))
    }

    "change it to 8 keeps the half of the day and the days" {
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("8"), alarm(LocalTime.of(7, 0))).command shouldBe
            "change my 7 am alarm to 8 am"
        shouldBeTakenBy("change my 7 am alarm to 8 am", "alarm", mapOf("from" to "7 am", "to" to "8 am"))
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("8"), alarm(LocalTime.of(19, 0))).command shouldBe
            "change my 7 pm alarm to 8 pm"
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("8:30 pm"), alarm(LocalTime.of(7, 0))).command shouldBe
            "change my 7 am alarm to 8:30 pm"
        // a weekday alarm stays a weekday alarm (its label goes, as with the full command)
        val weekday = rewriteOf(ClockFollowUps, FollowUpForm.SetTo("8"), alarm(LocalTime.of(7, 0), WEEKDAYS, "gym"))
            .command
        weekday shouldBe "change my 7 am alarm to 8 am on weekdays"
        shouldBeTakenBy(weekday!!, "alarm", mapOf("from" to "7 am", "to" to "8 am on weekdays"))
    }

    "no, 8 pm corrects the alarm; a place is no time" {
        val turn = alarm(LocalTime.of(19, 0))
        rewriteOf(ClockFollowUps, FollowUpForm.Correction("8 pm", CorrectionKind.NO), turn).command shouldBe
            "change my 7 pm alarm to 8 pm"
        rewriteOf(ClockFollowUps, FollowUpForm.Correction("boston", CorrectionKind.NO), turn).shouldBeNull()
        rewriteOf(ClockFollowUps, FollowUpForm.Correction(null, CorrectionKind.OTHER), turn).shouldBeNull()
    }

    "a timer is cancelled by its name, or by its length" {
        val pasta = timer("pasta", 8)
        pasta.turn.facts.timer shouldBe GarTimerFact("pasta", java.time.Duration.ofMinutes(8))
        rewriteOf(ClockFollowUps, FollowUpForm.Remove(delete = false), pasta).command shouldBe "cancel the pasta timer"
        shouldBeTakenBy("cancel the pasta timer", "timer", mapOf("name" to "pasta"))
        val unnamed = timer(null, 8)
        rewriteOf(ClockFollowUps, FollowUpForm.Remove(delete = false), unnamed).command shouldBe
            "cancel the 8 minute timer"
        shouldBeTakenBy("cancel the 8 minute timer", "timer", mapOf("name" to "8 minute"))
        // a name that would split the sentence goes by the length
        rewriteOf(ClockFollowUps, FollowUpForm.Remove(delete = false), timer("eggs and toast", 5)).command shouldBe
            "cancel the 5 minute timer"
        rewriteOf(ClockFollowUps, FollowUpForm.Remove(delete = false), timer(null, 60)).command shouldBe
            "cancel the 1 hour timer"
    }

    "time goes on a timer through the timer controls" {
        rewriteOf(ClockFollowUps, FollowUpForm.AddMore("5 minutes", less = false), timer(null, 10)).command shouldBe
            "add 5 minutes to the timer"
        shouldBeTakenBy("add 5 minutes to the timer", "timer_control")
        rewriteOf(ClockFollowUps, FollowUpForm.AddMore("2 minutes", less = true), timer("pasta", 10)).command shouldBe
            "take 2 minutes off the pasta timer"
        rewriteOf(ClockFollowUps, FollowUpForm.Control(ControlVerb.PAUSE), timer("pasta", 10)).command shouldBe
            "pause the pasta timer"
        shouldBeTakenBy("pause the pasta timer", "timer_control")
        // "make it 15 minutes": the difference, since the clock app can't set a timer's length
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("15 minutes"), timer(null, 10)).command shouldBe
            "add 5 minutes to the timer"
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("8"), timer("pasta", 10)).command shouldBe
            "take 2 minutes off the pasta timer"
        shouldBeTakenBy("take 2 minutes off the pasta timer", "timer_control")
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("1 hour"), timer(null, 10)).command shouldBe
            "add 50 minutes to the timer"
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("10"), timer(null, 10)).shouldBeNull()
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("pasta"), timer(null, 10)).shouldBeNull()
    }

    "make it 10 only right after the timer was set, and a bare number only after minutes" {
        // two minutes in, adding the difference to the length set would leave about 8 minutes
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("10"), timer(null, 5, ageMillis = 2 * MINUTE)).shouldBeNull()
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("10"), timer(null, 5, ageMillis = 5_000L)).command shouldBe
            "add 5 minutes to the timer"
        // "make it 90" after a 1 hour timer is no 90 hours
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("90"), timer(null, 60)).shouldBeNull()
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("90 minutes"), timer(null, 60)).command shouldBe
            "add 30 minutes to the timer"
        val seconds = rememberedBy(
            ClockFollowUps,
            answered("timer", "set a timer", null, TimerAnswerOutput(TimerAnswer.Started(null, 90_000L))),
        )
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("30"), seconds).shouldBeNull()
        rewriteOf(ClockFollowUps, FollowUpForm.SetTo("25 hours"), timer(null, 10)).shouldBeNull()
    }

    "a control of an unnamed timer keeps the timer as the referent" {
        val added = rememberedBy(
            ClockFollowUps,
            answered("timer_control", "add 5 minutes to the timer", TimerCommand.AddTime(5 * MINUTE, null), FakeOutput(null)),
        )
        added.turn.kind shouldBe GarTurnKind.MODIFIABLE
        rewriteOf(ClockFollowUps, FollowUpForm.Remove(delete = false), added).command shouldBe "cancel the timer"
        val shown = rememberedBy(
            ClockFollowUps,
            answered("timer_control", "show my timers", TimerCommand.Show, FakeOutput(null)),
        )
        shown.turn.kind shouldBe GarTurnKind.ANSWER
    }

    "and the pasta timer asks the timer query again" {
        val query = rememberedBy(
            ClockFollowUps,
            answered("timer", "how much time is left on the timer", null, TimerAnswerOutput(TimerAnswer.Left(null, MINUTE))),
        )
        rewriteOf(ClockFollowUps, FollowUpForm.WhatAbout("the pasta timer", null, bareAnd = true), query).command shouldBe
            "how much time is left on the pasta timer"
        shouldBeTakenBy("how much time is left on the pasta timer", "timer")
        rewriteOf(ClockFollowUps, FollowUpForm.WhatAbout("tomorrow", null, bareAnd = false), query).shouldBeNull()
    }

    "the stopwatch pauses, resumes and resets" {
        val turn = rememberedBy(
            ClockFollowUps,
            answered("stopwatch", "start the stopwatch", Sentences.Stopwatch.Start, FakeOutput(null)),
        )
        rewriteOf(ClockFollowUps, FollowUpForm.Control(ControlVerb.PAUSE), turn).command shouldBe "stop the stopwatch"
        shouldBeTakenBy("stop the stopwatch", "stopwatch")
        rewriteOf(ClockFollowUps, FollowUpForm.Control(ControlVerb.RESET), turn).command shouldBe "reset the stopwatch"
        shouldBeTakenBy("reset the stopwatch", "stopwatch")
    }

    "spoken times and days" {
        ClockFollowUps.spokenTime(LocalTime.of(0, 0)) shouldBe "12 am"
        ClockFollowUps.spokenTime(LocalTime.of(12, 5)) shouldBe "12:05 pm"
        ClockFollowUps.withDays("8 am", DayOfWeek.entries.toSet()) shouldBe "every day at 8 am"
        ClockFollowUps.withDays("8 am", setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) shouldBe "every weekend at 8 am"
        ClockFollowUps.withDays("6 am", setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)) shouldBe
            "every monday and wednesday at 6 am"
    }
})
