package org.stypox.dicio.gar.sheet

import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.GarKeepScreenOn
import org.stypox.dicio.gar.ui.GarMicState
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion

/**
 * An answered turn: the interaction's [skill], the [question] (null for none), the [answer], and
 * whether it was [spoken] by TTS (then screen readers aren't asked to announce it too).
 */
data class GarSheetTurn(
    val skill: SkillInfo?,
    val question: String?,
    val answer: SkillOutput,
    val spoken: Boolean = false,
)

/**
 * The turns of the evaluator's log that belong to one show of the sheet: the [pending] question
 * and the [answered] turns past the [baseline], which the caller keeps for the next [select].
 */
data class GarSheetTurns(
    val baseline: Int,
    val pending: PendingQuestion?,
    val answered: List<GarSheetTurn>,
) {
    val latest: GarSheetTurn? get() = answered.lastOrNull()
}

/** The base auto-dismiss times, before the accessibility settings lengthen them. */
data class GarSheetTimeouts(
    /** Idle with an answer on the sheet. */
    val answeredMillis: Long = ANSWERED_MILLIS,
    /** Idle without one: silence, nothing understood, a false wake word, an STT error. */
    val emptyMillis: Long = EMPTY_MILLIS,
    /** Speech input can't be used ([GarMicState.Unavailable]). */
    val unavailableMillis: Long = UNAVAILABLE_MILLIS,
) {
    companion object {
        const val ANSWERED_MILLIS = 8_000L
        const val EMPTY_MILLIS = 6_000L
        const val UNAVAILABLE_MILLIS = 15_000L
    }
}

/**
 * What the sheet does while nothing happens: [dismissAt] (elapsedRealtime ms, null while busy,
 * typing or explored by touch) and whether its window keeps the screen on.
 */
data class GarSheetIdle(
    val busy: Boolean,
    val dismissAt: Long?,
    val keepScreenOn: Boolean,
)

/** The sheet's pure decisions, tested in GarSheetStateTest. */
object GarSheetState {
    /** All answered turns of [log], however they are grouped into interactions. */
    fun answeredCount(log: InteractionLog): Int =
        log.interactions.sumOf { it.questionsAnswers.size }

    /**
     * The baseline at show: every answered turn of [log] is from before, and so is a question
     * pending at show when [countPending] (a route started before the show lands as an answer
     * after it). The wake word's own capture is already running at show, so its pending
     * question is the sheet's: pass false for it.
     */
    fun baseline(log: InteractionLog, countPending: Boolean = true): Int =
        answeredCount(log) + if (countPending && log.pendingQuestion != null) 1 else 0

    /**
     * The sheet's turns in [log]. A question pending at show stays hidden until it is answered
     * (then it is within the baseline). If it ended without an answer (silence), the baseline
     * comes back down, so that the sheet's own first answer isn't taken for it.
     */
    fun select(log: InteractionLog, baseline: Int): GarSheetTurns {
        val count = answeredCount(log)
        val base = if (log.pendingQuestion == null && count < baseline) count else baseline
        val answered = log.interactions
            .flatMap { interaction ->
                interaction.questionsAnswers.map {
                    GarSheetTurn(interaction.skill, it.question, it.answer, it.spoken)
                }
            }
            .drop(base)
        return GarSheetTurns(
            baseline = base,
            pending = log.pendingQuestion.takeIf { count >= base },
            answered = answered,
        )
    }

    /** Listening, loading to listen, transcribing, thinking or speaking. */
    fun busy(micState: GarMicState): Boolean = when (micState) {
        is GarMicState.Listening,
        GarMicState.Starting,
        GarMicState.Transcribing,
        is GarMicState.Thinking,
        GarMicState.Speaking -> true
        GarMicState.Idle,
        is GarMicState.Unavailable -> false
    }

    /**
     * When the sheet goes away by itself, and whether it keeps the screen on.
     * @param continuedOpen a continued-listening window is open (or about to be)
     * @param lastBusyAt the last moment the sheet was busy or touched (elapsedRealtime ms)
     * @param typing the text field has focus: no auto-dismiss
     * @param touchExploration a screen reader explores by touch (TalkBack): no auto-dismiss, since
     *   swiping from item to item sends the sheet no touches and an answer takes long to hear
     * @param timeouts already lengthened for accessibility
     * @param settingOn "Keep the screen on while GAR is open"
     */
    fun idleDecision(
        micState: GarMicState,
        hasAnswer: Boolean,
        continuedOpen: Boolean,
        locked: Boolean,
        lastBusyAt: Long,
        now: Long,
        timeouts: GarSheetTimeouts,
        typing: Boolean = false,
        settingOn: Boolean = true,
        touchExploration: Boolean = false,
    ): GarSheetIdle {
        val busy = busy(micState) || continuedOpen
        val dismissAt = when {
            busy || typing || touchExploration -> null
            micState is GarMicState.Unavailable -> lastBusyAt + timeouts.unavailableMillis
            hasAnswer -> lastBusyAt + timeouts.answeredMillis
            else -> lastBusyAt + timeouts.emptyMillis
        }
        // GarKeepScreenOn's rule with "interacting" = busy; over the lock screen also for a
        // while after: when idle the flag goes, so the screen times out (and the sheet with it)
        val interacting = busy ||
            locked && now - lastBusyAt < GarKeepScreenOn.LOCKED_WINDOW_MILLIS
        return GarSheetIdle(
            busy = busy,
            dismissAt = dismissAt,
            keepScreenOn = settingOn && interacting,
        )
    }
}
