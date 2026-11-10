package org.stypox.dicio.skills.agenda

import android.util.Log
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.SlotResolvers
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.gar.ask.GarPickOutput
import org.stypox.dicio.gar.ask.GarYesNoOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.notes.NotesSay
import org.stypox.dicio.skills.notes.isPhoneLocked
import org.stypox.dicio.util.getString
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * Runs a change the user said yes to, or picked: the question was asked while the calendar could
 * be read, so a permission taken away since or a provider error is said here instead of reaching
 * Dicio's error card.
 */
internal suspend fun afterYes(info: SkillInfo, change: suspend () -> SkillOutput): SkillOutput = try {
    change()
} catch (e: SecurityException) {
    MissingPermissionsSkillOutput(info)
} catch (e: Exception) {
    Log.e("AgendaSkill", "Could not change the calendar", e)
    AgendaOutput.Failed
}

/** Deletes one of several: one that fails doesn't stop the rest (a lost permission does). */
internal suspend fun deleteOrFalse(agenda: Agenda, event: AgendaEvent): Boolean = try {
    agenda.delete(event)
} catch (e: SecurityException) {
    throw e
} catch (e: Exception) {
    Log.e("AgendaSkill", "Could not delete event ${event.id}", e)
    false
}

/** How the agenda and reminders skills say an event: "Dentist, tomorrow at 2:00 PM". */
class EventWords(private val ctx: SkillContext, private val formatter: GarValueFormatter) {
    fun title(event: AgendaEvent): String = event.title ?: ctx.getString(R.string.skill_calendar_untitled)

    /** "tomorrow at 2:00 PM", "Friday" for an all-day event. */
    fun time(event: AgendaEvent, speech: Boolean = true): String =
        time(event.begin, event.allDay, speech)

    fun time(begin: LocalDateTime, allDay: Boolean, speech: Boolean = true): String =
        (if (allDay) formatter.format(begin.toLocalDate(), speech) else formatter.format(begin, speech))
            .orEmpty()

    /** "Dentist, tomorrow at 2:00 PM". */
    fun both(event: AgendaEvent, speech: Boolean = true): String =
        ctx.getString(R.string.gar_agenda_event, title(event), time(event, speech))
}

/**
 * "What's my next event", "when is my dentist appointment", "move my dentist appointment to
 * 3pm", "cancel my meeting with john", "clear my calendar tomorrow" (gar/PARITY.md, batch 6),
 * English only, no tool: the calendar skill keeps adding events and reading a day. Changes are
 * read back and asked about first, then read back from the provider once done. Repeating events
 * are opened in the calendar app instead (changing one instance of a series needs the app's own
 * choice of "this one or all"), and events in read-only calendars are left alone.
 */
class AgendaSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Sentences.Agenda>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    private val agenda: (SkillContext) -> Agenda = { AndroidAgenda(it.android) },
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val formatterFactory: (SkillContext) -> GarValueFormatter = GarValueFormatter::forContext,
) : Skill<AgendaRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, AgendaRequest?> {
        val request = AgendaWords.request(input) ?: return Pair(AlwaysWorstScore, null)
        return Pair(data.score(ctx, input).first, request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: AgendaRequest?): SkillOutput {
        val request = inputData ?: throw IllegalArgumentException("No agenda request")
        if (isLocked(ctx)) {
            return GarLockedOutput()
        }
        val agenda = agenda(ctx)
        if (!agenda.allowed) {
            return MissingPermissionsSkillOutput(correspondingSkillInfo)
        }
        val resolvers = SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now)
        val words = EventWords(ctx, formatterFactory(ctx))
        return try {
            when (request) {
                is AgendaRequest.Next -> next(agenda, request.about, request.where)
                is AgendaRequest.Move -> move(ctx, agenda, resolvers, words, request)
                is AgendaRequest.Cancel -> cancel(ctx, agenda, resolvers, words, request)
            }
        } catch (e: SecurityException) {
            MissingPermissionsSkillOutput(correspondingSkillInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read or change the calendar", e)
            AgendaOutput.Failed
        }
    }

    private suspend fun next(agenda: Agenda, about: String, where: Boolean): SkillOutput {
        val from = now().toLocalDateTime()
        val words = about.split(" ").filter { it.isNotEmpty() && it !in ABOUT_FILLERS }
        val event = agenda.events(from, from.plusDays(LOOK_AHEAD_DAYS))
            .filter { !it.begin.isBefore(from) || (it.allDay && it.end.isAfter(from) && words.isNotEmpty()) }
            .firstOrNull { event -> words.all { NameMatcher.sharesWord(it, event.title.orEmpty()) } }
        return AgendaOutput.Next(event, about.takeIf { words.isNotEmpty() }, LOOK_AHEAD_DAYS, where)
    }

    /** The events [ref] means, soonest first (the next one when it only says "my meeting"). */
    private suspend fun find(agenda: Agenda, query: EventQuery): List<AgendaEvent> {
        val now = now().toLocalDateTime()
        val (from, to) = if (query.day != null) {
            Pair(query.day.atStartOfDay(), query.day.plusDays(1).atStartOfDay())
        } else {
            Pair(now, now.plusDays(LOOK_AHEAD_DAYS))
        }
        val all = agenda.events(from, to)
        val events = all.filter(query::matches).ifEmpty {
            query.loosened()?.let { loose -> all.filter(loose::matches) }.orEmpty()
        }
        return if (query.nothingSaid) events.filter { !it.begin.isBefore(now) }.take(1) else events
    }

    private suspend fun move(
        ctx: SkillContext,
        agenda: Agenda,
        resolvers: SlotResolvers,
        words: EventWords,
        request: AgendaRequest.Move,
    ): SkillOutput {
        var query = EventQuery.parse(request.ref, resolvers)
        if (request.from.isNotEmpty()) {
            EventQuery.readWhen(request.from, resolvers)?.let { (day, times) ->
                query = query.copy(day = query.day ?: day, times = query.times.ifEmpty { times })
            }
        }
        val events = find(agenda, query)
        return choose(ctx, events, words, request.ref) { event -> planMove(ctx, agenda, resolvers, words, event, request.to) }
    }

    private fun planMove(
        ctx: SkillContext,
        agenda: Agenda,
        resolvers: SlotResolvers,
        words: EventWords,
        event: AgendaEvent,
        to: String,
    ): SkillOutput {
        unchangeable(agenda, event)?.let { return it }
        val plan = when (val plan = MovePlan.of(event, to, resolvers)) {
            MovePlan.Unclear -> return AgendaOutput.UnclearTime(event, words)
            MovePlan.AllDayTime -> return AgendaOutput.AllDayTime(event, words)
            is MovePlan.To -> plan
        }
        if (plan.begin == event.begin) {
            return AgendaOutput.AlreadyThen(event, words)
        }
        if (plan.begin.isBefore(now().toLocalDateTime()) && !event.allDay) {
            return AgendaOutput.PastTime
        }
        return GarYesNoOutput(
            info = correspondingSkillInfo,
            yesNoData = yesNoData,
            question = { c ->
                c.getString(
                    R.string.gar_agenda_move_question,
                    words.title(event), words.time(event), words.time(plan.begin, event.allDay),
                )
            },
            details = { listOfNotNull(event.location, event.calendarName.takeIf { it.isNotEmpty() }) },
        ) {
            val moved = try {
                agenda.move(event, plan.begin, plan.end)
            } catch (e: Exception) {
                Log.e(TAG, "Could not move event ${event.id}", e)
                null
            }
            if (moved == null) AgendaOutput.Failed else AgendaOutput.Moved(moved, words)
        }
    }

    private suspend fun cancel(
        ctx: SkillContext,
        agenda: Agenda,
        resolvers: SlotResolvers,
        words: EventWords,
        request: AgendaRequest.Cancel,
    ): SkillOutput {
        val query = EventQuery.parse(request.ref, resolvers)
        if (request.all) {
            if (query.day == null) {
                return AgendaOutput.NotFound(request.ref)
            }
            val events = find(agenda, query.copy(words = listOf(), without = null))
            val changeable = events.filter { it.writable && !it.repeats }
            if (changeable.isEmpty()) {
                return if (events.isEmpty()) AgendaOutput.NothingThatDay else AgendaOutput.NoneChangeable
            }
            return GarYesNoOutput(
                info = correspondingSkillInfo,
                yesNoData = yesNoData,
                question = { c ->
                    c.android.resources.getQuantityString(
                        R.plurals.gar_agenda_delete_many_question, changeable.size, changeable.size,
                    )
                },
                details = { changeable.map { words.both(it, speech = false) } },
            ) {
                afterYes(correspondingSkillInfo) {
                    val deleted = changeable.count { deleteOrFalse(agenda, it) }
                    AgendaOutput.DeletedMany(deleted, events.size - changeable.size)
                }
            }
        }
        return choose(ctx, find(agenda, query), words, request.ref) { event ->
            unchangeable(agenda, event) ?: GarYesNoOutput(
                info = correspondingSkillInfo,
                yesNoData = yesNoData,
                question = { c -> c.getString(R.string.gar_agenda_delete_question, words.both(event)) },
                details = { listOfNotNull(event.location, event.calendarName.takeIf { it.isNotEmpty() }) },
            ) {
                afterYes(correspondingSkillInfo) {
                    if (agenda.delete(event)) AgendaOutput.Deleted(event, words) else AgendaOutput.Failed
                }
            }
        }
    }

    /** Why GAR leaves [event] alone, or null if it may change it. */
    private fun unchangeable(agenda: Agenda, event: AgendaEvent): SkillOutput? = when {
        event.repeats -> AgendaOutput.Repeats(event.title, agenda.open(event))
        !event.writable -> AgendaOutput.ReadOnly(event.title, event.calendarName)
        else -> null
    }

    /** One event goes on to [then], several are picked from ("Which one?"), none is said. */
    private suspend fun choose(
        ctx: SkillContext,
        events: List<AgendaEvent>,
        words: EventWords,
        ref: String,
        then: suspend (AgendaEvent) -> SkillOutput,
    ): SkillOutput = when (events.size) {
        0 -> AgendaOutput.NotFound(ref)
        1 -> then(events[0])
        else -> {
            val options = events.take(MAX_OPTIONS)
            GarPickOutput(
                info = correspondingSkillInfo,
                options = options,
                question = { c ->
                    c.getString(R.string.gar_agenda_which, joinedOr(c, options.map { words.both(it) }))
                },
                shown = { _, event -> words.both(event, speech = false) },
                nameOf = { it.title.orEmpty() },
                onPick = { _, event -> then(event) },
            )
        }
    }

    companion object {
        private const val TAG = "AgendaSkill"

        /** How far ahead "next" and an event without a day are looked for. */
        const val LOOK_AHEAD_DAYS = 60L

        /** At most this many events are offered in "Which one?". */
        const val MAX_OPTIONS = 4

        private val ABOUT_FILLERS =
            setOf("my", "the", "a", "an", "with", "about", "for", "our", "next", "upcoming", "any")

        /** "A, B or C". */
        fun joinedOr(ctx: SkillContext, items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> ctx.getString(R.string.gar_agenda_or, items.dropLast(1).joinToString(", "), items.last())
        }
    }
}

