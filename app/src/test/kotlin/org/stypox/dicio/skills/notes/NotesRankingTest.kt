package org.stypox.dicio.skills.notes

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.agenda.AgendaInfo
import org.stypox.dicio.skills.agenda.AgendaRequest
import org.stypox.dicio.skills.agenda.ReminderRequest
import org.stypox.dicio.skills.agenda.RemindersInfo
import org.stypox.dicio.skills.contact_info.ContactField
import org.stypox.dicio.skills.contact_info.ContactInfoInfo
import org.stypox.dicio.skills.contact_info.ContactRequest
import java.io.File

/** What the user told GAR to remember, for the facts skill's questions. */
private val REMEMBERED = GarNotesData(
    facts = listOf(
        GarFact("my locker is 42", 1), GarFact("i parked on level 3", 2),
        GarFact("my wifi password is hunter2", 3),
    ),
)

/**
 * Every English skill of a new install, with gar:notes' skills built here: the facts skill with
 * [REMEMBERED] (its questions are its own only when a fact answers them). Works whether or not
 * [allEnglishSkills] lists them already.
 */
private fun skills(): List<Skill<*>> {
    val ctx = FakeSkillContext()
    val yesNo = Sentences.UtilYesNo["en"]!!
    val mine = listOf(
        ListsInfo.build(ctx)!!, NotesInfo.build(ctx)!!,
        FactsSkill(FactsInfo, Sentences.Facts["en"]!!, yesNo, remembered = { REMEMBERED }),
        AgendaInfo.build(ctx)!!, RemindersInfo.build(ctx)!!, ContactInfoInfo.build(ctx)!!,
    )
    val ids = mine.map { it.correspondingSkillInfo.id }.toSet()
    return allEnglishSkills().filter { it.correspondingSkillInfo.id !in ids } + mine
}

private val ranker = SkillRanker(
    GarOnlineSkills.forRanking(skills(), UserSettingsSerializer.defaultValue.enabledSkillsMap),
    MockSkill(Specificity.LOW, 0.0f),
)

private val matchCheck = GarMatchCheck.State(
    ToolCatalog.load(
        File("src/main/gar/tools.yml").readText(),
        File("src/main/sentences/skill_definitions.yml").readText(),
    ).tools,
    parserFormatter = ENGLISH_PARSER_FORMATTER,
)

/** The skill and input data that win [input], and whether GAR's match check would reject it. */
private fun rank(input: String): Triple<String?, Any?, Boolean> {
    val best = ranker.getBest(FakeSkillContext().forInput(input), input)
    return Triple(
        best?.skill?.correspondingSkillInfo?.id,
        best?.inputData,
        best != null && GarMatchCheck.rejects(matchCheck, best, input),
    )
}

/**
 * gar:notes (gar/PARITY.md, batch 6): lists, notes, facts, the next event and changes to events,
 * reminders and contacts' details take their phrasings from every other English skill, and
 * nothing else.
 */
