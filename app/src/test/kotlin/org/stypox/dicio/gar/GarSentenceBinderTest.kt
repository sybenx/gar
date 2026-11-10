package org.stypox.dicio.gar

import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.tools.SentenceMapping
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.stypox.dicio.gar.call.GarSentenceBinder
import org.stypox.dicio.sentences.Sentences
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import org.dicio.numbers.unit.Duration as DicioDuration
import org.dicio.numbers.unit.Number as DicioNumber

private val NOON = LocalDateTime.of(2026, 10, 25, 12, 0)

class GarSentenceBinderTest : StringSpec({
    "a tool without a sentence for its values fails loudly" {
        shouldThrow<IllegalArgumentException> {
            GarSentenceBinder.buildInput(FLASHLIGHT_TOOL, mapOf("state" to "sideways"))
        }.message shouldBe "No sentence for state=sideways in flashlight"
        shouldThrow<IllegalArgumentException> {
            GarSentenceBinder.buildInput(FLASHLIGHT_TOOL, mapOf("state" to null))
        }.message shouldBe "No sentence for state=null in flashlight"
        shouldThrow<IllegalArgumentException> {
            GarSentenceBinder.buildInput(ALARM_TOOL, mapOf("time" to SEVEN_AM, "label" to "gym"))
        }.message shouldBe "alarm_set has no sentence, it is LLM-only"
    }

    "captures must match the generated constructor" {
        val oneCapture =
            TIMER_SET_TOOL.copy(sentence = SentenceMapping.Fixed("set", listOf("duration")))
        shouldThrow<IllegalStateException> {
            GarSentenceBinder.constructorFor(oneCapture, "set")
        }.message shouldBe "No constructor with 1 parameters for set of timer"
    }

    "a capture the tool has no slot for is null" {
        val durationOnly = TIMER_SET_TOOL.copy(slots = listOf(TIMER_SET_TOOL.slots[0]))
        val input = GarSentenceBinder.buildInput(
            durationOnly,
            mapOf("duration" to Duration.ofMinutes(10), "name" to "pasta"),
        ).shouldBeInstanceOf<Sentences.Timer.Set>()
        input.duration?.toJavaDuration() shouldBe Duration.ofMinutes(10)
        input.name shouldBe null
    }

    "values are converted to the capture types" {
        GarSentenceBinder.toCaptureValue(Duration.ofMinutes(10), "duration", NOON)
            .shouldBeInstanceOf<DicioDuration>().toJavaDuration() shouldBe Duration.ofMinutes(10)
        val duration = DicioDuration(Duration.ofSeconds(5))
        GarSentenceBinder.toCaptureValue(duration, "duration", NOON) shouldBeSameInstanceAs duration

        val five = ResolvedNumber(5.0, percent = false, isInteger = true)
        GarSentenceBinder.toCaptureValue(five, "number", NOON)
            .shouldBeInstanceOf<DicioNumber>().let {
                it.isInteger shouldBe true
                it.integerValue() shouldBe 5L
            }
        val twoAndAHalf = ResolvedNumber(2.5, percent = false, isInteger = false)
        GarSentenceBinder.toCaptureValue(twoAndAHalf, "number", NOON)
            .shouldBeInstanceOf<DicioNumber>().let {
                it.isDecimal shouldBe true
                it.decimalValue() shouldBe 2.5
            }

        val sevenPm = LocalDateTime.of(2026, 10, 27, 19, 0)
        GarSentenceBinder.toCaptureValue(
            ResolvedDateTime(sevenPm, dateSpoken = true, timeSpoken = true), "date_time", NOON,
        ) shouldBe sevenPm
        // a time said without a date is kept: the date is the next one with that time
        GarSentenceBinder.toCaptureValue(
            ResolvedDateTime(sevenPm, dateSpoken = false, timeSpoken = true), "date_time", NOON,
        ) shouldBe sevenPm
        GarSentenceBinder.toCaptureValue(sevenPm, "date_time", NOON) shouldBe sevenPm
        GarSentenceBinder.toCaptureValue(LocalTime.of(7, 0), "date_time", NOON) shouldBe
            LocalDateTime.of(2026, 10, 26, 7, 0)

        GarSentenceBinder.toCaptureValue("pasta", "string", NOON) shouldBe "pasta"
        GarSentenceBinder.toCaptureValue(null, "number", NOON) shouldBe null
    }

    "values of the wrong type fail loudly" {
        for ((value, captureType) in listOf(
            ResolvedNumber(5.0, percent = false, isInteger = true) to "duration",
            "five" to "number",
            Duration.ofMinutes(5) to "number",
            "seven" to "date_time",
            Duration.ofMinutes(5) to "date_time",
            "pasta" to "foo",
        )) {
            withClue("$value to $captureType") {
                shouldThrow<IllegalArgumentException> {
                    GarSentenceBinder.toCaptureValue(value, captureType, NOON)
                }
            }
        }
        shouldThrow<IllegalArgumentException> {
            GarSentenceBinder.toCaptureValue("five", "number", NOON)
        }.message shouldContain "java.lang.String"
        shouldThrow<IllegalArgumentException> { GarSentenceBinder.captureClass("foo") }
    }

    "a time-only value is its next occurrence, strictly after now" {
        GarSentenceBinder.nextOccurrence(LocalTime.NOON, NOON) shouldBe NOON.plusDays(1)
        GarSentenceBinder.nextOccurrence(LocalTime.of(12, 0, 1), NOON) shouldBe NOON.plusSeconds(1)
        GarSentenceBinder.nextOccurrence(LocalTime.of(11, 59), NOON) shouldBe
            LocalDateTime.of(2026, 10, 26, 11, 59)
    }
})
