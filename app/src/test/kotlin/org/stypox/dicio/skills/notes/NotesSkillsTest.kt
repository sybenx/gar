package org.stypox.dicio.skills.notes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.ask.GarYesNoOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.sentences.Sentences
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 11, 2, 15, 0, 0, 0, ZoneOffset.UTC)
private val YES_NO = Sentences.UtilYesNo["en"]!!

private fun lists(notes: GarNotes, locked: Boolean = false) =
    ListsSkill(ListsInfo, Sentences.Lists["en"]!!, YES_NO, notes = { notes }, isLocked = { locked })

private fun noteSkill(notes: GarNotes, locked: Boolean = false, app: NotesApp? = null) = NotesSkill(
    NotesInfo, Sentences.Notes["en"]!!, YES_NO,
    notes = { notes }, isLocked = { locked }, notesApp = { app }, now = { NOW },
)

private fun facts(notes: GarNotes, locked: Boolean = false) = FactsSkill(
    FactsInfo, Sentences.Facts["en"]!!, YES_NO,
    notes = { notes }, remembered = { notes.current() }, isLocked = { locked }, now = { NOW },
)

class NotesSkillsTest : StringSpec({
    "lists: add, read back, remove, and ask before clearing" {
        val notes = GarNotes(MemoryNotesStore())
        val ctx = FakeSkillContext()
        lists(notes).generateOutput(ctx, ListRequest.Add(listOf("milk", "eggs"), "shopping "))
            .shouldBeInstanceOf<ListsOutput.Added>().result shouldBe ListAdd("shopping", listOf("milk", "eggs"), listOf())
        lists(notes).generateOutput(ctx, ListRequest.Read(""))
            .shouldBeInstanceOf<ListsOutput.Show>().list?.items shouldBe listOf("milk", "eggs")
        lists(notes).generateOutput(ctx, ListRequest.Has("egg", ""))
            .shouldBeInstanceOf<ListsOutput.Has>().found shouldBe "eggs"
        lists(notes).generateOutput(ctx, ListRequest.Remove("eggs", ""))
            .shouldBeInstanceOf<ListsOutput.Removed>().item shouldBe "eggs"
        lists(notes).generateOutput(ctx, ListRequest.Remove("bread", ""))
            .shouldBeInstanceOf<ListsOutput.NotOn>()

        val ask = lists(notes).generateOutput(ctx, ListRequest.Clear("shopping", delete = false))
            .shouldBeInstanceOf<GarYesNoOutput>()
        notes.current().lists.single().items shouldBe listOf("milk")
        ask.answerYes(ctx).shouldBeInstanceOf<ListsOutput.Cleared>()
        notes.current().lists.single().items shouldBe listOf()
    }

    "lists: adding works locked, reading doesn't" {
        val notes = GarNotes(MemoryNotesStore())
        val ctx = FakeSkillContext()
        lists(notes, locked = true).generateOutput(ctx, ListRequest.Add(listOf("milk"), ""))
            .shouldBeInstanceOf<ListsOutput.Added>()
        lists(notes, locked = true).generateOutput(ctx, ListRequest.Read(""))
            .shouldBeInstanceOf<GarLockedOutput>()
    }

    "notes: saved with the notes app's button, read newest first, deleted" {
        val notes = GarNotes(MemoryNotesStore())
        val ctx = FakeSkillContext()
        val app = NotesApp("com.omgodse.notally", "Notally")
        val saved = noteSkill(notes, app = app).generateOutput(ctx, NoteRequest.Take("call the dentist"))
            .shouldBeInstanceOf<NotesOutput.Saved>()
        saved.text shouldBe "Call the dentist"
        saved.app shouldBe app
        noteSkill(notes).generateOutput(ctx, NoteRequest.Take("buy stamps"))
        noteSkill(notes).generateOutput(ctx, NoteRequest.Read(lastOnly = false))
            .shouldBeInstanceOf<NotesOutput.Read>().notes.map { it.text } shouldBe listOf("Call the dentist", "Buy stamps")
        noteSkill(notes).generateOutput(ctx, NoteRequest.DeleteLast)
            .shouldBeInstanceOf<NotesOutput.DeletedLast>().note.text shouldBe "Buy stamps"
        noteSkill(notes).generateOutput(ctx, NoteRequest.Take(null)).shouldBeInstanceOf<AskNoteOutput>()
        noteSkill(notes, locked = true).generateOutput(ctx, NoteRequest.Read(lastOnly = true))
            .shouldBeInstanceOf<GarLockedOutput>()
        noteSkill(notes).generateOutput(ctx, NoteRequest.DeleteAll).shouldBeInstanceOf<GarYesNoOutput>()
            .answerYes(ctx) shouldBe NotesOutput.DeletedAll
        notes.current().notes shouldBe listOf()
    }

    "facts: remembered, recalled, forgotten; questions only when a fact answers them" {
        val notes = GarNotes(MemoryNotesStore())
        val ctx = FakeSkillContext()
        val skill = facts(notes)
        skill.generateOutput(ctx, FactRequest.Remember("i parked on level 3"))
            .shouldBeInstanceOf<FactsOutput.Remembered>()
        skill.generateOutput(ctx, FactRequest.Recall("where did i park", always = true))
            .shouldBeInstanceOf<FactsOutput.Recalled>().fact?.text shouldBe "i parked on level 3"
        skill.generateOutput(ctx, FactRequest.Recall("whats my locker number", always = false))
            .shouldBeInstanceOf<FactsOutput.Recalled>().fact shouldBe null

        // ranking: "what's my locker number" is the facts skill's once a fact answers it
        val question = "what's my locker number"
        skill.score(FakeSkillContext().forInput(question), question).first shouldBe AlwaysWorstScore
        skill.generateOutput(ctx, FactRequest.Remember("my locker is 42"))
        (skill.score(FakeSkillContext().forInput(question), question).first == AlwaysWorstScore) shouldBe false
        val weather = "what's the weather like"
        skill.score(FakeSkillContext().forInput(weather), weather).first shouldBe AlwaysWorstScore

        skill.generateOutput(ctx, FactRequest.Forget("where i parked", always = true))
            .shouldBeInstanceOf<FactsOutput.Forgot>().fact.text shouldBe "i parked on level 3"
        skill.generateOutput(ctx, FactRequest.Forget("where i parked", always = true)) shouldBe FactsOutput.ForgetNone
        facts(notes, locked = true).generateOutput(ctx, FactRequest.ListAll).shouldBeInstanceOf<GarLockedOutput>()
        skill.generateOutput(ctx, FactRequest.ForgetAll).shouldBeInstanceOf<GarYesNoOutput>().answerYes(ctx)
        notes.current().facts shouldBe listOf()
    }

    "every skill scores worst for what isn't its own" {
        val notes = GarNotes(MemoryNotesStore())
        for (input in listOf("what time is it", "call mom", "set a timer for ten minutes", "remind me to buy milk")) {
            withClue(input) {
                val ctx = FakeSkillContext().forInput(input)
                lists(notes).score(ctx, input).first shouldBe AlwaysWorstScore
                noteSkill(notes).score(ctx, input).first shouldBe AlwaysWorstScore
                facts(notes).score(ctx, input).first shouldBe AlwaysWorstScore
            }
        }
    }
})
