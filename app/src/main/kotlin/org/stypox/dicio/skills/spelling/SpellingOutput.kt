package org.stypox.dicio.skills.spelling

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString

/** "Necessary is spelled N, E, C, E, S, S, A, R, Y.", with the word large on the card. */
class SpellingOutput(val word: String) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
        R.string.skill_spelling_spelled,
        word.replaceFirstChar { it.titlecase(ctx.locale) },
        SpellingWords.spokenLetters(word, ctx.getString(R.string.skill_spelling_apostrophe)),
    )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Text(
                text = word,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Subtitle(text = word.uppercase(ctx.locale).toList().joinToString(" "))
        }
    }
}
