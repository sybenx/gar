package org.stypox.dicio.skills.alarm

import android.app.Notification
import android.provider.AlarmClock
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Alarm
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

// Sunday 2026-10-25, like the other alarm tests
private val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 25, 22, 0)
private val TOMORROW_SEVEN = NextAlarm(LocalDateTime.of(2026, 10, 26, 7, 0), setBy = null)

/**
 * The clock app as the ringing alarm sees it: [firing] is what its notifications tell, [ringing]
 * what the sound tells, and [onStart] is how the clock app reacts to an intent.
 */
private class RingingClock(
    var firing: FiringAlarmCheck = FiringAlarmCheck.Firing,
    var ringing: Boolean = true,
    var next: NextAlarm? = TOMORROW_SEVEN,
    var installed: Boolean = true,
) : ClockApp {
    val started = mutableListOf<AlarmIntent>()
    var onStart: RingingClock.(AlarmIntent) -> Unit = {}

    override fun canHandle(intent: AlarmIntent): Boolean = installed

    override fun start(intent: AlarmIntent): Boolean {
        if (!installed) return false
        started += intent
        onStart(intent)
        return true
    }

    override fun nextAlarm(): NextAlarm? = next

    override fun isAlarmRinging(): Boolean = ringing

    /** The clock app's alarm stops ringing. */
    fun stopRinging() {
        firing = if (firing == FiringAlarmCheck.NoAccess) firing else FiringAlarmCheck.None
        ringing = false
    }
}

private fun ringingSkill(clock: RingingClock) = AlarmSkill(
    correspondingSkillInfo = AlarmInfo,
    data = Sentences.Alarm["en"]!!,
    clockFactory = { clock },
    formatterFactory = { englishFormatter() },
    now = { NOW.atZone(ZoneOffset.UTC) },
    firingAlarm = { clock.firing },
    pause = {},
)

private fun ctx(input: String = "") = FakeSkillContext().forInput(input)

private suspend fun run(clock: RingingClock, input: AlarmRequest): SkillOutput =
    ringingSkill(clock).generateOutput(ctx(), input)

/** The alarm sentence for [input] and its score, while the clock app's alarm [firing]. */
private fun match(input: String, firing: FiringAlarmCheck): Pair<Float, Alarm> {
    val (score, request) = ringingSkill(RingingClock(firing = firing)).score(ctx(input), input)
    return Pair(score.scoreIn01Range(), request.shouldBeInstanceOf<AlarmSentenceMatch>().sentence)
}

/** Every English skill, with the alarm skill's ringing state set to [firing]. */
private fun ranker(firing: FiringAlarmCheck): SkillRanker {
    val skills: List<Skill<*>> = allEnglishSkills().map {
        if (it is AlarmSkill) ringingSkill(RingingClock(firing = firing)) else it
    }
    return SkillRanker(skills, MockSkill(Specificity.LOW, 0.0f))
}

private fun winner(ranker: SkillRanker, input: String): Pair<String?, Any?> {
    val best = ranker.getBest(ctx(input), input)
    return Pair(best?.skill?.correspondingSkillInfo?.id, best?.inputData)
}

