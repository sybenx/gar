package org.stypox.dicio.gar.followup

import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.conversation.GarInputMode
import org.stypox.dicio.gar.conversation.GarTurnElement
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

/** 09:00 on [org.stypox.dicio.gar.TEST_TODAY]. */
val FOLLOW_UP_NOW: LocalDateTime = LocalDateTime.of(2026, 10, 25, 9, 0)

/** The memory's clock in tests: milliseconds since a fake boot, moved by hand. */
class FakeClock(var now: Long = 1_000_000L) {
    fun advance(millis: Long) {
        now += millis
    }
}

/** Resets [GarConversationMemory] for a test: its own [clock], every adapter, nothing remembered. */
fun resetMemory(clock: FakeClock = FakeClock()): FakeClock {
    GarConversationMemory.clock = { clock.now }
    GarConversationMemory.adapters = GarFollowUpAdapters.DEFAULT
    GarConversationMemory.enabled = true // forgets
    return clock
}

private val elementIds = AtomicLong()

fun turnElement(
    locked: Boolean = false,
    continued: Boolean = false,
    whileSpeaking: Boolean = false,
    mode: GarInputMode = GarInputMode.SPOKEN,
) = GarTurnElement(elementIds.incrementAndGet(), mode, continued, whileSpeaking, locked)

fun answered(
    skillId: String,
    input: String,
    inputData: Any?,
    output: SkillOutput,
    plan: InteractionPlan = InteractionPlan.FinishInteraction,
    call: GarCall? = null,
) = GarAnswered(skillId, input, inputData, call, output, plan)

/**
 * One whole turn of the evaluator, as SkillEvaluator brackets it. [continuesStep]: matched from a
 * pending question's batch; a test of a step left unanswered passes false.
 */
fun remember(
    answered: GarAnswered?,
    element: GarTurnElement = turnElement(),
    lockedNow: Boolean = element.lockedAtStart,
    continuesStep: Boolean = true,
) {
    val token = GarConversationMemory.begin(element, continuesStep)
    GarConversationMemory.finish(token, answered, lockedNow)
}

/** A memory view of hand-made turns, for the adapters' rewrites. */
class FakeView(
    override val turns: List<GarRememberedTurn>,
    override val inFlight: Boolean = false,
    override val newestIsLatestAnswer: Boolean = true,
) : GarMemoryView {
    override val newest: GarRememberedTurn? get() = turns.firstOrNull()

    override fun <T : Any> latest(maxAgeMillis: Long, fact: (GarFacts) -> T?): T? =
        turns.filter { it.ageMillis <= maxAgeMillis }.firstNotNullOfOrNull { fact(it.turn.facts) }
}

class FakeFollowUpContext(
    override val memory: GarMemoryView,
    override val mediaPlaying: Boolean = false,
    override val continued: Boolean = false,
    override val now: LocalDateTime = FOLLOW_UP_NOW,
) : GarFollowUpContext {
    override fun skill(id: String): Skill<*>? = null
}

/** The turn [adapter] remembers for [answered], [ageMillis] old. */
fun rememberedBy(adapter: GarFollowUpAdapter, answered: GarAnswered, ageMillis: Long = 1_000L) =
    GarRememberedTurn(checkNotNull(adapter.remember(answered)) { "${answered.skillId} not remembered" }, adapter, ageMillis)

/** What [adapter] makes of [form] about [turn], with only [turn] remembered. */
fun rewriteOf(
    adapter: GarFollowUpAdapter,
    form: cc.aaro.gar.router.followup.FollowUpForm,
    turn: GarRememberedTurn,
    mediaPlaying: Boolean = false,
): GarRewrite? = adapter.rewrite(form, turn, FakeFollowUpContext(FakeView(listOf(turn)), mediaPlaying))

/** The command of a [GarRewrite.Command], or null. */
val GarRewrite?.command: String? get() = (this as? GarRewrite.Command)?.text
