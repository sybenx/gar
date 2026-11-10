package org.stypox.dicio.gar.conversation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class GarUtteranceTrackerTest : StringSpec({
    "nothing queued and not speaking: the runnable runs at once" {
        val tracker = GarUtteranceTracker()
        var ran = 0
        tracker.runWhenFinished(speaking = false) { ran++ }
        ran shouldBe 1
    }

    "right after speak the engine may say it isn't speaking: the runnable still waits" {
        val tracker = GarUtteranceTracker()
        var ran = 0
        tracker.queued("dicio_1")
        tracker.pending.shouldBeTrue()
        tracker.runWhenFinished(speaking = false) { ran++ }
        ran shouldBe 0
        tracker.finished("dicio_1")
        ran shouldBe 1
        tracker.pending.shouldBeFalse()
    }

    "it runs on the end of the last queued utterance, once" {
        val tracker = GarUtteranceTracker()
        var ran = 0
        tracker.queued("dicio_1")
        tracker.queued("dicio_2")
        tracker.runWhenFinished(speaking = true) { ran++ }
        tracker.finished("dicio_1")
        ran shouldBe 0
        tracker.finished("dicio_2")
        ran shouldBe 1
        tracker.finished("dicio_2") // a late onStop after onDone
        ran shouldBe 1
    }

    "an error of the last utterance runs it too, so a question still gets its microphone" {
        val tracker = GarUtteranceTracker()
        var ran = 0
        tracker.queued("dicio_1")
        tracker.runWhenFinished(speaking = false) { ran++ }
        tracker.finished("dicio_1") // onError reports it like onDone
        ran shouldBe 1
    }

    "the engine still speaking with nothing queued: it waits for the next end" {
        val tracker = GarUtteranceTracker()
        var ran = 0
        tracker.runWhenFinished(speaking = true) { ran++ }
        ran shouldBe 0
        tracker.queued("dicio_1")
        tracker.finished("dicio_1")
        ran shouldBe 1
    }

    "stopSpeaking clears it: it never runs, not even after a later utterance" {
        val tracker = GarUtteranceTracker()
        var earlier = 0
        var later = 0
        tracker.queued("dicio_1")
        tracker.runWhenFinished(speaking = false) { earlier++ }
        tracker.stopped()
        tracker.pending.shouldBeFalse()
        tracker.finished("dicio_1") // the stopped utterance's onStop
        earlier shouldBe 0

        tracker.queued("dicio_2")
        tracker.runWhenFinished(speaking = false) { later++ }
        tracker.finished("dicio_2")
        earlier shouldBe 0
        later shouldBe 1
    }

    "a runnable may queue the next one from inside" {
        val tracker = GarUtteranceTracker()
        val order = mutableListOf<String>()
        tracker.queued("dicio_1")
        tracker.runWhenFinished(speaking = false) {
            order.add("first")
            tracker.runWhenFinished(speaking = false) { order.add("second") }
        }
        tracker.finished("dicio_1")
        order shouldBe listOf("first", "second")
    }
})
