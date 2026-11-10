package org.stypox.dicio.gar.followup

import android.os.SystemClock
import android.util.Log
import cc.aaro.gar.router.followup.FollowUpForms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarCancelledOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.conversation.GarEndsConversation
import org.stypox.dicio.gar.conversation.GarTurnElement
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.skills.conversation.ConversationInfo
import org.stypox.dicio.skills.conversation.ConversationNothingToRepeatOutput
import org.stypox.dicio.skills.conversation.ConversationRepeatOutput
import org.stypox.dicio.skills.fallback.text.TextFallbackInfo
import org.stypox.dicio.skills.small_talk.SmallTalkInfo

/**
 * GAR's memory of the last answered turns, which follow-ups refer to ("turn it off", "what about
 * tomorrow", "call her"): gar/DESIGN.md, "Follow-ups". In RAM only, newest first, at most
 * [MAX_TURNS] turns of the last [MEMORY_MILLIS].
 *
 * `SkillEvaluator` brackets every turn: [awaitSettled] before its match lock, [begin] under it
 * right after matching, [finish] in a `finally` after the output, its plan and speech. Only an
 * output that finishes a step is remembered, through the adapter of the skill that answered
 * ([GarFollowUpAdapters]); a skill without one leaves a bare turn, which follow-ups don't reach.
 * Errors, permission cards, locked answers, Dicio's text fallback, the conversation skill, small
 * talk and the follow-up skill's own answers leave the memory as it was, so "repeat that" or a
 * misheard input between two turns doesn't break "turn it off". They still count as the latest
 * answer ([GarMemoryView.newestIsLatestAnswer]), which "cancel it" needs; only "repeat that"
 * doesn't.
 */
object GarConversationMemory {
    private const val TAG = "GarConversationMemory"

    const val MEMORY_MILLIS = 5 * 60_000L
    const val CORRECTION_MILLIS = 60_000L
    const val REMOVE_MILLIS = 60_000L
    const val PERSON_MILLIS = 2 * 60_000L
    const val SETTLE_MILLIS = 10_000L

    /** A token never finished (a crash) stops counting as a turn in flight after this. */
    const val TOKEN_MILLIS = 30_000L

    const val MAX_TURNS = 3

