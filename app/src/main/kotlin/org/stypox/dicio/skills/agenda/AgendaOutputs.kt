package org.stypox.dicio.skills.agenda

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.skills.notes.NotesSay
import org.stypox.dicio.util.getString

/**
 * [text] with one full stop where a spoken time ending in "p.m." met the sentence's own: "haircut
 * guy is wednesday, the eleventh at two p.m.." (2026-11-05).
 */
internal fun singleStop(text: String): String = text.replace(DOUBLE_STOP, ".")

private val DOUBLE_STOP = Regex("(?<!\\.)\\.\\.(?=\\s|$)")

sealed interface AgendaOutput : SkillOutput {

    /**
     * The next event (about [about], if said), or null when there is none within [days]: "Dentist
     * is tomorrow at 2:00 PM." for one asked about by name, with its place for [where].
     */
    class Next(
        val event: AgendaEvent?,
        val about: String?,
        val days: Long,
        val where: Boolean = false,
    ) : AgendaOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val words = EventWords(ctx, GarValueFormatter.forContext(ctx))
            return singleStop(when {
                event == null && about != null -> ctx.getString(R.string.gar_agenda_next_none_about, about, days)
                event == null -> ctx.getString(R.string.gar_agenda_next_none, days)
                where && event.location != null -> ctx.getString(
                    R.string.gar_agenda_where, words.title(event), event.location, words.time(event),
                )
                else -> {
                    val said = if (about != null) {
                        ctx.getString(R.string.gar_agenda_when, words.title(event), words.time(event))
                    } else {
                        ctx.getString(R.string.gar_agenda_next, words.both(event))
                    }
                    if (where) said + " " + ctx.getString(R.string.gar_agenda_no_place) else said
                }
            })
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                event?.location?.let { Body(text = it) }
                event?.calendarName?.takeIf { it.isNotEmpty() }?.let { Body(text = it) }
            }
        }
    }

    /** [event] as the provider has it after the move. */
    class Moved(val event: AgendaEvent, private val words: EventWords) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            singleStop(ctx.getString(R.string.gar_agenda_moved, words.title(event), words.time(event)))
    }

    class Deleted(val event: AgendaEvent, private val words: EventWords) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            singleStop(ctx.getString(R.string.gar_agenda_deleted, words.both(event)))
    }

    /** [skipped] events were left alone: repeating or read-only. */
    class DeletedMany(val deleted: Int, val skipped: Int) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val resources = ctx.android.resources
            val done = resources.getQuantityString(R.plurals.gar_agenda_deleted_many, deleted, deleted)
            return if (skipped == 0) done else done + " " +
                resources.getQuantityString(R.plurals.gar_agenda_skipped, skipped, skipped)
        }
    }

    class NotFound(val ref: String) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_agenda_not_found, ref.removePrefix("my ").removePrefix("the "))
    }

    data object NothingThatDay : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_agenda_nothing_that_day)
    }

    data object NoneChangeable : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_agenda_none_changeable)
    }

    /** A repeating event, which GAR opened in the calendar app if [opened]. */
    class Repeats(val title: String?, val opened: Boolean) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
            if (opened) R.string.gar_agenda_repeats_opened else R.string.gar_agenda_repeats,
            title ?: ctx.getString(R.string.skill_calendar_untitled),
        )
    }

    class ReadOnly(val title: String?, val calendar: String) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
            R.string.gar_agenda_read_only, title ?: ctx.getString(R.string.skill_calendar_untitled), calendar,
        )
    }

    class UnclearTime(val event: AgendaEvent, private val words: EventWords) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_agenda_unclear_time, words.title(event))
    }

    class AllDayTime(val event: AgendaEvent, private val words: EventWords) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.gar_agenda_all_day_time, words.title(event))
    }

    class AlreadyThen(val event: AgendaEvent, private val words: EventWords) : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            singleStop(ctx.getString(R.string.gar_agenda_already, words.both(event)))
    }

    data object PastTime : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.skill_calendar_past_time)
    }

    data object Failed : AgendaOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_agenda_failed)
    }
}

sealed interface RemindersOutput : SkillOutput {

    /** The upcoming reminders, soonest first; [day] as said ("today"), "" for all of them. */
    class Listed(val reminders: List<AgendaEvent>, val day: String, private val words: EventWords) : RemindersOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            if (reminders.isEmpty()) {
                return if (day.isEmpty()) {
                    ctx.getString(R.string.gar_reminders_none)
                } else {
                    ctx.getString(R.string.gar_reminders_none_day, day)
                }
            }
            val spoken = reminders.take(SPOKEN).map { words.both(it) }
            val more = reminders.size - spoken.size
            val list = NotesSay.joined(
                ctx,
                spoken + listOfNotNull(
                    more.takeIf { it > 0 }?.let { ctx.android.resources.getQuantityString(R.plurals.gar_lists_more, it, it) }
                ),
            )
            return ctx.android.resources.getQuantityString(R.plurals.gar_reminders_list, reminders.size, reminders.size, list)
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                for (reminder in reminders) {
                    Body(text = words.both(reminder, speech = false))
                }
            }
        }

        private companion object {
            const val SPOKEN = 3
        }
    }

    class Cancelled(val reminder: AgendaEvent, private val words: EventWords) : RemindersOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            singleStop(ctx.getString(R.string.gar_reminders_cancelled, words.both(reminder)))
    }

    class CancelledMany(val count: Int) : RemindersOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.android.resources.getQuantityString(R.plurals.gar_reminders_cancelled_many, count, count)
    }

    /** No reminder about [about]; [noReminders] when there is none at all. */
    class NotFound(val about: String, val noReminders: Boolean) : RemindersOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = when {
            noReminders || about.isEmpty() -> ctx.getString(R.string.gar_reminders_none)
            else -> ctx.getString(R.string.gar_reminders_not_found, about)
        }
    }
}
