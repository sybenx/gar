package org.stypox.dicio.skills.stopwatch

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

object StopwatchInfo : SkillInfo("stopwatch") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_stopwatch)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_stopwatch)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.AvTimer)

    // built without sentences in languages that have none: GAR's stopwatch tool doesn't need them
    override fun build(ctx: SkillContext): Skill<*> =
        StopwatchSkill(StopwatchInfo, Sentences.Stopwatch[ctx.sentencesLanguage]) {
            StopwatchController.get(ctx.android)
        }
}
