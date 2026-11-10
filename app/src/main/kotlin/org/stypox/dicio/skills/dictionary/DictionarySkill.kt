package org.stypox.dicio.skills.dictionary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import java.io.IOException

/**
 * Definitions and synonyms (gar/PARITY.md, batch 5), offline from Open English WordNet: "define
 * serendipity", "what does ubiquitous mean", "synonym for happy". The dictionary is a one-time
 * download ([DictionaryPack]), which is why GAR asks about this skill like about the online ones
 * (`GarOnlineSkill.DICTIONARY`): a yes downloads it and answers.
 *
 * English only, read in Kotlin ([DictionaryRequests]): only a whole request of its forms, so no
 * near-miss of a sentence runs it. "How do you spell X" stays the spelling skill's.
 *
 * @param pack the dictionary's files, built on first use (the app's files directory)
 */
class DictionarySkill(
    correspondingSkillInfo: SkillInfo,
    private val pack: (SkillContext) -> DictionaryPack,
) : Skill<DictionaryRequest?>(correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, DictionaryRequest?> {
        val request = DictionaryRequests.parse(input)
        return Pair(if (request == null) AlwaysWorstScore else FloatScore(1.0f), request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: DictionaryRequest?): SkillOutput {
        val request = inputData
            ?: throw IllegalStateException("a dictionary match without a word ran")
        val pack = pack(ctx)
        try {
            pack.install()
        } catch (e: IOException) {
            return DictionaryOutput.DownloadFailed
        }
        val entries = withContext(Dispatchers.IO) { WordNet(pack.dir).lookup(request.word) }
        return when {
            entries.isEmpty() -> DictionaryOutput.NotFound(request.word)
            request.synonyms -> DictionaryOutput.Synonyms(request.word, synonymsOf(request.word, entries))
            else -> DictionaryOutput.Definitions(request.word, entries)
        }
    }

    companion object {
        /**
         * Other words for [word]: those of the meanings of its most frequent part of speech (for an
         * adjective with a few similar ones), the most frequent meanings first, at most
         * [MAX_SYNONYMS].
         */
        fun synonymsOf(word: String, entries: List<DictionaryEntry>): List<String> {
            val said = WordNet.keyOf(word)
            return entries.first().senses.take(SYNONYM_SENSES)
                // WordNet lists similar adjectives alphabetically: a few of each meaning
                .flatMap { it.words + it.similar.take(SIMILAR_PER_SENSE) }
                .filter { WordNet.keyOf(it) != said && WordNet.keyOf(it) != entries.first().lemma }
                .distinctBy { it.lowercase() }
                .take(MAX_SYNONYMS)
        }

        private const val SYNONYM_SENSES = 4
        private const val SIMILAR_PER_SENSE = 3
        const val MAX_SYNONYMS = 6
    }
}
