package org.stypox.dicio.gar.tools

import android.content.Context
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.settings.datastore.WakeDevice
import org.stypox.dicio.skills.listening.ListeningInfo
import javax.inject.Inject
import javax.inject.Singleton

/** A tool that can be offered to the model right now, with the skill that runs it. */
data class OfferedTool(
    val tool: ToolSpec,
    val skillInfo: SkillInfo,
    val skill: Skill<*>,
)

interface GarToolsProvider {
    /**
     * The catalog tools whose skill is enabled and available ([SkillInfo.build] is not null), in
     * catalog order, plus the tools of online skills that are off, behind their ask-once gate
     * (gar:weather), without tools that can't do anything right now (the wake word tool while no
     * wake word is set up). The order and content only change when the enabled skills, the
     * language or the wake word setting change, so the model's cached prompt prefix stays valid.
     */
    suspend fun offeredTools(ctx: SkillContext): List<OfferedTool>

    /**
     * Every catalog tool, whatever is enabled: its prompt is the largest any [offeredTools] can
     * need, so the model's context is sized from it (the offered tools are often still empty
     * while Dicio builds its skills at startup).
     */
    fun allTools(): List<ToolSpec> = listOf()

    /**
     * Like [offeredTools], but first waits (at most [timeoutMillis]) for Dicio to finish building
     * its skills, which happens right after startup: used to warm the model up with the real
     * prompt instead of skipping the warm-up.
     */
    suspend fun awaitOfferedTools(ctx: SkillContext, timeoutMillis: Long): List<OfferedTool> =
        offeredTools(ctx)

    /**
     * Whether the user turned the skill [skillId] on, from the same settings [offeredTools]
     * reads: a skill without a tool that answers other tools' calls (gar:messages) runs only then.
     */
    fun isSkillEnabled(skillId: String): Boolean = false
}

@Singleton
class GarToolsProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillHandler: SkillHandler,
) : GarToolsProvider {

    // `lazy` retries on the next access if loading throws
    private val catalog: ToolCatalog by lazy {
        ToolCatalog.load(
            toolsYaml = readAsset(TOOLS_ASSET),
            skillDefinitionsYaml = readAsset(SKILL_DEFINITIONS_ASSET),
        )
    }

    override fun allTools(): List<ToolSpec> = catalog.tools

    override fun isSkillEnabled(skillId: String): Boolean =
        skillHandler.enabledSkillsInfo.value?.any { it.id == skillId } == true

    private class Cache(
        val enabledSkills: List<SkillInfo>,
        val language: String,
        val wakeWordSetUp: Boolean,
        val tools: List<OfferedTool>,
    )

    @Volatile
    private var cache: Cache? = null

    override suspend fun awaitOfferedTools(ctx: SkillContext, timeoutMillis: Long): List<OfferedTool> {
        withTimeoutOrNull(timeoutMillis) { skillHandler.enabledSkillsInfo.filterNotNull().first() }
        return offeredTools(ctx)
    }

    override suspend fun offeredTools(ctx: SkillContext): List<OfferedTool> {
        val enabledSkills = skillHandler.enabledSkillsInfo.value ?: return listOf()
        val language = ctx.sentencesLanguage
        val wakeWordSetUp = isWakeWordSetUp(enabledSkills)
        cache?.let {
            if (it.enabledSkills === enabledSkills && it.language == language &&
                it.wakeWordSetUp == wakeWordSetUp
            ) {
                return it.tools
            }
        }

        // gar:weather: the online skills that are off, whose tools run behind their gate
        val gatedSkills = skillHandler.allSkillInfoList.filter { info ->
            GarOnlineSkill.of(info.id) != null && enabledSkills.none { it.id == info.id }
        }
        val tools = offeredTools(catalog, enabledSkills, ctx, gatedSkills)
            .filter { wakeWordSetUp || it.skillInfo.id != LISTENING_SKILL }
        cache = Cache(enabledSkills, language, wakeWordSetUp, tools)
        return tools
    }

    /**
     * The listening skill only answers "no wake word is set up" without a wake word device, which
     * GAR's defaults turn off, so its tool is offered only once one is chosen.
     */
    private suspend fun isWakeWordSetUp(enabledSkills: List<SkillInfo>): Boolean {
        val listening = enabledSkills.firstOrNull { it.id == LISTENING_SKILL } as? ListeningInfo
            ?: return false
        return listening.dataStore.data.first().wakeDevice != WakeDevice.WAKE_DEVICE_NOTHING
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    companion object {
        const val TOOLS_ASSET = "gar/tools.yml"
        private const val LISTENING_SKILL = "listening"
        const val SKILL_DEFINITIONS_ASSET = "gar/skill_definitions.yml"

        /**
         * Pairs each catalog tool with its enabled skill, building each skill once. A tool of an
         * online skill that is off ([gatedSkills], gar:weather) is offered too, behind the skill's
         * gate ([GarOnlineGateSkill]), so that the model's call asks once whether to turn it on
         * (gar/PARITY.md, "Keeping the model's prompt small").
         */
        fun offeredTools(
            catalog: ToolCatalog,
            enabledSkills: List<SkillInfo>,
            ctx: SkillContext,
            gatedSkills: List<SkillInfo> = listOf(),
        ): List<OfferedTool> {
            val infoById = enabledSkills.associateBy { it.id }
            val gatedById = gatedSkills.associateBy { it.id }
            val built = HashMap<String, Skill<*>?>()
            return catalog.tools.mapNotNull { tool ->
                val gated = tool.skillId !in infoById
                val info = infoById[tool.skillId] ?: gatedById[tool.skillId] ?: return@mapNotNull null
                // not getOrPut, which builds again when the skill built is null
                val skill = if (info.id in built) {
                    built[info.id]
                } else {
                    info.build(ctx)
                        ?.let { skill ->
                            val online = GarOnlineSkill.of(info.id)
                            if (gated && online != null) GarOnlineGateSkill(online, skill) else skill
                        }
                        .also { built[info.id] = it }
                } ?: return@mapNotNull null
                OfferedTool(tool, info, skill)
            }
        }
    }
}
