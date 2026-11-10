package org.stypox.dicio.gar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.tools.SentenceMapping
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.context.SpeechOutputDevice
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarSlotResolver
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.call.GarValueFormatter
import org.stypox.dicio.mocked
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

val ENGLISH_PARSER_FORMATTER = ParserFormatter(Locale.ENGLISH)

class FakeSkillContext(
    override val sentencesLanguage: String = "en",
    override val parserFormatter: ParserFormatter? = ENGLISH_PARSER_FORMATTER,
) : SkillContextInternal {
    override val android: Context get() = mocked()
    override val locale: Locale = Locale.ENGLISH
    override val speechOutputDevice: SpeechOutputDevice get() = mocked()
    override var previousOutput: SkillOutput? = null
    override var standardMatchHelper: MatchHelper? = null

    /** Prepares [standardMatchHelper] like the evaluator does before scoring [input]. */
    fun forInput(input: String): FakeSkillContext {
        standardMatchHelper = MatchHelper(parserFormatter, input)
        return this
    }
}

class FakeSkillInfo(id: String) : SkillInfo(id) {
    override fun name(context: Context): String = mocked()
    override fun sentenceExample(context: Context): String = mocked()
    @Composable override fun icon(): Painter = mocked()
    override fun build(ctx: SkillContext): Skill<*>? = mocked()
}

class FakeOutput(val input: Any?) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = "done"
    @Composable override fun GraphicalOutput(ctx: SkillContext) {}
}

/** A target skill that records the input data it was run with. */
open class RecordingSkill(info: SkillInfo) : Skill<Any?>(info, Specificity.HIGH) {
    val inputs = mutableListOf<Any?>()

    override fun score(ctx: SkillContext, input: String): Pair<Score, Any?> =
        Pair(AlwaysWorstScore, null)

    override suspend fun generateOutput(ctx: SkillContext, inputData: Any?): SkillOutput {
        inputs.add(inputData)
        return FakeOutput(inputData)
    }
}

/** An LLM-only target skill: its input is built from the slots by [inputFromSlots]. */
class RecordingToolSkill(info: SkillInfo) : RecordingSkill(info), GarToolSkill {
    override fun inputFromSlots(slots: Map<String, Any?>): Any = slots.toMap()
}

val TEST_TODAY: LocalDate = LocalDate.of(2026, 10, 25)

fun englishFormatter() = GarValueFormatter(
    parserFormatter = ENGLISH_PARSER_FORMATTER,
    locale = Locale.ENGLISH,
    words = GarValueFormatter.Words(
        today = "today",
        tomorrow = "tomorrow",
        dateAtTime = "%1\$s at %2\$s",
        every = "every %1\$s",
        percent = "%1\$s percent",
    ),
    today = { TEST_TODAY },
)

val SEVEN_AM = ResolvedTime(LocalTime.of(7, 0))
val SEVEN_PM = ResolvedTime(LocalTime.of(19, 0))

/** Stands in for `:gar:router`'s resolvers with a few fixed phrases. */
val fakeResolver = GarSlotResolver { slot, raw ->
    when (slot.kind) {
        SlotKind.DURATION -> when (raw) {
            "ten minutes" -> Resolution.Resolved(Duration.ofMinutes(10))
            else -> Resolution.Unresolvable("not a duration")
        }
        SlotKind.TIME -> when (raw) {
            "seven" -> Resolution.Ambiguous(listOf(SEVEN_AM, SEVEN_PM), AmbiguityReason.AM_OR_PM)
            "seven pm" -> Resolution.Resolved(SEVEN_PM)
            else -> Resolution.Unresolvable("not a time")
        }
        SlotKind.ENUM -> DefaultGarSlotResolver.resolveEnum(slot, raw)
        else -> DefaultGarSlotResolver.resolveText(raw)
    }
}

