package org.stypox.dicio.gar

import cc.aaro.gar.router.tools.SentenceMapping
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.call.GarSlotRewrites
import org.stypox.dicio.gar.call.spokenPhoneNumber

private val CALCULATION_SLOT =
    SlotSpec("calculation", SlotKind.TEXT, true, "", captureType = "string")

private val CALCULATE_TOOL = ToolSpec(
    name = "calculate",
    skillId = "calculator",
    sentence = SentenceMapping.Fixed("calculate", listOf("calculation")),
    description = "Calculate the result of a math expression.",
    confirm = false,
    slots = listOf(CALCULATION_SLOT),
    examples = listOf(),
)

/** [GarSlotRewrites] on its own; GarCallFlowTest runs calculations through Dicio's calculator. */
class GarSlotRewritesTest : StringSpec({
    "prefix calculations become infix, in any case and with any of their verbs" {
        for ((raw, rewritten) in listOf(
            "Divide 10 by 2" to "10 divided by 2",
            "take away five from ten" to "ten minus five",
            "take five from ten" to "ten minus five",
            "sum three with four" to "three plus four",
            "add three plus four" to "three plus four",
            "multiply six with seven" to "six times seven",
            "multiply six times seven" to "six times seven",
            "multiply six and seven" to "six times seven",
            "  square   root of  nine " to "nine to the power of 0.5",
            // an "and" inside a number doesn't separate the operands
            "multiply one hundred and five by two" to "one hundred and five times two",
            "add one hundred and five to ten" to "one hundred and five plus ten",
            "sum one hundred and five with ten" to "one hundred and five plus ten",
            "add five and one hundred" to "five plus one hundred",
        )) {
            withClue(raw) { GarSlotRewrites.calculation(raw) shouldBe rewritten }
        }
    }

    "a calculation that isn't in prefix form is kept as it was" {
        GarSlotRewrites.calculation("two  plus  two") shouldBe "two  plus  two"
        GarSlotRewrites.calculation(" 12 divided by 4") shouldBe " 12 divided by 4"
        // the verb must come first
        GarSlotRewrites.calculation("please divide 10 by 2") shouldBe "please divide 10 by 2"
    }

    "rewrites only apply to their tool's slot" {
        GarSlotRewrites.rewrite(CALCULATE_TOOL, CALCULATION_SLOT, "divide 10 by 2") shouldBe
            "10 divided by 2"
        val notes = CALCULATE_TOOL.copy(skillId = "notes")
        GarSlotRewrites.rewrite(notes, CALCULATION_SLOT, "divide 10 by 2") shouldBe "divide 10 by 2"
        GarSlotRewrites.rewrite(TIMER_SET_TOOL, TIMER_SET_TOOL.slots[1], "the pasta timer") shouldBe
            "pasta"
        // the timer's generic words stay in other tools' text
        GarSlotRewrites.rewrite(ALARM_TOOL, ALARM_TOOL.slots[1], "the gym alarm") shouldBe
            "the gym alarm"
    }

    "timer names lose only the generic words around them" {
        for ((raw, name) in listOf(
            "My TIMER" to "",
            "Pasta Timer" to "Pasta",
            "timer for the pasta" to "pasta",
            "pasta for dinner" to "pasta for dinner",
            "the timer named the big one" to "big one",
            "  " to "",
        )) {
            withClue(raw) { GarSlotRewrites.timerName(raw) shouldBe name }
        }
    }

    "phone numbers are digits or digit words only" {
        spokenPhoneNumber("five five five 1212") shouldBe "5551212"
        spokenPhoneNumber("o one two") shouldBe "012"
        spokenPhoneNumber("Nine, one, one.") shouldBe "911"
        // "plus" only as the first word, and at least three digits
        spokenPhoneNumber("one plus two three").shouldBeNull()
        spokenPhoneNumber("plus four four").shouldBeNull()
        spokenPhoneNumber("").shouldBeNull()
    }
})
