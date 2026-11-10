package org.stypox.dicio.skills.battery

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

private fun status(
    percent: Int? = 63,
    plugged: Boolean = false,
    charging: Boolean = false,
    full: Boolean = false,
    timeToFull: Duration? = null,
) = BatteryStatus(percent, plugged, charging, full, timeToFull)

class BatteryTest : StringSpec({
    "the answer follows the question and the battery" {
        val charging = status(plugged = true, charging = true, timeToFull = Duration.ofMinutes(80))
        val noEstimate = status(plugged = true, charging = true)
        val held = status(percent = 80, plugged = true)
        val full = status(percent = 100, plugged = true, full = true)
        val unplugged = status()
        for ((case, answer) in listOf(
            Pair(BatteryQuestion.LEVEL, unplugged) to BatteryAnswer.LEVEL,
            Pair(BatteryQuestion.LEVEL, charging) to BatteryAnswer.LEVEL_CHARGING,
            Pair(BatteryQuestion.LEVEL, full) to BatteryAnswer.FULL,
            Pair(BatteryQuestion.CHARGING, charging) to BatteryAnswer.CHARGING,
            Pair(BatteryQuestion.CHARGING, held) to BatteryAnswer.PLUGGED_NOT_CHARGING,
            Pair(BatteryQuestion.CHARGING, unplugged) to BatteryAnswer.NOT_CHARGING,
            Pair(BatteryQuestion.CHARGING, full) to BatteryAnswer.FULL,
            Pair(BatteryQuestion.CHARGE_TIME, charging) to BatteryAnswer.TIME_TO_FULL,
            Pair(BatteryQuestion.CHARGE_TIME, noEstimate) to BatteryAnswer.CHARGING_NO_ESTIMATE,
            Pair(BatteryQuestion.CHARGE_TIME, held) to BatteryAnswer.PLUGGED_NOT_CHARGING,
            Pair(BatteryQuestion.CHARGE_TIME, unplugged) to BatteryAnswer.NOT_CHARGING,
            Pair(BatteryQuestion.CHARGE_TIME, full) to BatteryAnswer.FULL,
            Pair(BatteryQuestion.LEVEL, status(percent = null)) to BatteryAnswer.UNKNOWN,
        )) {
            withClue(case) { BatteryAnswer.of(case.first, case.second) shouldBe answer }
        }
    }

    "questions about the phone's battery" {
        for (input in listOf(
            "what's my battery", "how much battery do i have left", "is my phone charging",
            "how long until it's fully charged", "what percent is my phone at",
            "how much power is left", "is the charger plugged in",
            // how Vosk hears "what's my battery"
            "where's my battery", "where is my battery level",
        )) {
            withClue(input) { BatteryWords.asksAboutBattery(input) shouldBe true }
        }
    }

    "settings, commands and other devices aren't" {
        for (input in listOf(
            "turn on battery saver", "open battery settings", "is low power mode on",
            "is my watch charging", "how much battery do my earbuds have", "battery usage",
            "turn on wireless charging", "is fast charging on", "power off", "what's fifteen percent of eighty",
            "remind me to charge my phone", "what time is it",
        )) {
            withClue(input) { BatteryWords.asksAboutBattery(input) shouldBe false }
        }
    }
})
