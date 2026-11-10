package org.stypox.dicio.skills.currency

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R

/** Currency conversion with the ECB's daily rates, English only: read in Kotlin. */
object CurrencyInfo : SkillInfo("currency") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_currency)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_currency)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.CurrencyExchange)

    override fun build(ctx: SkillContext): Skill<*>? {
        if (ctx.sentencesLanguage.substringBefore('-').substringBefore('_') != "en") {
            return null
        }
        return newSkill()
    }

    /** The skill, with its rates in the app's files directory. */
    fun newSkill(): CurrencySkill =
        CurrencySkill(CurrencyInfo) { ctx -> EcbRateSource.forFilesDir(ctx.android.filesDir) }
}
