package org.stypox.dicio.skills.math

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.skills.calculator.CalculatorOutput
import java.util.Locale

private fun parse(input: String) = SpokenMath.parse(input, ENGLISH_PARSER_FORMATTER, Locale.US)

private fun CalculatorOutput.result(): String? = CalculatorOutput::class.java
    .getDeclaredField("result").apply { isAccessible = true }.get(this) as String?

class SpokenMathTest : StringSpec({
    "spoken arithmetic is computed with the usual precedence" {
        for ((input, value) in listOf(
            "seven plus nine" to 16.0, "twelve times eight" to 96.0,
            "what's fifteen percent of eighty" to 12.0, "the square root of sixty four" to 8.0,
            "five squared" to 25.0, "two cubed" to 8.0, "two plus three times four" to 14.0,
            "ten divided by four" to 2.5, "two to the power of ten" to 1024.0,
            "minus five plus three" to -2.0, "what is 8 times 7 please" to 56.0,
            "a twenty percent tip on eighty five dollars" to 17.0, "5 + 3" to 8.0, "12/4" to 3.0,
            "100 - 1" to 99.0, "one hundred and five minus five" to 100.0,
            "how much is three over four" to 0.75, "what does seven plus nine equal" to 16.0,
            "nine minus twelve" to -3.0, "1,000 times 3" to 3000.0, "2 x 3" to 6.0,
            "the cube root of twenty seven" to 3.0, "fifty percent of 30" to 15.0,
        )) {
            withClue(input) { parse(input).shouldNotBeNull().value shouldBe (value plusOrMinus 1e-9) }
        }
    }

    "a percent after plus or minus is of the value before it, as on a phone's calculator" {
        for ((input, value) in listOf(
            "100 plus 20 percent" to 120.0, "what's eighty plus fifteen percent" to 92.0,
            "fifty minus ten percent" to 45.0, "200 minus 25 percent" to 150.0,
            "100 plus 10 percent plus 10 percent" to 121.0, "ten plus twenty plus ten percent" to 33.0,
            // not before it, nor times or divided by
            "20 percent plus 100" to 100.2, "100 times 15 percent" to 15.0,
            "80 times 15 percent" to 12.0, "ten percent plus five percent" to 0.15,
        )) {
            withClue(input) { parse(input).shouldNotBeNull().value shouldBe (value plusOrMinus 1e-9) }
        }
        parse("what's eighty plus fifteen percent")?.interpretation shouldBe "80 + 15% ="
    }

    "the card shows the expression" {
        parse("what's fifteen percent of eighty")?.interpretation shouldBe "15% of 80 ="
        parse("seven plus nine")?.interpretation shouldBe "7 + 9 ="
        parse("the square root of sixty four")?.interpretation shouldBe "√64 ="
        parse("two plus three times four")?.interpretation shouldBe "2 + 3 x 4 ="
    }

    "a division by zero has no value" {
        parse("ten divided by zero").shouldNotBeNull().value.shouldBeNull()
    }

    "what the calculator's sentence computes too is marked" {
        parse("what is two plus two").shouldNotBeNull().let {
            Triple(it.calculatorLead, it.plainOperators, it.extraWords) shouldBe Triple(true, true, false)
        }
        parse("what's fifteen percent of eighty")?.plainOperators shouldBe false
        parse("what is 8 times 7 please")?.extraWords shouldBe true
        parse("seven plus nine")?.calculatorLead shouldBe false
    }

    "anything but arithmetic is none" {
        for (input in listOf(
            "five", "minus five", "fifty percent", "set a timer for five minutes",
            "call five five five plus one", "seven eleven", "two by four", "plus size clothes",
            "what's the score of the game", "page two of three", "", "times square",
            "what time is it", "five times", "plus", "two plus plus three", "roll two dice",
            "twenty percent off fifty",
        )) {
            withClue(input) { parse(input).shouldBeNull() }
        }
    }

    "the math skill leaves the calculator's own inputs to it" {
        val skill = MathSkill(MathInfo)
        fun score(input: String) = skill.score(FakeSkillContext().forInput(input), input).first
        score("seven plus nine") shouldBe FloatScore(1.0f)
        score("what's fifteen percent of eighty") shouldBe FloatScore(1.0f)
        score("what is 8 times 7 please") shouldBe FloatScore(1.0f)
        score("what is two plus two") shouldBe AlwaysWorstScore
        score("calculate 12 divided by 4") shouldBe AlwaysWorstScore
        score("set a timer for five minutes") shouldBe AlwaysWorstScore
    }

    "the answer is the calculator's" {
        val skill = MathSkill(MathInfo)
        val ctx = FakeSkillContext()
        val (_, input) = skill.score(ctx.forInput("seven plus nine"), "seven plus nine")
        (skill.generateOutput(ctx, input) as CalculatorOutput).result() shouldBe "16"
        val (_, byZero) = skill.score(ctx.forInput("one over zero"), "one over zero")
        (skill.generateOutput(ctx, byZero) as CalculatorOutput).result().shouldBeNull()
    }
})
