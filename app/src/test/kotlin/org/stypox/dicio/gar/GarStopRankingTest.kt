package org.stypox.dicio.gar

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldNotBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.gar.timer.Moment
import org.stypox.dicio.gar.timer.StartedTimer
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Alarm
import org.stypox.dicio.sentences.Sentences.Conversation
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.alarm.AlarmInfo
import org.stypox.dicio.skills.alarm.AlarmSentenceMatch
import org.stypox.dicio.skills.alarm.AlarmSkill
import org.stypox.dicio.skills.alarm.FiringAlarmCheck
import org.stypox.dicio.skills.conversation.ConversationInfo
import org.stypox.dicio.skills.conversation.ConversationMatch
import org.stypox.dicio.skills.conversation.ConversationSkill
import org.stypox.dicio.skills.timer.TimerCommand
import org.stypox.dicio.skills.timer.TimerControlInfo
import org.stypox.dicio.skills.timer.TimerControlSkill
import org.stypox.dicio.skills.timer.TimerInfo
import java.io.File

private const val MINUTE = 60_000L

/** A timer GAR started in the clock app, which has ended (and rings, if the phone says so). */
private fun ringingTimer(name: String?) = StartedTimer(name, MINUTE, Moment(0, 0, 1))

/** A timer GAR started in the clock app, still counting. */
private fun runningTimer(name: String?) =
    StartedTimer(name, 10 * MINUTE, Moment(10 * MINUTE, 10 * MINUTE, 1))

/**
 * Every English skill as a new install ranks them, while the timers GAR started are [timers], the
 * clock app's timer rings or not ([timer]) and its alarm rings or not ([alarm]). The skills that
 * ask what rings get it from here instead of `GarRinging` and the notifications, as on the phone,
 * where the timer and the alarm register it.
 */
private class Phone(
    val timers: List<StartedTimer> = listOf(),
    val timer: Boolean = false,
    val alarm: Boolean = false,
) {
    private val anyRinging: () -> Boolean = { timer || alarm }

    val ranker = SkillRanker(
        GarOnlineSkills.forRanking(
            allEnglishSkills().map(::withRinging),
            UserSettingsSerializer.defaultValue.enabledSkillsMap,
        ),
        MockSkill(Specificity.LOW, 0.0f),
    )

    private fun withRinging(skill: Skill<*>): Skill<*> = when (skill) {
        is AlarmSkill -> AlarmSkill(
            AlarmInfo, Sentences.Alarm["en"]!!,
            firingAlarm = { if (alarm) FiringAlarmCheck.Firing else FiringAlarmCheck.None },
        )
        is ConversationSkill ->
            ConversationSkill(ConversationInfo, Sentences.Conversation["en"]!!, isRinging = anyRinging)
        is TimerControlSkill -> TimerControlSkill(
            TimerControlInfo, Sentences.TimerControl["en"]!!,
            startedTimers = { timers },
            // the ringing ones end at the start of the fake boot
            endedTimers = { timers.filter { it.end.elapsed <= 0 } },
            timerRinging = { timer }, alarmRinging = { alarm },
        ) { throw AssertionError("not run") }
        else -> skill
    }

    fun winner(input: String): Pair<String?, Any?> {
        val best = ranker.getBest(FakeSkillContext().forInput(input), input)
        return Pair(best?.skill?.correspondingSkillInfo?.id, best?.inputData)
    }

    fun skillOf(input: String): String? = winner(input).first
}

private fun alarmSentence(data: Any?) = data.shouldBeInstanceOf<AlarmSentenceMatch>().sentence

/**
 * Who takes "stop" (gar/DESIGN.md, "Stop: who takes it"): the clock app's ringing timer first, then the
 * clock app's ringing alarm, then GAR's conversation skill. Also the inputs that two of the
 * timers', the alarm's, the conversation's and the everyday answers' skills could both take.
 */
