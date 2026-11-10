package cc.aaro.gar.router.resolve

import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.format.testTool
import cc.aaro.gar.router.format.text
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.numbers.ParserFormatter
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

private val NOW = LocalDateTime.of(2026, 10, 25, 14, 30, 15).atZone(ZoneOffset.UTC)
private val english = ParserFormatter(Locale.ENGLISH)
private val filter = SlotPlausibilityFilter({ SlotResolvers(english, "en") { NOW } })

private fun slot(name: String, kind: SlotKind, required: Boolean = false) =
    SlotSpec(name, kind, required, "")

private val alarmSet = testTool("alarm_set", slot("time", SlotKind.TIME, required = true), text("label"))
private val dateQuery = testTool("date_query", slot("day", SlotKind.DATE))
private val timerSet = testTool("timer_set", slot("duration", SlotKind.DURATION, required = true), text("name"))
private val volumeSet = testTool("volume_set", slot("level", SlotKind.NUMBER, required = true))
private val reminderAdd = testTool("reminder_add", text("what", required = true), slot("when", SlotKind.DATETIME, required = true))
private val calendarQuery = testTool("calendar_query", slot("day", SlotKind.DATE)).copy(skillId = "calendar")

private fun call(tool: ToolSpec, vararg slots: Pair<String, String>) =
    RouterOutput.Call(tool, slots.toMap(), tool.slots.filter { it.required && it.name !in slots.toMap() }.map { it.name }, "x")

