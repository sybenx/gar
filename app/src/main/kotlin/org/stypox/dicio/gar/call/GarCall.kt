package org.stypox.dicio.gar.call

import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.tools.ConfirmText
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.TelephoneSkill
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * A tool call the model made, plus the slot values resolved or answered so far.
 *
 * @param rawSlots slot strings exactly as the model produced them
 * @param values resolved values by slot name (see [GarSlotResolver]); a slot present with a null
 * value is an optional slot the user left out
 */
data class GarCall(
    val tool: ToolSpec,
    val rawSlots: Map<String, String>,
    val utterance: String,
    val values: Map<String, Any?> = mapOf(),
)

/**
 * Implemented by skills that have no sentences (LLM-only skills), so GAR can build their input.
 * Sentence-backed skills don't need it, see [GarSentenceBinder].
 */
interface GarToolSkill {
    /**
     * @param slots resolved values by slot name (see [GarSlotResolver] for the types); optional
     * slots the user left out are null
     * @return the input data passed to the skill's `generateOutput`
     */
    fun inputFromSlots(slots: Map<String, Any?>): Any?

    /**
     * The input for [call], for skills that read more than the slots (gar:weather: the slotless
     * weather tool takes the day and the place from the utterance).
     */
    fun inputFromCall(ctx: SkillContext, call: GarCall): Any? = inputFromSlots(call.values)
}

/** What GAR asks the user about one slot. */
sealed interface GarQuestion {
    /**
     * The slot is missing, or [unresolved] could not be understood: ask for it by slot kind.
     */
    data class Value(val unresolved: String?) : GarQuestion

    /** Several readings are possible: ask the user to pick one of [options]. */
    data class Pick(val options: List<Any?>, val reason: AmbiguityReason) : GarQuestion
}

/** An answer to a [GarQuestion], see [GarCallRunner.interpret]. */
sealed interface GarAnswer {
    data class Value(val value: Any?) : GarAnswer
    data class NeedsPick(val options: List<Any?>, val reason: AmbiguityReason) : GarAnswer
}

/**
 * Takes a [GarCall] from raw slots to the target skill's output: resolves each slot in order,
 * asks about missing, unclear or ambiguous ones, asks for confirmation if needed, and finally
 * runs [target] with the input it expects.
 *
 * @param targetInfo the info of [target], used for every skill and output GAR adds, so that cards
 * show the target skill
 * @param confirm whether calls of tools with [ToolSpec.confirm] need a yes before running
 * @param confirmAllTools with [confirm], also confirm tools without [ToolSpec.confirm] (for models
 * that call a tool for almost anything)
 */
