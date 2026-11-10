package org.stypox.dicio.skills.alarm

import android.provider.AlarmClock
import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.eval.scoreAndWrapResult
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarAskSlotOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarConfirmOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarQuestion
import org.stypox.dicio.gar.call.GarRoutedSkill
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Alarm
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Calendar

// Sunday 2026-10-25, like TEST_TODAY of the GAR tests
private val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 25, 22, 0)
private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

private fun sourceFile(path: String): String {
    val file = sequenceOf(File(path), File("app", path)).firstOrNull { it.isFile }
        ?: throw AssertionError("Cannot find $path from ${File("").absolutePath}")
    return file.readText()
}

private fun realAlarmTools() = ToolCatalog.load(
    sourceFile("src/main/gar/tools.yml"),
    sourceFile("src/main/sentences/skill_definitions.yml"),
).tools.filter { it.skillId == AlarmInfo.id }

/** Records what the skill sends to the clock app. */
private class FakeClock(
    var next: NextAlarm? = null,
    var installed: Boolean = true,
    var ringing: Boolean = false,
) : ClockApp {
    val started = mutableListOf<AlarmIntent>()

    override fun canHandle(intent: AlarmIntent): Boolean = installed

    override fun start(intent: AlarmIntent): Boolean {
        if (!installed) return false
        started += intent
        return true
    }

    override fun nextAlarm(): NextAlarm? = next

    override fun isAlarmRinging(): Boolean = ringing
}

/**
 * @param firing what the clock app's notifications tell (no notification access by default)
 * @param pauses records the skill's pauses, which take no time
 */
private fun alarmSkill(
    clock: FakeClock,
    firing: () -> FiringAlarmCheck = { FiringAlarmCheck.NoAccess },
    pauses: MutableList<Long> = mutableListOf(),
) = AlarmSkill(
    correspondingSkillInfo = AlarmInfo,
    data = Sentences.Alarm["en"]!!,
    clockFactory = { clock },
    formatterFactory = { englishFormatter() },
    now = { NOW.atZone(ZoneOffset.UTC) },
    firingAlarm = firing,
    pause = { pauses += it },
)

private fun ctx(input: String = "") = FakeSkillContext().forInput(input)

/** What the sentences extract from [input], and how well they match it. */
private fun match(input: String): Pair<Float, Alarm> {
    val (score, request) = alarmSkill(FakeClock()).score(ctx(input), input)
    return Pair(score.scoreIn01Range(), request.shouldBeInstanceOf<AlarmSentenceMatch>().sentence)
}

private suspend fun run(skill: AlarmSkill, input: AlarmRequest): SkillOutput =
    skill.generateOutput(ctx(), input)

/** The follow-up skills of a question. */
private fun nextSkills(output: SkillOutput): List<Skill<*>> =
    when (val plan = output.getInteractionPlan(ctx())) {
        is InteractionPlan.StartSubInteraction -> plan.nextSkills
        is InteractionPlan.ReplaceSubInteraction -> plan.nextSkills
        else -> throw AssertionError("Unexpected plan $plan")
    }

private suspend fun answer(skill: Skill<*>, input: String): SkillOutput {
    val ctx = ctx(input)
    val result = skill.scoreAndWrapResult(ctx, input)
    result.score shouldBeSameInstanceAs AlwaysBestScore
    return result.generateOutput(ctx)
}

