package org.stypox.dicio.skills.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString

sealed interface DictionaryOutput : SkillOutput {

    /**
     * "Serendipity, noun: good luck in making unexpected and fortunate discoveries.": the most
     * frequent meaning; the card has up to three meanings of each part of speech.
     */
    data class Definitions(val word: String, val entries: List<DictionaryEntry>) : DictionaryOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val entry = entries.first()
            return ctx.getString(
                R.string.skill_dictionary_definition,
                word.replaceFirstChar { it.titlecase(ctx.locale) },
                ctx.getString(partOfSpeechName(entry.partOfSpeech)),
                entry.senses.first().definition,
            )
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = word)
                for (entry in entries.take(MAX_PARTS_OF_SPEECH)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(partOfSpeechName(entry.partOfSpeech)),
                        style = MaterialTheme.typography.titleSmall,
                        fontStyle = FontStyle.Italic,
                    )
                    entry.senses.take(MAX_SENSES).forEachIndexed { index, sense ->
                        Text(
                            text = "${index + 1}. ${sense.definition}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        sense.examples.firstOrNull()?.let { example ->
                            Text(
                                text = "\"$example\"",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Source()
            }
        }
    }

    /** "Some words for happy: felicitous, glad and well-chosen." */
    data class Synonyms(val word: String, val synonyms: List<String>) : DictionaryOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = if (synonyms.isEmpty()) {
            ctx.getString(R.string.skill_dictionary_no_synonyms, word)
        } else {
            ctx.getString(
                R.string.skill_dictionary_synonyms, word,
                spokenList(synonyms, ctx.getString(R.string.skill_dictionary_and)),
            )
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = word)
                Subtitle(text = getSpeechOutput(ctx))
                Spacer(modifier = Modifier.height(8.dp))
                Source()
            }
        }
    }

    /** "I couldn't find frobnicate in the dictionary." */
    data class NotFound(val word: String) : DictionaryOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_dictionary_not_found, word)
    }

    /** The one-time download didn't work (offline, or a damaged file). */
    data object DownloadFailed : DictionaryOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_dictionary_download_failed)

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                Body(text = stringResource(R.string.skill_dictionary_download_detail))
            }
        }
    }

    companion object {
        private const val MAX_PARTS_OF_SPEECH = 3
        private const val MAX_SENSES = 3

        fun partOfSpeechName(pos: PartOfSpeech): Int = when (pos) {
            PartOfSpeech.NOUN -> R.string.skill_dictionary_noun
            PartOfSpeech.VERB -> R.string.skill_dictionary_verb
            PartOfSpeech.ADJECTIVE -> R.string.skill_dictionary_adjective
            PartOfSpeech.ADVERB -> R.string.skill_dictionary_adverb
        }

        /** "a", "a and b", "a, b and c". */
        fun spokenList(items: List<String>, and: String): String = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " $and " + items.last()
        }
    }
}

@Composable
private fun Source() {
    Text(
        text = stringResource(R.string.skill_dictionary_source),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
