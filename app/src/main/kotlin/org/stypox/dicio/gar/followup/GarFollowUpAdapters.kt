package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.AdjustKind
import cc.aaro.gar.router.followup.FollowUpForm
import cc.aaro.gar.router.followup.SongVerb
import org.stypox.dicio.gar.followup.adapters.AgendaFollowUps
import org.stypox.dicio.gar.followup.adapters.AnswerFollowUps
import org.stypox.dicio.gar.followup.adapters.ClockFollowUps
import org.stypox.dicio.gar.followup.adapters.DeviceFollowUps
import org.stypox.dicio.gar.followup.adapters.MediaFollowUps
import org.stypox.dicio.gar.followup.adapters.NotesFollowUps
import org.stypox.dicio.gar.followup.adapters.PeopleFollowUps
import org.stypox.dicio.gar.followup.adapters.PlaceFollowUps
import org.stypox.dicio.gar.followup.adapters.RepeatFollowUps

/** The adapter of each skill whose turns follow-ups may refer to; others leave bare turns. */
class GarFollowUpAdapters(val adapters: List<GarFollowUpAdapter>) {
    fun forSkill(skillId: String): GarFollowUpAdapter =
        adapters.firstOrNull { skillId in it.skillIds } ?: BareFollowUps

    companion object {
        val DEFAULT = GarFollowUpAdapters(
            listOf(
                DeviceFollowUps, ClockFollowUps, AnswerFollowUps, RepeatFollowUps, PeopleFollowUps,
                PlaceFollowUps, MediaFollowUps, AgendaFollowUps, NotesFollowUps,
            )
        )
    }
}

/**
 * The turn of a skill without an adapter: no facts, no rewrites. It still ends the topic, so
 * "what about tomorrow" after it doesn't reach back to an older weather answer.
 */
object BareFollowUps : GarFollowUpAdapter {
    override val skillIds: Set<String> = setOf()

    override fun remember(answered: GarAnswered): GarTurn = GarTurn(
        skillId = answered.skillId,
        action = "",
        slots = mapOf(),
        facts = GarFacts(),
        command = answered.input,
        kind = GarTurnKind.ANSWER,
    )

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? = null
}

/**
 * Which remembered turn a follow-up may reach and what GAR does with it (gar/DESIGN.md,
 * "Follow-ups", the reach table): destructive forms only the newest turn within a minute, and only
 * while nothing was answered after it, changes of a clock the newest turn, settings by walking
 * back over plain answers to the newest setting (not past the music or a clock), answers
 * and corrections the newest turn, persons and places by their newest fact. When nothing fits,
 * GAR asks ("Turn what off?") for the forms whose words name an action, and leaves the others to
 * the rest of Dicio's skills ([GarFollowUpOutcome.Deferred]).
 */
object GarFollowUpReach {
    /** Settings words of [GarFacts.setting] that "turn it up" may mean while music plays. */
    const val BRIGHTNESS = "brightness"
    const val VOLUME = "volume"

    /** What a "turn it off" with nothing remembered means while music plays. */
    const val PAUSE_MUSIC = "pause the music"

