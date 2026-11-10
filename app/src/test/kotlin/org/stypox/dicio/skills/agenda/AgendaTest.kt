package org.stypox.dicio.skills.agenda

import cc.aaro.gar.router.resolve.SlotResolvers
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.ask.GarPickOutput
import org.stypox.dicio.gar.ask.GarYesNoOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.gar.shouldBeTakenBy
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Sunday 2026-10-25 at 10:00, like GAR's other tests ([org.stypox.dicio.gar.TEST_TODAY]). */
private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 25, 10, 0, 0, 0, ZoneOffset.UTC)
private val TOMORROW: LocalDate = LocalDate.of(2026, 10, 26)
private val resolvers = SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") { NOW }

private fun event(id: Long, title: String, begin: LocalDateTime, hours: Long = 1, writable: Boolean = true, repeats: Boolean = false) =
    AgendaEvent(id, title, begin, begin.plusHours(hours), writable = writable, repeats = repeats, calendarName = "Personal")

private val DENTIST = event(1, "Dentist", TOMORROW.atTime(14, 0))
private val STANDUP = event(2, "Team standup", TOMORROW.atTime(9, 0), repeats = true)
private val LUNCH_JOHN = event(3, "Lunch with John", LocalDate.of(2026, 10, 27).atTime(12, 0))
private val MEETING_JOHN = event(4, "Meeting with John", LocalDate.of(2026, 10, 30).atTime(9, 0))
private val HOLIDAYS = event(5, "Holiday party", LocalDate.of(2026, 10, 28).atTime(18, 0), writable = false)
private val PAST = event(6, "Breakfast", LocalDate.of(2026, 10, 25).atTime(8, 0))

/** A calendar in memory: events and GAR's reminders, with what was changed. */
class FakeAgenda(
    events: List<AgendaEvent>,
    reminders: List<AgendaEvent> = listOf(),
    override val allowed: Boolean = true,
) : Agenda {
    val events = events.toMutableList()
    val reminders = reminders.toMutableList()
    val opened = mutableListOf<AgendaEvent>()

    override suspend fun events(from: LocalDateTime, to: LocalDateTime) =
        events.filter { it.end.isAfter(from) && it.begin.isBefore(to) }.sortedBy { it.begin }

    override suspend fun reminders(from: LocalDateTime) = reminders.filter { !it.begin.isBefore(from) }.sortedBy { it.begin }

    override suspend fun move(event: AgendaEvent, begin: LocalDateTime, end: LocalDateTime): AgendaEvent {
        val moved = event.copy(begin = begin, end = end)
        events[events.indexOfFirst { it.id == event.id }] = moved
        return moved
    }

    override suspend fun delete(event: AgendaEvent): Boolean =
        events.removeIf { it.id == event.id } or reminders.removeIf { it.id == event.id }

    override fun open(event: AgendaEvent): Boolean {
        opened += event
        return true
    }
}

private fun agendaSkill(agenda: Agenda, locked: Boolean = false) = AgendaSkill(
    AgendaInfo, Sentences.Agenda["en"]!!, Sentences.UtilYesNo["en"]!!,
    agenda = { agenda }, isLocked = { locked }, now = { NOW }, formatterFactory = { englishFormatter() },
)

private fun remindersSkill(agenda: Agenda, locked: Boolean = false) = RemindersSkill(
    RemindersInfo, Sentences.Reminders["en"]!!, Sentences.UtilYesNo["en"]!!,
    agenda = { agenda }, isLocked = { locked }, now = { NOW }, formatterFactory = { englishFormatter() },
)

private fun calendar() = FakeAgenda(listOf(PAST, DENTIST, STANDUP, LUNCH_JOHN, MEETING_JOHN, HOLIDAYS))

