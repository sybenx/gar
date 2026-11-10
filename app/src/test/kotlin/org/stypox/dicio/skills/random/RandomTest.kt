package org.stypox.dicio.skills.random

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.sentences.Sentences

private fun skill() = RandomSkill(RandomInfo, Sentences.Random["en"]!!, kotlin.random.Random(42))

private fun request(input: String): RandomRequest? =
    skill().score(FakeSkillContext().forInput(input), input).second

class RandomTest : StringSpec({
    "dice as people name them" {
        for ((capture, dice) in listOf(
            "a die" to RandomRequest.Dice(1, 6), "a dice" to RandomRequest.Dice(1, 6),
            "the dice" to RandomRequest.Dice(1, 6), "two dice" to RandomRequest.Dice(2, 6),
            "2 dice" to RandomRequest.Dice(2, 6), "a pair of dice" to RandomRequest.Dice(2, 6),
            "a d twenty" to RandomRequest.Dice(1, 20), "a d20" to RandomRequest.Dice(1, 20),
            "d 20" to RandomRequest.Dice(1, 20), "2d6" to RandomRequest.Dice(2, 6),
            "three d eight" to RandomRequest.Dice(3, 8),
            "a twenty sided die" to RandomRequest.Dice(1, 20),
            "a twenty-sided die" to RandomRequest.Dice(1, 20),
            "three six sided dice" to RandomRequest.Dice(3, 6),
            "a one hundred sided die for me" to RandomRequest.Dice(1, 100),
            "a dee twelve please" to RandomRequest.Dice(1, 12),
        )) {
            withClue(capture) { RandomPhrases.dice(capture, ENGLISH_PARSER_FORMATTER) shouldBe dice }
        }
    }

    "rolling something else is no dice" {
        for (capture in listOf(
            "down the window", "the credits", "up the window", "a joint", "fifty dice",
            "a one sided die", "dice and a coin", "",
        )) {
            withClue(capture) { RandomPhrases.dice(capture, ENGLISH_PARSER_FORMATTER).shouldBeNull() }
        }
    }

    "a range in either order" {
        RandomPhrases.range("one", "ten", ENGLISH_PARSER_FORMATTER) shouldBe RandomRequest.Pick(1, 10)
        RandomPhrases.range("100", "1", ENGLISH_PARSER_FORMATTER) shouldBe RandomRequest.Pick(1, 100)
        RandomPhrases.range("a", "b", ENGLISH_PARSER_FORMATTER).shouldBeNull()
    }

    "the sentences take coins, dice and numbers to pick, and nothing else" {
        request("flip a coin") shouldBe RandomRequest.Coin
        request("heads or tails") shouldBe RandomRequest.Coin
        request("roll a d twenty") shouldBe RandomRequest.Dice(1, 20)
        request("pick a number between one and ten") shouldBe RandomRequest.Pick(1, 10)
        request("pick a random number") shouldBe RandomPhrases.DEFAULT_RANGE
        request("give me a random number from 5 to 15") shouldBe RandomRequest.Pick(5, 15)
        for (input in listOf(
            "roll down the window", "flip the screen", "what's my phone number", "pick up the phone",
            "give me a number",
        )) {
            withClue(input) {
                skill().score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
    }

    "the results are within what was asked" {
        val skill = skill()
        val ctx = FakeSkillContext()
        repeat(50) {
            skill.generateOutput(ctx, RandomRequest.Dice(3, 20)).shouldBeInstanceOf<DiceOutput>().let {
                it.rolls shouldHaveSize 3
                it.rolls.forEach { roll -> roll shouldBeInRange 1..20 }
                it.total shouldBe it.rolls.sum()
            }
            skill.generateOutput(ctx, RandomRequest.Pick(5, 7)).shouldBeInstanceOf<NumberOutput>()
                .value shouldBeInRange 5L..7L
        }
        skill.generateOutput(ctx, RandomRequest.Coin).shouldBeInstanceOf<CoinOutput>()
    }

    "an 8, an 11, an 18" {
        listOf(1, 7, 8, 11, 18, 20, 80, 88, 100, 800).filter { DiceOutput.startsWithVowelSound(it) } shouldBe
            listOf(8, 11, 18, 80, 88, 800)
    }
})
