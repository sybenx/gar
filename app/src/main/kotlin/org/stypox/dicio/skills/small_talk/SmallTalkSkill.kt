package org.stypox.dicio.skills.small_talk

import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.SmallTalk

/** A small talk sentence, and the part of day a greeting named ("good morning"). */
data class SmallTalkMatch(val sentence: SmallTalk, val partOfDay: PartOfDay? = null)

/**
 * Hello, thank you, who are you, how are you, bye, and what can you do: short friendly answers in
 * GAR's voice, so that they don't get the text fallback ("Could you repeat?"). "What can you do"
 * names a few things and shows the list of what GAR can do. Only inputs made entirely of small
 * talk words ([SmallTalkWords]) count, and only close matches: "who are you calling", "thank
 * you, set a timer" and "help me find my phone" go elsewhere.
 */
class SmallTalkSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<SmallTalk>,
) : Skill<SmallTalkMatch>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, SmallTalkMatch> {
        val (score, sentence) = data.score(ctx, input)
        val takes = score.scoreIn01Range() >= MIN_SCORE && SmallTalkWords.isSmallTalk(input)
        val match = SmallTalkMatch(sentence, SmallTalkWords.partOfDay(input))
        return Pair(if (takes) score else AlwaysWorstScore, match)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: SmallTalkMatch): SkillOutput =
        when (inputData.sentence) {
            SmallTalk.Hello -> GreetingOutput(inputData.partOfDay)
            SmallTalk.Goodbye -> SmallTalkOutput(SmallTalkAnswer.GOODBYE)
            SmallTalk.Thanks -> SmallTalkOutput(SmallTalkAnswer.THANKS)
            SmallTalk.Who -> SmallTalkOutput(SmallTalkAnswer.WHO)
            SmallTalk.How -> SmallTalkOutput(SmallTalkAnswer.HOW)
            SmallTalk.Help -> HelpOutput()
        }

    companion object {
        /** Closer than Dicio's thresholds: a small talk sentence with another word isn't one. */
        const val MIN_SCORE = 0.85f
    }
}

/** The words of small talk (English). */
object SmallTalkWords {
    /** Whether every word of [input] is a small talk word. */
    fun isSmallTalk(input: String): Boolean {
        val words = words(input)
        return words.isNotEmpty() && words.all { it in WORDS }
    }

    /** The part of day a greeting names ("good morning"), or null. */
    fun partOfDay(input: String): PartOfDay? {
        val words = words(input)
        if ("good" !in words) {
            return null
        }
        return when {
            "morning" in words -> PartOfDay.MORNING
            "afternoon" in words -> PartOfDay.AFTERNOON
            "evening" in words -> PartOfDay.EVENING
            else -> null
        }
    }

    private fun words(input: String): List<String> =
        Utterances.words(Utterances.normalize(input)).map { it.replace("'", "") }

    private val WORDS = setOf(
        // greetings and goodbyes
        "hello", "hi", "hey", "howdy", "greetings", "yo", "hiya", "there", "good", "morning",
        "afternoon", "evening", "day", "night", "bye", "goodbye", "goodnight", "see", "ya",
        "later",
        // thanks and praise
        "thank", "thanks", "very", "much", "so", "lot", "many", "cheers", "appreciate",
        "appreciated", "job", "well", "done", "work", "nice", "great", "best", "awesome",
        "amazing", "youre", "re", "alright", "perfect", "cool",
        // who and how
        "who", "what", "whats", "s", "am", "talking", "speaking", "your", "name", "tell",
        "about", "yourself", "introduce", "how", "hows", "doing", "today", "going", "things",
        "been", "up",
        // help
        "can", "could", "able", "else", "know", "help", "say", "ask", "commands", "use", "show",
        "skills", "features", "this", "app", "need", "for", "do",
        // function words and fillers
        "i", "me", "you", "it", "is", "are", "have", "to", "a", "the", "oh", "ok", "okay",
        "please", "just", "really", "gar", "dicio", "assistant", "google", "buddy", "friend",
    )
}
