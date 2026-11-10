package org.stypox.dicio.skills.knowledge

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import java.io.IOException
import java.time.LocalDate

/**
 * General knowledge from Wikipedia (gar/PARITY.md, batch 5): "who is taylor swift", "what is a
 * black hole", "how tall is mount everest", "when was einstein born", "tell me about the eiffel
 * tower". An online skill under GAR's ask-once rule (`GarOnlineSkill.KNOWLEDGE`): only the
 * subject's words go to en.wikipedia.org. It speaks one or two sentences; the card shows the
 * summary and a link to the page.
 *
 * English only, read in Kotlin ([KnowledgeQuestions]): the input must be a whole question of its
 * forms about a subject that is no phone command, so a word left out of a sentence never makes it
 * take "what's the weather" or "what is the time". It scores [SCORE]: a full match of another
 * skill wins.
 *
 * @param wikipedia built when a question runs (its HTTP client isn't needed to score)
 */
class KnowledgeSkill(
    correspondingSkillInfo: SkillInfo,
    private val wikipedia: () -> Wikipedia = { Wikipedia() },
    private val today: () -> LocalDate = { LocalDate.now() },
) : Skill<KnowledgeQuestion?>(correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, KnowledgeQuestion?> {
        val question = KnowledgeQuestions.parse(input)
        return Pair(if (question == null) AlwaysWorstScore else FloatScore(SCORE), question)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: KnowledgeQuestion?): SkillOutput {
        val question = inputData
            ?: throw IllegalStateException("a knowledge match without a question ran")
        val summary = try {
            wikipedia().find(question.subject)
        } catch (e: IOException) {
            return KnowledgeOutput.Unreachable
        } catch (e: IllegalArgumentException) { // not the JSON Wikipedia sends
            return KnowledgeOutput.Unreachable
        } ?: return KnowledgeOutput.NotFound(question.subject)

        if (summary.disambiguation) {
            return KnowledgeOutput.Ambiguous(summary)
        }
        return KnowledgeOutput.Answer(summary, answerOf(summary, question))
    }

    /** What to say for [question] from [summary]. */
    fun answerOf(summary: WikiSummary, question: KnowledgeQuestion): KnowledgeSpoken {
        val years = WikiAnswers.lifeYears(summary.description)
        return when {
            years == null || question.kind !in LIFE_KINDS ->
                KnowledgeSpoken.Text(WikiAnswers.spokenText(summary, question))
            question.kind == KnowledgeKind.BORN -> KnowledgeSpoken.Born(summary.title, years)
            question.kind == KnowledgeKind.DIED -> KnowledgeSpoken.Died(summary.title, years)
            else -> KnowledgeSpoken.Age(summary.title, years, today().year)
        }
    }

    private companion object {
        /**
         * Just above Dicio's first threshold for HIGH skills (0.85), so that knowledge comes
         * before the calculator's catch-all "what is .calculation." (MEDIUM), which GAR rejects;
         * below a good match of another HIGH skill, which wins (a remembered fact, a contact's
         * number, "what is hello in spanish").
         */
        const val SCORE = 0.86f

        val LIFE_KINDS = setOf(KnowledgeKind.BORN, KnowledgeKind.DIED, KnowledgeKind.AGE)
    }
}

/** What the knowledge skill says, before it becomes a string. */
sealed interface KnowledgeSpoken {
    /** Sentences of the summary. */
    data class Text(val text: String) : KnowledgeSpoken

    /** "Taylor Swift was born in 1989." or "... was born in 1879 and died in 1955." */
    data class Born(val title: String, val years: Pair<Int, Int?>) : KnowledgeSpoken

    /** "Albert Einstein died in 1955.", or no death listed. */
    data class Died(val title: String, val years: Pair<Int, Int?>) : KnowledgeSpoken

    /** "Taylor Swift was born in 1989, about 37 years ago." or "... lived from 1879 to 1955." */
    data class Age(val title: String, val years: Pair<Int, Int?>, val thisYear: Int) :
        KnowledgeSpoken
}
