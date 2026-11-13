package org.stypox.dicio.gar.online

import android.app.KeyguardManager
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.GarFallbackImpl
import org.stypox.dicio.gar.call.GarLockedOutput

/**
 * The skills Dicio ranks (a GAR call in `SkillHandler`): the enabled skills, with the online ones
 * behind GAR's check of English inputs ([GarCheckedOnlineSkill]), plus a gate for each online skill
 * that is off ([GarOnlineGateSkill]), which asks once whether to turn it on. See gar/DESIGN.md,
 * "Online skills".
 */
object GarOnlineSkills {
    /**
     * @param allSkills every skill of the app, in ranking order (`SkillHandler.allSkillInfoList`)
     * @param enabledSkills Dicio's switches; a skill without one is on
     * @param built the enabled skills that are available, as `SkillHandler` built them
     * @return the skills to rank, see [forRanking]
     */
    fun rankedSkills(
        allSkills: List<SkillInfo>,
        enabledSkills: Map<String, Boolean>,
        built: List<Pair<SkillInfo, Skill<*>>>,
        ctx: SkillContext,
    ): List<Skill<*>> {
        val builtById = built.associate { (info, skill) -> Pair(info.id, skill) }
        return forRanking(
            allSkills.mapNotNull { info ->
                builtById[info.id] ?: if (isGated(info.id, enabledSkills)) info.build(ctx) else null
            },
            enabledSkills,
        )
    }

    /**
     * [skills] as Dicio ranks them with Dicio's switches [enabledSkills]: the enabled ones that
     * aren't online in their order, then each online skill behind GAR's check when it is on and
     * behind a gate when it is off. The online skills come last because Dicio's ranker compares
     * its own scores among themselves differently than with other scores (`StandardScore`): a
     * skill that doesn't take an input scores worst, and first in the list ("weather") it changed
     * which of the other skills won ("what month is it" lost the current time). Last, it changes
     * nothing, and the other skills rank exactly as without any online skill.
     */
    fun forRanking(
        skills: List<Skill<*>>,
        enabledSkills: Map<String, Boolean>,
        settingsFor: (SkillContext) -> GarOnlineSettings = GarOnlineSettings::forContext,
    ): List<Skill<*>> {
        val others = skills.filter {
            val id = it.correspondingSkillInfo.id
            GarOnlineSkill.of(id) == null && enabledSkills.getOrDefault(id, true)
        }
        val online = skills.mapNotNull { skill ->
            val onlineSkill = GarOnlineSkill.of(skill.correspondingSkillInfo.id)
                ?: return@mapNotNull null
            if (enabledSkills.getOrDefault(onlineSkill.id, true)) {
                GarCheckedOnlineSkill(onlineSkill, skill)
            } else {
                GarOnlineGateSkill(onlineSkill, skill, settingsFor = settingsFor)
            }
        }
        return others + online
    }

    /** Whether [id] is an online skill that is off, which GAR asks about. */
    private fun isGated(id: String, enabledSkills: Map<String, Boolean>): Boolean =
        GarOnlineSkill.of(id) != null && !enabledSkills.getOrDefault(id, true)
}

/**
 * An online skill that is on: it scores and runs like [wrapped], except that in English it only
 * takes the inputs that [GarOnlineInputs] accepts (others score worst and go to the skills they
 * went to with this one off). Other languages have no such check.
 */
open class GarCheckedOnlineSkill(
    val online: GarOnlineSkill,
    val wrapped: Skill<*>,
) : Skill<Any?>(wrapped.correspondingSkillInfo, wrapped.specificity) {

    /** Whether inputs in languages other than English are scored like [wrapped] scores them. */
    protected open val takesOtherLanguages: Boolean = true

    override fun score(ctx: SkillContext, input: String): Pair<Score, Any?> {
        val (score, inputData) = wrapped.score(ctx, input)
        val takes = if (ctx.sentencesLanguage == GarFallbackImpl.ENGLISH) {
            GarOnlineInputs.takes(online, ctx, input, inputData)
        } else {
            takesOtherLanguages
        }
        return Pair(if (takes) score else AlwaysWorstScore, inputData)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Any?): SkillOutput =
        runWrapped(ctx, inputData)

    /** [wrapped]'s own output for [inputData], which came from [wrapped]'s score. */
    suspend fun runWrapped(ctx: SkillContext, inputData: Any?): SkillOutput {
        @Suppress("UNCHECKED_CAST")
        return (wrapped as Skill<Any?>).generateOutput(ctx, inputData)
    }
}

/**
 * An online skill that is off. It has [wrapped]'s [SkillInfo] (so the card shows its name and icon)
 * and takes the inputs [wrapped] would take when on, in English only (GAR's question and the input
 * check are English). When one comes:
 * - the user hasn't chosen about the skill yet: GAR asks whether to turn it on, naming what it
 *   contacts ([GarOnlineAskOutput]); a yes turns it on and answers the input with it right away;
 * - the user already chose (said no, or switched it off in Settings > Skills): GAR says that it is
 *   off and where to turn it on ([GarOnlineIsOffOutput]), without asking, unless the skill has
 *   its own answer for that ([GarOfflineFirstSkill.offOutput], gar:weather: the user's weather app);
 * - it was turned on in the meantime: it just runs.
 *
 * @param isDeviceLocked whether the phone is locked (with a PIN, pattern, ...): then GAR doesn't
 * ask and remembers nothing, since Dicio's sentences also match on the lock screen (wake word) and
 * turning an online skill on is the owner's lasting choice; it says "Unlock your phone first."
 */
class GarOnlineGateSkill(
    online: GarOnlineSkill,
    wrapped: Skill<*>,
    val isDeviceLocked: (SkillContext) -> Boolean = { ctx ->
        ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    },
    private val settingsFor: (SkillContext) -> GarOnlineSettings = GarOnlineSettings::forContext,
) : GarCheckedOnlineSkill(online, wrapped) {

    override val takesOtherLanguages: Boolean = false

    /** What the skill answers while off instead of "it's off" (gar:weather: the user's weather app). */
    suspend fun offOutput(ctx: SkillContext, inputData: Any?, justDeclined: Boolean): SkillOutput? =
        (wrapped as? GarOfflineFirstSkill)?.offOutput(ctx, inputData, justDeclined)

    override suspend fun generateOutput(ctx: SkillContext, inputData: Any?): SkillOutput {
        // gar:weather: an answer from an app on the phone needs no internet, so no question
        (wrapped as? GarOfflineFirstSkill)?.offlineOutput(ctx, inputData)?.let { return it }
        val settings = settingsFor(ctx)
        return when {
            settings.isEnabled(online.id) -> runWrapped(ctx, inputData)
            settings.wasAsked(online.id) -> offOutput(ctx, inputData, justDeclined = false)
                ?: GarOnlineIsOffOutput(online)
            isDeviceLocked(ctx) -> GarLockedOutput()
            else -> GarOnlineAskOutput(this, inputData, settings)
        }
    }
}
