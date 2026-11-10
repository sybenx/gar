package org.stypox.dicio.skills.spelling

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.dicio.skill.skill.AlwaysWorstScore
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.sentences.Sentences

class SpellingTest : StringSpec({
    "the word to spell, without the words around it" {
        for ((capture, word) in listOf(
            "necessary" to "necessary", " Rhythm " to "rhythm", "the word necessary" to "necessary",
            "ice cream" to "ice cream", "o'clock" to "o'clock", "necessary for me" to "necessary",
            "necessary please" to "necessary", "out necessary" to "necessary",
            "out rhythm for me" to "rhythm", "out the word necessary" to "necessary",
            // "how do you spell out" spells "out"
            "out" to "out", "address" to "address",
        )) {
            withClue(capture) { SpellingWords.wordToSpell(capture) shouldBe word }
        }
    }

    "a capture that names no word is none" {
        for (capture in listOf(
            "check", "it", "my name", "that word", "a b c d e", "", "on", "something", "42",
            "out the address", "the plural of mouse", "the number",
        )) {
            withClue(capture) { SpellingWords.wordToSpell(capture).shouldBeNull() }
        }
    }

    "letters are separated for text to speech" {
        SpellingWords.spokenLetters("necessary", "apostrophe") shouldBe "N, E, C, E, S, S, A, R, Y"
        SpellingWords.spokenLetters("ice cream", "apostrophe") shouldBe "I, C, E. C, R, E, A, M"
        SpellingWords.spokenLetters("o'clock", "apostrophe") shouldBe "O, apostrophe, C, L, O, C, K"
    }

    "the sentences take spelling questions only" {
        val skill = SpellingSkill(SpellingInfo, Sentences.Spelling["en"]!!)
        fun word(input: String) = skill.score(FakeSkillContext().forInput(input), input).second
        word("how do you spell necessary") shouldBe "necessary"
        word("spell rhythm") shouldBe "rhythm"
        word("how is necessary spelled") shouldBe "necessary"
        word("what's the spelling of accommodate") shouldBe "accommodate"
        word("spell out necessary") shouldBe "necessary"
        word("can you spell out rhythm for me") shouldBe "rhythm"
        word("how do you spell address") shouldBe "address"
        for (input in listOf(
            "spell out the address",
            "turn on spell check", "how do you spell my name", "spell it", "can you open firefox",
            "can you dial the dentist",
        )) {
            withClue(input) {
                skill.score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
    }
})