class NotesRankingTest : StringSpec({
    "each skill takes its own requests" {
        val expected = listOf(
            "add milk to my shopping list" to Pair("lists", ListRequest.Add(listOf("milk"), "shopping ")),
            "put eggs on the list" to Pair("lists", ListRequest.Add(listOf("eggs"), "")),
            "what's on my shopping list" to Pair("lists", ListRequest.Read("shopping ")),
            "remove eggs from the list" to Pair("lists", ListRequest.Remove("eggs", "")),
            "clear my shopping list" to Pair("lists", ListRequest.Clear("shopping ", false)),
            "take a note call the dentist" to Pair("notes", NoteRequest.Take("call the dentist")),
            "read my notes" to Pair("notes", NoteRequest.Read(false)),
            "note to self buy stamps" to Pair("notes", NoteRequest.Take("buy stamps")),
            "delete my last note" to Pair("notes", NoteRequest.DeleteLast),
            "what do you remember" to Pair("facts", FactRequest.ListAll),
            "is milk on my shopping list" to Pair("lists", ListRequest.Has("milk", "shopping ")),
            "what lists do i have" to Pair("lists", ListRequest.Lists),
            "remember that my locker is 42" to Pair("facts", FactRequest.Remember("my locker is 42")),
            "remember i parked on level 3" to Pair("facts", FactRequest.Remember("i parked on level 3")),
            "where did i park" to Pair("facts", FactRequest.Recall("where did i park", true)),
            "what's my locker number" to Pair("facts", FactRequest.Recall("whats my locker number", false)),
            "forget where i parked" to Pair("facts", FactRequest.Forget("where i parked", true)),
            "remember my parking spot" to Pair("facts", FactRequest.AskWhat("my parking spot")),
            "remember where i parked" to Pair("facts", FactRequest.AskWhat("where i parked")),
            "what's my wifi password" to Pair("facts", FactRequest.Recall("whats my wifi password", false)),
            "what's my next event" to Pair("agenda", AgendaRequest.Next("")),
            "when is my next meeting" to Pair("agenda", AgendaRequest.Next("")),
            "move my dentist appointment to 3pm" to
                Pair("agenda", AgendaRequest.Move("my dentist appointment", "", "3pm")),
            "cancel my meeting with john" to Pair("agenda", AgendaRequest.Cancel("meeting with john", false)),
            "delete tomorrow's 9am event" to Pair("agenda", AgendaRequest.Cancel("tomorrows 9am event", false)),
            "what are my reminders" to Pair("reminders", ReminderRequest.List("")),
            "cancel the reminder about the dentist" to Pair("reminders", ReminderRequest.Cancel("dentist")),
            "delete all reminders" to Pair("reminders", ReminderRequest.CancelAll),
            "cancel my reminder to call mom" to Pair("reminders", ReminderRequest.Cancel("call mom")),
            "don't remind me to call mom" to Pair("reminders", ReminderRequest.Cancel("call mom")),
            "do i have any reminders today" to Pair("reminders", ReminderRequest.List("today")),
            "reschedule the dentist to friday" to Pair("agenda", AgendaRequest.Move("the dentist", "", "friday")),
            "clear my calendar for tomorrow" to Pair("agenda", AgendaRequest.Cancel("tomorrow", true)),
            "what's next on my calendar" to Pair("agenda", AgendaRequest.Next("")),
            "when is my dentist appointment" to Pair("agenda", AgendaRequest.Next("dentist")),
            "what's mom's number" to Pair("contact_info", ContactRequest.Info("mom", ContactField.NUMBER)),
            "what's dad's email" to Pair("contact_info", ContactRequest.Info("dad", ContactField.EMAIL)),
            "what's sarah's address" to Pair("contact_info", ContactRequest.Info("sarah", ContactField.ADDRESS)),
            "where does grandma live" to Pair("contact_info", ContactRequest.Info("grandma", ContactField.ADDRESS)),
            "when is mom's birthday" to Pair("contact_info", ContactRequest.Info("mom", ContactField.BIRTHDAY)),
            "what's my number" to Pair("contact_info", ContactRequest.Info("", ContactField.NUMBER)),
            "add a new contact" to Pair("contact_info", ContactRequest.Add("", "", false)),
            "save this number as joe" to Pair("contact_info", ContactRequest.Add("joe", "", true)),
        )
        for ((input, winner) in expected) {
            withClue(input) { rank(input) shouldBe Triple(winner.first, winner.second, false) }
        }
    }

    "other skills keep theirs" {
        val expected = listOf(
            "what's on my calendar tomorrow" to "calendar",
            "remind me to call mom at five pm" to "calendar",
            "set a reminder for eight am to take my pills" to "calendar",
            "call mom" to "telephone",
            "send a text to mom" to "sms",
            "set a timer for ten minutes" to "timer",
            "what time is it" to "current_time",
            "cancel my alarm" to "alarm",
            "what's my battery level" to "battery",
            "who called me" to "call_log",
            "read my messages" to "messages",
            "cancel the timer" to "timer",
            "remind me to buy milk" to "calendar",
        )
        val mine = setOf("lists", "notes", "facts", "agenda", "reminders", "contact_info")
        for ((input, skill) in expected) {
            withClue(input) {
                val winner = rank(input).first
                winner shouldNotBe null
                (winner in mine) shouldBe false
                winner shouldBe skill
            }
        }
    }

    "a remembered fact doesn't take a question that shares one word with it" {
        for (input in listOf("how do i turn on wifi", "do you know the time")) {
            withClue(input) { rank(input).first shouldNotBe "facts" }
        }
    }
})
