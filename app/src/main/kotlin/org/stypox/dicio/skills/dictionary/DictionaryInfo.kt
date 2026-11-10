package org.stypox.dicio.skills.dictionary

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Abc
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R

/** Definitions and synonyms, English only: its requests are read in Kotlin. */
object DictionaryInfo : SkillInfo("dictionary") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_dictionary)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_dictionary)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Abc)

    override fun build(ctx: SkillContext): Skill<*>? {
        if (ctx.sentencesLanguage.substringBefore('-').substringBefore('_') != "en") {
            return null
        }
        return DictionarySkill(DictionaryInfo) { skillCtx ->
            DictionaryPack.forFilesDir(skillCtx.android.filesDir)
        }
    }
}
