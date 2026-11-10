package org.stypox.dicio.gar.call

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.aaro.gar.router.resolve.AmbiguityReason
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.conversation.GarConversationSkill
import org.stypox.dicio.gar.conversation.GarOpenAnswer
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString
import java.time.format.TextStyle

/**
 * Asks the user about one slot of [call] and listens for the answer. Answers that aren't
 * recognized fall through to Dicio's text fallback, which keeps this question's skills on the
 * stack, so the user can simply try again.
 *
 * @param nested see [GarCallRunner.next]
 */
class GarAskSlotOutput(
    val runner: GarCallRunner,
    val call: GarCall,
    val slot: SlotSpec,
    val question: GarQuestion,
    private val nested: Boolean,
) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String {
        val text = questionText(ctx, speech = true)
        return if (question is GarQuestion.Value && question.unresolved != null) {
            ctx.getString(R.string.gar_ask_not_understood) + " " + text
        } else {
            text
        }
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan {
        val skills = listOf(
            GarAnswerSkill.forQuestion(runner, call, slot, question),
            // last, so that it wins over an answer skill that also accepts the input
            GarCancelSkill(runner.targetInfo),
        )
        return if (nested) {
            InteractionPlan.ReplaceSubInteraction(reopenMicrophone = true, nextSkills = skills)
        } else {
            InteractionPlan.StartSubInteraction(reopenMicrophone = true, nextSkills = skills)
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            val options = displayOptions()
            if (options.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Body(
                    text = options.withIndex()
                        .joinToString("\n") { (i, option) -> "${i + 1}. $option" }
                )
            }
        }
    }

    private fun displayOptions(): List<String> = when (question) {
        // the days of a month are too many to list, and are said by their day anyway
        is GarQuestion.Pick -> if (question.reason == AmbiguityReason.DAY_OF_MONTH) {
            listOf()
        } else {
            question.options.map { runner.formatter.format(it, false) ?: "" }
        }
        is GarQuestion.Value ->
            if (slot.kind == SlotKind.ENUM) slot.enumValues else listOf()
    }

    private fun questionText(ctx: SkillContext, speech: Boolean): String {
        return when (question) {
            is GarQuestion.Pick -> when (question.reason) {
                AmbiguityReason.AM_OR_PM -> ctx.getString(R.string.gar_ask_am_pm)
                AmbiguityReason.SEVERAL_MATCHES -> ctx.getString(
                    R.string.gar_ask_which_options,
                    question.options.mapNotNull { runner.formatter.format(it, speech) }
                        .joinToString(ctx.getString(R.string.gar_options_separator)),
                )
                AmbiguityReason.DAY_OF_MONTH -> question.options
                    .firstNotNullOfOrNull { GarCallRunner.dateOf(it) }
                    ?.let {
                        val month = it.month.getDisplayName(TextStyle.FULL, ctx.locale)
                        ctx.getString(R.string.gar_ask_day_in_month, month)
                    }
                    ?: ctx.getString(R.string.gar_ask_date)
            }

            is GarQuestion.Value -> when (slot.kind) {
                SlotKind.DURATION -> ctx.getString(R.string.gar_ask_duration)
                SlotKind.TIME -> ctx.getString(R.string.gar_ask_time)
                SlotKind.DATETIME -> ctx.getString(R.string.gar_ask_datetime)
                SlotKind.DATE -> ctx.getString(R.string.gar_ask_date)
                SlotKind.NUMBER -> if (slot.name == "level") {
                    ctx.getString(R.string.gar_ask_level)
                } else {
                    ctx.getString(R.string.gar_ask_number)
                }
                SlotKind.CONTACT -> ctx.getString(R.string.gar_ask_contact)
                SlotKind.APP -> ctx.getString(R.string.gar_ask_app)
                SlotKind.ENUM -> ctx.getString(
                    R.string.gar_ask_which_options,
                    slot.enumValues.joinToString(ctx.getString(R.string.gar_options_separator)),
                )
                SlotKind.TEXT -> if (slot.name == "where") {
                    ctx.getString(R.string.gar_ask_where)
                } else {
                    ctx.getString(R.string.gar_ask_text, slot.name.replace('_', ' '))
                }
            }
        }
    }
}

