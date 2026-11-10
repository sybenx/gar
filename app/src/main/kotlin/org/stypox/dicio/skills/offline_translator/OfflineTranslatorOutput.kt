package org.stypox.dicio.skills.offline_translator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.skills.translation.TranslationHeader
import org.stypox.dicio.util.getString

sealed interface OfflineTranslatorOutput : SkillOutput {

    /** "Good morning in Spanish is: buenos días.", the translation large on the card. */
    data class Translated(val request: OfflineTranslationRequest, val translation: String) :
        OfflineTranslatorOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = request.target?.let { target ->
            ctx.getString(
                R.string.skill_offline_translator_translated, request.text,
                target.translation.replaceFirstChar { it.titlecase(ctx.locale) }, spoken,
            )
        } ?: ctx.getString(R.string.skill_offline_translator_translated_default, spoken)

        /** The translation without its last period: the sentence around it adds one. */
        private val spoken: String get() = translation.trim().removeSuffix(".")

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SelectionContainer {
                    Text(
                        text = translation,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = request.text)
                request.target?.let { target ->
                    Spacer(modifier = Modifier.height(8.dp))
                    TranslationHeader(sourceLanguage = request.source, targetLanguage = target)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.skill_offline_translator_source),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    /**
     * "Here's that in Offline Translator.": its service couldn't translate (a language pack
     * missing, say), so its popup shows the text. [reason] is what it said, for the card.
     */
    data class Opened(val request: OfflineTranslationRequest, val reason: String?) :
        OfflineTranslatorOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_offline_translator_opened)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                Body(text = request.text)
                reason?.let { Body(text = it) }
            }
        }
    }

    /** Neither the service nor the popup worked. */
    data class Failed(val reason: String?) : OfflineTranslatorOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_offline_translator_failed)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                reason?.let { Body(text = it) }
            }
        }
    }
}
