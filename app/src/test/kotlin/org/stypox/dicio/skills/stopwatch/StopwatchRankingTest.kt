package org.stypox.dicio.skills.stopwatch

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.skills.alarm.AlarmInfo
import org.stypox.dicio.skills.alarm.AlarmSkill
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.current_time.CurrentTimeSkill
import org.stypox.dicio.skills.date.DateInfo
import org.stypox.dicio.skills.date.DateSkill
import org.stypox.dicio.skills.listening.ListeningInfo
import org.stypox.dicio.skills.listening.ListeningSkill
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.media.MediaSkill
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.search.SearchSkill
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.timer.TimerSkill

private fun stopwatch(language: String = "en") = StopwatchSkill(
    StopwatchInfo, Sentences.Stopwatch[language],
) { throw AssertionError("not run") }

/** The skills whose English sentences are near the stopwatch's, ranked like the evaluator does. */
private fun ranker(withStopwatch: StopwatchSkill = stopwatch()): SkillRanker {
    val yesNo = Sentences.UtilYesNo["en"]!!
    return SkillRanker(
        listOf(
            SearchSkill(SearchInfo, Sentences.Search["en"]!!),
            TimerSkill(TimerInfo, Sentences.Timer["en"]!!, yesNo),
            CurrentTimeSkill(CurrentTimeInfo, Sentences.CurrentTime["en"]!!),
            MediaSkill(MediaInfo, Sentences.Media["en"]!!),
            ListeningSkill(
                ListeningInfo(UserSettingsModule.newDataStoreForPreviews()),
                Sentences.Listening["en"]!!,
            ),
            AlarmSkill(AlarmInfo, Sentences.Alarm["en"]!!),
            DateSkill(DateInfo, Sentences.Date["en"]),
            withStopwatch,
        ),
        MockSkill(Specificity.LOW, 0.0f),
    )
}

private fun winner(ranker: SkillRanker, input: String): Pair<String?, Any?> {
    val best = ranker.getBest(FakeSkillContext().forInput(input), input)
    return Pair(best?.skill?.correspondingSkillInfo?.id, best?.inputData)
}

class StopwatchRankingTest : StringSpec({
    "the stopwatch sentences win over the timer's" {
        val ranker = ranker()
        val cases = mapOf(
            // Dicio's timer cancel sentence took these as a timer called "stopwatch"
            "stop the stopwatch" to StopwatchAction.STOP,
            "cancel the stopwatch" to StopwatchAction.RESET,
            "pause the stopwatch" to StopwatchAction.STOP,
            "stop my stopwatch" to StopwatchAction.STOP,
            "turn off the stopwatch" to StopwatchAction.STOP,
            "end the stopwatch" to StopwatchAction.STOP,
            "start the stopwatch" to StopwatchAction.START,
            "start a stopwatch" to StopwatchAction.START,
            "start a new stop watch" to StopwatchAction.START,
            "resume the stopwatch" to StopwatchAction.RESUME,
            "keep the stopwatch going" to StopwatchAction.RESUME,
            "reset the stopwatch" to StopwatchAction.RESET,
            "clear the stopwatch" to StopwatchAction.RESET,
            "mark a lap" to StopwatchAction.LAP,
            "new lap on the stopwatch" to StopwatchAction.LAP,
            "how long has the stopwatch been running" to StopwatchAction.ELAPSED,
            "what's the time on the stopwatch" to StopwatchAction.ELAPSED,
            "check the stopwatch" to StopwatchAction.ELAPSED,
        )
        for ((input, action) in cases) {
            withClue(input) { winner(ranker, input) shouldBe Pair(StopwatchInfo.id, action) }
        }
    }

    "without the stopwatch sentences the timer took them (the problem they fix)" {
        val ranker = ranker(stopwatch(language = "xx"))
        for (input in listOf("stop the stopwatch", "cancel the stopwatch")) {
            withClue(input) { winner(ranker, input).first shouldBe TimerInfo.id }
        }
    }

    "timers, alarms, the time and media keep their sentences" {
        val ranker = ranker()
        val cases = listOf(
            "stop the timer" to TimerInfo.id,
            "cancel the pasta timer" to TimerInfo.id,
            "set a timer for ten minutes" to TimerInfo.id,
            "how much time is left on the timer" to TimerInfo.id,
            "what time is it" to CurrentTimeInfo.id,
            "stop the music" to MediaInfo.id,
            "pause the song" to MediaInfo.id,
            "stop listening" to "listening",
            "cancel my alarm" to AlarmInfo.id,
            "what's the date" to DateInfo.id,
        )
        for ((input, skill) in cases) {
            withClue(input) { winner(ranker, input).first shouldBe skill }
        }
    }

    "the stopwatch doesn't take other commands" {
        val ranker = ranker()
        val inputs = listOf(
            "stop", "start", "reset", "stop the timer", "start a timer", "watch a movie",
            "stop watching", "set the clock", "check the weather", "how long is a marathon",
            "what time is it", "lap", "lap swimming near me",
            // without the stopwatch in them, the stopwatch sentences scored these above Dicio's
            // thresholds (StopwatchSkill.mentionsStopwatch)
            "keep going", "keep the music going", "how long has the timer been running",
            "set the timer to zero", "how long has it been", "what's the time on my watch",
        )
        val stolen = inputs.associateWith { winner(ranker, it).first }
            .filterValues { it == StopwatchInfo.id }
        stolen shouldBe mapOf()
    }
})
