package org.stypox.dicio.gar.sheet

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.ui.GarMicState
import org.stypox.dicio.gar.ui.garble.GarbleMarkState

private class ProvenanceAnswer(val name: String) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = name

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
    }
}

class GarSheetProvenanceTest : StringSpec({
    "an answer without routing is instant" {
        val tracker = GarSheetProvenanceTracker()
        tracker.onPending(pending = true, routing = false)
        tracker.onRouting(null, now = 100)
        tracker.routeFor(ProvenanceAnswer("a"), now = 120) shouldBe GarSheetRoute.Instant
    }

    "a routed answer took the route's time" {
        val tracker = GarSheetProvenanceTracker()
        tracker.onPending(pending = true, routing = false)
        tracker.onRouting(1_000, now = 1_000)
        tracker.onRouting(null, now = 2_400)
        tracker.routeFor(ProvenanceAnswer("a"), now = 2_500) shouldBe GarSheetRoute.Model(1_400)
    }

    "an answer seen before the route ended counts until now" {
        val tracker = GarSheetProvenanceTracker()
        tracker.onRouting(1_000, now = 1_000)
        tracker.routeFor(ProvenanceAnswer("a"), now = 1_800) shouldBe GarSheetRoute.Model(800)
    }

    "the route is kept for the same answer and not reused by the next" {
        val tracker = GarSheetProvenanceTracker()
        val first = ProvenanceAnswer("a")
        tracker.onRouting(0, now = 0)
        tracker.onRouting(null, now = 500)
        tracker.routeFor(first, now = 600) shouldBe GarSheetRoute.Model(500)
        tracker.routeFor(first, now = 9_000) shouldBe GarSheetRoute.Model(500)
        tracker.routeFor(ProvenanceAnswer("b"), now = 9_100) shouldBe GarSheetRoute.Instant
    }

    "a new question forgets a route that ended without an answer" {
        val tracker = GarSheetProvenanceTracker()
        tracker.onRouting(0, now = 0)
        tracker.onRouting(null, now = 500)
        tracker.onPending(pending = false, routing = false)
        tracker.onPending(pending = true, routing = false)
        tracker.routeFor(ProvenanceAnswer("a"), now = 900) shouldBe GarSheetRoute.Instant
    }

    "the sheet's marks follow the mic state" {
        garSheetHeaderMark(GarMicState.Listening(null)) shouldBe GarbleMarkState.Understood
        garSheetHeaderMark(GarMicState.Idle) shouldBe GarbleMarkState.Understood
        garSheetHeaderMark(GarMicState.Speaking) shouldBe GarbleMarkState.Speaking
        garSheetHeaderMark(
            GarMicState.Thinking(0, null, model = true, loadingModel = false)
        ) shouldBe GarbleMarkState.Thinking
        garSheetListeningLayout(GarMicState.Listening(null), typing = false) shouldBe true
        garSheetListeningLayout(GarMicState.Starting, typing = false) shouldBe true
        garSheetListeningLayout(GarMicState.Listening(5_000), typing = false) shouldBe false
        garSheetListeningLayout(GarMicState.Listening(null), typing = true) shouldBe false
        garSheetListeningLayout(GarMicState.Idle, typing = false) shouldBe false
        // through Transcribing and Thinking only if it was up already (not after typing)
        garSheetListeningLayout(GarMicState.Transcribing, typing = false, wasListening = true) shouldBe true
        garSheetListeningLayout(GarMicState.Transcribing, typing = false) shouldBe false
        val thinking = GarMicState.Thinking(0, null, model = true, loadingModel = false)
        garSheetListeningLayout(thinking, typing = false, hasAnswer = true, wasListening = true) shouldBe
            false
        garSheetListeningLayout(
            thinking, typing = false, hasAnswer = true, userAsked = true, wasListening = true,
        ) shouldBe true
        garSheetHeaderMark(thinking, listeningLayout = true) shouldBe GarbleMarkState.Understood
    }

    "provenance seconds have one decimal" {
        garSheetSeconds(1_400) shouldBe "1.4"
        garSheetSeconds(250) shouldBe "0.3"
        garSheetSeconds(12_000) shouldBe "12.0"
    }
})
