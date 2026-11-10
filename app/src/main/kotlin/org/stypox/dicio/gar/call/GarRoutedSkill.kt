package org.stypox.dicio.gar.call

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.sentences.Sentences

/**
 * The skill GAR hands to Dicio's evaluator for a call the model made. Its
 * [correspondingSkillInfo] is the TARGET skill's info, so Dicio checks the target's permissions
 * and shows the target's icon before [generateOutput] resolves the slots and runs the target
 * (see [GarCallRunner]).
 *
 * It's never put in a skill batch, so [score] is never called.
 */
class GarRoutedSkill(
    targetInfo: SkillInfo,
    private val target: Skill<*>,
    val confirm: Boolean,
    /** See [GarCallRunner.confirmAllTools]. */
    val confirmAllTools: Boolean = false,
    private val resolverFactory: (SkillContext) -> GarSlotResolver = DefaultGarSlotResolver::forContext,
    private val formatterFactory: (SkillContext) -> GarValueFormatter = GarValueFormatter::forContext,
) : Skill<GarCall>(targetInfo, target.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, GarCall> {
        throw UnsupportedOperationException("GarRoutedSkill is only created by GarFallback")
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: GarCall): SkillOutput {
        // gar:notes: a calendar, reminder, call or text call for a list, a note, a fact, the
        // next event, the reminders or a contact's number, before GAR asks about its slots
        org.stypox.dicio.skills.notes.NotesCalls.output(ctx, inputData)?.let { return it }
        return newRunner(ctx).next(ctx, inputData, nested = false)
    }

    fun newRunner(ctx: SkillContext) = GarCallRunner(
        targetInfo = correspondingSkillInfo,
        target = target,
        confirm = confirm,
        resolver = resolverFactory(ctx),
        formatter = formatterFactory(ctx),
        yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage],
        confirmAllTools = confirmAllTools,
    )
}
