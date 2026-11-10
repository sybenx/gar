package org.stypox.dicio.skills.notes

import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.RecordingSkill
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.skills.agenda.AgendaRequest
import org.stypox.dicio.skills.agenda.ReminderRequest
import org.stypox.dicio.skills.contact_info.ContactField
import org.stypox.dicio.skills.contact_info.ContactRequest

private fun tool(name: String, skill: String) = ToolSpec(
    name = name,
    skillId = skill,
    sentence = null,
    description = name,
    confirm = false,
    slots = listOf(SlotSpec("what", SlotKind.TEXT, false, "")),
    examples = listOf(),
)

private fun call(tool: ToolSpec, utterance: String) = GarCall(tool, mapOf(), utterance)

/** gar:notes: the model's calendar, reminder, call and text calls that gar:notes answers. */
class NotesCallsTest : StringSpec({
    val query = tool("calendar_query", "calendar")
    val reminder = tool("reminder_add", "calendar")
    val callContact = tool("call_contact", "telephone")
    val timer = tool("timer_set", "timer")

    "the calls' own requests stay theirs" {
        for ((tool, utterance) in listOf(
            query to "what's on my calendar tomorrow",
            reminder to "remind me to call mom at five",
            callContact to "call mom",
            timer to "set a timer to check the oven",
            // only the calendar's tools reach the lists
            callContact to "add milk to my shopping list",
            // a question about a fact goes to the facts skill only with its own sentences
            reminder to "what's my locker number",
        )) {
            withClue(utterance) {
                NotesCalls.output(FakeSkillContext(), call(tool, utterance)) { info, _ -> RecordingSkill(info) } shouldBe null
            }
        }
    }

    "misrouted requests reach their skill, with what the skill's own reading takes" {
        for ((tool, utterance, expected) in listOf(
            Triple(query, "what's my next meeting", "agenda" to AgendaRequest.Next("")),
            Triple(query, "what are my reminders", "reminders" to ReminderRequest.List("")),
            Triple(reminder, "add milk to my shopping list", "lists" to ListRequest.Add(listOf("milk"), "shopping ")),
            Triple(reminder, "take a note call the dentist", "notes" to NoteRequest.Take("call the dentist")),
            Triple(reminder, "remember that my locker is 42", "facts" to FactRequest.Remember("my locker is 42")),
            Triple(reminder, "where did i park", "facts" to FactRequest.Recall("where did i park", true)),
            Triple(callContact, "what's mom's number", "contact_info" to ContactRequest.Info("mom", ContactField.NUMBER)),
        )) {
            withClue(utterance) {
                var ran: RecordingSkill? = null
                NotesCalls.output(FakeSkillContext(), call(tool, utterance)) { info, _ ->
                    RecordingSkill(info).also { ran = it }
                }.shouldBeInstanceOf<FakeOutput>()
                Pair(ran?.correspondingSkillInfo?.id, ran?.inputs?.single()) shouldBe expected
            }
        }
    }
})
