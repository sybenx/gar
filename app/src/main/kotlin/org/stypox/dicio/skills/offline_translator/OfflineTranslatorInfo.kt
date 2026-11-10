package org.stypox.dicio.skills.offline_translator

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

/**
 * Translation on the phone with Offline Translator, English only; Dicio's translation sentences.
 * Whether the app is installed is checked when an input comes, so installing it later works.
 */
object OfflineTranslatorInfo : SkillInfo("offline_translator") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_offline_translator)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_offline_translator)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Translate)

    override fun build(ctx: SkillContext): Skill<*>? {
        if (ctx.sentencesLanguage != "en") {
            return null
        }
        val data = Sentences.Translation[ctx.sentencesLanguage] ?: return null
        return OfflineTranslatorSkill(OfflineTranslatorInfo, data)
    }
}