val TIMER_SET_TOOL = ToolSpec(
    name = "timer_set",
    skillId = "timer",
    sentence = SentenceMapping.Fixed("set", listOf("duration", "name")),
    description = "Start a countdown timer.",
    confirm = true,
    slots = listOf(
        SlotSpec("duration", SlotKind.DURATION, true, "How long", captureType = "duration"),
        SlotSpec("name", SlotKind.TEXT, false, "Name", captureType = "string"),
    ),
    examples = listOf(),
)

val FLASHLIGHT_TOOL = ToolSpec(
    name = "flashlight",
    skillId = "flashlight",
    sentence = SentenceMapping.ByEnum("state", mapOf("on" to "turn_on", "off" to "turn_off")),
    description = "Turn the flashlight on or off.",
    confirm = false,
    slots = listOf(
        SlotSpec("state", SlotKind.ENUM, true, "On or off", enumValues = listOf("on", "off")),
    ),
    examples = listOf(),
)

/** An LLM-only tool (no sentence) with two required slots. */
val ALARM_TOOL = ToolSpec(
    name = "alarm_set",
    skillId = "alarm",
    sentence = null,
    description = "Set an alarm.",
    confirm = true,
    slots = listOf(
        SlotSpec("time", SlotKind.TIME, true, "When"),
        SlotSpec("label", SlotKind.TEXT, true, "Label"),
    ),
    examples = listOf(),
)

/** Every English skill of a new install, ranked as `SkillHandler` ranks them. */
private val followUpTargetRanker by lazy {
    org.stypox.dicio.eval.SkillRanker(
        org.stypox.dicio.gar.online.GarOnlineSkills.forRanking(
            allEnglishSkills(),
            org.stypox.dicio.settings.datastore.UserSettingsSerializer.defaultValue.enabledSkillsMap,
        ),
        org.stypox.dicio.MockSkill(Specificity.LOW, 0.0f),
    )
}

private val followUpMatchCheck by lazy {
    GarMatchCheck.State(
        tools = cc.aaro.gar.router.tools.ToolCatalog.load(
            java.io.File("src/main/gar/tools.yml").readText(),
            java.io.File("src/main/sentences/skill_definitions.yml").readText(),
        ).tools,
        parserFormatter = ENGLISH_PARSER_FORMATTER,
    )
}

/**
 * Checks a follow-up's rewrite ([org.stypox.dicio.gar.followup.GarRewrite.Command]): [text] is a
 * whole sentence ([org.stypox.dicio.gar.conversation.GarFollowUps.COMMAND_SCORE]) of the skill
 * [skillId] among every English skill, GAR's match check of the real catalog accepts it, and its
 * input data reads [slots] back (by property name, also inside a sentence match's `sentence`).
 */
fun shouldBeTakenBy(text: String, skillId: String, slots: Map<String, String> = mapOf()) {
    val best = followUpTargetRanker.getBest(FakeSkillContext().forInput(text), text)
    withClue(text) {
        best?.skill?.correspondingSkillInfo?.id shouldBe skillId
        (best!!.score.scoreIn01Range() >= org.stypox.dicio.gar.conversation.GarFollowUps.COMMAND_SCORE) shouldBe true
        GarMatchCheck.rejects(followUpMatchCheck, best, text) shouldBe false
        for ((name, value) in slots) {
            withClue(name) { slotOf(best.inputData, name)?.trim()?.lowercase() shouldBe value }
        }
    }
}

/** The property [name] of [data] as a string, or of its `sentence` or `request`; null if none. */
fun slotOf(data: Any?, name: String): String? {
    data ?: return null
    val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
    data.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
        ?.let { return it.invoke(data)?.toString() }
    for (nested in listOf("getSentence", "getRequest")) {
        val inner = data.javaClass.methods.firstOrNull { it.name == nested && it.parameterCount == 0 }
            ?.invoke(data) ?: continue
        slotOf(inner, name)?.let { return it }
    }
    return null
}
