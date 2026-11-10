package org.stypox.dicio.skills.knowledge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.ShareUtils
import org.stypox.dicio.util.getString

sealed interface KnowledgeOutput : SkillOutput {

    /** One or two sentences spoken; the card has the title, the summary and the page's link. */
    data class Answer(val summary: WikiSummary, val spoken: KnowledgeSpoken) : KnowledgeOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = when (spoken) {
            is KnowledgeSpoken.Text -> spoken.text
            is KnowledgeSpoken.Born -> spoken.years.second?.let { died ->
                ctx.getString(
                    R.string.skill_knowledge_born_died, spoken.title, spoken.years.first, died,
                )
            } ?: ctx.getString(R.string.skill_knowledge_born, spoken.title, spoken.years.first)
            is KnowledgeSpoken.Died -> spoken.years.second?.let { died ->
                ctx.getString(R.string.skill_knowledge_died, spoken.title, died)
            } ?: ctx.getString(R.string.skill_knowledge_no_death, spoken.title, spoken.years.first)
            is KnowledgeSpoken.Age -> spoken.years.second?.let { died ->
                ctx.getString(R.string.skill_knowledge_lived, spoken.title, spoken.years.first, died)
            } ?: ctx.getString(
                R.string.skill_knowledge_age, spoken.title, spoken.years.first,
                spoken.thisYear - spoken.years.first,
            )
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            WikiCard(summary)
        }
    }

    /** "Mercury can mean more than one thing.", with Wikipedia's list on the card. */
    data class Ambiguous(val summary: WikiSummary) : KnowledgeOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_knowledge_ambiguous, summary.title)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            WikiCard(summary)
        }
    }

    /** "I couldn't find the frobnicator on Wikipedia." */
    data class NotFound(val subject: String) : KnowledgeOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_knowledge_not_found, subject)
    }

    /** No connection, or an answer that isn't Wikipedia's. */
    data object Unreachable : KnowledgeOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_knowledge_unreachable)
    }
}

@Composable
private fun WikiCard(summary: WikiSummary) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Headline(text = summary.title)
        summary.description?.let { Subtitle(text = it) }
        Spacer(modifier = Modifier.height(8.dp))
        Body(text = summary.extract)
        TextButton(onClick = { ShareUtils.openUrlInBrowser(context, summary.url) }) {
            Text(text = stringResource(R.string.skill_knowledge_read_more))
        }
        Text(
            text = stringResource(R.string.skill_knowledge_source),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
