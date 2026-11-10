package cc.aaro.gar.router.resolve

import cc.aaro.gar.router.tools.SlotKind
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun mentions(kind: SlotKind, raw: String) = TypedValueWords.mentionsValue(kind, raw)

class TypedValueWordsTest : StringSpec({
    "vague values say when, how long or how much" {
        for ((kind, raw) in listOf(
            SlotKind.DURATION to "a few minutes",
            SlotKind.DURATION to "five more minutes",
            SlotKind.DURATION to "a while",
            SlotKind.DURATION to "a couple",
            SlotKind.DURATION to "twelve",
            SlotKind.DATETIME to "later",
            SlotKind.DATETIME to "soon",
            SlotKind.DATETIME to "after lunch",
            SlotKind.DATETIME to "this evening",
            SlotKind.DATETIME to "tomorrow's meeting",
            SlotKind.DATETIME to "on mondays",
            SlotKind.DATETIME to "at 7",
            SlotKind.DATETIME to "next weekend",
            SlotKind.DATETIME to "the twenty first",
            SlotKind.TIME to "half past",
            SlotKind.TIME to "bedtime",
            SlotKind.DATE to "christmas eve in december",
            SlotKind.NUMBER to "the minimum",
            SlotKind.NUMBER to "fifty",
            SlotKind.NUMBER to "a few percent",
        )) {
            withClue("$kind $raw") { mentions(kind, raw) shouldBe true }
        }
    }

    "other words say nothing of the kind" {
        for ((kind, raw) in listOf(
            SlotKind.DURATION to "timer",
            SlotKind.DURATION to "time",
            SlotKind.DURATION to "counting up",
            SlotKind.DURATION to "tomorrow", // a day, not an amount of time
            SlotKind.TIME to "alarm",
            SlotKind.TIME to "ring sarah",
            SlotKind.TIME to "when",
            SlotKind.TIME to "the clock",
            SlotKind.DATETIME to "to buy milk",
            SlotKind.DATETIME to "pay rent",
            SlotKind.DATETIME to "the next one", // "one" alone is the pronoun
            SlotKind.DATETIME to "may i", // "may" is no month here
            SlotKind.DATE to "france",
            SlotKind.DATE to "next full moon",
            SlotKind.DATE to "date",
            SlotKind.DATE to "christmas", // holidays aren't resolved
            SlotKind.NUMBER to "heat",
            SlotKind.NUMBER to "later", // a time, not a number
            SlotKind.TEXT to "five minutes", // only typed kinds have values
        )) {
            withClue("$kind $raw") { mentions(kind, raw) shouldBe false }
        }
    }

    "a part of day in a greeting is no value" {
        mentions(SlotKind.TIME, "good morning") shouldBe false
        mentions(SlotKind.DATETIME, "good night") shouldBe false
        mentions(SlotKind.TIME, "good afternoon") shouldBe false
        mentions(SlotKind.DATETIME, "good tomorrow morning") shouldBe true
        mentions(SlotKind.DATETIME, "good evening at eight") shouldBe true
        // only parts of day: "good friday" is a day
        mentions(SlotKind.TIME, "good friday") shouldBe true
    }

    "any digit counts, and words count with plural and possessive endings" {
        for (raw in listOf("7pm", "5th", "10:30", "mp3")) {
            for (kind in listOf(SlotKind.TIME, SlotKind.NUMBER, SlotKind.DURATION)) {
                withClue("$kind $raw") { mentions(kind, raw) shouldBe true }
            }
        }
        mentions(SlotKind.TIME, "tonight's") shouldBe true
        mentions(SlotKind.NUMBER, "tonight's") shouldBe false
        mentions(SlotKind.TIME, "weekends") shouldBe true
        // a unit the resolvers can't read keeps the call, and GAR asks
        mentions(SlotKind.DURATION, "fortnight") shouldBe true
        mentions(SlotKind.TIME, "fortnight") shouldBe true
        // "may" counts through the ordinal; other months alone
        mentions(SlotKind.TIME, "may fifth") shouldBe true
        mentions(SlotKind.TIME, "march") shouldBe true
        for (raw in listOf("once", "twice", "a.m.")) {
            for (kind in listOf(SlotKind.TIME, SlotKind.NUMBER, SlotKind.DURATION)) {
                withClue("$kind $raw") { mentions(kind, raw) shouldBe false }
            }
        }
        mentions(SlotKind.NUMBER, "full blast") shouldBe false
        mentions(SlotKind.NUMBER, "loudest") shouldBe false
    }
})
