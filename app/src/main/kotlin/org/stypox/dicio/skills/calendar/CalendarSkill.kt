package org.stypox.dicio.skills.calendar

import android.app.KeyguardManager
import android.util.Log
import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import kotlinx.coroutines.flow.first
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.GarSlotResolver
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Calendar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Adds events and reminders to a calendar of the calendar provider, and reads a day's events.
 *
 * Input comes in two forms. Dicio's sentences give [Calendar] with free-text captures, which
 * [fromSentence] splits ([CalendarPhrases]) and hands to GAR's slot runner to resolve, asking about
 * what's missing or unclear; the skill then confirms by itself. The model's tool calls (GAR) give
 * a [CalendarRequest] built by [inputFromSlots] from values GAR resolved and confirmed. Both end
 * in [handle].
 *
 * @param isDeviceLocked whether the phone is locked (with a PIN, pattern, ...): then Dicio's
 * sentences don't run the skill, like GAR's calendar tools (`on_lock_screen: false`), since they
 * also match on the lock screen (wake word) and the skill reads events aloud and adds them
 */
class CalendarSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Calendar>,
    val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val formatterFactory: (SkillContext) -> GarValueFormatter = GarValueFormatter::forContext,
    private val isDeviceLocked: (SkillContext) -> Boolean = { ctx ->
        ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    },
) : Skill<Any>(correspondingSkillInfo, data.specificity), GarToolSkill {

    /**
     * Dicio's score, but only for input with a calendar word: the free-text captures would
     * otherwise make e.g. "set a timer for ten minutes to check the pasta" look like "set a
     * reminder for ... to ...", taking it from the timer skill or from GAR's model.
     */
    override fun score(ctx: SkillContext, input: String): Pair<Score, Any> {
        val (score, sentence) = data.score(ctx, input)
        return if (CalendarPhrases.hasCue(sentence, input)) {
            Pair(score, sentence)
        } else {
            Pair(AlwaysWorstScore, sentence)
        }
    }

    /** The model's calls: GAR has already asked for and confirmed the values. */
    override fun inputFromSlots(slots: Map<String, Any?>): Any =
        CalendarRequest.fromSlots(slots, confirm = false, now = localNow())

    override suspend fun generateOutput(ctx: SkillContext, inputData: Any): SkillOutput {
        return when (inputData) {
            // GAR's own calls are checked by GarFallback before they get here
            is Calendar -> if (isDeviceLocked(ctx)) GarLockedOutput() else fromSentence(ctx, inputData)
            is CalendarRequest -> handle(ctx, inputData)
            else -> throw IllegalArgumentException("Unexpected calendar input: $inputData")
        }
    }

    /**
     * The fast path: the captures become raw slots, which GAR's slot runner resolves with the same
     * resolvers as the model's calls, asking when one is missing ("When?") or unclear ("Morning
     * or evening?"). The resulting request is confirmed by [ConfirmAddOutput].
     */
    private suspend fun fromSentence(ctx: SkillContext, sentence: Calendar): SkillOutput {
        val resolvers = resolvers(ctx)
        val phrases = CalendarPhrases(resolvers)
        val (tool, raw) = when (sentence) {
            is Calendar.Create -> Pair(
                ADD_TOOL,
                phrases.createSlots(sentence.title, sentence.begin, sentence.end, sentence.duration),
            )
            is Calendar.Reminder -> Pair(
                REMINDER_TOOL,
                phrases.reminderSlots(sentence.what, sentence.`when`)
                    .mapKeys { (key, _) -> if (key == CalendarRequest.WHAT) REMINDER_SLOT else key },
            )
            is Calendar.Query -> Pair(QUERY_TOOL, phrases.querySlots(sentence.`when`))
        }

        val runner = slotRunner(ctx, resolvers) { slots ->
            CalendarRequest.fromSlots(
                slots = slots.mapKeys { (key, _) -> if (key == REMINDER_SLOT) CalendarRequest.WHAT else key },
                confirm = true,
                end = { start ->
                    (slots[CalendarRequest.END] as? String)
                        ?.let { CalendarTime.endAfter(start, resolvers.dateTime(it)) }
                },
                now = localNow(),
            )
        }
        return runner.next(ctx, GarCall(tool, raw, "", startFromEnd(raw, resolvers)), nested = false)
    }

    /**
     * "from three to five pm": the end answers whether the start is am or pm, so it's resolved
     * here instead of asking.
     */
    private fun startFromEnd(raw: Map<String, String>, resolvers: SlotResolvers): Map<String, Any?> {
        val start = raw[CalendarRequest.WHEN] ?: return mapOf()
        val end = raw[CalendarRequest.END] ?: return mapOf()
        val options = (resolvers.dateTime(start) as? Resolution.Ambiguous)
            ?.takeIf { it.reason == AmbiguityReason.AM_OR_PM }?.options ?: return mapOf()
        return CalendarTime.startBefore(options, resolvers.dateTime(end))
            ?.let { mapOf(CalendarRequest.WHEN to it) }
            ?: mapOf()
    }

    private fun resolvers(ctx: SkillContext) =
        SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now)

    /** The local date and time, for what is said and shown (the provider gets moments). */
    private fun localNow(): LocalDateTime = now().toLocalDateTime()

    /** Whether [at] has passed, as a moment: in the hour the clock goes back too. */
    private fun hasPassed(at: ResolvedDateTime): Boolean {
        val now = now()
        return at.atZone(now.zone).isBefore(now)
    }

    /** GAR's slot runner for the fast path, running [build]'s request through [handle]. */
    private fun slotRunner(
        ctx: SkillContext,
        resolvers: SlotResolvers,
        resolver: GarSlotResolver = DefaultGarSlotResolver(resolvers),
        build: (Map<String, Any?>) -> CalendarRequest,
    ) = GarCallRunner(
        targetInfo = correspondingSkillInfo,
        target = RequestTarget(this, build),
        confirm = false, // the skill confirms by itself, with the calendar shown
        resolver = resolver,
        formatter = formatterFactory(ctx),
        yesNoData = yesNoData,
        now = { localNow() },
    )

    /** The runner that asks for the time of a reminder said with a day or a part of day only. */
    private fun reminderTimeRunner(
        ctx: SkillContext,
        request: CalendarRequest.AddReminder,
    ): GarCallRunner {
        val resolvers = resolvers(ctx)
        val base = DefaultGarSlotResolver(resolvers)
        val resolver = if (request.at.approximateTime) {
            NearestTimeResolver(base, request.at.dateTime.toLocalTime())
        } else {
            base
        }
        return slotRunner(ctx, resolvers, resolver) { slots ->
            val built = CalendarRequest.fromSlots(slots, request.confirm, now = localNow())
            if (request.at.approximateTime && built is CalendarRequest.AddReminder) {
                val at = reminderAfterPartOfDay(request.at, built.at.dateTime.toLocalTime(), localNow())
                built.copy(at = built.at.copy(dateTime = at))
            } else {
                built
            }
        }
    }

    /** Runs a complete request, from either path. */
    internal suspend fun handle(ctx: SkillContext, request: CalendarRequest): SkillOutput {
        return when (request) {
            is CalendarRequest.Query -> query(ctx, request.day ?: now().toLocalDate())

            is CalendarRequest.AddEvent -> add(
                ctx,
                PlannedAdd(
                    title = request.title,
                    times = CalendarTime.eventTimes(request.start, request.duration, request.end),
                    reminder = false,
                    said = request.start,
                ),
                request.confirm,
            )

            is CalendarRequest.AddReminder -> when {
                // "remind me to call mom tomorrow": ask for the time; also for a part of day
                // ("tonight"), whose hour is only a guess, but then "nine" is 9 pm
                !request.at.timeSpoken || request.at.approximateTime ->
                    reminderTimeRunner(ctx, request).next(
                        ctx,
                        GarCall(
                            tool = REMINDER_TIME_TOOL,
                            rawSlots = mapOf(),
                            utterance = "",
                            values = mapOf(
                                CalendarRequest.WHAT to request.what,
                                CalendarRequest.DAY to ResolvedDate(
                                    // "tonight" said after 9 pm is still today, see
                                    // [reminderAfterPartOfDay]
                                    if (request.at.approximateTime && !request.at.dateSpoken) {
                                        now().toLocalDate()
                                    } else {
                                        request.at.dateTime.toLocalDate()
                                    }
                                ),
                            ),
                        ),
                        nested = false,
                    )

                hasPassed(request.at) -> CalendarOutput.PastTime

                else -> add(
                    ctx,
                    PlannedAdd(
                        title = request.what,
                        times = CalendarTime.reminderTimes(request.at.dateTime, request.at.offset),
                        reminder = true,
                        said = request.at,
                    ),
                    request.confirm,
                )
            }
        }
    }

    private fun query(ctx: SkillContext, day: LocalDate): SkillOutput {
        val events = try {
            CalendarStore.eventsOn(ctx.android.contentResolver, day, ZoneId.systemDefault())
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the calendar", e)
            return CalendarOutput.ReadFailed
        }
        return EventsOutput(day, events, now().toLocalDate())
    }

    private suspend fun add(ctx: SkillContext, planned: PlannedAdd, confirm: Boolean): SkillOutput {
        val calendars = try {
            CalendarStore.calendars(ctx.android.contentResolver)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the calendars", e)
            listOf()
        }
        val savedId = ctx.android.calendarSettingsDataStore.data.first().targetCalendarId
        val calendar = CalendarChoice.choose(calendars, savedId)
            ?: return insertInApp(ctx, planned)

        return if (confirm) {
            ConfirmAddOutput(planned, calendar, this)
        } else {
            insert(ctx, planned, calendar)
        }
    }

    /** Adds [planned] to [calendar], after the user confirmed. */
    internal suspend fun insert(ctx: SkillContext, planned: PlannedAdd, calendar: CalendarEntry): SkillOutput {
        val context = ctx.android
        val resolver = context.contentResolver
        val zone = ZoneId.systemDefault()
        val eventId = try {
            CalendarStore.insertEvent(
                resolver,
                CalendarValues.event(calendar.id, planned.title, planned.times, zone, planned.reminder),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not add the event", e)
            return CalendarOutput.AddFailed
        }

        var notificationsOff = false
        if (planned.reminder) {
            try {
                CalendarStore.insertReminder(resolver, CalendarValues.alertAtStart(eventId))
            } catch (e: Exception) {
                // an event that never alerts is no reminder
                Log.e(TAG, "Could not add the alert of reminder $eventId", e)
                try {
                    CalendarStore.deleteEvent(resolver, eventId)
                } catch (e2: Exception) {
                    Log.e(TAG, "Could not delete reminder $eventId", e2)
                }
                return CalendarOutput.AddFailed
            }
            CalendarReminderNotifier.track(
                context,
                Tracked(
                    eventId,
                    CalendarTime.providerMillis(planned.times.start, false, zone, planned.times.offset),
                ),
            )
            notificationsOff = CalendarReminderNotifier.garNotifies(context) &&
                    !CalendarReminderNotifier.canNotify(context)
        }

        val canOpen = CalendarStore.launchFor(
            context.packageManager, CalendarStore.viewEventIntent(eventId)
        ) !is Launch.None
        return AddedOutput(planned, calendar.name, eventId, canOpen, notificationsOff)
    }

    /** No calendar takes new events: let a calendar app add it (never the stock one). */
    private fun insertInApp(ctx: SkillContext, planned: PlannedAdd): SkillOutput {
        val intent = CalendarStore.insertIntent(
            CalendarValues.insertExtras(planned.title, planned.times, ZoneId.systemDefault())
        )
        return if (CalendarStore.start(ctx.android, intent)) {
            CalendarOutput.OpenedApp
        } else {
            CalendarOutput.NoCalendar
        }
    }

    companion object {
        private const val TAG = "CalendarSkill"

        /** The fast path's name for what to be reminded about: GAR asks "What reminder?". */
        const val REMINDER_SLOT = "reminder"

        private fun tool(name: String, vararg slots: SlotSpec) = ToolSpec(
            name = name,
            skillId = CalendarInfo.id,
            sentence = null,
            description = name,
            confirm = false,
            slots = slots.toList(),
            examples = listOf(),
        )

        // The fast path's slots for GAR's slot runner. They mirror the tools in tools.yml, plus
        // the end of an event ("until five"), which stays text until CalendarTime.endAfter places
        // it after the start.
        val ADD_TOOL = tool(
            "calendar_add",
            SlotSpec(CalendarRequest.TITLE, SlotKind.TEXT, true, ""),
            SlotSpec(CalendarRequest.WHEN, SlotKind.DATETIME, true, ""),
            SlotSpec(CalendarRequest.DURATION, SlotKind.DURATION, false, ""),
            SlotSpec(CalendarRequest.END, SlotKind.TEXT, false, ""),
        )
        val REMINDER_TOOL = tool(
            "reminder_add",
            SlotSpec(REMINDER_SLOT, SlotKind.TEXT, true, ""),
            SlotSpec(CalendarRequest.WHEN, SlotKind.DATETIME, true, ""),
        )
        val QUERY_TOOL = tool(
            "calendar_query",
            SlotSpec(CalendarRequest.DAY, SlotKind.DATE, false, ""),
        )
        /** A reminder said with a day but no time: asks "What time?". */
        val REMINDER_TIME_TOOL = tool(
            "reminder_time",
            SlotSpec(CalendarRequest.WHAT, SlotKind.TEXT, true, ""),
            SlotSpec(CalendarRequest.DAY, SlotKind.DATE, true, ""),
            SlotSpec(CalendarRequest.TIME, SlotKind.TIME, true, ""),
        )
    }
}

