package org.stypox.dicio.skills.notes

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.ask.GarYesNoOutput
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.conversation.GarConversationSkill
import org.stypox.dicio.gar.conversation.GarOpenAnswer
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.getString
import java.time.ZonedDateTime

/**
 * "Add milk to my shopping list", "what's on my list", "remove eggs from the list", "clear my
 * shopping list" (gar/PARITY.md, batch 6), English only, no tool. Lists are GAR's own
 * ([GarNotes]), so GAR reads them back, like Google's lists. Dicio's matcher takes a sentence with
 * a word left out, so a match only counts when [ListWords] reads the whole input as a list request.
 * Adding works on a locked phone; reading, removing and clearing need it unlocked.
 */
class ListsSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Sentences.Lists>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    private val notes: (SkillContext) -> GarNotes = { GarNotes.get(it.android) },
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
) : Skill<ListRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, ListRequest?> {
        val request = ListWords.request(input) ?: return Pair(AlwaysWorstScore, null)
        return Pair(data.score(ctx, input).first, request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: ListRequest?): SkillOutput {
        val request = inputData ?: throw IllegalArgumentException("No list request")
        val store = notes(ctx)
        if (request !is ListRequest.Add && request !is ListRequest.Create && isLocked(ctx)) {
            return GarLockedOutput()
        }
        val current = store.current()
        return when (request) {
            is ListRequest.Add -> {
                val name = NotesBook.listName(current, request.list)
                var result: ListAdd? = null
                store.update { data -> NotesBook.add(data, name, request.items).also { result = it.second }.first }
                ListsOutput.Added(result!!)
            }
            is ListRequest.Create -> {
                val name = NotesBook.canonicalList(request.list).ifEmpty { NotesBook.DEFAULT_LIST }
                val existed = NotesBook.list(current, name) != null
                store.update { NotesBook.create(it, name) }
                ListsOutput.Created(name, existed)
            }
            is ListRequest.Remove -> {
                val name = NotesBook.listName(current, request.list)
                val item = NotesBook.findItem(current, name, request.item)
                    ?: return ListsOutput.NotOn(name, request.item)
                store.update { NotesBook.remove(it, name, item) }
                ListsOutput.Removed(name, item)
            }
            is ListRequest.Has -> {
                val name = NotesBook.listName(current, request.list)
                if (request.item in ANYTHING) {
                    ListsOutput.Show(name, NotesBook.list(current, name))
                } else {
                    ListsOutput.Has(name, request.item, NotesBook.findItem(current, name, request.item))
                }
            }
            is ListRequest.Read -> {
                val name = NotesBook.listName(current, request.list)
                ListsOutput.Show(name, NotesBook.list(current, name))
            }
            is ListRequest.Clear -> clear(ctx, store, NotesBook.listName(current, request.list), request.delete)
            ListRequest.Lists -> ListsOutput.All(current.lists)
        }
    }

    /** Asks first, with how many items go: a list read back is hard to make again. */
    private fun clear(ctx: SkillContext, store: GarNotes, name: String, delete: Boolean): SkillOutput {
        val list = NotesBook.list(store.current(), name) ?: return ListsOutput.Show(name, null)
        if (list.items.isEmpty() && !delete) {
            return ListsOutput.Show(name, list)
        }
        val count = list.items.size
        return GarYesNoOutput(
            info = correspondingSkillInfo,
            yesNoData = yesNoData,
            question = { c ->
                val shown = NotesSay.listName(c, name)
                when {
                    !delete -> c.android.resources
                        .getQuantityString(R.plurals.gar_lists_clear_question, count, shown, count)
                    count == 0 -> c.getString(R.string.gar_lists_delete_empty_question, shown)
                    else -> c.android.resources
                        .getQuantityString(R.plurals.gar_lists_delete_question, count, shown, count)
                }
            },
            details = { list.items },
        ) {
            store.update { if (delete) NotesBook.delete(it, name) else NotesBook.clear(it, name) }
            ListsOutput.Cleared(name, delete)
        }
    }

    private companion object {
        /** "Is there anything on my list": the list itself. */
        val ANYTHING = setOf("anything", "something", "stuff", "things", "everything")
    }
}

