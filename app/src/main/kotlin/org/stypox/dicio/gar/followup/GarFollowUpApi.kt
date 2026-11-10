package org.stypox.dicio.gar.followup

/** How far follow-ups may reach a remembered turn (gar/DESIGN.md, "Follow-ups"). */
enum class GarTurnKind {
    /** A setting switched or moved: "turn it off" may walk back to it over other turns. */
    REVERSIBLE,

    /** A clock, a reminder or an event: changed or cancelled only while it is the newest turn. */
    MODIFIABLE,

    /** An answer: "what about tomorrow" and corrections reach it while it is the newest turn. */
    ANSWER,
}

/** Where a remembered person came from. */
enum class GarPersonSource { CONTACT, CALL_LOG, NOTIFICATION }

/**
 * A person a turn named. [unique]: the lookup chose that contact without asking "Which Erin?",
 * and the name has no words that would split a sentence ([GarRewriteText.safe]).
 */
data class GarPerson(val name: String, val number: String?, val source: GarPersonSource, val unique: Boolean)

data class GarPlace(val name: String, val address: String?, val lat: Double?, val lon: Double?)

data class GarTimerFact(val name: String?, val length: java.time.Duration)

data class GarAlarmFact(val time: java.time.LocalTime, val days: Set<java.time.DayOfWeek>, val label: String?)

data class GarSong(val title: String, val artist: String?)

/** The entities a turn's answer named, which later turns may refer to. */
data class GarFacts(
    val person: GarPerson? = null,
    val place: GarPlace? = null,
    val day: java.time.LocalDate? = null,
    val time: java.time.LocalTime? = null,
    val timer: GarTimerFact? = null,
    val alarm: GarAlarmFact? = null,
    val app: String? = null,
    val setting: String? = null,
    val song: GarSong? = null,
    /** Only text the user wrote: a text or email they sent, a note they dictated. */
    val ownText: String? = null,
    val others: List<String> = listOf(),
)

/**
 * One answered turn, as its skill's adapter remembers it.
 *
 * @param action the sentence id, tool name or the adapter's own name for it
 * @param slots the command's slots as words, what a rewrite starts from
 * @param command the full command as matched (the rewrite, for a follow-up)
 */
data class GarTurn(
    val skillId: String,
    val action: String,
    val slots: Map<String, String>,
    val facts: GarFacts,
    val command: String,
    val kind: GarTurnKind,
)

/** What SkillEvaluator hands the memory after a turn (B builds it, A reads it). */
data class GarAnswered(
    val skillId: String,
    /** What was matched (the rewrite, for a follow-up). */
    val input: String,
    val inputData: Any?,
    /** GarTurnElement.call: the model's complete call, when one ran this turn. */
    val call: org.stypox.dicio.gar.call.GarCall?,
    /** Unwrapped from GarFollowUpOutput by the memory before an adapter sees it. */
    val output: org.dicio.skill.skill.SkillOutput,
    val plan: org.dicio.skill.skill.InteractionPlan,
)

@JvmInline value class GarTurnToken(val id: Long)

class GarRememberedTurn(val turn: GarTurn, val adapter: GarFollowUpAdapter, val ageMillis: Long)

interface GarMemoryView {
    /** Newest first, within [GarConversationMemory.MEMORY_MILLIS], visible under the lock rule. */
    val turns: List<GarRememberedTurn>
    val newest: GarRememberedTurn?

    /** An earlier turn still generating after the wait. */
    val inFlight: Boolean

    /**
     * Whether [newest] is also the latest turn GAR answered: no small talk, text fallback or error
     * came after it ("repeat that" doesn't count).
     */
    val newestIsLatestAnswer: Boolean

    fun <T : Any> latest(maxAgeMillis: Long, fact: (GarFacts) -> T?): T?
}

/** What an adapter makes of a follow-up. */
sealed interface GarRewrite {
    /** The full command, matched again through the normal path. */
    data class Command(val text: String) : GarRewrite

    /** The target's own skill and input data, when a value can't go into text. */
    data class Direct(val text: String, val match: org.stypox.dicio.eval.SkillWithResult<*>) : GarRewrite

    /** An answer from what the turn remembers ("That's ABBA."). */
    data class Say(val speech: String) : GarRewrite
}

interface GarFollowUpContext {
    val now: java.time.LocalDateTime
    val mediaPlaying: Boolean
    val continued: Boolean
    val memory: GarMemoryView

    /** The default batch's skill with this id, for [GarRewrite.Direct]. */
    fun skill(id: String): org.dicio.skill.skill.Skill<*>?
}

/** One skill family's memory of its turns and rewrites of follow-ups about them. */
interface GarFollowUpAdapter {
    val skillIds: Set<String>

    /** Mirrors the target tools' `on_lock_screen`. */
    val onLockScreen: Boolean get() = false

    /** "Another one" repeats the turn's command. */
    val repeatable: Boolean get() = false

    /** The turn [answered] leaves, or null to leave the memory as it was. */
    fun remember(answered: GarAnswered): GarTurn?

    /** [form] about [turn] as a full command, or null when it isn't about this turn. */
    fun rewrite(
        form: cc.aaro.gar.router.followup.FollowUpForm,
        turn: GarRememberedTurn,
        context: GarFollowUpContext,
    ): GarRewrite?
}
