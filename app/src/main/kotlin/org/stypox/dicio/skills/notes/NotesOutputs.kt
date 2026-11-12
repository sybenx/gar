package org.stypox.dicio.skills.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** How GAR says lists, items and times. English, like the sentences. */
object NotesSay {
    /** At most this many items, notes or facts are spoken; the card shows them all. */
    const val SPOKEN = 8

    /** "shopping list", "to do list". */
    fun listName(ctx: SkillContext, name: String): String =
        ctx.getString(R.string.gar_lists_list, name)

    /** "milk", "milk and eggs", "milk, eggs and bread". */
    fun joined(ctx: SkillContext, items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> ctx.getString(R.string.gar_notes_and, items.dropLast(1).joinToString(", "), items.last())
    }

    /** Up to [SPOKEN] items, then "and 4 more". */
    fun spokenItems(ctx: SkillContext, items: List<String>): String {
        if (items.size <= SPOKEN) {
            return joined(ctx, items)
        }
        val more = items.size - SPOKEN + 1
        return joined(
            ctx,
            items.take(SPOKEN - 1) +
                ctx.android.resources.getQuantityString(R.plurals.gar_lists_more, more, more),
        )
    }

    fun sentence(text: String): String = text.replaceFirstChar { it.uppercase() }

    /** "today at 3:12 PM", "yesterday at 9:05 AM", "on October 12". */
    fun whenSaved(ctx: SkillContext, millis: Long, now: ZonedDateTime): String {
        val at = Instant.ofEpochMilli(millis).atZone(now.zone)
        val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(ctx.locale)
        return when (ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())) {
            0L -> ctx.getString(R.string.gar_notes_today, clock.format(at))
            1L -> ctx.getString(R.string.gar_notes_yesterday, clock.format(at))
            else -> ctx.getString(
                R.string.gar_notes_on,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(ctx.locale).format(at),
            )
        }
    }

    /** A fact said back inside a sentence: "your locker is 42." */
    fun factInSentence(text: String): String =
        FactWords.secondPerson(text).replaceFirstChar { it.lowercase() }
}

/** A Share button for a list or the notes, sending their text to an app the user picks. */
@Composable
internal fun ShareButton(text: String) {
    val context = LocalContext.current
    Spacer(modifier = Modifier.height(8.dp))
    GarbleSecondaryButton(onClick = { NotesApps.share(context, text) }) { // GAR
        Text(text = stringResource(R.string.gar_lists_share))
    }
}

sealed interface ListsOutput : SkillOutput {

    class Added(val result: ListAdd) : ListsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val list = NotesSay.listName(ctx, result.list)
            val added = NotesSay.joined(ctx, result.added)
            val already = NotesSay.joined(ctx, result.already)
            return NotesSay.sentence(
                when {
                    result.added.isEmpty() -> ctx.getString(R.string.gar_lists_already, already, list)
                    result.already.isEmpty() -> ctx.getString(R.string.gar_lists_added, added, list)
                    else -> ctx.getString(
                        R.string.gar_lists_added_some, added, list, NotesSay.sentence(already),
                    )
                }
            )
        }
    }

    class Removed(val list: String, val item: String) : ListsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_lists_removed, item, NotesSay.listName(ctx, list))
    }

    class NotOn(val list: String, val item: String) : ListsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_lists_not_on, item, NotesSay.listName(ctx, list))
    }

    /** "Is milk on my list": [found] is the item as kept, or null. */
    class Has(val list: String, val item: String, val found: String?) : ListsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
            if (found != null) R.string.gar_lists_has else R.string.gar_lists_has_not,
            found ?: item,
            NotesSay.listName(ctx, list),
        )
    }

    /** A list read back; [list] is null when there is no list called [name]. */
    class Show(val name: String, val list: GarList?) : ListsOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val shown = NotesSay.listName(ctx, name)
            val items = list?.items ?: return ctx.getString(R.string.gar_lists_none, shown)
            if (items.isEmpty()) {
                return NotesSay.sentence(ctx.getString(R.string.gar_lists_empty, shown))
            }
            return ctx.android.resources.getQuantityString(
                R.plurals.gar_lists_items, items.size, shown, items.size, NotesSay.spokenItems(ctx, items),
            )
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            val items = list?.items.orEmpty()
            Column {
                Headline(text = NotesSay.sentence(NotesSay.listName(ctx, name)))
                if (items.isEmpty()) {
                    Body(text = getSpeechOutput(ctx))
                }
                for (item in items) {
                    Body(text = "• $item")
                }
                if (items.isNotEmpty()) {
                    ShareButton(
                        NotesSay.sentence(NotesSay.listName(ctx, name)) + "\n" +
                            items.joinToString("\n") { "- $it" }
                    )
                }
            }
        }
    }

    class Cleared(val list: String, val deleted: Boolean) : ListsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = NotesSay.sentence(
            ctx.getString(
                if (deleted) R.string.gar_lists_deleted else R.string.gar_lists_cleared,
                NotesSay.listName(ctx, list),
            )
        )
    }

    class Created(val list: String, val existed: Boolean) : ListsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = NotesSay.sentence(
            ctx.getString(
                if (existed) R.string.gar_lists_exists else R.string.gar_lists_created,
                NotesSay.listName(ctx, list),
            )
        )
    }

    class All(val lists: List<GarList>) : ListsOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            if (lists.isEmpty()) {
                ctx.getString(R.string.gar_lists_no_lists)
            } else {
                ctx.android.resources.getQuantityString(
                    R.plurals.gar_lists_all, lists.size, lists.size,
                    NotesSay.joined(ctx, lists.map { it.name }),
                )
            }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                for (list in lists) {
                    Subtitle(text = NotesSay.sentence(NotesSay.listName(ctx, list.name)))
                    Body(text = list.items.joinToString(", "))
                }
            }
        }
    }
}