class GarCallRunner(
    val targetInfo: SkillInfo,
    val target: Skill<*>,
    val confirm: Boolean,
    val resolver: GarSlotResolver,
    val formatter: GarValueFormatter,
    val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>?,
    val confirmAllTools: Boolean = false,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    /**
     * @param nested false for the output that answers the user's command, true for outputs that
     * answer a GAR follow-up question (they replace GAR's batch of skills instead of adding one)
     */
    suspend fun next(ctx: SkillContext, call: GarCall, nested: Boolean): SkillOutput {
        val values = call.values.toMutableMap()
        // gar:media: a media_play app the user didn't say after on, in or with ("album") is none
        org.stypox.dicio.skills.media.MediaPlaySkill.unsaidAppSlot(call)?.let { values[it] = null }
        for (slot in call.tool.slots) {
            if (slot.name in values) {
                continue
            }

            val raw = call.rawSlots[slot.name]
                ?.let { GarSlotRewrites.rewrite(call.tool, slot, it) }
                ?.takeIf { it.isNotBlank() }
            if (raw == null) {
                if (slot.required) {
                    return ask(call.copy(values = values), slot, GarQuestion.Value(null), nested)
                }
                values[slot.name] = null
                continue
            }

            when (val resolution = resolver.resolve(slot, raw)) {
                is Resolution.Resolved -> values[slot.name] = resolution.value
                is Resolution.Ambiguous -> return ask(
                    call.copy(values = values),
                    slot,
                    GarQuestion.Pick(resolution.options, resolution.reason),
                    nested,
                )
                is Resolution.Unresolvable ->
                    return ask(call.copy(values = values), slot, GarQuestion.Value(raw), nested)
            }
        }

        // gar:settings: a device_setting call as its words ask (a question is the status), and
        // asked about when it switches a setting they don't name
        val complete = org.stypox.dicio.skills.device_settings.DeviceSettingCalls
            .checked(call.copy(values = values))
        return if (confirm && (call.tool.confirm || confirmAllTools) ||
            org.stypox.dicio.skills.device_settings.DeviceSettingCalls.needsConfirm(complete)
        ) {
            GarConfirmOutput(this, complete, nested)
        } else {
            run(ctx, complete, fromFollowUp = nested)
        }
    }

    /** Continues [call] once the user answered the question about [slot]. */
    suspend fun answered(
        ctx: SkillContext,
        call: GarCall,
        slot: SlotSpec,
        answer: GarAnswer,
    ): SkillOutput {
        return when (answer) {
            is GarAnswer.Value ->
                next(ctx, call.copy(values = call.values + (slot.name to answer.value)), true)
            is GarAnswer.NeedsPick ->
                ask(call, slot, GarQuestion.Pick(answer.options, answer.reason), true)
        }
    }

    /**
     * Runs the target skill with all slots resolved.
     *
     * @param fromFollowUp true when one of GAR's follow-up skills (answer or confirmation) runs
     * the target, so GAR's batch has to make way for the target's plan, see [GarFollowUpOutput]
     */
    suspend fun run(ctx: SkillContext, call: GarCall, fromFollowUp: Boolean): SkillOutput {
        // gar:followup: the complete call, which the follow-ups' memory remembers the turn by
        org.stypox.dicio.gar.conversation.GarTurnElement.current()?.call = call
        // gar:answers: a place in a current_time call, units in a calculation, holidays
        (directOutput(call) ?: GarEverydayCalls.output(ctx, call)
            // gar:messages: read_notifications by person or app, sms_send for an email or a reply
            ?: org.stypox.dicio.skills.messages.MessagesCalls.output(ctx, call, targetInfo))
            ?.let { return if (fromFollowUp) GarFollowUpOutput(it) else it }
        // gar:knowledge: money in a calculation, through the currency skill's ask-once gate
        org.stypox.dicio.skills.currency.CurrencyCalls.output(ctx, call)
            ?.let { return if (fromFollowUp) GarFollowUpOutput(it) else it }
        // gar:settings: an open_app call for a page of Android's Settings ("battery settings"), a
        // volume call about the brightness
        (org.stypox.dicio.skills.device_settings.SettingsPageCalls.output(ctx, call)
            ?: org.stypox.dicio.skills.device_settings.DeviceSettingCalls.output(ctx, call))
            ?.let { return if (fromFollowUp) GarFollowUpOutput(it) else it }

        val input = if (call.tool.sentence == null) {
            // gar:weather: an online skill that is off runs behind its gate, which wraps it
            val toolSkill = target as? GarToolSkill
                ?: (target as? org.stypox.dicio.gar.online.GarCheckedOnlineSkill)?.wrapped as? GarToolSkill
                ?: throw IllegalStateException(
                    "${call.tool.name} has no sentence, so skill ${targetInfo.id} must implement GarToolSkill"
                )
            toolSkill.inputFromCall(ctx, call)
        } else {
            GarSentenceBinder.buildInput(call.tool, call.values, now())
        }

        @Suppress("UNCHECKED_CAST")
        val output = (target as Skill<Any?>).generateOutput(ctx, input)
        return if (fromFollowUp) GarFollowUpOutput(output) else output
    }

    /**
     * The output for a call that GAR answers itself because the target skill can't take it, or
     * null to run the target.
     */
    private fun directOutput(call: GarCall): SkillOutput? {
        // the telephone skill only matches contact names: dial a number spelled out digit by
        // digit with the same confirmation the telephone skill uses for contacts
        if (target is TelephoneSkill) {
            val number = call.tool.slots.filter { it.kind == SlotKind.CONTACT }
                .firstNotNullOfOrNull { (call.values[it.name] as? String)?.let(::spokenPhoneNumber) }
            if (number != null) {
                return ConfirmCallOutput(number, number, target.yesNoData)
            }
        }
        return null
    }

    /**
     * @return what [input] answers to [question] about [slot] of [tool], or null if it doesn't
     * answer it (cancel phrases never answer, see [GarCancelSkill])
     */
    fun interpret(input: String, tool: ToolSpec, slot: SlotSpec, question: GarQuestion): GarAnswer? {
        if (normalizeAnswer(input).isEmpty() || isCancelPhrase(input)) {
            return null
        }

        return when (question) {
            is GarQuestion.Pick -> if (question.reason == AmbiguityReason.DAY_OF_MONTH) {
                pickDay(input, slot, question.options)
            } else {
                pick(input, slot, question)?.let { GarAnswer.Value(it) }
            }
            is GarQuestion.Value -> when (
                val resolution = GarSlotRewrites.rewrite(tool, slot, input)
                    .takeIf { it.isNotBlank() }
                    ?.let { resolver.resolve(slot, it) }
                    ?: return null
            ) {
                is Resolution.Resolved -> GarAnswer.Value(resolution.value)
                is Resolution.Ambiguous ->
                    GarAnswer.NeedsPick(resolution.options, resolution.reason)
                is Resolution.Unresolvable ->
                    if (slot.kind == SlotKind.ENUM) {
                        parseOptionIndex(input, slot.enumValues.size)
                            ?.let { GarAnswer.Value(slot.enumValues[it]) }
                    } else {
                        null
                    }
            }
        }
    }

    private fun pick(input: String, slot: SlotSpec, question: GarQuestion.Pick): Any? {
        val options = question.options

        // the option itself, as shown or spoken, first: "last fm" is the app Last.fm, not the
        // last option
        val normalized = normalizeAnswer(input)
        options.firstOrNull { option ->
            sequenceOf(false, true).any { speech ->
                formatter.format(option, speech)?.let(::normalizeAnswer) == normalized
            }
        }?.let { return it }

        // "the second one", "last"
        parseOptionIndex(input, options.size)?.let { return options[it] }

        // "pm", "in the morning"
        if (question.reason == AmbiguityReason.AM_OR_PM) {
            parseAmPm(input)?.let { pm ->
                options.firstOrNull { option -> hourOf(option)?.let { (it >= 12) == pm } == true }
                    ?.let { return it }
            }
        }

        // a complete new answer ("seven pm"), except for free text where anything would match
        if (slot.kind !in FREE_TEXT_KINDS) {
            val resolution = resolver.resolve(slot, input)
            if (resolution is Resolution.Resolved) {
                return resolution.value
            }
        }
        return null
    }

    /**
     * The answer to "Which day in December?" ([AmbiguityReason.DAY_OF_MONTH], [options] are the
     * month's days): the day said in that month ("the fifth", "5", "the twelfth at nine"), at the
     * time said before unless the answer says one, or a complete new answer ("next friday",
     * "january": then which day in January). A day of this month that has passed answers nothing,
     * so the question stays. English, like the resolvers' day words.
     */
    private fun pickDay(input: String, slot: SlotSpec, options: List<Any?>): GarAnswer? {
        val month = options.firstNotNullOfOrNull { dateOf(it) }?.let { YearMonth.from(it) } ?: return null
        val name = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val inMonth = { value: Any? -> dateOf(value)?.let { YearMonth.from(it) } == month }
        // the resolvers read a passed day of this month in the same month next year
        val passed = { value: Any? -> dateOf(value)?.let { YearMonth.from(it) } == month.plusYears(1) }
        var dayPassed = false

        /** [value], or the option of its day when the answer only said the day. */
        fun answer(value: Any?): GarAnswer {
            if ((value as? ResolvedDateTime)?.timeSpoken == true) {
                return GarAnswer.Value(value)
            }
            val onDay = options.filter { dateOf(it) == dateOf(value) }
            return when {
                onDay.isEmpty() -> GarAnswer.Value(value)
                onDay.size == 1 -> GarAnswer.Value(onDay[0])
                else -> GarAnswer.NeedsPick(onDay, AmbiguityReason.AM_OR_PM)
            }
        }

        // the year last, after the time the answer may end with ("december the fifth at nine
        // pm"), only for a month that isn't the next one of that name ("december 2027")
        val ways = listOf("$input of $name", "$name $input")
        for (said in ways + ways.map { "$it ${month.year}" }) {
            when (val resolution = resolver.resolve(slot, said)) {
                is Resolution.Resolved -> if (inMonth(resolution.value)) {
                    return answer(resolution.value)
                } else if (passed(resolution.value)) {
                    dayPassed = true
                }
                // "the fifth at seven": morning or evening?
                is Resolution.Ambiguous -> if (resolution.reason == AmbiguityReason.AM_OR_PM) {
                    if (resolution.options.all(inMonth)) {
                        return GarAnswer.NeedsPick(resolution.options, resolution.reason)
                    } else if (resolution.options.all(passed)) {
                        dayPassed = true
                    }
                }
                is Resolution.Unresolvable -> {}
            }
        }
        // "the fifth" said on the 25th is a passed day, not the fifth of next month that the
        // answer alone is, so nothing is answered; a day of next year said as such stays
        return when (val resolution = resolver.resolve(slot, input)) {
            is Resolution.Resolved ->
                if (dayPassed && !passed(resolution.value)) null else answer(resolution.value)
            is Resolution.Ambiguous -> if (dayPassed && !resolution.options.all(passed)) {
                null
            } else {
                GarAnswer.NeedsPick(resolution.options, resolution.reason)
            }
            is Resolution.Unresolvable -> null
        }
    }

    /**
     * The confirmation question for the call: the tool's [ToolSpec.confirmText] filled in, e.g.
     * "Set the pasta timer for ten minutes?", or else "<description>: <values>?".
     */
    fun summary(call: GarCall, speech: Boolean): String {
        val template = when (val text = call.tool.confirmText) {
            is ConfirmText.Template -> text.text
            is ConfirmText.ByValue -> text.templates[call.values[text.slot]]
            null -> null
        }
        if (template != null) {
            return fillTemplate(template) { formatter.format(call.values[it], speech) }
        }

        val description = call.tool.description.trim().trimEnd('.')
        val values = call.tool.slots.mapNotNull { formatter.format(call.values[it.name], speech) }
        return if (values.isEmpty()) {
            "$description?"
        } else {
            "$description: ${values.joinToString(", ")}?"
        }
    }

    private fun ask(
        call: GarCall,
        slot: SlotSpec,
        question: GarQuestion,
        nested: Boolean,
    ): SkillOutput = GarAskSlotOutput(this, call, slot, question, nested)

    companion object {
        private val FREE_TEXT_KINDS = setOf(SlotKind.TEXT, SlotKind.CONTACT, SlotKind.APP)
        private val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]*)\\}")

        /**
         * Replaces `{slot}` placeholders in [template] with [value], dropping those without a
         * value along with the extra space they leave ("Cancel the {name} timer?" -> "Cancel the
         * timer?").
         */
        fun fillTemplate(template: String, value: (String) -> String?): String =
            PLACEHOLDER.replace(template) { value(it.groupValues[1]) ?: "" }
                .replace(Regex("\\s+"), " ")
                .replace(Regex(" ([?.!,])"), "$1")
                .trim()

        /** The day of a date-like value: GAR's resolved types or `java.time` ones. */
        fun dateOf(value: Any?): LocalDate? = when (value) {
            is ResolvedDate -> value.date
            is ResolvedDateTime -> value.dateTime.toLocalDate()
            is LocalDate -> value
            is LocalDateTime -> value.toLocalDate()
            else -> null
        }

        /** The hour of a time-like option, used to tell am from pm. */
        fun hourOf(option: Any?): Int? = when (option) {
            is ResolvedTime -> option.time.hour
            is ResolvedDateTime -> option.dateTime.hour
            is LocalTime -> option.hour
            is LocalDateTime -> option.hour
            else -> null
        }
    }
}