class AgendaTest : StringSpec({
    "calendar requests, whole" {
        val expected = listOf(
            "what's my next event" to AgendaRequest.Next(""),
            "when is my next meeting" to AgendaRequest.Next(""),
            "what's my next meeting with john" to AgendaRequest.Next("john"),
            "when is my dentist appointment" to AgendaRequest.Next("dentist"),
            "what's next on my calendar" to AgendaRequest.Next(""),
            "move my dentist appointment to 3pm" to AgendaRequest.Move("my dentist appointment", "", "3pm"),
            "reschedule the dentist to friday" to AgendaRequest.Move("the dentist", "", "friday"),
            "move my meeting from 3 to 4" to AgendaRequest.Move("my meeting", "3", "4"),
            "cancel my meeting with john" to AgendaRequest.Cancel("meeting with john", all = false),
            "delete tomorrow's 9am event" to AgendaRequest.Cancel("tomorrows 9am event", all = false),
            "clear my calendar for tomorrow" to AgendaRequest.Cancel("tomorrow", all = true),
            "delete everything on friday" to AgendaRequest.Cancel("friday", all = true),
            "where is my meeting with john" to AgendaRequest.Next("john", where = true),
            "where's my next meeting" to AgendaRequest.Next("", where = true),
            "move my call to 4" to AgendaRequest.Move("my call", "", "4"),
            // 2026-11-05: "next" is no part of the name, and other ways to ask by name
            "when's my next haircut appointment" to AgendaRequest.Next("haircut"),
            "when is my upcoming dentist appointment" to AgendaRequest.Next("dentist"),
            "do i have a haircut appointment" to AgendaRequest.Next("haircut"),
            "is there any haircut appointment on the next calendar" to AgendaRequest.Next("haircut"),
            "are there any dentist appointments coming up" to AgendaRequest.Next("dentist"),
            "check for a haircut appointment" to AgendaRequest.Next("haircut"),
            "check my calendar for haircut" to AgendaRequest.Next("haircut"),
        )
        for ((input, request) in expected) {
            withClue(input) { AgendaWords.request(input) shouldBe request }
        }
        for (input in listOf(
            "change the volume to 5", "cancel the timer", "delete the reminder about the milk", "cancel my order",
            "what's on my calendar tomorrow", "push my meeting back by an hour",
            "delete all my voicemails", "clear all notifications", "switch my call to speaker",
            "delete all my contacts",
            // a bare kind or a day is the calendar skill's; a search for no event isn't the agenda's
            "do i have any meetings", "is there a meeting on my calendar", "do i have a meeting tomorrow",
            "check for updates", "look for a lunch place", "do i have any messages",
        )) {
            withClue(input) { AgendaWords.request(input) shouldBe null }
        }
    }

    "reminder requests, whole" {
        val expected = listOf(
            "what are my reminders" to ReminderRequest.List(""),
            "do i have any reminders today" to ReminderRequest.List("today"),
            "cancel the reminder about the dentist" to ReminderRequest.Cancel("dentist"),
            "cancel my dentist reminder" to ReminderRequest.Cancel("dentist"),
            "don't remind me to call mom" to ReminderRequest.Cancel("call mom"),
            "cancel the reminder" to ReminderRequest.Cancel(""),
            "delete all reminders" to ReminderRequest.CancelAll,
            "delete my reminders" to ReminderRequest.CancelAll,
        )
        for ((input, request) in expected) {
            withClue(input) { ReminderWords.request(input) shouldBe request }
        }
        ReminderWords.request("remind me to call mom at five") shouldBe null
    }

    "an event said by day, time and title words" {
        EventQuery.parse("tomorrows 9am event", resolvers) shouldBe EventQuery(TOMORROW, listOf(LocalTime.of(9, 0)), listOf())
        EventQuery.parse("meeting with john", resolvers) shouldBe EventQuery(null, listOf(), listOf("john"))
        EventQuery.parse("dentist appointment", resolvers) shouldBe EventQuery(null, listOf(), listOf("dentist"))
        EventQuery.parse("my meeting", resolvers).nothingSaid shouldBe true
        // a kind of event is a title word, never "the next event"
        EventQuery.parse("my lunch", resolvers) shouldBe EventQuery(null, listOf(), listOf("lunch"))
        EventQuery.parse("lunch with john", resolvers) shouldBe
            EventQuery(null, listOf(), listOf("lunch", "john"), without = listOf("john"))
    }

    "cancel my lunch never means another event" {
        agendaSkill(FakeAgenda(listOf(DENTIST, MEETING_JOHN)))
            .generateOutput(FakeSkillContext(), AgendaRequest.Cancel("my lunch", all = false))
            .shouldBeInstanceOf<AgendaOutput.NotFound>()
        agendaSkill(calendar()).generateOutput(FakeSkillContext(), AgendaRequest.Cancel("my lunch", all = false))
            .shouldBeInstanceOf<GarYesNoOutput>()
        // "lunch with john" still finds the event "John" when no title says lunch
        val john = event(7, "John", TOMORROW.atTime(12, 0))
        val agenda = FakeAgenda(listOf(DENTIST, john))
        agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaRequest.Cancel("lunch with john", all = false))
            .shouldBeInstanceOf<GarYesNoOutput>().answerYes(FakeSkillContext())
            .shouldBeInstanceOf<AgendaOutput.Deleted>().event shouldBe john
    }

    "one time of a series changed on its own counts as repeating" {
        AndroidAgenda.isOfSeries(null, null, null, null) shouldBe false
        AndroidAgenda.isOfSeries("FREQ=WEEKLY", null, null, null) shouldBe true
        AndroidAgenda.isOfSeries(null, null, "42", null) shouldBe true
        AndroidAgenda.isOfSeries(null, null, null, "abc@example.com") shouldBe true
        AndroidAgenda.isOfSeries(" ", null, null, "") shouldBe false
    }

    "the new ways to ask by name are the agenda's whole sentences" {
        for ((input, about) in listOf(
            "when's my next haircut appointment" to "haircut",
            "do i have a haircut appointment" to "haircut",
            "is there any haircut appointment on the next calendar" to "haircut",
            "check for a haircut appointment" to "haircut",
        )) {
            shouldBeTakenBy(input, "agenda", mapOf("about" to about))
        }
    }

    "a name finds a title by word or prefix, and a time ending a sentence has one stop" {
        val haircut = event(8, "haircut with sam", LocalDate.of(2026, 11, 11).atTime(14, 0))
        val agenda = FakeAgenda(listOf(DENTIST, haircut))
        agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaWords.request("when's my next haircut appointment")!!)
            .shouldBeInstanceOf<AgendaOutput.Next>().event shouldBe haircut
        agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaRequest.Next("hair"))
            .shouldBeInstanceOf<AgendaOutput.Next>().event shouldBe haircut
        singleStop("haircut with sam is wednesday, the eleventh at two p.m..") shouldBe
            "haircut with sam is wednesday, the eleventh at two p.m."
        singleStop("Wait... what.") shouldBe "Wait... what."
    }

    "the next event, and the next one about something" {
        val ctx = FakeSkillContext()
        agendaSkill(calendar()).generateOutput(ctx, AgendaRequest.Next(""))
            .shouldBeInstanceOf<AgendaOutput.Next>().event shouldBe STANDUP
        agendaSkill(calendar()).generateOutput(ctx, AgendaRequest.Next("john"))
            .shouldBeInstanceOf<AgendaOutput.Next>().event shouldBe LUNCH_JOHN
        agendaSkill(calendar()).generateOutput(ctx, AgendaRequest.Next("plumber"))
            .shouldBeInstanceOf<AgendaOutput.Next>().event shouldBe null
    }

    "moving asks first with both times, keeps the length and reads the new time back" {
        val agenda = calendar()
        val ask = agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaRequest.Move("my dentist appointment", "", "3"))
            .shouldBeInstanceOf<GarYesNoOutput>()
        agenda.events.first { it.id == DENTIST.id } shouldBe DENTIST
        ask.answerYes(FakeSkillContext()).shouldBeInstanceOf<AgendaOutput.Moved>()
        // "3" for a 2 PM appointment is 3 PM, on its own day
        agenda.events.first { it.id == DENTIST.id }.begin shouldBe TOMORROW.atTime(15, 0)
        agenda.events.first { it.id == DENTIST.id }.end shouldBe TOMORROW.atTime(16, 0)

        MovePlan.of(DENTIST, "friday", resolvers) shouldBe
            MovePlan.To(LocalDate.of(2026, 10, 30).atTime(14, 0), LocalDate.of(2026, 10, 30).atTime(15, 0))
        MovePlan.of(DENTIST, "whenever", resolvers) shouldBe MovePlan.Unclear
    }

    "repeating and read-only events are left alone" {
        val agenda = calendar()
        agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaRequest.Cancel("tomorrows 9am event", all = false))
            .shouldBeInstanceOf<AgendaOutput.Repeats>().opened shouldBe true
        agenda.opened shouldBe listOf(STANDUP)
        agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaRequest.Cancel("holiday party", all = false))
            .shouldBeInstanceOf<AgendaOutput.ReadOnly>()
        agenda.events.size shouldBe 6
    }

    "deleting asks first; several matches ask which one" {
        val agenda = calendar()
        val pick = agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaRequest.Cancel("meeting with john", all = false))
            .shouldBeInstanceOf<GarPickOutput<AgendaEvent>>()
        pick.options shouldBe listOf(LUNCH_JOHN, MEETING_JOHN)
        pick.pick("the second one") shouldBe MEETING_JOHN
        pick.pick("lunch with john") shouldBe LUNCH_JOHN
        pick.pick("never mind") shouldBe null
        val ask = pick.choose(FakeSkillContext(), MEETING_JOHN).shouldBeInstanceOf<GarYesNoOutput>()
        ask.answerYes(FakeSkillContext()).shouldBeInstanceOf<AgendaOutput.Deleted>()
        agenda.events.none { it.id == MEETING_JOHN.id } shouldBe true

        agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaRequest.Cancel("meeting with bob", all = false))
            .shouldBeInstanceOf<AgendaOutput.NotFound>()
    }

    "clearing a day asks about the events GAR may delete" {
        val agenda = calendar()
        val ask = agendaSkill(agenda).generateOutput(FakeSkillContext(), AgendaRequest.Cancel("tomorrow", all = true))
            .shouldBeInstanceOf<GarYesNoOutput>()
        ask.answerYes(FakeSkillContext()).shouldBeInstanceOf<AgendaOutput.DeletedMany>().let {
            it.deleted shouldBe 1
            it.skipped shouldBe 1
        }
        agenda.events.map { it.id } shouldBe listOf(PAST.id, STANDUP.id, LUNCH_JOHN.id, MEETING_JOHN.id, HOLIDAYS.id)
    }

    "locked, or without the calendar, nothing is read" {
        agendaSkill(calendar(), locked = true).generateOutput(FakeSkillContext(), AgendaRequest.Next(""))
            .shouldBeInstanceOf<GarLockedOutput>()
        agendaSkill(FakeAgenda(listOf(), allowed = false)).generateOutput(FakeSkillContext(), AgendaRequest.Next(""))
            .shouldBeInstanceOf<MissingPermissionsSkillOutput>()
    }

    "reminders: listed, and read back and asked about before cancelling" {
        val call = event(10, "call mom", NOW.toLocalDateTime().withHour(17), hours = 0)
        val dentist1 = event(11, "go to the dentist", TOMORROW.atTime(8, 0), hours = 0)
        val dentist2 = event(12, "book the dentist", TOMORROW.atTime(18, 0), hours = 0)
        val agenda = FakeAgenda(listOf(), listOf(call, dentist1, dentist2))
        val ctx = FakeSkillContext()
        remindersSkill(agenda).generateOutput(ctx, ReminderRequest.List(""))
            .shouldBeInstanceOf<RemindersOutput.Listed>().reminders shouldBe listOf(call, dentist1, dentist2)
        remindersSkill(agenda).generateOutput(ctx, ReminderRequest.List("tomorrow"))
            .shouldBeInstanceOf<RemindersOutput.Listed>().reminders shouldBe listOf(dentist1, dentist2)

        val one = remindersSkill(agenda).generateOutput(ctx, ReminderRequest.Cancel("mom"))
            .shouldBeInstanceOf<GarYesNoOutput>()
        agenda.reminders shouldBe listOf(call, dentist1, dentist2)
        one.answerYes(ctx).shouldBeInstanceOf<RemindersOutput.Cancelled>()
        agenda.reminders shouldBe listOf(dentist1, dentist2)

        val ask = remindersSkill(agenda).generateOutput(ctx, ReminderRequest.Cancel("dentist"))
            .shouldBeInstanceOf<GarYesNoOutput>()
        agenda.reminders.size shouldBe 2
        ask.answerYes(ctx).shouldBeInstanceOf<RemindersOutput.CancelledMany>().count shouldBe 2

        remindersSkill(agenda).generateOutput(ctx, ReminderRequest.Cancel("milk"))
            .shouldBeInstanceOf<RemindersOutput.NotFound>()
        // naming one in "Which reminder?" is the choice
        val both = FakeAgenda(listOf(), listOf(dentist1, dentist2))
        remindersSkill(both).generateOutput(ctx, ReminderRequest.Cancel(""))
            .shouldBeInstanceOf<GarPickOutput<AgendaEvent>>().choose(ctx, dentist2)
            .shouldBeInstanceOf<RemindersOutput.Cancelled>()
        both.reminders shouldBe listOf(dentist1)
        // "cancel the reminder" with only one still asks
        remindersSkill(FakeAgenda(listOf(), listOf(dentist1))).generateOutput(ctx, ReminderRequest.Cancel(""))
            .shouldBeInstanceOf<GarYesNoOutput>()
        remindersSkill(FakeAgenda(listOf(), listOf(dentist1))).generateOutput(ctx, ReminderRequest.CancelAll)
            .shouldBeInstanceOf<GarYesNoOutput>()
    }
})