sealed interface NotesOutput : SkillOutput {

    /** A note saved, read back; [app] is a notes app to also save it in, if one takes notes. */
    class Saved(val text: String, val app: NotesApp?) : NotesOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_notes_saved, text)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            val context = LocalContext.current
            Column {
                Headline(text = getSpeechOutput(ctx))
                if (app != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GarbleSecondaryButton(onClick = { NotesApps.send(context, app, text) }) { // GAR
                        Text(text = stringResource(R.string.gar_notes_save_in, app.label))
                    }
                }
            }
        }
    }

    /** The notes, newest first; [lastOnly] reads only the newest. */
    class Read(val notes: List<GarNote>, val lastOnly: Boolean, val now: ZonedDateTime) : NotesOutput {
        private val newest = notes.reversed()

        override fun getSpeechOutput(ctx: SkillContext): String {
            val last = newest.firstOrNull() ?: return ctx.getString(R.string.gar_notes_none)
            if (lastOnly) {
                return ctx.getString(R.string.gar_notes_last, NotesSay.whenSaved(ctx, last.created, now), last.text)
            }
            val spoken = newest.take(SPOKEN_NOTES).joinToString(" ") { it.text.trimEnd('.') + "." }
            return ctx.android.resources.getQuantityString(R.plurals.gar_notes_count, notes.size, notes.size, spoken)
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                for (note in if (lastOnly) newest.take(1) else newest) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Subtitle(text = NotesSay.sentence(NotesSay.whenSaved(ctx, note.created, now)))
                    Body(text = note.text)
                }
                if (newest.isNotEmpty()) {
                    ShareButton(newest.joinToString("\n\n") { it.text })
                }
            }
        }

        private companion object {
            const val SPOKEN_NOTES = 3
        }
    }

    class DeletedLast(val note: GarNote) : NotesOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_notes_deleted_last, note.text)
    }

    data object DeletedAll : NotesOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_notes_deleted_all)
    }
}

sealed interface FactsOutput : SkillOutput {

    class Remembered(val text: String) : FactsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_facts_remembered, NotesSay.factInSentence(text))
    }

    /** The best answer to a question, or null when nothing remembered answers it. */
    class Recalled(val fact: GarFact?, val now: ZonedDateTime) : FactsOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            fact?.let { FactWords.secondPerson(it.text) } ?: ctx.getString(R.string.gar_facts_unknown)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                Body(
                    text = fact?.let {
                        ctx.getString(R.string.gar_facts_saved_at, NotesSay.whenSaved(ctx, it.saved, now))
                    } ?: ctx.getString(R.string.gar_facts_tip)
                )
            }
        }
    }

    class Forgot(val fact: GarFact) : FactsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_facts_forgot, NotesSay.factInSentence(fact.text))
    }

    data object ForgetNone : FactsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_facts_forget_none)
    }

    data object ForgotAll : FactsOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_facts_forgot_all)
    }

    /** Everything remembered, newest first. */
    class All(val facts: List<GarFact>) : FactsOutput {
        private val newest = facts.reversed()

        override fun getSpeechOutput(ctx: SkillContext): String {
            if (facts.isEmpty()) {
                return ctx.getString(R.string.gar_facts_none)
            }
            val spoken = newest.take(SPOKEN_FACTS).joinToString(" ") { FactWords.secondPerson(it.text) }
            return ctx.android.resources.getQuantityString(R.plurals.gar_facts_all, facts.size, facts.size, spoken)
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                for (fact in newest) {
                    Body(text = FactWords.secondPerson(fact.text))
                }
                if (facts.isEmpty()) {
                    Body(text = ctx.getString(R.string.gar_facts_tip))
                }
            }
        }

        private companion object {
            const val SPOKEN_FACTS = 3
        }
    }
}