/**
 * When a reminder said with a part of day only ([said]: "tonight", "tomorrow morning") rings, at
 * the [time] the user named when asked: on the day said, or today when no day was said (the
 * resolver moves "tonight" said at 11:30 pm to tomorrow night, but "eleven forty five" is in a
 * quarter of an hour); after the next midnight when the time is more than 12 hours before the part
 * of day's hour ("tonight", "one": 1 am tomorrow, not 1 am today, which has passed); and without a
 * day said, a time that has passed today is tomorrow's.
 */
internal fun reminderAfterPartOfDay(
    said: ResolvedDateTime,
    time: LocalTime,
    now: LocalDateTime,
): LocalDateTime {
    val day = if (said.dateSpoken) said.dateTime.toLocalDate() else now.toLocalDate()
    var at = day.atTime(time)
    if (at.isBefore(day.atTime(said.dateTime.toLocalTime()).minusHours(12))) {
        at = at.plusDays(1)
    }
    if (!said.dateSpoken && at.isBefore(now)) {
        at = at.plusDays(1)
    }
    return at
}

/**
 * [base], but a time of day that could be am or pm is the one nearer to [around]: asked "What
 * time?" about "remind me tonight" (around 9 pm), "nine" is 9 pm, "one" 1 am and "twelve" (noon to
 * the resolvers when nothing else is said) midnight.
 */
