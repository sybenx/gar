package org.stypox.dicio.gar.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class GarSemanticsTest : StringSpec({
    "a skill row reads as its name, then its example, like the merged texts did" {
        GarSemantics.skillRowDescription("Weather", "What's the weather like?") shouldBe
            "Weather, What's the weather like?"
    }

    "a missing part leaves no stray comma" {
        GarSemantics.skillRowDescription("Timer", "  ") shouldBe "Timer"
        GarSemantics.skillRowDescription("", "Set a timer") shouldBe "Set a timer"
        GarSemantics.skillRowDescription(" Timer ", " Set a timer ") shouldBe "Timer, Set a timer"
    }

    // the chat list takes this modifier: an equal element lets the list skip recomposition when
    // the home screen recomposes (a composed {} modifier never compared equal)
    "the tap-stops-speech element is equal while its lambdas are the same" {
        val speaking = { false }
        val stop = {}
        GarTapStopsSpeechElement(speaking, stop) shouldBe GarTapStopsSpeechElement(speaking, stop)
        GarTapStopsSpeechElement(speaking, stop) shouldNotBe GarTapStopsSpeechElement({ true }, stop)
    }

    "an update swaps the lambdas in the node, so a gesture reads the current ones" {
        var stopped = 0
        val node = GarTapStopsSpeechElement({ false }, {}).create()
        node.speaking() shouldBe false
        GarTapStopsSpeechElement({ true }, { stopped += 1 }).update(node)
        node.speaking() shouldBe true
        node.stopSpeaking()
        stopped shouldBe 1
    }
})