class GarStopRankingTest : StringSpec({
    "nothing rings: stop is the conversation's, snooze the alarm's (to say nothing rings)" {
        val phone = Phone(timers = listOf(runningTimer("pasta")))
        for (input in listOf(
            "stop", "ok stop", "stop it", "be quiet", "shut up", "that's enough", "never mind",
            "cancel", "that's all", "thanks, that's it", "repeat that",
        )) {
            withClue(input) { phone.skillOf(input) shouldBe ConversationInfo.id }
        }
        phone.winner("stop").second shouldBe ConversationMatch(Conversation.Stop, silences = true)
        alarmSentence(phone.winner("snooze").also { it.first shouldBe AlarmInfo.id }.second) shouldBe
            Alarm.Snooze(null)
        // the ringing words alone are nobody's
        for (input in listOf("dismiss", "turn it off", "i'm up", "ok")) {
            withClue(input) {
                phone.skillOf(input) shouldNotBeIn listOf(TimerControlInfo.id, AlarmInfo.id)
            }
        }
    }

    "the clock app's ringing timer takes stop first" {
        val phone = Phone(timers = listOf(ringingTimer("pasta"), runningTimer("eggs")), timer = true)
        for ((input, command) in listOf(
            "stop" to TimerCommand.StopRinging(null),
            "ok stop" to TimerCommand.StopRinging(null),
            "shut up" to TimerCommand.StopRinging(null),
            "be quiet" to TimerCommand.StopRinging(null),
            "cancel" to TimerCommand.StopRinging(null),
            "dismiss" to TimerCommand.StopRinging(null),
            "turn it off" to TimerCommand.StopRinging(null),
            // a ringing timer is often called an alarm
            "stop the alarm" to TimerCommand.StopRinging(null),
            "stop the timer" to TimerCommand.StopRinging(null),
            "stop the pasta timer" to TimerCommand.StopRinging("pasta"),
            "snooze" to TimerCommand.AddTime(MINUTE, null),
            "ok thanks" to TimerCommand.StopRinging(null),
            "just stop" to TimerCommand.StopRinging(null),
            // the alarm's snooze sentence would take these, and say that no alarm rings
            "snooze for five minutes" to TimerCommand.AddTime(5 * MINUTE, null),
            "snooze it ten minutes" to TimerCommand.AddTime(10 * MINUTE, null),
        )) {
            withClue(input) { phone.winner(input) shouldBe Pair(TimerControlInfo.id, command) }
        }
        // the conversation keeps what isn't for what rings, other skills their commands
        for ((input, skill) in listOf(
            "never mind" to ConversationInfo.id, "that's all" to ConversationInfo.id,
            "repeat that" to ConversationInfo.id, "stop the music" to "media",
            "stop listening" to "listening", "thank you" to "small_talk",
        )) {
            withClue(input) { phone.skillOf(input) shouldBe skill }
        }
    }

    "then the clock app's ringing alarm" {
        val phone = Phone(timers = listOf(runningTimer("pasta")), alarm = true)
        for (input in listOf(
            "stop", "ok stop", "shut up", "be quiet", "turn it off", "dismiss", "i'm up",
            "stop the alarm", "cancel", "cancel it", "just stop", "stop stop", "alright stop", "ok",
            "alarm off", "mute it",
        )) {
            withClue(input) {
                val (skill, data) = phone.winner(input)
                skill shouldBe AlarmInfo.id
                alarmSentence(data) shouldBe Alarm.Stop
            }
        }
        alarmSentence(phone.winner("snooze").second) shouldBe Alarm.Snooze(null)
        alarmSentence(phone.winner("five more minutes").second) shouldBe Alarm.Snooze("five more minutes")
        // cancel sentences, which stop the ringing alarm (AlarmSkill.cancel)
        alarmSentence(phone.winner("turn off my alarm").second) shouldBe Alarm.Cancel(null)
        alarmSentence(phone.winner("turn the alarm off").second) shouldBe Alarm.Cancel(null)
        // the running timer and the conversation keep theirs
        phone.skillOf("stop the timer") shouldBe TimerInfo.id
        phone.skillOf("pause the timer") shouldBe TimerControlInfo.id
        phone.skillOf("never mind") shouldBe ConversationInfo.id
        // "five more minutes" is no snooze while nothing rings
        Phone().skillOf("five more minutes") shouldNotBe AlarmInfo.id
    }

    "with both ringing, stop stops the timers first, and the alarm by its name" {
        val both = Phone(timers = listOf(ringingTimer("pasta")), timer = true, alarm = true)
        for ((input, command) in listOf(
            "stop" to TimerCommand.StopRinging(null),
            "turn it off" to TimerCommand.StopRinging(null),
            "stop the timer" to TimerCommand.StopRinging(null),
            "stop the pasta timer" to TimerCommand.StopRinging("pasta"),
            "snooze" to TimerCommand.AddTime(MINUTE, null),
        )) {
            withClue(input) { both.winner(input) shouldBe Pair(TimerControlInfo.id, command) }
        }
        for ((input, sentence) in listOf(
            "stop the alarm" to Alarm.Stop,
            "silence the alarm" to Alarm.Stop,
            "snooze the alarm" to Alarm.Snooze(null),
            "turn off the alarm" to Alarm.Cancel(null),
            "dismiss the alarm" to Alarm.Cancel(null),
        )) {
            withClue(input) {
                val (skill, data) = both.winner(input)
                skill shouldBe AlarmInfo.id
                alarmSentence(data) shouldBe sentence
            }
        }

        alarmSentence(both.winner("snooze the alarm for ten minutes").second)
            .shouldBeInstanceOf<Alarm.Snooze>().duration?.trim() shouldBe "ten minutes"

        // once the timer stopped, the next "stop" is the alarm's
        val (skill, data) = Phone(alarm = true).winner("stop")
        skill shouldBe AlarmInfo.id
        alarmSentence(data) shouldBe Alarm.Stop
    }

    "a pending GAR question leaves the stop words to what rings" {
        val phone = Phone(timers = listOf(ringingTimer("pasta")), timer = true)
        // GAR's own question reads GarRinging, which the timer registers on the phone
        try {
            GarRinging.register("test_stop_ranking") { true }
            for ((input, isTimer) in listOf(
                "stop" to true, "shut up" to true, "just stop" to true, "ok please stop" to true,
                "never mind" to false, "cancel" to false,
            )) {
                phone.ranker.addBatchToTop(listOf(GarCancelSkill(FakeSkillInfo("question"))))
                withClue(input) {
                    val best = phone.ranker.getBest(FakeSkillContext().forInput(input), input)
                    (best?.skill?.correspondingSkillInfo?.id == TimerControlInfo.id) shouldBe isTimer
                    (best?.skill is GarCancelSkill) shouldBe !isTimer
                }
                phone.ranker.removeAllBatches()
            }
        } finally {
            GarRinging.unregister("test_stop_ranking")
        }
    }

    "GAR's match check keeps the stop matches, which name nothing" {
        val tools = ToolCatalog.load(
            File("src/main/gar/tools.yml").readText(),
            File("src/main/sentences/skill_definitions.yml").readText(),
        ).tools
        val state = GarMatchCheck.State(tools, listOf("pasta"), ENGLISH_PARSER_FORMATTER)
        GarMatchCheck.rejects(state, TimerControlInfo.id, TimerCommand.StopRinging(null), 1f, "stop")
            .shouldBeFalse()
        GarMatchCheck.rejects(state, ConversationInfo.id, ConversationMatch(Conversation.Stop), 1f, "stop")
            .shouldBeFalse()
        GarMatchCheck.rejects(state, AlarmInfo.id, AlarmSentenceMatch(Alarm.Stop), 1f, "stop")
            .shouldBeFalse()
    }

    "inputs that two writers' skills could both take" {
        val phone = Phone(timers = listOf(runningTimer("pasta")))
        val quiet = Phone()
        for ((input, skill) in listOf(
            // the timers' controls and the everyday answers
            "add five minutes" to TimerControlInfo.id,
            "how many minutes are in an hour" to "units",
            "how much time is left on the timer" to TimerInfo.id,
            "how many days until christmas" to "holiday",
            "show my timers" to TimerControlInfo.id,
            // the timers' controls and the alarm
            "cancel all alarms" to AlarmInfo.id,
            "move my alarm to eight" to AlarmInfo.id,
            // the conversation and small talk
            "thank you" to "small_talk", "thanks a lot" to "small_talk",
            "ok thanks" to "small_talk", "thank you google" to "small_talk",
            "thanks, that's all" to ConversationInfo.id, "that's all, thank you" to ConversationInfo.id,
            "never mind, thanks" to ConversationInfo.id, "no thanks" to ConversationInfo.id,
            "i'm good" to ConversationInfo.id,
            // the world clock, the local time and the alarm
            "what time is it in tokyo" to "world_time", "what time is it" to "current_time",
            "when is my next alarm" to AlarmInfo.id,
        )) {
            withClue(input) { phone.skillOf(input) shouldBe skill }
        }
        phone.skillOf("cancel all timers") shouldBeIn listOf(TimerInfo.id, TimerControlInfo.id)
        // without a timer, "add five minutes" adds to nothing
        quiet.skillOf("add five minutes") shouldNotBeIn listOf(TimerControlInfo.id)
    }
})