/**
 * "Take a note: call the dentist", "read my notes", "delete my last note" (gar/PARITY.md, batch
 * 6), English only, no tool. Notes are GAR's own ([GarNotes]), so GAR reads them back; the card
 * of a new note also offers it to a notes app on the phone ([NotesApps]). Only taken when
 * [NoteWords] reads the whole input as a note request; reading and deleting need an unlocked
 * phone.
 */
class NotesSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Sentences.Notes>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    private val notes: (SkillContext) -> GarNotes = { GarNotes.get(it.android) },
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
    private val notesApp: (SkillContext) -> NotesApp? = { NotesApps.find(it.android.packageManager) },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : Skill<NoteRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, NoteRequest?> {
        val request = NoteWords.request(input) ?: return Pair(AlwaysWorstScore, null)
        return Pair(data.score(ctx, input).first, request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: NoteRequest?): SkillOutput {
        val request = inputData ?: throw IllegalArgumentException("No note request")
        if (request !is NoteRequest.Take && isLocked(ctx)) {
            return GarLockedOutput()
        }
        val store = notes(ctx)
        return when (request) {
            is NoteRequest.Take -> request.text?.let { save(ctx, it) } ?: AskNoteOutput(this)
            is NoteRequest.Read -> NotesOutput.Read(store.current().notes, request.lastOnly, now())
            NoteRequest.DeleteLast -> {
                val last = store.current().notes.lastOrNull() ?: return NotesOutput.Read(listOf(), true, now())
                store.update { NotesBook.removeLastNote(it) }
                NotesOutput.DeletedLast(last)
            }
            NoteRequest.DeleteAll -> {
                val count = store.current().notes.size
                if (count == 0) {
                    return NotesOutput.Read(listOf(), false, now())
                }
                GarYesNoOutput(
                    info = correspondingSkillInfo,
                    yesNoData = yesNoData,
                    question = { c ->
                        c.android.resources.getQuantityString(R.plurals.gar_notes_delete_all_question, count, count)
                    },
                ) {
                    store.update { NotesBook.clearNotes(it) }
                    NotesOutput.DeletedAll
                }
            }
        }
    }

    /** Saves [text] as a note, read back, with the notes app's button on the card. */
    internal fun save(ctx: SkillContext, text: String): SkillOutput {
        val note = text.trim().replaceFirstChar { it.uppercase() }
        notes(ctx).update { NotesBook.addNote(it, note, now().toInstant().toEpochMilli()) }
        return NotesOutput.Saved(note, notesApp(ctx))
    }

    /**
     * The answer to "What's the note?": any words, as said, unless a cancel phrase. It gives way
     * to the conversation's own commands ("repeat that"), as a reminder's text does.
     */
    class TextAnswer(private val skill: NotesSkill) :
        Skill<String?>(skill.correspondingSkillInfo, Specificity.HIGH), GarOpenAnswer {

        override fun score(ctx: SkillContext, input: String): Pair<Score, String?> {
            val text = input.trim()
            return if (text.isEmpty() || isCancelPhrase(text)) {
                Pair(AlwaysWorstScore, null)
            } else {
                Pair(AlwaysBestScore, text)
            }
        }

        override fun yieldsTo(command: SkillWithResult<*>): Boolean = command.skill is GarConversationSkill

        override suspend fun generateOutput(ctx: SkillContext, inputData: String?): SkillOutput =
            GarFollowUpOutput(skill.save(ctx, inputData!!))
    }
}

/**
 * "Remember that my locker is 42", "where did I park", "what's my locker number", "forget where
 * I parked" (gar/PARITY.md, batch 6), English only, no tool. Facts are GAR's own ([GarNotes]),
 * found by their words ([NotesBook.recall]); nothing leaves the phone. A question is this skill's
 * only when a remembered fact answers it ([FactRequest.Recall.always] aside), so "what's my next
 * event" stays the calendar's; list and note requests stay theirs.
 */
class FactsSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Sentences.Facts>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    internal val notes: (SkillContext) -> GarNotes = { GarNotes.get(it.android) },
    /** What is remembered, for [score]: what this process loaded, never the disk. */
    private val remembered: () -> GarNotesData? = { GarNotes.peek()?.current() },
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : Skill<FactRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, FactRequest?> {
        val request = FactWords.request(input)
        val taken = when (request) {
            null -> false
            is FactRequest.Recall -> request.always || ListWords.request(input) == null &&
                NoteWords.request(input) == null &&
                NotesBook.recall(remembered() ?: GarNotesData(), request.question, strict = true).isNotEmpty()
            is FactRequest.Forget -> request.always ||
                NotesBook.recall(remembered() ?: GarNotesData(), request.what).isNotEmpty()
            else -> ListWords.request(input) == null && NoteWords.request(input) == null
        }
        return if (taken) Pair(data.score(ctx, input).first, request) else Pair(AlwaysWorstScore, null)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: FactRequest?): SkillOutput {
        val request = inputData ?: throw IllegalArgumentException("No fact request")
        if (request !is FactRequest.Remember && request !is FactRequest.AskWhat && isLocked(ctx)) {
            return GarLockedOutput()
        }
        val store = notes(ctx)
        return when (request) {
            is FactRequest.Remember -> remember(store, request.text)
            is FactRequest.AskWhat -> AskFactOutput(this, request.about)
            is FactRequest.Recall -> FactsOutput.Recalled(
                NotesBook.recall(store.current(), request.question).firstOrNull(), now(),
            )
            is FactRequest.Forget -> {
                val fact = NotesBook.recall(store.current(), request.what).firstOrNull()
                    ?: return FactsOutput.ForgetNone
                store.update { NotesBook.forget(it, fact) }
                FactsOutput.Forgot(fact)
            }
            FactRequest.ForgetAll -> {
                val count = store.current().facts.size
                if (count == 0) {
                    return FactsOutput.All(listOf())
                }
                GarYesNoOutput(
                    info = correspondingSkillInfo,
                    yesNoData = yesNoData,
                    question = { c ->
                        c.android.resources.getQuantityString(R.plurals.gar_facts_forget_all_question, count, count)
                    },
                ) {
                    store.update { NotesBook.forgetAll(it) }
                    FactsOutput.ForgotAll
                }
            }
            FactRequest.ListAll -> FactsOutput.All(store.current().facts)
        }
    }

    internal fun remember(store: GarNotes, text: String): SkillOutput {
        store.update { NotesBook.remember(it, text, now().toInstant().toEpochMilli()) }
        return FactsOutput.Remembered(text)
    }

    /**
     * The answer to "What should I remember about your parking spot?": any words, kept with what
     * it asked about ([FactWords.completed]), unless a cancel phrase. It gives way to the
     * conversation's own commands, as a note's text does.
     */
    class FactAnswer(private val skill: FactsSkill, private val about: String) :
        Skill<String?>(skill.correspondingSkillInfo, Specificity.HIGH), GarOpenAnswer {

        override fun score(ctx: SkillContext, input: String): Pair<Score, String?> {
            val text = input.trim()
            return if (text.isEmpty() || isCancelPhrase(text) || NotesText.of(text).isEmpty()) {
                Pair(AlwaysWorstScore, null)
            } else {
                Pair(AlwaysBestScore, text)
            }
        }

        override fun yieldsTo(command: SkillWithResult<*>): Boolean = command.skill is GarConversationSkill

        override suspend fun generateOutput(ctx: SkillContext, inputData: String?): SkillOutput =
            GarFollowUpOutput(skill.remember(skill.notes(ctx), FactWords.completed(about, inputData!!)))
    }
}

/** "What should I remember about your parking spot?", answered with the fact or "cancel". */
internal class AskFactOutput(private val skill: FactsSkill, private val about: String) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.gar_facts_ask, NotesSay.factInSentence(about).removeSuffix("."))

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(
                FactsSkill.FactAnswer(skill, about), GarCancelSkill(skill.correspondingSkillInfo),
            ),
        )
}

/** "What's the note?", answered with the note's text or "cancel". */
internal class AskNoteOutput(private val skill: NotesSkill) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_notes_ask)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(NotesSkill.TextAnswer(skill), GarCancelSkill(skill.correspondingSkillInfo)),
        )
}
