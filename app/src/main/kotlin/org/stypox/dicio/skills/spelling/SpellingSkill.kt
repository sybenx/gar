package org.stypox.dicio.skills.spelling

import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.Spelling

/**
 * "How do you spell necessary", "spell rhythm": the word letter by letter, spoken with a pause
 * after each letter, and large on the card. The sentences capture the word; only an input that
 * says "spell" and one to three words of letters count ([SpellingWords]), so "can you open
 * firefox", "turn on spell check" or "how do you spell my name" aren't taken.
 */
class SpellingSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Spelling>,
) : Skill<String?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String?> {
        val (score, match) = data.score(ctx, input)
        // Dicio's matcher leaves a word out: "can you open firefox" matched "can you spell .word."
        val word = when (match) {
            is Spelling.Spell -> match.word?.takeIf { SpellingWords.asksToSpell(input) }
                ?.let(SpellingWords::wordToSpell)
        }
        return Pair(if (word == null) AlwaysWorstScore else score, word)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String?): SkillOutput =
        SpellingOutput(inputData ?: throw IllegalStateException("a spelling match without a word"))
}

/** What to spell, and how (English). */
object SpellingWords {
    /** Whether [input] asks for a spelling: "spell", "spelled", "spelling". */
    fun asksToSpell(input: String): Boolean =
        Utterances.words(Utterances.normalize(input)).any { it in SPELL_WORDS }

    /**
     * The word or words of [capture] to spell ("necessary", "ice cream", "o'clock"), without the
     * fillers around them ("the word", "out", "for me", "please"), or null when it isn't one to
     * three words of letters, or names no word ("spell check", "it", "my name", "the address").
     */
    fun wordToSpell(capture: String): String? {
        var words = Utterances.words(Utterances.normalize(capture))
        // "spell out necessary"; "how do you spell out" still spells "out"
        if (words.size > 1 && words.first() == "out") {
            words = words.drop(1)
        }
        // "spell out the address", "spell the plural of mouse": a thing, not a word to spell
        if (words.size > 1 && words.first() == "the" && words[1] !in NAMING_WORDS) {
            return null
        }
        words = words.dropWhile { it in LEADING_FILLERS }
        while (words.isNotEmpty() && words.last() in TRAILING_FILLERS) {
            words = words.dropLast(1)
        }
        if (words.takeLast(2) == listOf("for", "me")) {
            words = words.dropLast(2)
        }
        if (words.isEmpty() || words.size > MAX_WORDS ||
            words.any { word -> word in NOT_WORDS || !word.all { it.isLetter() || it == '\'' } }
        ) {
            return null
        }
        return words.joinToString(" ")
    }

    /**
     * The letters to speak, uppercase and separated so that text to speech says each one with a
     * pause: "N, E, C, E, S, S, A, R, Y". Words are separated by a longer pause; an apostrophe is
     * named with [apostrophe].
     */
    fun spokenLetters(word: String, apostrophe: String): String =
        word.split(' ').joinToString(". ") { part ->
            part.map { if (it == '\'') apostrophe else it.uppercase() }.joinToString(", ")
        }

    private const val MAX_WORDS = 3
    private val SPELL_WORDS = setOf("spell", "spelled", "spelt", "spelling")
    private val LEADING_FILLERS = setOf("the", "word", "words", "name")

    /** After "the", the words that name a word to spell ("the word necessary"). */
    private val NAMING_WORDS = setOf("word", "words", "name")
    private val TRAILING_FILLERS = setOf("please", "correctly", "properly", "again")

    /** "spell check", pronouns, and things that aren't a word to spell ("my name"). */
    private val NOT_WORDS = setOf(
        "check", "checker", "checking", "checks", "it", "that", "this", "them", "these", "those",
        "my", "your", "his", "her", "their", "our", "me", "you", "him", "us", "something",
        "anything", "everything", "what", "on", "off",
    )
}