/**
 * Follow-up skill for a [GarAskSlotOutput]: matches only answers that resolve (or pick an
 * option), then continues the same call.
 */
open class GarAnswerSkill(
    private val runner: GarCallRunner,
    private val call: GarCall,
    private val slot: SlotSpec,
    private val question: GarQuestion,
) : Skill<GarAnswer?>(runner.targetInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, GarAnswer?> {
        // while a timer or an alarm rings, "shut up" and "turn it off" are for it (GarRinging),
        // not an event's title
        val answer = if (GarRinging.takes(ctx, input)) {
            null
        } else {
            runner.interpret(input, call.tool, slot, question)
        }
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: GarAnswer?): SkillOutput {
        // AlwaysWorstScore is never chosen, so inputData can't be null here
        return runner.answered(ctx, call, slot, inputData!!)
    }

    companion object {
        /** Slot kinds whose answer is any words said, as said. */
        private val FREE_TEXT_KINDS = setOf(SlotKind.TEXT, SlotKind.CONTACT)

        /** Text slots that name a thing, a place or a calculation, which no command is. */
        private val THING_SLOTS = setOf("where", "calculation")

        /**
         * The answer skill for [question]: a [GarOpenAnswerSkill] when any words answer it (a
         * value of a free-text slot), so that a new command can win over it.
         */
        fun forQuestion(
            runner: GarCallRunner,
            call: GarCall,
            slot: SlotSpec,
            question: GarQuestion,
        ): GarAnswerSkill = if (question is GarQuestion.Value && slot.kind in FREE_TEXT_KINDS) {
            GarOpenAnswerSkill(
                runner, call, slot, question,
                yieldsToCommands = slot.kind == SlotKind.CONTACT || slot.name in THING_SLOTS,
            )
        } else {
            GarAnswerSkill(runner, call, slot, question)
        }
    }
}

/**
 * A [GarAnswerSkill] that takes any words, the value of a free-text slot: an exact match of
 * Dicio's default batch wins over it, if it yields to it ([GarOpenAnswer]). The conversation's
 * own commands ("be quiet", "repeat that") always win; other commands only when the slot names a
 * thing ([yieldsToCommands]): "what's the weather" is no contact, but "call mom" is a fine
 * reminder or event title.
 */
class GarOpenAnswerSkill(
    runner: GarCallRunner,
    call: GarCall,
    slot: SlotSpec,
    question: GarQuestion,
    private val yieldsToCommands: Boolean,
) : GarAnswerSkill(runner, call, slot, question), GarOpenAnswer {
    override fun yieldsTo(command: SkillWithResult<*>): Boolean =
        yieldsToCommands || command.skill is GarConversationSkill
}

/**
 * Follow-up skill that drops the pending GAR call on "cancel", "never mind", "stop", ... While a
 * timer or an alarm rings, "stop" is for it ([GarRinging]): the input goes on to Dicio's default
 * batch, where its skill takes it.
 */
class GarCancelSkill(
    correspondingSkillInfo: SkillInfo,
    private val onCancel: () -> Unit = {},
) : Skill<Unit>(correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Unit> {
        val cancels = isCancelPhrase(input) &&
            !(GarRinging.isStopPhrase(input) && GarRinging.isRinging())
        return Pair(if (cancels) AlwaysBestScore else AlwaysWorstScore, Unit)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Unit): SkillOutput {
        onCancel()
        return GarCancelledOutput()
    }
}

/** "OK." Ends the interaction, removing GAR's follow-up skills. */
class GarCancelledOutput : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_cancelled)
}
