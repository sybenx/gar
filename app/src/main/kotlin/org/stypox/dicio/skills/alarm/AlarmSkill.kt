package org.stypox.dicio.skills.alarm

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.resolve.TypedSlotRewrites
import cc.aaro.gar.router.resolve.roundedAlarmTime
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import kotlinx.coroutines.delay
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarSentenceBinder
import org.stypox.dicio.gar.call.GarSlotResolver
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.gar.conversation.conversationWords
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Alarm
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * Sets, lists, changes and dismisses alarms through the clock app's `AlarmClock` intents
 * (GrapheneOS: AOSP DeskClock), and stops or snoozes its ringing alarm. Based on
 * DicioTeam/dicio-android#376 (alarm part).
 *
 * Times are not Dicio `date_time` captures, which misread spoken times ("seven fifteen" is July
 * 15): the sentences capture them as text, and matches go through GAR's [GarCallRunner] with the
 * tools of [AlarmTools], which resolves them with `:gar:router`'s [SlotResolvers] and asks
 * "Morning or evening?" or "What time?" when needed. Calls of the model come in already resolved
 * through [inputFromSlots]. Both end in an [AlarmInput].
 *
 * @param firingAlarm whether the clock app's alarm rings, see [FiringAlarms]
 * @param pause waits between the clock app's intents and while checking their effect
 */
class AlarmSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Alarm>,
    private val clockFactory: (SkillContext) -> ClockApp = { AndroidClockApp(it.android) },
    private val formatterFactory: (SkillContext) -> GarValueFormatter = GarValueFormatter::forContext,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val firingAlarm: () -> FiringAlarmCheck = FiringAlarms::current,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : Skill<AlarmRequest>(correspondingSkillInfo, data.specificity), GarToolSkill {

    override fun score(ctx: SkillContext, input: String): Pair<Score, AlarmRequest> {
        timeAndAlarm(ctx, input)?.let { return Pair(FloatScore(1.0f), AlarmSentenceMatch(it)) }
        val (score, matched) = data.score(ctx, input)
        // also while nothing rings, to say so
        val snooze = (matched as? Alarm.Snooze)?.let { snoozeOf(ctx, input, it) }
        val sentence = snooze ?: matched
        val takes = when (sentence) {
            // only while the clock app's alarm rings: otherwise GAR's conversation takes "stop"
            is Alarm.Stop -> isStopCommand(input) && firingAlarm() == FiringAlarmCheck.Firing
            is Alarm.Snooze -> snooze != null
            else -> mentionsAlarm(input, sentence) &&
                isPlausible(ctx, sentence, score.scoreIn01Range())
        }
        return Pair(if (takes) score else AlwaysWorstScore, AlarmSentenceMatch(sentence))
    }

    /**
     * "Eleven forty five alarm", "7 am alarm", "alarm six thirty", "set a 6:30 alarm" (the
     * Pixel's "1145 alarm.", 2026-11-05): a time of day and the word alarm. Not sentences: a
     * capture before "alarm" would also take "next alarm" and "set a 5 minute alarm" (a timer's),
     * so only time words count, and they must read as a time of day.
     */
    private fun timeAndAlarm(ctx: SkillContext, input: String): Alarm.Set? {
        val words = Utterances.words(Utterances.normalize(input))
        val time = TIME_AND_ALARM.firstNotNullOfOrNull { it.matchEntire(words.joinToString(" ")) }
            ?.groupValues?.get(1) ?: return null
        val timeWords = time.split(" ")
        if (timeWords.any { word -> word !in TIME_WORDS && word.any { !it.isDigit() } }) {
            return null
        }
        val resolvers = SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now)
        return Alarm.Set(time, null).takeIf { resolvers.time(time) !is Resolution.Unresolvable }
    }

    /**
     * Whether [input] names what [sentence] is about. Without this, a partial match with another
     * noun in place of "alarm" scores 0.75 to 0.86, above Dicio's thresholds: "clear all" or "turn
     * off all of the lights" would dismiss every alarm, "when is my next meeting" would tell the
     * next alarm. Sentence matches are never confirmed, so they must really be about alarms.
     */
    private fun mentionsAlarm(input: String, sentence: Alarm): Boolean {
        val keywords = when (sentence) {
            is Alarm.Set -> SET_WORDS
            is Alarm.Show -> SHOW_WORDS
            else -> ALARM_WORDS
        }
        return Utterances.words(Utterances.normalize(input)).any { it in keywords }
    }

    /**
     * Text captures take any words, so partial matches like "set a [timer] for ten minutes" or
     * "set the [volume] to fifty percent" would score well, with the time capture taking the rest.
     * Like a typed Dicio capture that doesn't parse, a time that is neither a time of day nor (for
     * setting) a duration or the next day ([isNextDay]) means no match, and GAR's model gets the
     * input instead. A change needs both of its times ("move my alarm to the living room").
     */
    private fun isPlausible(ctx: SkillContext, sentence: Alarm, score: Float): Boolean {
        val resolvers = SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now)
        val raw = when (sentence) {
            // "set an alarm" asks for the time, but a partial match like "wake up" is no request
            is Alarm.Set -> capture(sentence.time) ?: return score >= NO_TIME_MIN_SCORE
            // "turn off tomorrow's alarm": the next alarm, like without a time
            is Alarm.Cancel -> capture(sentence.time)
                ?.let { TypedSlotRewrites.alarmToCancel(it) }?.takeIf { it.isNotEmpty() }
                ?: return true
            is Alarm.Change -> {
                val from = capture(sentence.from)?.let(AlarmTools::changeFrom)
                    ?.takeIf { it.isNotEmpty() }
                if (from != null && resolvers.time(from) is Resolution.Unresolvable) {
                    return false
                }
                capture(sentence.to) ?: return false
            }
            else -> return true
        }
        return resolvers.time(raw) !is Resolution.Unresolvable ||
            (sentence is Alarm.Set && (resolvers.duration(raw) is Resolution.Resolved || isNextDay(raw)))
    }

    /**
     * [sentence] if it is a snooze, or null: snooze sentences name the snooze and nothing else,
     * so that "snooze notifications" isn't one, and a duration said must be one. "Five more
     * minutes" and "give me ten more minutes" are one only while the clock app's alarm rings,
     * with the whole duration ("five more minutes"), since the sentence only captures its number.
     */
    private fun snoozeOf(ctx: SkillContext, input: String, sentence: Alarm.Snooze): Alarm.Snooze? {
        val words = Utterances.words(Utterances.normalize(input))
        val frame = GarMatchCheck.withoutCaptures(words, listOf(sentence.duration))
        val resolves: (String) -> Boolean = {
            SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now).duration(it) is
                Resolution.Resolved
        }
        if (SNOOZE !in frame) {
            val duration = words.filter { it !in MORE_FILLERS }.joinToString(" ")
            val takes = MORE in frame && frame.all { it in MORE_FILLERS || it in MORE_WORDS } &&
                firingAlarm() == FiringAlarmCheck.Firing && resolves(duration)
            return sentence.copy(duration = duration).takeIf { takes }
        }
        if (frame.any { it !in SNOOZE_WORDS }) {
            return null
        }
        val duration = capture(sentence.duration) ?: return sentence
        return sentence.takeIf { resolves(duration) }
    }

    override fun inputFromSlots(slots: Map<String, Any?>): Any = AlarmTools.inputFromSlots(slots)

    override suspend fun generateOutput(ctx: SkillContext, inputData: AlarmRequest): SkillOutput {
        return when (inputData) {
            is AlarmSentenceMatch -> fromSentence(ctx, inputData.sentence)
            is AlarmInput.Set -> set(ctx, inputData)
            is AlarmInput.Show -> show(ctx, inputData.open)
            is AlarmInput.Cancel -> cancel(ctx, inputData.target)
            AlarmInput.Stop -> stop(ctx)
            is AlarmInput.Snooze -> snooze(ctx, inputData.duration)
            is AlarmInput.Change -> change(ctx, inputData)
        }
    }

    /** Resolves the captures of a sentence match (asking if needed), then runs it. */
    private suspend fun fromSentence(ctx: SkillContext, sentence: Alarm): SkillOutput {
        val call = when (sentence) {
            is Alarm.Set -> GarCall(
                tool = AlarmTools.SET_TOOL,
                rawSlots = listOfNotNull(
                    // "tomorrow morning" is no time to report as not understood: just ask for it
                    capture(sentence.time)?.takeUnless(::isNextDay)?.let { AlarmTools.TIME to it },
                    capture(sentence.label)?.let { AlarmTools.LABEL to it },
                ).toMap(),
                utterance = "",
            )
            is Alarm.Cancel -> GarCall(
                tool = AlarmTools.CANCEL_TOOL,
                rawSlots = listOfNotNull(capture(sentence.time)?.let { AlarmTools.TIME to it }).toMap(),
                utterance = "",
            )
            is Alarm.CancelAll -> return confirmCancelAll(ctx)
            is Alarm.Show -> return show(ctx, open = true)
            is Alarm.Query -> return show(ctx, open = false)
            is Alarm.Stop -> return stop(ctx)
            is Alarm.Snooze -> return snooze(
                ctx,
                capture(sentence.duration)?.let {
                    (resolvers(ctx).duration(it) as? Resolution.Resolved)?.value
                },
            )
            is Alarm.Change -> return confirmChange(ctx, sentence)
        }
        val base = DefaultGarSlotResolver(resolvers(ctx))
        val resolver = if (call.tool === AlarmTools.SET_TOOL) {
            // also "in ten minutes" for the time
            RelativeTimeResolver(base, resolvers(ctx), now)
        } else {
            base
        }
        return newRunner(ctx, resolver, confirm = false).next(ctx, call, nested = false)
    }

    /** Captures come with the spaces around them. */
    private fun capture(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    private fun resolvers(ctx: SkillContext) =
        SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage, now)

    /**
     * A runner that resolves and asks like after a model call. It confirms only the tools of
     * [AlarmTools] that need a yes even from a sentence ([AlarmTools.CHANGE_TOOL],
     * [AlarmTools.CANCEL_ALL_TOOL]): other sentence matches are as certain as any other Dicio
     * skill's.
     */
    private fun newRunner(ctx: SkillContext, resolver: GarSlotResolver, confirm: Boolean) =
        GarCallRunner(
            targetInfo = correspondingSkillInfo,
            target = this,
            confirm = confirm,
            resolver = resolver,
            formatter = formatterFactory(ctx),
            yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage],
            now = { now().toLocalDateTime() },
        )

    private fun set(ctx: SkillContext, input: AlarmInput.Set): SkillOutput {
        val intent = AlarmIntent.set(input.time.time, input.time.repeatDays, input.label)
        if (!clockFactory(ctx).start(intent)) {
            return AlarmOutput.NoClockApp
        }
        val now = now().toLocalDateTime()
        return AlarmOutput.Set(
            time = input.time,
            label = input.label,
            rings = if (input.time.repeatDays.isEmpty()) {
                GarSentenceBinder.nextOccurrence(input.time.time, now)
            } else {
                null
            },
            today = now.toLocalDate(),
            formatter = formatterFactory(ctx),
        )
    }

    private fun show(ctx: SkillContext, open: Boolean): SkillOutput {
        val clock = clockFactory(ctx)
        val showIntent = AlarmIntent.show()
        val canOpen = clock.canHandle(showIntent)
        val opened = open && canOpen && clock.start(showIntent)
        val openAlarms: () -> Unit = { clock.start(showIntent) }
        return AlarmOutput.Show(
            next = clock.nextAlarm(),
            opened = opened,
            openFailed = open && !opened,
            today = now().toLocalDate(),
            formatter = formatterFactory(ctx),
            // offered on the card when the user didn't ask to open them
            open = openAlarms.takeIf { canOpen && !opened },
        )
    }

    private suspend fun cancel(ctx: SkillContext, target: CancelTarget): SkillOutput {
        val clock = clockFactory(ctx)
        val intent = AlarmIntent.dismiss(target)
        if (!clock.canHandle(intent)) {
            return AlarmOutput.NoClockApp
        }
        // "turn off my alarm" while the clock app's alarm rings: that one, like "stop"
        if (target == CancelTarget.Next && firingAlarm() == FiringAlarmCheck.Firing) {
            return stop(ctx)
        }

        val ringing = clock.isAlarmRinging()
        val next = clock.nextAlarm()
        cancelBlocker(ctx, clock, byNext = target == CancelTarget.Next, ringing, next)
            ?.let { return it }

        val now = now().toLocalDateTime()
        val targetIsNext = !ringing && next != null && next.setBy == null && when (target) {
            is CancelTarget.At -> next.time.toLocalTime().truncatedTo(ChronoUnit.MINUTES) == target.time
            CancelTarget.Next -> true
            CancelTarget.All -> false
        }
        if (!clock.start(intent)) {
            return AlarmOutput.NoClockApp
        }
        return AlarmOutput.Dismissing(
            target = target,
            next = next?.takeIf { targetIsNext },
            today = now.toLocalDate(),
            formatter = formatterFactory(ctx),
        )
    }

    /**
     * Why the clock app can't dismiss an alarm now, and what was done instead, or null if it can
     * as far as GAR knows. With [ringing], the checks of the alarms to come are skipped: by time
     * or all the clock app finds the ringing alarm too.
     *
     * @param byNext the alarm is "the next one" rather than one at a time or all of them
     * @param ringing whether something rings ([ClockApp.isAlarmRinging])
     * @param next the next alarm of any app ([ClockApp.nextAlarm])
     */
    private fun cancelBlocker(
        ctx: SkillContext,
        clock: ClockApp,
        byNext: Boolean,
        ringing: Boolean,
        next: NextAlarm?,
    ): SkillOutput? {
        if (ringing) {
            // what rings isn't known to be the clock app's alarm (that one was stopped before):
            // if it is a timer, the clock app would skip its next alarm instead
            return if (byNext) {
                AlarmOutput.Ringing(needsAccess = firingAlarm() == FiringAlarmCheck.NoAccess)
            } else {
                null
            }
        }
        val now = now().toLocalDateTime()
        if (next == null) {
            // no app has an alarm set: the clock app would open just to say so
            return AlarmOutput.NoAlarms
        } else if (next.setBy != null) {
            // "my next alarm" is another app's: the clock app would dismiss its own next alarm,
            // a later one the user didn't mean
            return if (byNext) AlarmOutput.OtherApp(next, now.toLocalDate(), formatterFactory(ctx)) else null
        } else if (next.time.isAfter(now.plusHours(DISMISS_WINDOW_HOURS))) {
            // the clock app's first alarm rings later than that, so all of its alarms do and it
            // would dismiss none of them, without saying so
            return AlarmOutput.TooFarAhead(
                next = next,
                opened = clock.start(AlarmIntent.show()),
                today = now.toLocalDate(),
                formatter = formatterFactory(ctx),
            )
        }
        return null
    }

    /** "Delete all my alarms": checked like dismissing them, then confirmed. */
    private suspend fun confirmCancelAll(ctx: SkillContext): SkillOutput {
        val clock = clockFactory(ctx)
        if (!clock.canHandle(AlarmIntent.dismiss(CancelTarget.All))) {
            return AlarmOutput.NoClockApp
        }
        cancelBlocker(ctx, clock, byNext = false, clock.isAlarmRinging(), clock.nextAlarm())
            ?.let { return it }
        val call = GarCall(
            tool = AlarmTools.CANCEL_ALL_TOOL,
            rawSlots = mapOf(),
            utterance = "",
            values = mapOf(AlarmTools.WHICH to AlarmTools.ALL),
        )
        return newRunner(ctx, DefaultGarSlotResolver(resolvers(ctx)), confirm = true)
            .next(ctx, call, nested = false)
    }

    /**
     * Stops the clock app's ringing alarm: its next alarm while it rings ([AlarmIntent.dismiss]).
     * Says so only once its notification is gone.
     */
    private suspend fun stop(ctx: SkillContext): SkillOutput {
        // it may have stopped since the match (DeskClock silences it after some minutes)
        if (firingAlarm() != FiringAlarmCheck.Firing) {
            return AlarmOutput.NotRinging
        }
        if (!clockFactory(ctx).start(AlarmIntent.dismiss(CancelTarget.Next))) {
            return AlarmOutput.NoClockApp
        }
        return if (waitFor { firingAlarm() != FiringAlarmCheck.Firing }) {
            AlarmOutput.Stopped
        } else {
            AlarmOutput.StillRinging(snooze = false)
        }
    }

    /**
     * Snoozes the ringing alarm. Without notification access GAR goes by the sound alone: the
     * clock app only snoozes a ringing alarm, so a ringing timer comes to no harm, and the sound
     * tells whether it worked. When the clock app's next alarm changes to the snoozed one, GAR
     * says until when.
     */
    private suspend fun snooze(ctx: SkillContext, duration: Duration?): SkillOutput {
        val clock = clockFactory(ctx)
        val ringing: () -> Boolean = if (firingAlarm() == FiringAlarmCheck.NoAccess) {
            clock::isAlarmRinging
        } else {
            { firingAlarm() == FiringAlarmCheck.Firing }
        }
        if (!ringing()) {
            return AlarmOutput.NotRinging
        }
        val before = clock.nextAlarm()
        if (!clock.start(AlarmIntent.snooze(duration))) {
            return AlarmOutput.NoClockApp
        }

        val now = now().toLocalDateTime()
        var until: LocalDateTime? = null
        waitFor {
            until = snoozedUntil(before, clock.nextAlarm(), now, duration)
            until != null
        }
        val snoozedUntil = until
        if (snoozedUntil == null && ringing()) {
            return AlarmOutput.StillRinging(snooze = true)
        }
        return AlarmOutput.Snoozed(
            until = snoozedUntil?.toLocalTime(),
            ownLength = duration != null && snoozedUntil != null &&
                abs(Duration.between(now.plus(duration), snoozedUntil).seconds) > SNOOZE_SLACK_SECONDS,
            formatter = formatterFactory(ctx),
        )
    }

    /**
     * "Change my seven am alarm to seven thirty", "move my alarm to eight": the old alarm is the
     * clock app's next one when no time is said, checked like dismissing it. "Seven" for the old
     * alarm is the next alarm's seven when that is at seven; am or pm of the new time is the one
     * closer to the old time ([ChangeTimeResolver]). Then confirmed.
     */
    private suspend fun confirmChange(ctx: SkillContext, sentence: Alarm.Change): SkillOutput {
        val clock = clockFactory(ctx)
        if (!clock.canHandle(AlarmIntent.dismiss(CancelTarget.Next))) {
            return AlarmOutput.NoClockApp
        }
        val from = capture(sentence.from)?.let(AlarmTools::changeFrom)?.takeIf { it.isNotEmpty() }
        val next = clock.nextAlarm()
        cancelBlocker(ctx, clock, byNext = from == null, clock.isAlarmRinging(), next)
            ?.let { return it }

        // without a time, the blocker made sure that there is a next alarm and it's the clock app's
        val nextTime = next?.takeIf { it.setBy == null }
            ?.time?.toLocalTime()?.truncatedTo(ChronoUnit.MINUTES)
        val base = DefaultGarSlotResolver(resolvers(ctx))
        val fromTime = if (from == null) {
            nextTime
        } else {
            val fromSlot = AlarmTools.CHANGE_TOOL.slots.first { it.name == AlarmTools.FROM }
            (ChangeTimeResolver(base, next = nextTime, near = null).resolve(fromSlot, from)
                as? Resolution.Resolved)?.value?.let { (it as ResolvedTime).time }
        }
        val call = GarCall(
            tool = AlarmTools.CHANGE_TOOL,
            rawSlots = listOfNotNull(
                from?.let { AlarmTools.FROM to it },
                capture(sentence.to)?.let { AlarmTools.TO to it },
            ).toMap(),
            utterance = "",
            values = if (from == null) {
                mapOf(AlarmTools.FROM to ResolvedTime(checkNotNull(nextTime)))
            } else {
                mapOf()
            },
        )
        val resolver = ChangeTimeResolver(base, next = nextTime, near = fromTime)
        return newRunner(ctx, resolver, confirm = true).next(ctx, call, nested = false)
    }

    /**
     * Sets the new alarm, then dismisses the old one by time. The new one first: dismissing opens
     * the clock app, which then shows both.
     */
    private suspend fun change(ctx: SkillContext, input: AlarmInput.Change): SkillOutput {
        val clock = clockFactory(ctx)
        val setIntent = AlarmIntent.set(input.to.time, input.to.repeatDays, label = null)
        val dismissIntent = AlarmIntent.dismiss(CancelTarget.At(input.from))
        if (!clock.canHandle(setIntent) || !clock.canHandle(dismissIntent)) {
            return AlarmOutput.NoClockApp
        }
        if (input.to.time == input.from && input.to.repeatDays.isEmpty()) {
            // setting it again and then dismissing it would leave no alarm at all
            return AlarmOutput.AlreadyAt(input.from, formatterFactory(ctx))
        }
        val ringing = clock.isAlarmRinging()
        val next = clock.nextAlarm()
        cancelBlocker(ctx, clock, byNext = false, ringing, next)?.let { return it }

        if (!clock.start(setIntent)) {
            return AlarmOutput.NoClockApp
        }
        // AOSP DeskClock takes both intents in one singleInstance activity, which drops a second
        // intent that comes before the first one has finished
        pause(BETWEEN_INTENTS_MILLIS)
        val oldDismissed = clock.start(dismissIntent)

        val now = now().toLocalDateTime()
        return AlarmOutput.Changed(
            to = input.to,
            rings = if (input.to.repeatDays.isEmpty()) {
                GarSentenceBinder.nextOccurrence(input.to.time, now)
            } else {
                null
            },
            oldKnown = !ringing && next != null && next.setBy == null &&
                next.time.toLocalTime().truncatedTo(ChronoUnit.MINUTES) == input.from,
            oldDismissed = oldDismissed,
            today = now.toLocalDate(),
            formatter = formatterFactory(ctx),
        )
    }

    /** Waits until [done], for at most [WAIT_MILLIS]; whether it got done. */
    private suspend fun waitFor(done: () -> Boolean): Boolean {
        repeat((WAIT_MILLIS / WAIT_STEP_MILLIS).toInt()) {
            if (done()) {
                return true
            }
            pause(WAIT_STEP_MILLIS)
        }
        return done()
    }

    companion object {
        /** The least score of an alarm sentence without a time, see [isPlausible]. */
        const val NO_TIME_MIN_SCORE = 0.9f

        /**
         * "tomorrow", "tomorrow morning", "tonight": the alarm's day but not its time, which
         * GAR's runner then asks for ("set an alarm for tomorrow morning", "wake me up
         * tomorrow"), on the fast path and after a model call alike ([TypedSlotRewrites.isNextDay],
         * which the runner's slot rewrite applies to `alarm_set`'s time).
         */
        fun isNextDay(raw: String): Boolean = TypedSlotRewrites.isNextDay(raw)

        /** AOSP DeskClock only dismisses alarms that ring within this many hours. */
        const val DISMISS_WINDOW_HOURS = 24L

        /** Between the two intents of a change, see [change]. */
        const val BETWEEN_INTENTS_MILLIS = 1500L

        /** How long GAR waits for a stop or a snooze to show, and how often it looks. */
        const val WAIT_MILLIS = 2000L
        const val WAIT_STEP_MILLIS = 100L

        /** The clock app keeps whole minutes, so a snooze ends up to a minute early. */
        private const val SNOOZE_SLACK_SECONDS = 90L

        /**
         * Whether [input] tells the ringing alarm to stop: one of [STOP_PHRASES], with the
         * fillers of GAR's conversation phrases ("ok stop", "can you stop please",
         * [conversationWords]). Dicio's matcher leaves a word out, so without this "stop the
         * music" would match "stop the alarm".
         */
        fun isStopCommand(input: String): Boolean =
            conversationWords(input).joinToString(" ") in STOP_PHRASES

        /**
         * Whether [input] stops or snoozes the ringing alarm ([isStopCommand], or it says
         * "snooze"): what GAR's questions leave to this skill while the alarm rings.
         */
        fun isRingingCommand(input: String): Boolean =
            isStopCommand(input) || SNOOZE in Utterances.words(Utterances.normalize(input))

        /**
         * The stop sentences' phrases, without apostrophes. They include every stop phrase of
         * `GarRinging`, which GAR's conversation leaves to what rings.
         */
        val STOP_PHRASES: Set<String> = buildSet {
            addAll(listOf(
                "shut up", "be quiet", "keep quiet", "quiet", "hush", "enough", "thats enough",
                "that is enough", "that s enough",
            ))
            for (verb in listOf("stop", "silence", "dismiss", "cancel")) {
                addAll(listOf(verb, "$verb it", "$verb that"))
            }
            // not a bare "mute", which the listening skill keeps
            addAll(listOf("mute it", "mute that", "mute alarm", "mute the alarm", "mute my alarm"))
            addAll(listOf("ok", "okay", "alarm off", "the alarm off", "my alarm off"))
            for (verb in listOf("stop", "silence")) {
                for (owner in listOf("", "the ", "my ")) {
                    for (thing in listOf("alarm", "alarm clock", "ringing", "noise")) {
                        add("$verb $owner$thing")
                    }
                }
            }
            for (verb in listOf("turn", "shut", "switch")) {
                addAll(listOf("$verb it off", "$verb that off"))
            }
            for (me in listOf("im", "i m", "i am")) {
                addAll(listOf("$me up", "$me awake"))
            }
        }

        private const val SNOOZE = "snooze"
        private const val MORE = "more"

        /** The words of "give me five more minutes" besides the number, see [snoozeOf]. */
        private val MORE_WORDS = setOf(MORE, "minute", "minutes")
        private val MORE_FILLERS = setOf("give", "me", "please", "ok", "okay", "hey")

        /** The words a snooze sentence is made of, besides its duration. */
        private val SNOOZE_WORDS = setOf(
            SNOOZE, "it", "that", "the", "my", "alarm", "for", "another", "please", "again", "ok",
            "okay", "hey",
        )

        /**
         * When the alarm snoozed [now] rings again: the clock app's next alarm once it changed
         * from [before] to a time soon after [now], or null.
         */
        fun snoozedUntil(
            before: NextAlarm?,
            after: NextAlarm?,
            now: LocalDateTime,
            duration: Duration?,
        ): LocalDateTime? {
            if (after == null || after == before || after.setBy != null) {
                return null
            }
            val latest = now.plus(duration ?: Duration.ZERO).plusHours(SNOOZE_WINDOW_HOURS)
            return after.time.takeIf { it.isAfter(now) && !it.isAfter(latest) }
        }

        /** Beyond the asked length, see [snoozedUntil]. */
        private const val SNOOZE_WINDOW_HOURS = 1L

        /** "<time> alarm", "alarm <time>", "set a <time> alarm", see [timeAndAlarm]. */
        private val TIME_AND_ALARM = listOf(
            Regex("(?:(?:set|setup|create|make|add) (?:a |an |my |the )?(?:new )?)?(.+?) alarm(?: please)?"),
            // "alarm for six" and "alarm at six" are the sentences'
            Regex("alarm (?!for |at )(.+)"),
        )

        /** The words of a time of day; digits pass too ("6 30", "1145"). */
        private val TIME_WORDS = setOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen", "twenty", "thirty", "forty", "fifty", "oh", "o", "oclock", "clock", "am",
            "pm", "a", "p", "m", "noon", "midnight", "half", "quarter", "past", "to", "at", "for",
            "in", "the", "morning", "evening", "night", "tonight", "tomorrow",
        )

        /** The words an input must contain to match the alarm sentences, see [mentionsAlarm]. */
        private val ALARM_WORDS = setOf("alarm", "alarms")
        private val SET_WORDS = ALARM_WORDS + "wake"
        private val SHOW_WORDS = ALARM_WORDS + "clock"
    }
}