class SlotPlausibilityFilterTest : StringSpec({
    "words that aren't a value of the slot's kind drop the call" {
        for (call in listOf(
            call(dateQuery, "day" to "france"),
            call(alarmSet, "time" to "ring sarah"),
            call(alarmSet, "time" to "good morning"),
            call(timerSet, "duration" to "pasta"),
            call(volumeSet, "level" to "heat"),
            call(reminderAdd, "what" to "call mom", "when" to "call mom"),
            call(reminderAdd, "what" to "next one", "when" to "next one"), // a number with words
            call(alarmSet, "time" to "one hour"),
            // the tool's own subject, the text of the request, junk (probe and eval)
            call(timerSet, "duration" to "timer"),
            call(alarmSet, "time" to "alarm"),
            call(reminderAdd, "what" to "buy milk", "when" to "to buy milk"),
            call(timerSet, "duration" to "time me"),
            call(alarmSet, "time" to "good night"),
            // a complete value of another kind: another tool's request
            call(dateQuery, "day" to "tomorrow morning"), // the weather, the calendar
            call(dateQuery, "day" to "five pm"),
            call(timerSet, "duration" to "five pm"), // an alarm
            call(timerSet, "duration" to "friday"),
        )) {
            withClue(call) {
                filter.check(call).shouldBeInstanceOf<CallVerdict.Drop>().reason shouldContain
                    "${call.tool.name}."
            }
        }
    }

    "values, ambiguous values and missing slots keep the call" {
        for (call in listOf(
            call(dateQuery),
            call(dateQuery, "day" to "the day after tomorrow"),
            call(dateQuery, "day" to "on friday"),
            call(alarmSet, "time" to "seven"), // am or pm: GAR asks
            call(alarmSet, "time" to "six thirty every weekday", "label" to "anything at all"),
            call(alarmSet), // missing: GAR asks
            call(timerSet, "duration" to "twelve"), // the unit left out: GAR asks how long
            call(dateQuery, "day" to "twelve"),
            call(timerSet, "duration" to "an hour and a half"),
            call(timerSet, "duration" to "a couple of minutes"),
            call(volumeSet, "level" to "fifty percent"),
            call(reminderAdd, "what" to "check the oven", "when" to "in twenty minutes"),
            call(testTool("note", text("a")), "a" to "anything"),
        )) {
            withClue(call) { filter.check(call) shouldBe CallVerdict.Keep }
        }
    }

    "vague values of the slot's kind keep the call: GAR asks about them" {
        for (call in listOf(
            call(timerSet, "duration" to "few minutes"),
            call(timerSet, "duration" to "a little while"),
            call(timerSet, "duration" to "a couple"),
            call(timerSet, "duration" to "a minute or two"),
            call(reminderAdd, "what" to "water the plants", "when" to "later"),
            call(reminderAdd, "what" to "call john", "when" to "after lunch"),
            call(reminderAdd, "what" to "call john", "when" to "soon"),
            call(reminderAdd, "what" to "call john", "when" to "before dinner"),
            call(reminderAdd, "what" to "call john", "when" to "next weekend"),
            call(alarmSet, "time" to "early"),
            call(alarmSet, "time" to "saturday at nine"), // an alarm has no day: GAR asks
            call(dateQuery, "day" to "the first monday of next month"),
            call(volumeSet, "level" to "the minimum"),
            // durations said from now: the timer's, not a time of day
            call(timerSet, "duration" to "twenty minutes from now"),
            call(timerSet, "duration" to "ten minutes later"),
        )) {
            withClue(call) { filter.check(call) shouldBe CallVerdict.Keep }
        }
    }

    "the slot is judged after the app's rewrite, and an emptied slot counts as missing" {
        val rewriting = SlotPlausibilityFilter(
            resolvers = { SlotResolvers(english, "en") { NOW } },
            rewrite = { _, slot, raw ->
                when {
                    slot.kind != SlotKind.NUMBER -> raw
                    raw == "the middle" -> "fifty percent"
                    raw == "nothing" -> " "
                    else -> raw
                }
            },
        )
        rewriting.check(call(volumeSet, "level" to "the middle")) shouldBe CallVerdict.Keep
        rewriting.check(call(volumeSet, "level" to "nothing")) shouldBe CallVerdict.Keep
        rewriting.check(call(volumeSet, "level" to "heat")).shouldBeInstanceOf<CallVerdict.Drop>()
        filter.check(call(volumeSet, "level" to "the middle")).shouldBeInstanceOf<CallVerdict.Drop>()
    }

    "every typed slot of a call is judged, and the reason names the one that failed" {
        val calendarAdd = testTool("calendar_add", text("title"), slot("when", SlotKind.DATETIME, required = true),
            slot("duration", SlotKind.DURATION))
        val badDuration = filter.check(call(calendarAdd, "when" to "tomorrow at seven pm", "duration" to "pasta"))
        badDuration.shouldBeInstanceOf<CallVerdict.Drop>().reason shouldContain "calendar_add.duration"
        val badWhen = filter.check(call(calendarAdd, "when" to "pasta", "duration" to "an hour"))
        badWhen.shouldBeInstanceOf<CallVerdict.Drop>().reason shouldContain "calendar_add.when"
        // an ambiguous first slot doesn't end the checks
        val day = filter.check(call(calendarAdd, "when" to "seven", "duration" to "friday"))
        day.shouldBeInstanceOf<CallVerdict.Drop>().reason shouldContain
            "calendar_add.duration 'friday' is no duration: it is a day"
        filter.check(call(calendarAdd, "when" to "tomorrow at seven pm", "duration" to "an hour")) shouldBe
            CallVerdict.Keep
    }

    "the default rewrites, with the real tool names and skill ids" {
        val alarmSetReal = alarmSet.copy(skillId = "alarm")
        val alarmCancel = testTool("alarm_cancel", slot("time", SlotKind.TIME)).copy(skillId = "alarm")
        val volume = volumeSet.copy(skillId = "volume")
        val dateQueryReal = dateQuery.copy(skillId = "date")
        for (call in listOf(
            call(alarmSetReal, "time" to "tomorrow morning"), // emptied: GAR asks "What time?"
            call(alarmCancel, "time" to "my next alarm"),
            call(volume, "level" to "half"),
            call(volume, "level" to "the middle"),
            call(volume, "level" to "volume"),
            call(volume, "level" to "to the max"),
            call(dateQueryReal, "day" to "date"),
            call(dateQueryReal, "day" to "the week"),
            call(dateQueryReal, "day" to "a week"),
        )) {
            withClue(call) { filter.check(call) shouldBe CallVerdict.Keep }
        }
        filter.check(call(volume, "level" to "full blast")).shouldBeInstanceOf<CallVerdict.Drop>()
        // cancelling every alarm is no tool's request
        filter.check(call(alarmCancel, "time" to "all")).shouldBeInstanceOf<CallVerdict.Drop>()
    }

    "the order and reach of the drop rules" {
        for (call in listOf(
            // a bare number is kept before a time of day could drop it
            call(dateQuery, "day" to "seven"),
            call(dateQuery, "day" to "december twelve"),
            call(dateQuery, "day" to "march five"),
            call(dateQuery, "day" to "in a month"), // a day, not a time of day a month from now
            call(alarmSet, "time" to "in ten minutes"),
            call(alarmSet, "time" to "seven to eight"), // a range: GAR asks
            call(alarmSet, "time" to "every other day"),
            call(alarmSet, "time" to "six am from monday to friday"),
            call(alarmSet, "time" to "nineteen thirty"), // a bare number (1930)
            call(reminderAdd, "what" to "x", "when" to "one hour"),
            call(reminderAdd, "what" to "x", "when" to "two"),
            call(reminderAdd, "what" to "x", "when" to "in an hour or two"),
            call(reminderAdd, "what" to "x", "when" to "december"),
            // a month without its day: GAR asks which day
            call(dateQuery, "day" to "december"),
            call(dateQuery, "day" to "next month"),
            call(calendarQuery, "day" to "in january"),
        )) {
            withClue(call) { filter.check(call) shouldBe CallVerdict.Keep }
        }
        filter.check(call(dateQuery, "day" to "seven fifteen")).shouldBeInstanceOf<CallVerdict.Drop>()
            .reason shouldContain "it is a time of day"
        filter.check(call(alarmSet, "time" to "ten minutes")).shouldBeInstanceOf<CallVerdict.Drop>()
            .reason shouldContain "it is a duration"
    }

    "a time of day as a day: the calendar's events of that day, but no date" {
        // calendar_query's rewrite takes the day of it, so the call is kept
        for (day in listOf(
            "tonight", "tomorrow morning", "this afternoon", "this evening", "friday night",
            "tomorrow at three pm", "tonight's", "at seven", "noon",
        )) {
            withClue(day) { filter.check(call(calendarQuery, "day" to day)) shouldBe CallVerdict.Keep }
        }
        // date_query has no use for the time: "what's the weather tomorrow morning"
        for (day in listOf("tonight", "tomorrow morning", "this afternoon", "tomorrow at three pm")) {
            withClue(day) {
                filter.check(call(dateQuery, "day" to day)).shouldBeInstanceOf<CallVerdict.Drop>().reason shouldContain
                    "it is a time of day"
            }
        }
        // what isn't a day stays dropped, and a bare number is still asked about
        filter.check(call(calendarQuery, "day" to "france")).shouldBeInstanceOf<CallVerdict.Drop>()
        filter.check(call(calendarQuery, "day" to "seven")) shouldBe CallVerdict.Keep
    }

    "languages without number and date support keep every call" {
        for (resolvers in listOf(SlotResolvers(null, "en"), SlotResolvers(english, "xx"))) {
            SlotPlausibilityFilter({ resolvers }).check(call(dateQuery, "day" to "france")) shouldBe
                CallVerdict.Keep
        }
    }
})