    /**
     * @param locked whether the phone is locked now: a rewrite of an adapter that may not act
     * on the lock screen is [GarFollowUpOutcome.Locked]
     * @param startedWhileSpeaking GAR was talking when the capture started (a barge-in): "cancel
     * it" is the conversation's stop then
     */
    fun resolve(
        form: FollowUpForm,
        context: GarFollowUpContext,
        locked: Boolean,
        startedWhileSpeaking: Boolean,
    ): GarFollowUpMatch {
        val memory = context.memory
        val newest = memory.newest

        fun match(outcome: GarFollowUpOutcome, turn: GarRememberedTurn?, alwaysBest: Boolean = false) =
            GarFollowUpMatch(form, outcome, turn, alwaysBest)

        fun defer(to: String) = match(GarFollowUpOutcome.Deferred(to), newest)

        /** [rewrite] of [turn] as an outcome, confirmed first when [confirm]. */
        fun outcome(rewrite: GarRewrite?, turn: GarRememberedTurn, confirm: Boolean): GarFollowUpMatch? {
            rewrite ?: return null
            if (locked && !turn.adapter.onLockScreen) {
                return match(GarFollowUpOutcome.Locked, turn)
            }
            return match(GarFollowUpOutcome.of(rewrite, confirm), turn)
        }

        /**
         * The newest REVERSIBLE turn, walking back over plain answers ("what time is it"), when its
         * adapter rewrites [form]. It stops at a turn whose skill has an off or a stop of its own
         * (the music, a clock), and never skips a newer setting that can't take [form] ("turn it
         * off" after the brightness was set), since an older setting is then a guess.
         */
        fun walk(): GarFollowUpMatch? {
            for (turn in memory.turns) {
                if (turn.turn.skillId in OWN_STOP_SKILLS) {
                    return null
                }
                if (turn.turn.kind == GarTurnKind.REVERSIBLE) {
                    return outcome(turn.adapter.rewrite(form, turn, context), turn, confirm = false)
                }
            }
            return null
        }

        /** The newest turn, if it is [kind] and within [maxAge]. */
        fun newestTurn(kind: GarTurnKind?, maxAge: Long): GarRememberedTurn? =
            newest?.takeIf { (kind == null || it.turn.kind == kind) && it.ageMillis <= maxAge }

        fun ask(question: GarFollowUpQuestion, template: String) =
            match(GarFollowUpOutcome.Ask(question, template), null)

        /**
         * "Cancel it", "delete it" and a cancelling "turn it off": the newest turn within a
         * minute, and only while it is also the latest answer (small talk after it makes "cancel
         * it" the conversation's stop). It wins over the conversation's "cancel it", except while
         * GAR was talking; in a continued turn it asks first ("Cancel your 7 am alarm?"), since
         * the window may have heard the TV.
         */
        fun cancelling(): GarFollowUpMatch? {
            val turn = newestTurn(GarTurnKind.MODIFIABLE, GarConversationMemory.REMOVE_MILLIS) ?: return null
            if (!memory.newestIsLatestAnswer) {
                return null
            }
            val result = outcome(turn.adapter.rewrite(form, turn, context), turn, context.continued)
                ?: return null
            val acts = result.outcome is GarFollowUpOutcome.Rewritten ||
                result.outcome is GarFollowUpOutcome.Direct ||
                result.outcome is GarFollowUpOutcome.Confirm
            return result.copy(alwaysBest = acts && !startedWhileSpeaking)
        }

        /**
         * A change of the newest clock, reminder or event, confirmed in a continued turn when
         * [confirm] (pausing and resuming aren't).
         */
        fun modify(confirm: Boolean = context.continued): GarFollowUpMatch? {
            val turn = newestTurn(GarTurnKind.MODIFIABLE, GarConversationMemory.MEMORY_MILLIS) ?: return null
            return outcome(turn.adapter.rewrite(form, turn, context), turn, confirm)
        }

        /** "Turn it off" while music plays and nothing remembered takes it: the music. */
        fun pauseMusic(on: Boolean): GarFollowUpMatch? =
            match(GarFollowUpOutcome.Rewritten(PAUSE_MUSIC), null).takeIf { !on && context.mediaPlaying }

        /** An answer of the newest turn. */
        fun answer(maxAge: Long, confirm: (GarRememberedTurn) -> Boolean = { false }): GarFollowUpMatch? {
            val turn = newestTurn(null, maxAge) ?: return null
            return outcome(turn.adapter.rewrite(form, turn, context), turn, confirm(turn))
        }

        /** Whether the newest turn of the last minute is [skillId]'s or names [setting]. */
        fun justNow(skillIds: Set<String> = setOf(), setting: String? = null): Boolean =
            newestTurn(null, GarConversationMemory.CORRECTION_MILLIS)?.turn?.let {
                it.skillId in skillIds || (setting != null && it.facts.setting == setting)
            } == true

        val inFlight = memory.inFlight
        return when (form) {
            is FollowUpForm.Switch -> {
                val template = if (form.on) "turn on {answer}" else "turn off {answer}"
                val question = if (form.on) GarFollowUpQuestion.TURN_WHAT_ON else GarFollowUpQuestion.TURN_WHAT_OFF
                if (inFlight) {
                    ask(question, template)
                } else {
                    (if (form.on) null else cancelling()) ?: walk() ?: pauseMusic(form.on) ?: ask(question, template)
                }
            }
            is FollowUpForm.Adjust -> {
                val kind = form.kind
                val template = if (form.up) "turn up {answer}" else "turn down {answer}"
                when {
                    kind != null -> match(GarFollowUpOutcome.Rewritten(adjust(kind, form.up)), null)
                    inFlight -> ask(upOrDown(form.up), template)
                    // "turn it up" while music plays: the volume, unless the brightness just moved
                    context.mediaPlaying && !justNow(setting = BRIGHTNESS) ->
                        match(GarFollowUpOutcome.Rewritten(adjust(AdjustKind.VOLUME, form.up)), null)
                    else -> walk() ?: ask(upOrDown(form.up), template)
                }
            }
            is FollowUpForm.Remove -> if (inFlight) {
                defer("in_flight")
            } else {
                cancelling() ?: defer("reach")
            }
            FollowUpForm.Snooze -> defer("reach")
            is FollowUpForm.Control -> when {
                inFlight -> defer("in_flight")
                context.mediaPlaying && !justNow(skillIds = MEDIA_CLOCKS) -> defer("media")
                else -> modify(confirm = false) ?: defer("reach")
            }
            is FollowUpForm.SetTo -> if (inFlight) {
                ask(GarFollowUpQuestion.CHANGE_WHAT, "set {answer} to ${form.value}")
            } else {
                modify() ?: walk() ?: ask(GarFollowUpQuestion.CHANGE_WHAT, "set {answer} to ${form.value}")
            }
            is FollowUpForm.AddMore -> {
                val template = if (form.less) "take ${form.amount} off {answer}" else "add ${form.amount} to {answer}"
                if (inFlight) {
                    ask(GarFollowUpQuestion.ADD_TIME_TO_WHAT, template)
                } else {
                    modify() ?: ask(GarFollowUpQuestion.ADD_TIME_TO_WHAT, template)
                }
            }
            is FollowUpForm.WhatAbout -> if (inFlight) {
                defer("in_flight")
            } else {
                answer(GarConversationMemory.MEMORY_MILLIS) ?: defer("reach")
            }
            is FollowUpForm.Another -> when {
                inFlight -> defer("in_flight")
                context.mediaPlaying && !justNow(skillIds = SONG_SKILLS) -> defer("media")
                newest?.adapter?.repeatable != true -> defer("reach")
                else -> answer(GarConversationMemory.MEMORY_MILLIS) ?: defer("reach")
            }
            is FollowUpForm.AboutSong -> when {
                inFlight -> defer("in_flight")
                form.verb == SongVerb.PLAY_AGAIN && context.mediaPlaying && !justNow(skillIds = SONG_SKILLS) ->
                    defer("media")
                else -> answer(GarConversationMemory.MEMORY_MILLIS) ?: defer("reach")
            }
            is FollowUpForm.Correction -> if (inFlight) {
                defer("in_flight")
            } else {
                answer(GarConversationMemory.CORRECTION_MILLIS) {
                    context.continued && it.turn.kind == GarTurnKind.MODIFIABLE
                } ?: defer("reach")
            }
            // "any haircut?" right after a search by name: only an adapter that searches takes
            // it, and only within a minute ("any news" later is no search of the calendar)
            is FollowUpForm.AnyOf -> if (inFlight) {
                defer("in_flight")
            } else {
                answer(GarConversationMemory.CORRECTION_MILLIS) ?: defer("reach")
            }
            is FollowUpForm.SendTo -> if (inFlight) {
                defer("in_flight")
            } else {
                answer(GarConversationMemory.MEMORY_MILLIS) { context.continued } ?: defer("reach")
            }
            is FollowUpForm.AboutPerson -> if (form.back) {
                // call_log's CallBack and the messages' reply know the exact number and thread
                defer("back")
            } else {
                GarEntityCommands.person(form, context, locked)
            }
            is FollowUpForm.AboutPlace -> GarEntityCommands.place(form, context, locked)
        }
    }

    /** The full command of an adjustment named by its word: "louder", "brighter". */
    fun adjust(kind: AdjustKind, up: Boolean): String = when (kind) {
        AdjustKind.VOLUME -> if (up) "turn the volume up" else "turn the volume down"
        AdjustKind.BRIGHTNESS -> if (up) "turn up the brightness" else "turn down the brightness"
    }

    private fun upOrDown(up: Boolean) =
        if (up) GarFollowUpQuestion.TURN_WHAT_UP else GarFollowUpQuestion.TURN_WHAT_DOWN

    /** The newest turns after which "pause it" and "reset it" aren't the music's. */
    private val MEDIA_CLOCKS = setOf("stopwatch", "timer", "timer_control")

    /** The newest turns after which "play it again" and "another one" aren't the music's. */
    private val SONG_SKILLS = setOf("song", "media_play")

    /** Skills with an off or a stop of their own, which "turn it off" never walks past. */
    private val OWN_STOP_SKILLS = setOf(
        "media_play", "media_controls", "song", "alarm", "timer", "timer_control", "stopwatch",
    )
}