class AlarmRingingTest : StringSpec({
    "the clock app's ringing alarm is told from its notifications" {
        val clock = "com.android.deskclock"
        fun firing(
            packageName: String = clock,
            category: String? = Notification.CATEGORY_ALARM,
            flags: Int = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE,
            fullScreen: Boolean = true,
            clockPackage: String? = clock,
        ) = FiringAlarms.isFiringAlarm(packageName, clockPackage, category, flags, fullScreen)

        // DeskClock's ringing alarm, also when Android dropped its full-screen intent
        firing().shouldBeTrue()
        firing(fullScreen = false).shouldBeTrue()
        firing(flags = Notification.FLAG_ONGOING_EVENT, fullScreen = true).shouldBeTrue()
        // its running timers: the alarm category, but no foreground service nor full screen
        firing(flags = Notification.FLAG_ONGOING_EVENT, fullScreen = false).shouldBeFalse()
        // its ringing timers: no category
        firing(category = null).shouldBeFalse()
        // its upcoming, snoozed and missed alarms
        firing(category = Notification.CATEGORY_EVENT, flags = 0, fullScreen = false).shouldBeFalse()
        // another app's alarm: the alarm intents would reach the clock app's alarms instead
        firing(packageName = "com.urbandroid.sleep").shouldBeFalse()
        firing(clockPackage = null).shouldBeFalse()
    }

    "without the notification listener nothing is known" {
        // the unit tests have no listener running, like a phone without notification access
        FiringAlarms.current() shouldBe FiringAlarmCheck.NoAccess
    }

    "stop phrases match only while the clock app's alarm rings" {
        val inputs = listOf(
            "stop", "stop it", "ok stop", "can you stop please", "turn it off", "stop the alarm",
            "stop my alarm", "silence", "shut up", "be quiet", "keep quiet", "dismiss", "i'm up",
            "im awake", "enough", "that's enough", "hush", "silence the alarm", "cancel",
            "cancel it", "just stop", "alright stop", "ok", "okay", "alarm off", "mute it",
        )
        for (input in inputs) {
            withClue(input) {
                val (score, sentence) = match(input, FiringAlarmCheck.Firing)
                score shouldBeGreaterThan 0.9f
                sentence shouldBe Alarm.Stop
                match(input, FiringAlarmCheck.None).first shouldBe 0f
                match(input, FiringAlarmCheck.NoAccess).first shouldBe 0f
            }
        }
        // the phrases that GAR's conversation leaves to what rings
        AlarmSkill.STOP_PHRASES shouldContainAll GarRinging.STOP_PHRASES + GarRinging.CANCEL_PHRASES
        for (phrase in GarRinging.STOP_PHRASES + GarRinging.CANCEL_PHRASES) {
            withClue(phrase) { AlarmSkill.isStopCommand(phrase).shouldBeTrue() }
        }
        // a word said twice and fillers, as GAR's answers drop them
        for (input in listOf("stop stop", "uh stop", "ok please stop", "um, just stop")) {
            withClue(input) { AlarmSkill.isStopCommand(input).shouldBeTrue() }
        }
        // a word left out is no stop: other things to stop, and GAR's conversation
        for (input in listOf(
            "stop the music", "stop the timer", "stop the stopwatch", "stop listening",
            "stop the video", "turn it on", "never mind", "cancel my order", "stop navigation",
        )) {
            withClue(input) {
                AlarmSkill.isStopCommand(input).shouldBeFalse()
                match(input, FiringAlarmCheck.Firing).first shouldBe 0f
            }
        }
    }

    "snooze sentences match with or without a ringing alarm, and only about the snooze" {
        for ((input, duration) in listOf(
            "snooze" to null, "snooze it" to null, "snooze the alarm" to null, "ok snooze" to null,
            "snooze for ten minutes" to "ten minutes", "snooze ten minutes" to "ten minutes",
            "snooze it for 10 minutes" to "10 minutes", "snooze for another five minutes" to "five minutes",
        )) {
            for (firing in listOf(FiringAlarmCheck.Firing, FiringAlarmCheck.None, FiringAlarmCheck.NoAccess)) {
                withClue("$input ($firing)") {
                    val (score, sentence) = match(input, firing)
                    score shouldBeGreaterThan 0.9f
                    sentence.shouldBeInstanceOf<Alarm.Snooze>().duration?.trim() shouldBe duration
                }
            }
        }
        for (input in listOf("snooze notifications", "snooze notifications for an hour", "snooze for a bit")) {
            withClue(input) { match(input, FiringAlarmCheck.Firing).first shouldBe 0f }
        }

        // "five more minutes" only while the alarm rings, with the whole duration
        for ((input, duration) in listOf(
            "five more minutes" to "five more minutes", "give me ten more minutes" to "ten more minutes",
            "ok 5 more minutes please" to "5 more minutes",
        )) {
            withClue(input) {
                val (score, sentence) = match(input, FiringAlarmCheck.Firing)
                score shouldBeGreaterThan 0.9f
                sentence shouldBe Alarm.Snooze(duration)
                match(input, FiringAlarmCheck.None).first shouldBe 0f
                match(input, FiringAlarmCheck.NoAccess).first shouldBe 0f
            }
        }
        match("add five more minutes", FiringAlarmCheck.Firing).first shouldBe 0f
    }

    "while the clock app's alarm rings, the alarm skill takes its commands and nothing else" {
        val ringing = ranker(FiringAlarmCheck.Firing)
        for ((input, sentence) in listOf(
            "stop" to Alarm.Stop, "ok stop" to Alarm.Stop, "turn it off" to Alarm.Stop,
            "shut up" to Alarm.Stop, "dismiss" to Alarm.Stop, "stop the alarm" to Alarm.Stop,
            "snooze" to Alarm.Snooze(null),
        )) {
            withClue(input) {
                val (skill, data) = winner(ringing, input)
                skill shouldBe AlarmInfo.id
                data.shouldBeInstanceOf<AlarmSentenceMatch>().sentence shouldBe sentence
            }
        }
        // "turn off the alarm" is a cancel sentence, which stops the ringing alarm too
        winner(ringing, "turn off the alarm").second.shouldBeInstanceOf<AlarmSentenceMatch>()
            .sentence shouldBe Alarm.Cancel(null)
        for ((input, skill) in listOf(
            "stop the music" to "media", "stop the timer" to "timer",
            "stop the stopwatch" to "stopwatch", "stop listening" to "listening",
            "turn off wifi" to "connectivity", "stop the video" to "media",
        )) {
            withClue(input) { winner(ringing, input).first shouldBe skill }
        }

        // nothing rings: "stop" is left to other skills
        for (firing in listOf(FiringAlarmCheck.None, FiringAlarmCheck.NoAccess)) {
            val quiet = ranker(firing)
            for (input in listOf("stop", "turn it off", "shut up", "dismiss", "i'm up")) {
                withClue("$input ($firing)") { winner(quiet, input).first shouldNotBe AlarmInfo.id }
            }
            winner(quiet, "snooze").first shouldBe AlarmInfo.id
        }
    }

    "stop and snooze matches aren't sent to the model for naming no alarm" {
        val tools = ToolCatalog.load(
            File("src/main/gar/tools.yml").readText(),
            File("src/main/sentences/skill_definitions.yml").readText(),
        ).tools
        val state = GarMatchCheck.State(tools, listOf(), ENGLISH_PARSER_FORMATTER)
        fun rejects(sentence: Alarm, input: String) =
            GarMatchCheck.rejects(state, AlarmInfo.id, AlarmSentenceMatch(sentence), 1f, input)

        rejects(Alarm.Stop, "stop").shouldBeFalse()
        rejects(Alarm.Stop, "turn it off").shouldBeFalse()
        rejects(Alarm.Snooze(null), "snooze").shouldBeFalse()
        rejects(Alarm.Snooze(" ten minutes"), "snooze for ten minutes").shouldBeFalse()
        rejects(Alarm.Change(" seven am ", " seven thirty"), "change my seven am alarm to seven thirty")
            .shouldBeFalse()
        // the alarm's keyword outside the captures still counts for the others
        rejects(Alarm.Change(" seven am alarm ", " eight"), "move seven am alarm to eight").shouldBeTrue()
    }

    "stop dismisses the ringing alarm and waits until it is gone" {
        val clock = RingingClock().apply { onStart = { stopRinging() } }
        run(clock, AlarmSentenceMatch(Alarm.Stop)) shouldBe AlarmOutput.Stopped
        // DeskClock dismisses the ringing alarm as the next one
        clock.started shouldBe listOf(AlarmIntent.dismiss(CancelTarget.Next))

        // "turn off my alarm" while it rings, also after a call of the model
        for (input in listOf(AlarmSentenceMatch(Alarm.Cancel(null)), AlarmInput.Cancel(CancelTarget.Next))) {
            val again = RingingClock().apply { onStart = { stopRinging() } }
            withClue(input) {
                run(again, input) shouldBe AlarmOutput.Stopped
                again.started shouldBe listOf(AlarmIntent.dismiss(CancelTarget.Next))
            }
        }

        // DeskClock asks which one when two ring: still ringing after GAR's wait
        val two = RingingClock()
        run(two, AlarmInput.Stop) shouldBe AlarmOutput.StillRinging(snooze = false)
        two.started shouldBe listOf(AlarmIntent.dismiss(CancelTarget.Next))

        // it stopped between the match and the run (DeskClock silences it after a while)
        val stopped = RingingClock(firing = FiringAlarmCheck.None, ringing = false)
        run(stopped, AlarmInput.Stop) shouldBe AlarmOutput.NotRinging
        stopped.started.shouldBeEmpty()
    }

    "snooze says until when the alarm rings again" {
        // DeskClock's next alarm becomes the snoozed one
        val snoozed = NextAlarm(NOW.plusMinutes(10), setBy = null)
        val clock = RingingClock().apply {
            onStart = {
                stopRinging()
                next = snoozed
            }
        }
        run(clock, AlarmSentenceMatch(Alarm.Snooze(null))).shouldBeInstanceOf<AlarmOutput.Snoozed>().let {
            it.until shouldBe LocalTime.of(22, 10)
            it.ownLength.shouldBeFalse()
        }
        clock.started shouldBe listOf(AlarmIntent.snooze(null))

        // the length asked for goes to the clock app, which may use its own
        for ((minutes, ownLength) in listOf(10L to false, 5L to true)) {
            val asked = RingingClock().apply {
                onStart = {
                    stopRinging()
                    next = snoozed
                }
            }
            withClue(minutes) {
                run(asked, AlarmSentenceMatch(Alarm.Snooze(" $minutes minutes ")))
                    .shouldBeInstanceOf<AlarmOutput.Snoozed>().ownLength shouldBe ownLength
                asked.started shouldBe listOf(AlarmIntent.snooze(Duration.ofMinutes(minutes)))
            }
        }

        // another alarm comes before the snoozed one: no time to tell
        val earlier = RingingClock(next = NextAlarm(NOW.plusMinutes(5), setBy = null))
            .apply { onStart = { stopRinging() } }
        run(earlier, AlarmInput.Snooze(null)).shouldBeInstanceOf<AlarmOutput.Snoozed>().until.shouldBeNull()
    }

    "without notification access snooze goes by the sound" {
        // the clock app only snoozes a ringing alarm, so a ringing timer comes to no harm
        val clock = RingingClock(firing = FiringAlarmCheck.NoAccess).apply { onStart = { stopRinging() } }
        run(clock, AlarmInput.Snooze(null)).shouldBeInstanceOf<AlarmOutput.Snoozed>().until.shouldBeNull()
        clock.started shouldBe listOf(AlarmIntent.snooze(null))

        // nothing the clock app could snooze: it still rings
        val timer = RingingClock(firing = FiringAlarmCheck.NoAccess)
        run(timer, AlarmInput.Snooze(null)) shouldBe AlarmOutput.StillRinging(snooze = true)

        // nothing rings
        val quiet = RingingClock(firing = FiringAlarmCheck.NoAccess, ringing = false)
        run(quiet, AlarmInput.Snooze(null)) shouldBe AlarmOutput.NotRinging
        quiet.started.shouldBeEmpty()

        // with access, a sound that isn't the clock app's alarm (a timer) is no alarm to snooze
        val notAlarm = RingingClock(firing = FiringAlarmCheck.None, ringing = true)
        run(notAlarm, AlarmInput.Snooze(null)) shouldBe AlarmOutput.NotRinging
        notAlarm.started.shouldBeEmpty()
    }

    "the snoozed alarm is the clock app's next alarm once it changed to a time soon after" {
        val before = TOMORROW_SEVEN
        fun until(after: NextAlarm?, duration: Duration? = null) =
            AlarmSkill.snoozedUntil(before, after, NOW, duration)

        until(NextAlarm(NOW.plusMinutes(9), null)) shouldBe NOW.plusMinutes(9)
        until(before).shouldBeNull()
        until(null).shouldBeNull()
        until(NextAlarm(NOW.plusMinutes(9), "Sleep")).shouldBeNull()
        until(NextAlarm(NOW.plusHours(2), null)).shouldBeNull()
        until(NextAlarm(NOW.plusHours(2), null), Duration.ofHours(1)) shouldBe NOW.plusHours(2)
        until(NextAlarm(NOW.minusMinutes(1), null)).shouldBeNull()
    }

    "snoozing sends the length in whole minutes, if any" {
        AlarmIntent.snooze(null) shouldBe AlarmIntent(AlarmClock.ACTION_SNOOZE_ALARM)
        fun minutes(duration: Duration) =
            AlarmIntent.snooze(duration).extras[AlarmClock.EXTRA_ALARM_SNOOZE_DURATION]
        minutes(Duration.ofMinutes(10)) shouldBe 10
        minutes(Duration.ofSeconds(30)) shouldBe 1
        minutes(Duration.ofSeconds(90)) shouldBe 2
        minutes(Duration.ofDays(3)) shouldBe 24 * 60
    }
})