class AlarmSkillTest : StringSpec({
    "the fast path's tools have the same slots as the tools in tools.yml" {
        val tools = realAlarmTools().associateBy { it.name }
        tools.keys shouldBe setOf(AlarmTools.SET, AlarmTools.LIST, AlarmTools.CANCEL)
        for (local in listOf(AlarmTools.SET_TOOL, AlarmTools.CANCEL_TOOL)) {
            val real = tools.getValue(local.name)
            withClue(local.name) {
                real.sentence.shouldBeNull() // LLM-only, see AlarmTools
                real.slots.map { Triple(it.name, it.kind, it.required) } shouldBe
                        local.slots.map { Triple(it.name, it.kind, it.required) }
            }
        }
    }

    "each alarm tool builds its own input from its slots" {
        val seven = ResolvedTime(LocalTime.of(7, 0), WEEKDAYS)
        val tools = realAlarmTools().associateBy { it.name }

        // GAR passes every slot of the called tool, null when an optional one was left out
        fun input(tool: String, vararg values: Pair<String, Any?>): AlarmInput {
            val slots = tools.getValue(tool).slots.associate { slot ->
                slot.name to values.firstOrNull { it.first == slot.name }?.second
            }
            return AlarmTools.inputFromSlots(slots)
        }

        input(AlarmTools.SET, "time" to seven, "label" to "the gym alarm") shouldBe
                AlarmInput.Set(seven, "gym")
        input(AlarmTools.SET, "time" to seven) shouldBe AlarmInput.Set(seven, null)
        input(AlarmTools.CANCEL, "time" to seven) shouldBe
                AlarmInput.Cancel(CancelTarget.At(LocalTime.of(7, 0)))
        input(AlarmTools.CANCEL) shouldBe AlarmInput.Cancel(CancelTarget.Next)
        input(AlarmTools.LIST) shouldBe AlarmInput.Show(open = false)

        // and through the skill, as GarCallRunner calls it
        alarmSkill(FakeClock()).inputFromSlots(mapOf()) shouldBe AlarmInput.Show(open = false)
    }

    "labels lose the generic words around them" {
        AlarmTools.cleanLabel("gym") shouldBe "gym"
        AlarmTools.cleanLabel("the gym alarm") shouldBe "gym"
        AlarmTools.cleanLabel("called take my pills") shouldBe "take my pills"
        AlarmTools.cleanLabel("for work") shouldBe "work"
        AlarmTools.cleanLabel("my alarm").shouldBeNull()
        AlarmTools.cleanLabel("  ").shouldBeNull()
        AlarmTools.cleanLabel("morning run") shouldBe "morning run"
        // the time again, as the model often fills the label (eval, Qwen3-0.6B)
        AlarmTools.cleanLabel("quarter past five tomorrow").shouldBeNull()
        AlarmTools.cleanLabel("six tomorrow morning").shouldBeNull()
        AlarmTools.cleanLabel("next alarm").shouldBeNull()
        AlarmTools.cleanLabel("7:30 am").shouldBeNull()
    }

    "setting builds a skip-ui alarm with repeat days and label" {
        AlarmIntent.set(LocalTime.of(6, 30), WEEKDAYS, "gym") shouldBe AlarmIntent(
            AlarmClock.ACTION_SET_ALARM,
            mapOf(
                AlarmClock.EXTRA_HOUR to 6,
                AlarmClock.EXTRA_MINUTES to 30,
                AlarmClock.EXTRA_SKIP_UI to true,
                AlarmClock.EXTRA_DAYS to listOf(
                    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
                    Calendar.FRIDAY,
                ),
                AlarmClock.EXTRA_MESSAGE to "gym",
            ),
        )
        AlarmIntent.set(LocalTime.of(19, 5), setOf(), null) shouldBe AlarmIntent(
            AlarmClock.ACTION_SET_ALARM,
            mapOf(
                AlarmClock.EXTRA_HOUR to 19,
                AlarmClock.EXTRA_MINUTES to 5,
                AlarmClock.EXTRA_SKIP_UI to true,
            ),
        )
        AlarmIntent.calendarDays(setOf(DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)) shouldBe
                listOf(Calendar.SATURDAY, Calendar.SUNDAY)
    }

    "dismissing by time sends the hour as DeskClock reads it" {
        // DeskClock: hour > 12 with is_pm is invalid, is_pm adds 12 below 12
        fun dismissAt(hour: Int, minute: Int) =
            AlarmIntent.dismiss(CancelTarget.At(LocalTime.of(hour, minute))).extras
        fun deskClockHour(extras: Map<String, Any>): Int {
            val hour = extras[AlarmClock.EXTRA_HOUR] as Int
            val pm = extras[AlarmClock.EXTRA_IS_PM] as Boolean
            (pm && hour > 12).shouldBeFalse()
            return if (pm && hour < 12) hour + 12 else hour
        }

        for ((hour, minute) in listOf(0 to 30, 7 to 0, 11 to 59, 12 to 0, 12 to 30, 19 to 15, 23 to 59)) {
            val extras = dismissAt(hour, minute)
            withClue("$hour:$minute") {
                extras[AlarmClock.EXTRA_ALARM_SEARCH_MODE] shouldBe AlarmClock.ALARM_SEARCH_MODE_TIME
                deskClockHour(extras) shouldBe hour
                extras[AlarmClock.EXTRA_MINUTES] shouldBe minute
            }
        }
        dismissAt(19, 15)[AlarmClock.EXTRA_HOUR] shouldBe 7

        AlarmIntent.dismiss(CancelTarget.Next) shouldBe AlarmIntent(
            AlarmClock.ACTION_DISMISS_ALARM,
            mapOf(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_NEXT),
        )
        AlarmIntent.dismiss(CancelTarget.All) shouldBe AlarmIntent(
            AlarmClock.ACTION_DISMISS_ALARM,
            mapOf(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_ALL),
        )
        AlarmIntent.show() shouldBe AlarmIntent(AlarmClock.ACTION_SHOW_ALARMS)
    }

    "days are said relative to today" {
        val today = NOW.toLocalDate()
        AlarmDay.of(today, today) shouldBe AlarmDay.Today
        AlarmDay.of(today.plusDays(1), today) shouldBe AlarmDay.Tomorrow
        AlarmDay.of(today.plusDays(2), today) shouldBe AlarmDay.Weekday(DayOfWeek.TUESDAY)
        AlarmDay.of(today.plusDays(6), today) shouldBe AlarmDay.Weekday(DayOfWeek.SATURDAY)
        AlarmDay.of(today.plusDays(7), today) shouldBe AlarmDay.Date(LocalDate.of(2026, 11, 1))
    }

    "the fast path also takes times relative to now" {
        val resolvers = SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") { NOW.atZone(ZoneOffset.UTC) }
        val resolver = RelativeTimeResolver(DefaultGarSlotResolver(resolvers), resolvers) {
            NOW.plusSeconds(40).atZone(ZoneOffset.UTC)
        }
        val time = SlotSpec("time", SlotKind.TIME, true, "")
        resolver.resolve(time, "ten minutes") shouldBe
                Resolution.Resolved(ResolvedTime(LocalTime.of(22, 11)))
        // with "in", GAR's own time resolver takes it, from its own now (NOW here)
        resolver.resolve(time, "in eight hours") shouldBe
                Resolution.Resolved(ResolvedTime(LocalTime.of(6, 0)))
        resolver.resolve(time, "an hour and a half") shouldBe
                Resolution.Resolved(ResolvedTime(LocalTime.of(23, 31)))
        resolver.resolve(time, "two days").shouldBeInstanceOf<Resolution.Unresolvable>()
        resolver.resolve(time, "pasta").shouldBeInstanceOf<Resolution.Unresolvable>()
        resolver.resolve(time, "seven").shouldBeInstanceOf<Resolution.Ambiguous<*>>()
            .reason shouldBe AmbiguityReason.AM_OR_PM
        resolver.resolve(time, "six thirty am every weekday") shouldBe
                Resolution.Resolved(ResolvedTime(LocalTime.of(6, 30), WEEKDAYS))
        // other kinds stay as they are
        resolver.resolve(SlotSpec("label", SlotKind.TEXT, false, ""), "ten minutes") shouldBe
                Resolution.Resolved("ten minutes")
    }

    "the english sentences capture time and label as said" {
        fun set(input: String): Alarm.Set {
            val (score, sentence) = match(input)
            withClue(input) { score shouldBeGreaterThan 0.8f }
            return sentence.shouldBeInstanceOf<Alarm.Set>()
        }
        fun cancel(input: String) = match(input).second.shouldBeInstanceOf<Alarm.Cancel>().time?.trim()
        set("set an alarm for seven thirty every weekday").time?.trim() shouldBe "seven thirty every weekday"
        set("alarm for quarter past five").time?.trim() shouldBe "quarter past five"
        set("wake me up at seven").time?.trim().shouldBeIn("seven", "at seven")
        set("wake me up in eight hours").time?.trim().shouldBeIn("eight hours", "in eight hours")
        set("wake me up tomorrow at six am").time?.trim() shouldBe "tomorrow at six am"
        set("set an alarm for six called gym").let {
            it.time?.trim() shouldBe "six"
            it.label?.trim() shouldBe "gym"
        }
        set("set an alarm").time.shouldBeNull()
        // a time and the word alarm, as said on 2026-11-05 ("1145 alarm")
        set("eleven forty five alarm").time?.trim() shouldBe "eleven forty five"
        set("seven am alarm").time?.trim() shouldBe "seven am"
        set("alarm six thirty").time?.trim() shouldBe "six thirty"
        set("set a six thirty alarm").time?.trim() shouldBe "six thirty"

        match("what alarms do i have").second shouldBe Alarm.Query
        match("when is my next alarm").second shouldBe Alarm.Query
        match("show my alarms").second shouldBe Alarm.Show
        cancel("cancel my alarm").shouldBeNull()
        cancel("cancel my next alarm").shouldBeNull()
        cancel("cancel my seven am alarm") shouldBe "seven am"
        cancel("turn off the alarm at six thirty") shouldBe "six thirty"
        cancel("delete the seven am alarm") shouldBe "seven am"
        match("what time is my alarm").second shouldBe Alarm.Query
        match("delete all my alarms").second shouldBe Alarm.CancelAll
        match("cancel all alarms").second shouldBe Alarm.CancelAll
        match("cancel every alarm").second shouldBe Alarm.CancelAll
        match("delete each one of my alarms").second shouldBe Alarm.CancelAll

        // partial matches whose "time" is no time don't match, so GAR's model gets them
        for (input in listOf(
            "set a timer for ten minutes", "set a reminder for tomorrow",
            "set the volume to fifty percent", "add an event to my calendar", "cancel the pasta timer",
            "turn up the volume", "wake up", "set an alarm for banana", "my alarm",
            "the alarm", "banana alarm",
        )) {
            withClue(input) { match(input).first shouldBeLessThan 0.5f }
        }
    }

    "a sentence time without am or pm is asked for, then the alarm is set" {
        val clock = FakeClock()
        val skill = alarmSkill(clock)
        val ask = run(skill, AlarmSentenceMatch(Alarm.Set("seven", "gym")))
            .shouldBeInstanceOf<GarAskSlotOutput>()
        ask.question.shouldBeInstanceOf<GarQuestion.Pick>().reason shouldBe AmbiguityReason.AM_OR_PM
        clock.started.shouldBeEmpty()

        val output = answer(nextSkills(ask)[0], "in the morning")
            .shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<AlarmOutput.Set>()
        output.time shouldBe ResolvedTime(LocalTime.of(7, 0))
        output.label shouldBe "gym"
        output.rings shouldBe LocalDateTime.of(2026, 10, 26, 7, 0)
        clock.started shouldBe listOf(AlarmIntent.set(LocalTime.of(7, 0), setOf(), "gym"))
    }

    "a full sentence sets the alarm right away, a missing time is asked for" {
        val clock = FakeClock()
        val skill = alarmSkill(clock)
        val output = run(skill, AlarmSentenceMatch(Alarm.Set("six thirty am every weekday", null)))
            .shouldBeInstanceOf<AlarmOutput.Set>()
        output.rings.shouldBeNull() // repeating
        clock.started.single().extras[AlarmClock.EXTRA_DAYS] shouldBe AlarmIntent.calendarDays(WEEKDAYS)

        run(skill, AlarmSentenceMatch(Alarm.Set("ten minutes", null)))
            .shouldBeInstanceOf<AlarmOutput.Set>().time shouldBe ResolvedTime(LocalTime.of(22, 10))

        run(skill, AlarmSentenceMatch(Alarm.Set(null, null))).shouldBeInstanceOf<GarAskSlotOutput>()
            .question shouldBe GarQuestion.Value(null)
        run(skill, AlarmSentenceMatch(Alarm.Set("banana", null))).shouldBeInstanceOf<GarAskSlotOutput>()
            .question shouldBe GarQuestion.Value("banana")
        clock.started.size shouldBe 2
    }

    "the next day without a time matches and asks for the time" {
        // the model's alarm_set(time: "tomorrow morning") is dropped by the call filter, so these
        // must not go to the model
        for (input in listOf(
            "set an alarm for tomorrow morning", "set an alarm for tomorrow", "wake me up tomorrow",
            "wake me tomorrow morning", "alarm for tomorrow", "set an alarm for tonight",
        )) {
            withClue(input) {
                val (score, sentence) = match(input)
                score shouldBeGreaterThan 0.8f
                val clock = FakeClock()
                run(alarmSkill(clock), AlarmSentenceMatch(sentence)).shouldBeInstanceOf<GarAskSlotOutput>()
                    .question shouldBe GarQuestion.Value(null)
                clock.started.shouldBeEmpty()
            }
        }
        // an alarm rings at the next occurrence of its time: other days stay with the model
        for (input in listOf(
            "set an alarm for friday", "set an alarm for saturday at nine", "wake me up next week",
            "set an alarm for this", "my alarm didn't go off this morning",
        )) {
            withClue(input) { match(input).first shouldBeLessThan 0.5f }
        }
        AlarmSkill.isNextDay("tomorrow's") shouldBe true
        AlarmSkill.isNextDay("in the morning") shouldBe false
        AlarmSkill.isNextDay("tomorrow at seven") shouldBe false
    }

    "a model call is resolved, confirmed and set through GAR" {
        val clock = FakeClock()
        val tool = realAlarmTools().first { it.name == AlarmTools.SET }
        val routed = GarRoutedSkill(
            targetInfo = AlarmInfo,
            target = alarmSkill(clock),
            confirm = true,
            formatterFactory = { englishFormatter() },
        )
        val ask = routed.generateOutput(
            ctx(), GarCall(tool, mapOf("time" to "seven", "label" to "the gym alarm"), "")
        ).shouldBeInstanceOf<GarAskSlotOutput>()
        val confirm = answer(nextSkills(ask)[0], "pm").shouldBeInstanceOf<GarConfirmOutput>()
        confirm.runner.summary(confirm.call, speech = false) shouldBe "Set an alarm for 7:00 PM?"

        // the card's own skills (its interaction plan needs Android strings for the buttons)
        answer(confirm.nextSkills("Yes", "No")[0], "yes").shouldBeInstanceOf<GarFollowUpOutput>()
            .output.shouldBeInstanceOf<AlarmOutput.Set>().label shouldBe "gym"
        clock.started shouldBe listOf(AlarmIntent.set(LocalTime.of(19, 0), setOf(), "gym"))
    }

    "without a clock app nothing is sent" {
        val clock = FakeClock(installed = false)
        val skill = alarmSkill(clock)
        run(skill, AlarmInput.Set(ResolvedTime(LocalTime.of(7, 0)), null)) shouldBe AlarmOutput.NoClockApp
        run(skill, AlarmInput.Cancel(CancelTarget.Next)) shouldBe AlarmOutput.NoClockApp
        run(skill, AlarmInput.Show(open = true)).shouldBeInstanceOf<AlarmOutput.Show>().let {
            it.openFailed.shouldBeTrue()
            it.open.shouldBeNull()
        }
        clock.started.shouldBeEmpty()
    }

    "the next alarm is told, the alarms are opened only when asked" {
        val next = NextAlarm(LocalDateTime.of(2026, 10, 26, 7, 0), setBy = null)
        val clock = FakeClock(next = next)
        val skill = alarmSkill(clock)

        val query = run(skill, AlarmSentenceMatch(Alarm.Query)).shouldBeInstanceOf<AlarmOutput.Show>()
        query.next shouldBe next
        query.opened.shouldBeFalse()
        clock.started.shouldBeEmpty()
        query.open.shouldNotBeNull().invoke()
        clock.started shouldBe listOf(AlarmIntent.show())

        clock.started.clear()
        val show = run(skill, AlarmSentenceMatch(Alarm.Show)).shouldBeInstanceOf<AlarmOutput.Show>()
        show.opened.shouldBeTrue()
        show.open.shouldBeNull()
        clock.started shouldBe listOf(AlarmIntent.show())
    }

    "cancelling the next alarm" {
        // no alarm at all: nothing to open
        FakeClock().let { clock ->
            run(alarmSkill(clock), AlarmSentenceMatch(Alarm.Cancel(null))) shouldBe AlarmOutput.NoAlarms
            clock.started.shouldBeEmpty()
        }

        // within 24 hours: dismissed, and said which one
        val tomorrow = NextAlarm(LocalDateTime.of(2026, 10, 26, 7, 0), setBy = null)
        FakeClock(next = tomorrow).let { clock ->
            run(alarmSkill(clock), AlarmSentenceMatch(Alarm.Cancel(null)))
                .shouldBeInstanceOf<AlarmOutput.Dismissing>().next shouldBe tomorrow
            clock.started shouldBe listOf(AlarmIntent.dismiss(CancelTarget.Next))
        }

        // "turn off tomorrow's alarm": the next alarm too (the timer took it before)
        val (score, sentence) = match("turn off tomorrow's alarm")
        score shouldBeGreaterThan 0.8f
        sentence.shouldBeInstanceOf<Alarm.Cancel>().time?.trim() shouldBe "tomorrow's"
        FakeClock(next = tomorrow).let { clock ->
            run(alarmSkill(clock), AlarmSentenceMatch(sentence))
                .shouldBeInstanceOf<AlarmOutput.Dismissing>().next shouldBe tomorrow
            clock.started shouldBe listOf(AlarmIntent.dismiss(CancelTarget.Next))
        }

        // more than 24 hours ahead: DeskClock would refuse silently, so show the alarms
        val tuesday = NextAlarm(LocalDateTime.of(2026, 10, 27, 7, 0), setBy = null)
        FakeClock(next = tuesday).let { clock ->
            run(alarmSkill(clock), AlarmInput.Cancel(CancelTarget.Next))
                .shouldBeInstanceOf<AlarmOutput.TooFarAhead>().opened.shouldBeTrue()
            clock.started shouldBe listOf(AlarmIntent.show())
        }

        // set by another app: the clock app would dismiss its own next alarm, a later one, so
        // nothing is sent
        val other = NextAlarm(LocalDateTime.of(2026, 10, 26, 6, 0), setBy = "Sleep")
        FakeClock(next = other).let { clock ->
            run(alarmSkill(clock), AlarmInput.Cancel(CancelTarget.Next))
                .shouldBeInstanceOf<AlarmOutput.OtherApp>().next shouldBe other
            clock.started.shouldBeEmpty()
            // by time or all, the clock app dismisses its own alarms, with the caveat
            run(alarmSkill(clock), AlarmInput.Cancel(CancelTarget.All))
                .shouldBeInstanceOf<AlarmOutput.Dismissing>().next.shouldBeNull()
            clock.started shouldBe listOf(AlarmIntent.dismiss(CancelTarget.All))
        }
    }

    "when the clock app's next alarm is more than a day away, nothing can be dismissed" {
        val tuesday = NextAlarm(LocalDateTime.of(2026, 10, 27, 7, 0), setBy = null)
        for (target in listOf(CancelTarget.At(LocalTime.of(20, 30)), CancelTarget.All)) {
            val clock = FakeClock(next = tuesday)
            withClue(target) {
                run(alarmSkill(clock), AlarmInput.Cancel(target))
                    .shouldBeInstanceOf<AlarmOutput.TooFarAhead>().next shouldBe tuesday
                clock.started shouldBe listOf(AlarmIntent.show())
            }
        }
    }

    "while something rings that may not be the clock app's alarm, the next alarm stays" {
        // something rings, and the clock app's next alarm is a backup alarm ten minutes later:
        // if a timer rings, the clock app would skip the backup alarm
        val backup = NextAlarm(LocalDateTime.of(2026, 10, 25, 22, 10), setBy = null)
        val clock = FakeClock(next = backup, ringing = true)
        // without notification access the card offers it
        run(alarmSkill(clock), AlarmInput.Cancel(CancelTarget.Next)) shouldBe
                AlarmOutput.Ringing(needsAccess = true)
        // with it, GAR knows that the clock app's alarm doesn't ring
        run(alarmSkill(clock, firing = { FiringAlarmCheck.None }), AlarmInput.Cancel(CancelTarget.Next)) shouldBe
                AlarmOutput.Ringing(needsAccess = false)
        clock.started.shouldBeEmpty()

        // by time, the clock app finds the ringing alarm, even without any other alarm
        clock.next = null
        run(alarmSkill(clock), AlarmInput.Cancel(CancelTarget.At(LocalTime.of(22, 0))))
            .shouldBeInstanceOf<AlarmOutput.Dismissing>().next.shouldBeNull()
        clock.started shouldBe listOf(AlarmIntent.dismiss(CancelTarget.At(LocalTime.of(22, 0))))
    }

    "sentences only match inputs about alarms" {
        // partial matches with another noun in place of "alarm" score 0.75 to 0.86 without the
        // keyword check, above Dicio's 0.7 threshold for high specificity skills, and sentence
        // matches are never confirmed: these would have dismissed alarms or told the next one
        for (input in listOf(
            "clear all", "dismiss all", "cancel all", "delete all", "turn off all of the lights",
            "delete all of my notes", "clear all the timers", "cancel the next", "turn off next",
            "remove my next", "dismiss next", "cancel my seven am", "when is my next meeting",
            "do i have any messages", "which apps do i have", "what time is my flight",
            "tell me my notifications", "open the camera app", "remind me at seven am",
        )) {
            withClue(input) { match(input).first shouldBe 0f }
        }
    }

    "cancelling by time or all" {
        val tomorrow = NextAlarm(LocalDateTime.of(2026, 10, 26, 7, 0), setBy = null)
        val clock = FakeClock(next = tomorrow)
        val skill = alarmSkill(clock)

        // the time of the next alarm: known to be within 24 hours
        run(skill, AlarmSentenceMatch(Alarm.Cancel("seven am")))
            .shouldBeInstanceOf<AlarmOutput.Dismissing>().next shouldBe tomorrow
        // another time: maybe further ahead, so the caveat is said
        run(skill, AlarmSentenceMatch(Alarm.Cancel("eight thirty pm")))
            .shouldBeInstanceOf<AlarmOutput.Dismissing>().let {
                it.target shouldBe CancelTarget.At(LocalTime.of(20, 30))
                it.next.shouldBeNull()
            }
        // all of them only after a yes
        val confirm = run(skill, AlarmSentenceMatch(Alarm.CancelAll)).shouldBeInstanceOf<GarConfirmOutput>()
        confirm.runner.summary(confirm.call, speech = false) shouldBe "Dismiss all your alarms?"
        clock.started.size shouldBe 2
        answer(confirm.nextSkills("Yes", "No")[0], "yes").shouldBeInstanceOf<GarFollowUpOutput>()
            .output.shouldBeInstanceOf<AlarmOutput.Dismissing>().target shouldBe CancelTarget.All
        clock.started shouldBe listOf(
            AlarmIntent.dismiss(CancelTarget.At(LocalTime.of(7, 0))),
            AlarmIntent.dismiss(CancelTarget.At(LocalTime.of(20, 30))),
            AlarmIntent.dismiss(CancelTarget.All),
        )

        // am or pm is asked like for setting
        run(skill, AlarmSentenceMatch(Alarm.Cancel("seven"))).shouldBeInstanceOf<GarAskSlotOutput>()
            .question.shouldBeInstanceOf<GarQuestion.Pick>()
    }

    "dismissing all alarms is checked before it is confirmed, and a no sends nothing" {
        FakeClock().let { clock ->
            run(alarmSkill(clock), AlarmSentenceMatch(Alarm.CancelAll)) shouldBe AlarmOutput.NoAlarms
            clock.started.shouldBeEmpty()
        }
        // more than a day away: the clock app could dismiss none, so the alarms are opened
        FakeClock(next = NextAlarm(LocalDateTime.of(2026, 10, 27, 7, 0), setBy = null)).let { clock ->
            run(alarmSkill(clock), AlarmSentenceMatch(Alarm.CancelAll))
                .shouldBeInstanceOf<AlarmOutput.TooFarAhead>()
            clock.started shouldBe listOf(AlarmIntent.show())
        }
        FakeClock(next = NextAlarm(LocalDateTime.of(2026, 10, 26, 7, 0), setBy = null)).let { clock ->
            val confirm = run(alarmSkill(clock), AlarmSentenceMatch(Alarm.CancelAll))
                .shouldBeInstanceOf<GarConfirmOutput>()
            answer(confirm.nextSkills("Yes", "No")[0], "no").shouldBeInstanceOf<GarCancelledOutput>()
            clock.started.shouldBeEmpty()
        }
    }

    "the change sentences capture both times as said" {
        fun change(input: String): Alarm.Change {
            val (score, sentence) = match(input)
            withClue(input) { score shouldBeGreaterThan 0.8f }
            return sentence.shouldBeInstanceOf<Alarm.Change>()
        }
        fun Alarm.Change.times() = Pair(from?.trim(), to?.trim())

        change("change my seven am alarm to seven thirty").times() shouldBe Pair("seven am", "seven thirty")
        change("move my alarm to eight").times() shouldBe Pair(null, "eight")
        change("change my alarm from seven to eight").times() shouldBe Pair("seven", "eight")
        change("reschedule my alarm for seven to eight").times() shouldBe Pair("seven", "eight")
        change("push my wake up alarm back to eight").times() shouldBe Pair("wake up", "eight")
        change("move my 11 pm alarm to twelve").times() shouldBe Pair("11 pm", "twelve")
        change("change my alarm to 7:30").times() shouldBe Pair(null, "7:30")

        // a change needs times: other things move or change to other things
        for (input in listOf(
            "change the alarm volume to fifty percent", "move the alarm to the living room",
            "change the wake word", "move my meeting to eight", "change my gym alarm to eight",
        )) {
            withClue(input) { match(input).first shouldBe 0f }
        }

        // "my wake up alarm" is the next one, like "my next alarm"
        AlarmTools.changeFrom("wake up") shouldBe ""
        AlarmTools.changeFrom("next") shouldBe ""
        AlarmTools.changeFrom("tomorrow's") shouldBe ""
        AlarmTools.changeFrom("seven am") shouldBe "seven am"
    }

    "changing an alarm sets the new one and then dismisses the old one, after a yes" {
        val tomorrow = NextAlarm(LocalDateTime.of(2026, 10, 26, 7, 0), setBy = null)
        val clock = FakeClock(next = tomorrow)
        val pauses = mutableListOf<Long>()
        val skill = alarmSkill(clock, pauses = pauses)

        // "seven thirty" is the morning's, next to the old alarm
        val confirm = run(skill, AlarmSentenceMatch(Alarm.Change("seven am", "seven thirty")))
            .shouldBeInstanceOf<GarConfirmOutput>()
        confirm.runner.summary(confirm.call, speech = false) shouldBe "Change your 7:00 AM alarm to 7:30 AM?"
        clock.started.shouldBeEmpty()

        val changed = answer(confirm.nextSkills("Yes", "No")[0], "yes")
            .shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<AlarmOutput.Changed>()
        changed.to shouldBe ResolvedTime(LocalTime.of(7, 30))
        changed.rings shouldBe LocalDateTime.of(2026, 10, 26, 7, 30)
        changed.oldKnown.shouldBeTrue()
        changed.oldDismissed.shouldBeTrue()
        clock.started shouldBe listOf(
            AlarmIntent.set(LocalTime.of(7, 30), setOf(), null),
            AlarmIntent.dismiss(CancelTarget.At(LocalTime.of(7, 0))),
        )
        // DeskClock drops an intent that comes too soon after the first
        pauses shouldBe listOf(AlarmSkill.BETWEEN_INTENTS_MILLIS)
    }

    "a change without a time is the next alarm's, and am or pm follows the old alarm" {
        suspend fun confirmed(next: NextAlarm?, from: String?, to: String): GarConfirmOutput =
            run(alarmSkill(FakeClock(next = next)), AlarmSentenceMatch(Alarm.Change(from, to)))
                .shouldBeInstanceOf<GarConfirmOutput>()
        fun GarConfirmOutput.times() =
            Pair(call.values[AlarmTools.FROM], call.values[AlarmTools.TO])

        val morning = NextAlarm(LocalDateTime.of(2026, 10, 26, 7, 0), setBy = null)
        val evening = NextAlarm(LocalDateTime.of(2026, 10, 26, 19, 0), setBy = null)
        fun at(hour: Int, minute: Int = 0) = ResolvedTime(LocalTime.of(hour, minute))

        confirmed(morning, null, "eight").times() shouldBe Pair(at(7), at(8))
        confirmed(morning, "wake up", "eight").times() shouldBe Pair(at(7), at(8))
        confirmed(evening, null, "eight").times() shouldBe Pair(at(19), at(20))
        // "seven" is the next alarm's seven
        confirmed(evening, "seven", "eight").times() shouldBe Pair(at(19), at(20))
        confirmed(morning, "seven", "seven thirty").times() shouldBe Pair(at(7), at(7, 30))
        // the closer one, also across midnight
        confirmed(morning, "11 pm", "one").times() shouldBe Pair(at(23), at(1))
        confirmed(morning, "6 am", "ten").times() shouldBe Pair(at(6), at(10))

        // a "seven" that isn't the next alarm's is asked for
        run(alarmSkill(FakeClock(next = morning)), AlarmSentenceMatch(Alarm.Change("eight", "nine")))
            .shouldBeInstanceOf<GarAskSlotOutput>().slot.name shouldBe AlarmTools.FROM
        // as close either way: 9 AM to "three" is as far from 3 AM as from 3 PM, so GAR asks
        for (from in listOf("nine am", "nine pm")) {
            withClue(from) {
                val change = AlarmSentenceMatch(Alarm.Change(from, "three"))
                val ask = run(alarmSkill(FakeClock(next = morning)), change)
                    .shouldBeInstanceOf<GarAskSlotOutput>()
                ask.slot.name shouldBe AlarmTools.TO
                ask.question.shouldBeInstanceOf<GarQuestion.Pick>().reason shouldBe AmbiguityReason.AM_OR_PM
            }
        }

        ChangeTimeResolver.minutesApart(LocalTime.of(23, 0), LocalTime.MIDNIGHT) shouldBe 60
        ChangeTimeResolver.minutesApart(LocalTime.of(7, 0), LocalTime.of(19, 30)) shouldBe 690
    }

    "a change is checked like a dismissal before it is confirmed" {
        // no alarm to change
        FakeClock().let { clock ->
            run(alarmSkill(clock), AlarmSentenceMatch(Alarm.Change(null, "eight"))) shouldBe AlarmOutput.NoAlarms
            run(alarmSkill(clock), AlarmSentenceMatch(Alarm.Change("seven am", "eight am"))) shouldBe
                    AlarmOutput.NoAlarms
            clock.started.shouldBeEmpty()
        }
        // the next alarm is another app's
        FakeClock(next = NextAlarm(LocalDateTime.of(2026, 10, 26, 6, 0), setBy = "Sleep")).let { clock ->
            run(alarmSkill(clock), AlarmSentenceMatch(Alarm.Change(null, "eight")))
                .shouldBeInstanceOf<AlarmOutput.OtherApp>()
            clock.started.shouldBeEmpty()
        }
        // more than a day away: opened instead
        FakeClock(next = NextAlarm(LocalDateTime.of(2026, 10, 27, 7, 0), setBy = null)).let { clock ->
            run(alarmSkill(clock), AlarmSentenceMatch(Alarm.Change(null, "eight")))
                .shouldBeInstanceOf<AlarmOutput.TooFarAhead>()
            clock.started shouldBe listOf(AlarmIntent.show())
        }
        // something rings: the next alarm isn't the one the user means
        FakeClock(next = NextAlarm(LocalDateTime.of(2026, 10, 26, 7, 0), setBy = null), ringing = true)
            .let { clock ->
                run(alarmSkill(clock), AlarmSentenceMatch(Alarm.Change(null, "eight")))
                    .shouldBeInstanceOf<AlarmOutput.Ringing>()
                clock.started.shouldBeEmpty()
            }
    }

    "a change to the same time or of an unknown alarm" {
        val tomorrow = NextAlarm(LocalDateTime.of(2026, 10, 26, 6, 0), setBy = null)
        FakeClock(next = tomorrow).let { clock ->
            // setting it again and dismissing it would leave none
            run(alarmSkill(clock), AlarmInput.Change(LocalTime.of(7, 0), ResolvedTime(LocalTime.of(7, 0))))
                .shouldBeInstanceOf<AlarmOutput.AlreadyAt>().time shouldBe LocalTime.of(7, 0)
            clock.started.shouldBeEmpty()

            // not the next alarm: maybe more than a day away, so the card says what can happen
            run(alarmSkill(clock), AlarmInput.Change(LocalTime.of(8, 0), ResolvedTime(LocalTime.of(9, 0), WEEKDAYS)))
                .shouldBeInstanceOf<AlarmOutput.Changed>().let {
                    it.oldKnown.shouldBeFalse()
                    it.rings.shouldBeNull() // repeating
                }
            clock.started shouldBe listOf(
                AlarmIntent.set(LocalTime.of(9, 0), WEEKDAYS, null),
                AlarmIntent.dismiss(CancelTarget.At(LocalTime.of(8, 0))),
            )
        }
    }

    "the sentences' own tools build their inputs" {
        val seven = ResolvedTime(LocalTime.of(7, 0))
        val half = ResolvedTime(LocalTime.of(7, 30))
        AlarmTools.inputFromSlots(mapOf(AlarmTools.FROM to seven, AlarmTools.TO to half)) shouldBe
                AlarmInput.Change(LocalTime.of(7, 0), half)
        AlarmTools.inputFromSlots(mapOf(AlarmTools.WHICH to AlarmTools.ALL)) shouldBe
                AlarmInput.Cancel(CancelTarget.All)
        // never offered to the model
        realAlarmTools().map { it.name } shouldNotContain AlarmTools.CHANGE
        realAlarmTools().map { it.name } shouldNotContain AlarmTools.CANCEL_ALL
    }
})