/**
 * "What are my reminders", "cancel the reminder about the dentist", "delete all reminders"
 * (gar/PARITY.md, batch 6), English only, no tool. GAR's reminders are calendar events with an
 * alert that the calendar skill made and remembers ([Agenda.reminders]); cancelling one deletes its
 * event, which can't be undone, so one reminder or several are read back and asked about first. A
 * reminder picked by name from "Which reminder?" is cancelled at once: naming it is the choice.
 */
class RemindersSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Sentences.Reminders>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    private val agenda: (SkillContext) -> Agenda = { AndroidAgenda(it.android) },
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val formatterFactory: (SkillContext) -> GarValueFormatter = GarValueFormatter::forContext,
) : Skill<ReminderRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, ReminderRequest?> {
        val request = ReminderWords.request(input) ?: return Pair(AlwaysWorstScore, null)
        return Pair(data.score(ctx, input).first, request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: ReminderRequest?): SkillOutput {
        val request = inputData ?: throw IllegalArgumentException("No reminder request")
        if (isLocked(ctx)) {
            return GarLockedOutput()
        }
        val agenda = agenda(ctx)
        if (!agenda.allowed) {
            return MissingPermissionsSkillOutput(correspondingSkillInfo)
        }
        val words = EventWords(ctx, formatterFactory(ctx))
        val resolvers = SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now)
        val reminders = try {
            agenda.reminders(now().toLocalDateTime())
        } catch (e: SecurityException) {
            return MissingPermissionsSkillOutput(correspondingSkillInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the reminders", e)
            return AgendaOutput.Failed
        }
        return when (request) {
            is ReminderRequest.List -> {
                val day = request.day.takeIf { it.isNotEmpty() }
                    ?.let { EventQuery.readWhen(it, resolvers)?.first }
                RemindersOutput.Listed(
                    reminders.filter { day == null || it.begin.toLocalDate() == day },
                    request.day,
                    words,
                )
            }
            ReminderRequest.CancelAll -> if (reminders.isEmpty()) {
                RemindersOutput.Listed(listOf(), "", words)
            } else {
                cancelSeveral(agenda, reminders, words)
            }
            is ReminderRequest.Cancel -> cancel(agenda, reminders, request.about, resolvers, words)
        }
    }

    private suspend fun cancel(
        agenda: Agenda,
        reminders: List<AgendaEvent>,
        about: String,
        resolvers: SlotResolvers,
        words: EventWords,
    ): SkillOutput {
        val query = EventQuery.parse(about, resolvers)
        val matching = reminders.filter(query::matches).ifEmpty {
            query.loosened()?.let { loose -> reminders.filter(loose::matches) }.orEmpty()
        }
        return when {
            matching.isEmpty() -> RemindersOutput.NotFound(about, reminders.isEmpty())
            matching.size == 1 -> GarYesNoOutput(
                info = correspondingSkillInfo,
                yesNoData = yesNoData,
                question = { c -> c.getString(R.string.gar_reminders_cancel_question, words.both(matching[0])) },
                details = { listOf(words.both(matching[0], speech = false)) },
            ) {
                cancelOne(agenda, matching[0], words)
            }
            // "cancel the reminder" with several: which one
            query.nothingSaid -> GarPickOutput(
                info = correspondingSkillInfo,
                options = matching.take(AgendaSkill.MAX_OPTIONS),
                question = { c ->
                    c.getString(
                        R.string.gar_reminders_which,
                        AgendaSkill.joinedOr(c, matching.take(AgendaSkill.MAX_OPTIONS).map { words.both(it) }),
                    )
                },
                shown = { _, reminder -> words.both(reminder, speech = false) },
                nameOf = { it.title.orEmpty() },
                onPick = { _, reminder -> cancelOne(agenda, reminder, words) },
            )
            else -> cancelSeveral(agenda, matching, words)
        }
    }

    private suspend fun cancelOne(agenda: Agenda, reminder: AgendaEvent, words: EventWords): SkillOutput =
        afterYes(correspondingSkillInfo) {
            if (agenda.delete(reminder)) RemindersOutput.Cancelled(reminder, words) else AgendaOutput.Failed
        }

    /** Reads them back and asks before cancelling more than one. */
    private fun cancelSeveral(agenda: Agenda, reminders: List<AgendaEvent>, words: EventWords): SkillOutput =
        GarYesNoOutput(
            info = correspondingSkillInfo,
            yesNoData = yesNoData,
            question = { c ->
                c.android.resources.getQuantityString(
                    R.plurals.gar_reminders_cancel_many_question, reminders.size, reminders.size,
                    NotesSay.joined(
                        c, reminders.take(SPOKEN).map { words.both(it) } +
                            listOfNotNull(
                                (reminders.size - SPOKEN).takeIf { it > 0 }?.let {
                                    c.android.resources.getQuantityString(R.plurals.gar_lists_more, it, it)
                                }
                            )
                    ),
                )
            },
            details = { reminders.map { words.both(it, speech = false) } },
        ) {
            afterYes(correspondingSkillInfo) {
                RemindersOutput.CancelledMany(reminders.count { deleteOrFalse(agenda, it) })
            }
        }

    private companion object {
        const val TAG = "RemindersSkill"
        const val SPOKEN = 3
    }
}
