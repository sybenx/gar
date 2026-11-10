package org.stypox.dicio.skills.calendar

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.util.RecognizeYesNoSkill
import org.stypox.dicio.util.getString
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

/** Short answers without a card of their own. */
object CalendarOutput {
    data object ReadFailed : HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_calendar_read_failed)
    }

    data object AddFailed : HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_calendar_add_failed)
    }

    /** No calendar takes new events, so a calendar app's editor was opened. */
    data object OpenedApp : HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_calendar_opened_app)
    }

    data object NoCalendar : HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_calendar_no_calendar)
    }

    data object PastTime : HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_calendar_past_time)
    }
}

/** "Add Dentist to your calendar for tomorrow at 3:00 PM?" / "Set a reminder for 5:00 PM: Call mom?" */
private fun question(ctx: SkillContext, planned: PlannedAdd, speech: Boolean): String {
    val start = GarValueFormatter.forContext(ctx).format(planned.start, speech) ?: ""
    return if (planned.reminder) {
        ctx.getString(R.string.skill_calendar_confirm_reminder, start, planned.title)
    } else {
        ctx.getString(R.string.skill_calendar_confirm_event, planned.title, start)
    }
}

/** "Tue, Oct 27, 3:00 – 4:00 PM", or the days of an all-day event. */
private fun timeRange(context: Context, times: EventTimes, showDate: Boolean = true): String {
    val zone = ZoneId.systemDefault()
    var flags = DateUtils.FORMAT_ABBREV_ALL
    if (showDate) {
        flags = flags or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
    }
    if (!times.allDay) {
        flags = flags or DateUtils.FORMAT_SHOW_TIME
    }
    // all-day events end at midnight, which formatDateRange treats as the end of the day before
    return DateUtils.formatDateRange(
        context,
        times.start.atZone(zone).toInstant().toEpochMilli(),
        times.end.atZone(zone).toInstant().toEpochMilli(),
        flags,
    )
}

@Composable
private fun EventDetails(planned: PlannedAdd, calendarName: String) {
    val context = LocalContext.current
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = planned.title, style = MaterialTheme.typography.titleMedium)
    Body(
        text = if (planned.reminder) {
            // a reminder goes off at its start, its 15 minutes don't matter
            DateUtils.formatDateTime(
                context,
                planned.times.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
            )
        } else {
            timeRange(context, planned.times)
        }
    )
    if (calendarName.isNotBlank()) {
        Body(text = stringResource(R.string.skill_calendar_in_calendar, calendarName))
    }
}

/**
 * Whether [logged], an answer in the interaction log, is [output]. After one of GAR's questions
 * ("When?") the log holds the output wrapped in a [GarFollowUpOutput].
 */
internal fun isAnswer(logged: SkillOutput?, output: SkillOutput): Boolean =
    logged === output || (logged as? GarFollowUpOutput)?.output === output

/**
 * The fast path's confirmation: what will be added to which calendar, answered by voice (yes/no)
 * or with the buttons, which send their label as input like GAR's confirmation card.
 */
class ConfirmAddOutput(
    private val planned: PlannedAdd,
    private val calendar: CalendarEntry,
    private val skill: CalendarSkill,
) : SkillOutput {

    /** Set once the user answered, to disable the buttons. */
    private var answered by mutableStateOf(false)

    override fun getSpeechOutput(ctx: SkillContext): String = question(ctx, planned, speech = true)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(
                ConfirmAddSkill(
                    skill = skill,
                    planned = planned,
                    calendar = calendar,
                    yesLabel = ctx.getString(R.string.gar_yes),
                    noLabel = ctx.getString(R.string.gar_no),
                    onAnswered = { answered = true },
                ),
                GarCancelSkill(CalendarInfo) { answered = true },
            ),
        )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the buttons only make sense while this card is the latest answer
        val log by skillEvaluator.state.collectAsState()
        val latest = log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
        val enabled = isAnswer(latest, this) && !answered

        val yes = stringResource(R.string.gar_yes)
        val no = stringResource(R.string.gar_no)
        val send = { label: String ->
            answered = true
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(label, 1.0f))))
        }

        Column {
            Headline(text = question(ctx, planned, speech = false))
            EventDetails(planned, calendar.name)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = { send(no) }, enabled = enabled) {
                    Text(text = no)
                }
                Button(onClick = { send(yes) }, enabled = enabled) {
                    Text(text = yes)
                }
            }
        }
    }
}

/** Voice yes/no through Dicio's `util_yes_no` sentences, plus the exact button labels. */
private class ConfirmAddSkill(
    private val skill: CalendarSkill,
    private val planned: PlannedAdd,
    private val calendar: CalendarEntry,
    private val yesLabel: String,
    private val noLabel: String,
    private val onAnswered: () -> Unit,
) : RecognizeYesNoSkill(CalendarInfo, skill.yesNoData) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Boolean> {
        return when (normalizeAnswer(input)) {
            normalizeAnswer(yesLabel) -> Pair(AlwaysBestScore, true)
            normalizeAnswer(noLabel) -> Pair(AlwaysBestScore, false)
            else -> super.score(ctx, input)
        }
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Boolean): SkillOutput {
        onAnswered()
        return if (inputData) {
            skill.insert(ctx, planned, calendar)
        } else {
            GarCancelledOutput()
        }
    }
}

