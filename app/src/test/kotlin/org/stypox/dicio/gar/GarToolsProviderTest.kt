package org.stypox.dicio.gar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.gar.tools.GarToolsProviderImpl
import org.stypox.dicio.mocked

/** A skill info that counts how often it is built, and builds [skill] (null: not available). */
private class CountingSkillInfo(id: String, private val skill: Skill<*>?) : SkillInfo(id) {
    var builds = 0

    override fun name(context: Context): String = mocked()
    override fun sentenceExample(context: Context): String = mocked()
    @Composable override fun icon(): Painter = mocked()

    override fun build(ctx: SkillContext): Skill<*>? {
        builds += 1
        return skill
    }
}

private val TIMER_CANCEL_TOOL = TIMER_SET_TOOL.copy(name = "timer_cancel")
private val CATALOG = ToolCatalog(listOf(TIMER_SET_TOOL, FLASHLIGHT_TOOL, TIMER_CANCEL_TOOL))

private fun offeredTools(vararg enabled: SkillInfo) =
    GarToolsProviderImpl.offeredTools(CATALOG, enabled.toList(), FakeSkillContext())

class GarToolsProviderTest : StringSpec({
    "the tools of enabled skills are offered in catalog order, each skill built once" {
        val timerInfo = CountingSkillInfo("timer", RecordingSkill(FakeSkillInfo("timer")))
        val offered = offeredTools(timerInfo)

        offered.map { it.tool } shouldBe listOf(TIMER_SET_TOOL, TIMER_CANCEL_TOOL)
        offered[0].skillInfo shouldBeSameInstanceAs timerInfo
        offered[0].skill shouldBeSameInstanceAs offered[1].skill
        timerInfo.builds shouldBe 1
    }

    "a skill that isn't available drops its tools, and is built once too" {
        val timerInfo = CountingSkillInfo("timer", null)
        val flashlightInfo =
            CountingSkillInfo("flashlight", RecordingSkill(FakeSkillInfo("flashlight")))
        offeredTools(timerInfo, flashlightInfo).map { it.tool } shouldBe listOf(FLASHLIGHT_TOOL)
        timerInfo.builds shouldBe 1
    }

    "skills without tools are ignored, and without enabled skills nothing is offered" {
        val other = CountingSkillInfo("weather", RecordingSkill(FakeSkillInfo("weather")))
        offeredTools(other).shouldBeEmpty()
        other.builds shouldBe 0
        offeredTools().shouldBeEmpty()
    }
})