/**
 * [base], plus times said relative to now for the "wake me up in eight hours" sentences: a
 * duration of less than a day becomes the time of day it ends at, rounded to the minute, that
 * much real time from now (also across a daylight saving change). Only used on the fast path;
 * the model's calls get GAR's own resolver.
 */
class RelativeTimeResolver(
    private val base: GarSlotResolver,
    private val resolvers: SlotResolvers,
    private val now: () -> ZonedDateTime,
) : GarSlotResolver {
    override fun resolve(slot: SlotSpec, raw: String): Resolution<Any?> {
        val resolution = base.resolve(slot, raw)
        if (slot.kind != SlotKind.TIME || resolution !is Resolution.Unresolvable) {
            return resolution
        }
        val duration = (resolvers.duration(raw) as? Resolution.Resolved)?.value
            ?: return resolution
        if (duration >= Duration.ofDays(1)) {
            return Resolution.Unresolvable("an alarm can't be a day or more away: '$raw'")
        }
        val now = now()
        return Resolution.Resolved(ResolvedTime(roundedAlarmTime(now.plus(duration), now)))
    }
}

/**
 * [base], with am or pm of a change's times picked where it is clear, instead of asked: the old
 * time ([AlarmTools.FROM]) is the one of the clock app's [next] alarm, if that is one of them; the
 * new time ([AlarmTools.TO]) is the one closer to [near], the old time ("change my seven am alarm
 * to seven thirty" is 7:30 AM, "move my 11 pm alarm to one" 1 AM), unless both are as close.
 */