/** The event or reminder was added: what, when and where, with a button to open it. */
class AddedOutput(
    private val planned: PlannedAdd,
    private val calendarName: String,
    private val eventId: Long,
    private val canOpen: Boolean,
    /** GAR notifies for reminders on this phone, but its notifications are turned off. */
    private val notificationsOff: Boolean,
) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String = text(ctx, speech = true)

    private fun text(ctx: SkillContext, speech: Boolean): String {
        if (!planned.reminder) {
            return if (calendarName.isBlank()) {
                ctx.getString(R.string.skill_calendar_added_event_no_name, planned.title)
            } else {
                ctx.getString(R.string.skill_calendar_added_event, planned.title, calendarName)
            }
        }

        val start = GarValueFormatter.forContext(ctx).format(planned.start, speech) ?: ""
        val set = ctx.getString(R.string.skill_calendar_added_reminder, start)
        return if (notificationsOff) {
            set + " " + ctx.getString(R.string.skill_calendar_notifications_off)
        } else {
            set
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        Column {
            Headline(text = text(ctx, speech = false))
            EventDetails(planned, calendarName)
            if (canOpen || notificationsOff) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (notificationsOff) {
                        OutlinedButton(onClick = { openNotificationSettings(context) }) {
                            Text(text = stringResource(R.string.skill_calendar_notification_settings))
                        }
                    }
                    if (canOpen) {
                        Button(
                            onClick = {
                                CalendarStore.start(context, CalendarStore.viewEventIntent(eventId))
                            }
                        ) {
                            Text(text = stringResource(R.string.skill_calendar_open))
                        }
                    }
                }
            }
        }
    }

    private fun openNotificationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** How a day is said: "today", "on Friday", "on November 3". */
enum class DayKind {
    TODAY, TOMORROW, YESTERDAY, THIS_WEEK, OTHER;

    companion object {
        fun of(day: LocalDate, today: LocalDate): DayKind = when (ChronoUnit.DAYS.between(today, day)) {
            0L -> TODAY
            1L -> TOMORROW
            -1L -> YESTERDAY
            in 2L..6L -> THIS_WEEK
            else -> OTHER
        }
    }
}

/** The events of a day: the first few spoken, all of them listed in cards that open the event. */
class EventsOutput(
    private val day: LocalDate,
    private val events: List<CalendarEvent>,
    private val today: LocalDate = LocalDate.now(),
) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String {
        val dayPhrase = dayPhrase(ctx)
        if (events.isEmpty()) {
            return ctx.getString(R.string.skill_calendar_nothing, dayPhrase)
        }

        val formatter = GarValueFormatter.forContext(ctx)
        val spoken = events.take(MAX_SPOKEN).joinToString(", ") { event ->
            val title = event.title ?: ctx.getString(R.string.skill_calendar_untitled)
            when {
                event.coversWholeDay(day) -> title
                event.endsOn(day) -> ctx.getString(
                    R.string.skill_calendar_event_until,
                    title,
                    formatter.format(event.end.toLocalTime(), speech = true),
                )
                else -> ctx.getString(
                    R.string.skill_calendar_event_at,
                    title,
                    formatter.format(event.begin.toLocalTime(), speech = true),
                )
            }
        }
        val list = if (events.size > MAX_SPOKEN) {
            ctx.getString(R.string.skill_calendar_and_more, spoken, events.size - MAX_SPOKEN)
        } else {
            spoken
        }
        return ctx.android.resources.getQuantityString(
            R.plurals.skill_calendar_you_have, events.size, events.size, dayPhrase, list,
        )
    }

    private fun dayPhrase(ctx: SkillContext): String = when (DayKind.of(day, today)) {
        DayKind.TODAY -> ctx.getString(R.string.skill_calendar_day_today)
        DayKind.TOMORROW -> ctx.getString(R.string.skill_calendar_day_tomorrow)
        DayKind.YESTERDAY -> ctx.getString(R.string.skill_calendar_day_yesterday)
        DayKind.THIS_WEEK -> ctx.getString(
            R.string.skill_calendar_day_on,
            day.dayOfWeek.getDisplayName(TextStyle.FULL, ctx.locale),
        )
        DayKind.OTHER -> ctx.getString(
            R.string.skill_calendar_day_on,
            day.format(DateTimeFormatter.ofPattern("MMMM d", ctx.locale)),
        )
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Headline(text = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(ctx.locale)))
            if (events.isEmpty()) {
                Body(text = getSpeechOutput(ctx))
            }
            for (event in events) {
                EventCard(
                    title = event.title ?: stringResource(R.string.skill_calendar_untitled),
                    location = event.location,
                    time = if (event.coversWholeDay(day)) {
                        stringResource(R.string.skill_calendar_all_day)
                    } else {
                        timeRange(
                            context,
                            EventTimes(event.begin, event.end, allDay = false),
                            showDate = !sameDay(event.begin, event.end),
                        )
                    },
                    onClick = { CalendarStore.start(context, CalendarStore.viewEventIntent(event.id)) },
                )
            }
        }
    }

    private fun sameDay(begin: LocalDateTime, end: LocalDateTime) =
        begin.toLocalDate() == day && !end.isAfter(day.plusDays(1).atStartOfDay())

    companion object {
        /** Only the first events are spoken, the card lists all of them. */
        const val MAX_SPOKEN = 3
    }
}

@Composable
private fun EventCard(title: String, location: String?, time: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
        )
        Text(
            text = if (location == null) time else "$time · $location",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 8.dp),
        )
    }
}