    /**
     * Whether the follow-up skill is on. Set when Dicio builds its skills, after a change of
     * the sentences language or of the enabled skills, which forgets every turn; nothing is
     * written while it is false.
     */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            forget()
        }

    /** Milliseconds since boot, deep sleep included; JVM tests replace it. */
    @Volatile
    internal var clock: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (e: RuntimeException) {
            System.nanoTime() / 1_000_000 // JVM tests, where Android's stubs throw
        }
    }

    /** Which adapter remembers each skill's turns. */
    @Volatile
    internal var adapters: GarFollowUpAdapters = GarFollowUpAdapters.DEFAULT

    private class Entry(
        val turn: GarTurn,
        val adapter: GarFollowUpAdapter,
        /** Answered over the lock screen: the only turns visible while the phone is locked. */
        val visibleLocked: Boolean,
        val answeredAt: Long,
    )

    private class Flight(
        val element: GarTurnElement,
        val startedAt: Long,
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val lock = Any()
    private var entries: List<Entry> = listOf()
    private val flights = LinkedHashMap<Long, Flight>()
    private var nextId = 1L

    /** The questions of the step that is still going on ("Call Erin?", the sms card), by skill. */
    private val asked = mutableListOf<Pair<String, SkillOutput>>()

    /**
     * The entry the latest answered turn left, or null when that turn was answered without being
     * remembered (small talk, the text fallback, an error): see [GarMemoryView.newestIsLatestAnswer].
     */
    private var latestAnswer: Entry? = null

    /** Before matchMutex: waits for an unfinished turn when utterances[0] is a follow-up form. */
    suspend fun awaitSettled(utterances: List<String>) {
        if (!enabled) {
            return
        }
        val first = utterances.firstOrNull() ?: return
        if (FollowUpForms.parse(first) == null) {
            return
        }
        val pending = synchronized(lock) {
            val now = clock()
            flights.values.filter { now - it.startedAt <= TOKEN_MILLIS }.map { it.done }
        }
        if (pending.isNotEmpty()) {
            withTimeoutOrNull(SETTLE_MILLIS) {
                pending.forEach { it.await() }
            }
        }
    }

    /**
     * Inside matchMutex, right after matching. [continuesStep]: the match came from a pending
     * question's batch; otherwise the step the user left ("Call Mom?" never answered) is over and
     * its questions are forgotten.
     */
    fun begin(turn: GarTurnElement, continuesStep: Boolean): GarTurnToken = synchronized(lock) {
        if (!continuesStep) {
            asked.clear()
        }
        val id = nextId++
        flights[id] = Flight(turn, clock())
        GarTurnToken(id)
    }

    /** After output, plan and speech, always (finally); [answered] null for errors and permission cards. */
    fun finish(token: GarTurnToken, answered: GarAnswered?, lockedNow: Boolean) {
        val flight = synchronized(lock) { flights.remove(token.id) } ?: return
        var entry: Entry? = null
        try {
            if (answered != null && enabled) {
                entry = remember(answered, visibleLocked = flight.element.lockedAtStart && lockedNow)
            }
        } catch (e: Exception) {
            // the memory must never break the evaluator: this turn is just not remembered
            try {
                Log.w(TAG, "Can't remember the turn of ${answered?.skillId}", e)
            } catch (notMocked: RuntimeException) {
                // JVM tests
            }
        } finally {
            val output = answered?.output
            if (output !is ConversationRepeatOutput && output !is ConversationNothingToRepeatOutput) {
                synchronized(lock) { latestAnswer = entry }
            }
            flight.done.complete(Unit)
        }
    }

    fun view(locked: Boolean): GarMemoryView = synchronized(lock) {
        val now = clock()
        val visible = entries.filter { now - it.answeredAt <= MEMORY_MILLIS && (!locked || it.visibleLocked) }
        View(
            turns = visible.map { GarRememberedTurn(it.turn, it.adapter, now - it.answeredAt) },
            inFlight = flights.values.any { now - it.startedAt <= TOKEN_MILLIS },
            newestIsLatestAnswer = latestAnswer != null && visible.firstOrNull() === latestAnswer,
        )
    }

    fun forget() {
        synchronized(lock) {
            entries = listOf()
            asked.clear()
            latestAnswer = null
        }
    }

    /**
     * The questions [skillId] asked during the step whose answer is being remembered now, oldest
     * first: an adapter reads the contact of "Call Erin?" when the call's "yes" finishes the step.
     */
    internal fun askedBy(skillId: String): List<SkillOutput> = synchronized(lock) {
        asked.filter { it.first == skillId }.map { it.second }
    }

    /**
     * Every question of that step, whichever skill asked it: call_log's "Call Erin?" is answered
     * by the telephone's yes.
     */
    internal fun askedByAny(): List<SkillOutput> = synchronized(lock) {
        asked.map { it.second }
    }

    /** The entry [raw] left, or null when it left the memory as it was. */
    private fun remember(raw: GarAnswered, visibleLocked: Boolean): Entry? {
        val output = unwrap(raw.output)
        val answered = raw.copy(output = output)
        if (!finishesStep(answered.plan)) {
            if (isRemembered(answered.skillId, output)) {
                synchronized(lock) { asked.add(Pair(answered.skillId, output)) }
            }
            return null
        }
        try {
            if (!isRemembered(answered.skillId, output)) {
                return null
            }
            val adapter = adapters.forSkill(answered.skillId)
            val turn = adapter.remember(answered) ?: return null
            val entry = Entry(turn, adapter, visibleLocked, clock())
            synchronized(lock) {
                entries = (listOf(entry) + entries).take(MAX_TURNS)
            }
            return entry
        } finally {
            synchronized(lock) { asked.clear() }
        }
    }

    /** A step ends with an answer, not with a question that reopens the microphone. */
    private fun finishesStep(plan: InteractionPlan): Boolean = when (plan) {
        is InteractionPlan.FinishInteraction,
        is InteractionPlan.FinishSubInteraction,
        is InteractionPlan.Continue -> !plan.reopenMicrophone
        is InteractionPlan.StartSubInteraction,
        is InteractionPlan.ReplaceSubInteraction -> false
    }

    private fun isRemembered(skillId: String, output: SkillOutput): Boolean =
        skillId !in NOT_REMEMBERED_SKILLS &&
            output !is ErrorSkillOutput &&
            output !is MissingPermissionsSkillOutput &&
            output !is GarLockedOutput &&
            output !is GarCancelledOutput &&
            output !is GarEndsConversation &&
            output !is GarFollowUpOwnOutput

    private fun unwrap(output: SkillOutput): SkillOutput =
        if (output is GarFollowUpOutput) unwrap(output.output) else output

    /** Skills whose answers aren't something to follow up on. */
    private val NOT_REMEMBERED_SKILLS = setOf(
        ConversationInfo.id, SmallTalkInfo.id, TextFallbackInfo.id, GarFollowUpInfo.id,
    )

    private class View(
        override val turns: List<GarRememberedTurn>,
        override val inFlight: Boolean,
        override val newestIsLatestAnswer: Boolean,
    ) : GarMemoryView {
        override val newest: GarRememberedTurn? get() = turns.firstOrNull()

        override fun <T : Any> latest(maxAgeMillis: Long, fact: (GarFacts) -> T?): T? =
            turns.filter { it.ageMillis <= maxAgeMillis }.firstNotNullOfOrNull { fact(it.turn.facts) }
    }
}