internal class NearestTimeResolver(
    private val base: GarSlotResolver,
    private val around: LocalTime,
) : GarSlotResolver {
    override fun resolve(slot: SlotSpec, raw: String): Resolution<Any?> {
        val resolution = base.resolve(slot, raw)
        if (slot.kind != SlotKind.TIME) {
            return resolution
        }
        val options = when {
            resolution is Resolution.Ambiguous && resolution.reason == AmbiguityReason.AM_OR_PM ->
                resolution.options
            // "twelve" alone is noon; with "am" it is midnight unless the user said noon or pm
            // ("twelve pm am" doesn't resolve, "noon am" stays noon)
            resolution is Resolution.Resolved &&
                (resolution.value as? ResolvedTime)?.time?.hour == 12 ->
                listOfNotNull(
                    resolution.value,
                    (base.resolve(slot, "$raw am") as? Resolution.Resolved)?.value
                        ?.takeIf { (it as? ResolvedTime)?.time?.hour == 0 },
                )
            else -> return resolution
        }
        val nearest = options.minByOrNull { option ->
            val time = (option as? ResolvedTime)?.time ?: return resolution
            val minutes = abs(time.toSecondOfDay() - around.toSecondOfDay()) / 60
            minOf(minutes, MINUTES_PER_DAY - minutes)
        }
        return Resolution.Resolved(nearest)
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * The target of GAR's slot runner on the fast path: builds the request from the resolved slots
 * and hands it to [CalendarSkill.handle].
 */
private class RequestTarget(
    private val skill: CalendarSkill,
    private val build: (Map<String, Any?>) -> CalendarRequest,
) : Skill<CalendarRequest>(skill.correspondingSkillInfo, skill.specificity), GarToolSkill {

    override fun score(ctx: SkillContext, input: String): Pair<Score, CalendarRequest> {
        throw UnsupportedOperationException("RequestTarget only runs requests")
    }

    override fun inputFromSlots(slots: Map<String, Any?>): Any = build(slots)

    override suspend fun generateOutput(ctx: SkillContext, inputData: CalendarRequest): SkillOutput =
        skill.handle(ctx, inputData)
}

/** An event or reminder ready to be added; [said] is how the user said its start. */
data class PlannedAdd(
    val title: String,
    val times: EventTimes,
    val reminder: Boolean,
    val said: ResolvedDateTime,
) {
    /** The start as said ("tomorrow at 3:00 PM", "Friday"), for [GarValueFormatter]. */
    val start: ResolvedDateTime get() = said.copy(dateTime = times.start)
}