class ChangeTimeResolver(
    private val base: GarSlotResolver,
    private val next: LocalTime?,
    private val near: LocalTime?,
) : GarSlotResolver {
    override fun resolve(slot: SlotSpec, raw: String): Resolution<Any?> {
        val resolution = base.resolve(slot, raw)
        if (resolution !is Resolution.Ambiguous || resolution.reason != AmbiguityReason.AM_OR_PM) {
            return resolution
        }
        val options = resolution.options.filterIsInstance<ResolvedTime>()
        val picked = when (slot.name) {
            AlarmTools.FROM -> next?.let { time -> options.firstOrNull { it.time == time } }
            AlarmTools.TO -> near?.let { time -> closest(options, time) }
            else -> null
        }
        return picked?.let { Resolution.Resolved(it) } ?: resolution
    }

    companion object {
        /**
         * The one of [options] closest to [near], or null when two are as close: 9 AM to "three"
         * is as far from 3 AM as from 3 PM, so GAR asks.
         */
        fun closest(options: List<ResolvedTime>, near: LocalTime): ResolvedTime? {
            val distances = options.map { minutesApart(it.time, near) }
            val least = distances.minOrNull() ?: return null
            return options[distances.indexOf(least)].takeIf { distances.count { it == least } == 1 }
        }

        /** Minutes between two times of day, around midnight if shorter. */
        fun minutesApart(a: LocalTime, b: LocalTime): Int {
            val minutes = abs(a.toSecondOfDay() - b.toSecondOfDay()) / 60
            return min(minutes, 24 * 60 - minutes)
        }
    }
}
