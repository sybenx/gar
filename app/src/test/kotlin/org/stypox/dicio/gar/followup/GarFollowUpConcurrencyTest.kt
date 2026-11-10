package org.stypox.dicio.gar.followup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.sentences.Sentences

/**
 * A follow-up said while the turn before it is still answering waits for that turn's memory
 * (gar/DESIGN.md, "Follow-ups"), at most [GarConversationMemory.SETTLE_MILLIS]; after that, GAR
 * asks instead of guessing.
 */
class GarFollowUpConcurrencyTest : StringSpec({
    "turn it off waits for the wifi turn still answering, not the older flashlight" {
        runTest {
            resetMemory()
            remember(flashlightOn())
            // "turn on wifi": matched, its output held back
            val wifi = GarConversationMemory.begin(turnElement(), continuesStep = false)
            val turnItOff = async {
                GarConversationMemory.awaitSettled(listOf("turn it off"))
                reach("turn it off")
            }
            runCurrent()
            turnItOff.isCompleted shouldBe false

            GarConversationMemory.finish(
                wifi,
                answered("connectivity", "turn on wifi", Sentences.Connectivity.WifiOn, FakeOutput(null)),
                lockedNow = false,
            )
            turnItOff.await().outcome shouldBe GarFollowUpOutcome.Rewritten("turn off wifi")
        }
    }

    "other inputs don't wait" {
        runTest {
            resetMemory()
            val held = GarConversationMemory.begin(turnElement(), continuesStep = false)
            val other = async { GarConversationMemory.awaitSettled(listOf("what's the weather")) }
            runCurrent()
            other.isCompleted shouldBe true
            GarConversationMemory.finish(held, null, lockedNow = false)
        }
    }

    "after the wait, a turn still in flight makes GAR ask" {
        runTest {
            resetMemory()
            remember(flashlightOn())
            val held = GarConversationMemory.begin(turnElement(), continuesStep = false)
            val turnItOff = async {
                GarConversationMemory.awaitSettled(listOf("turn it off"))
                reach("turn it off")
            }
            advanceTimeBy(GarConversationMemory.SETTLE_MILLIS + 1)
            runCurrent()
            turnItOff.await().outcome shouldBe
                GarFollowUpOutcome.Ask(GarFollowUpQuestion.TURN_WHAT_OFF, "turn off {answer}")
            GarConversationMemory.finish(held, null, lockedNow = false)
        }
    }

    "the evaluator's match of the follow-up turns wifi off" {
        runTest {
            resetMemory()
            val phone = FollowUpReplayPhone()
            phone.say("turn on the flashlight")
            val wifi = GarConversationMemory.begin(turnElement(), continuesStep = false)
            val followUp = async {
                GarConversationMemory.awaitSettled(listOf("turn it off"))
                phone.match("turn it off").second.inputData
            }
            runCurrent()
            GarConversationMemory.finish(
                wifi,
                answered("connectivity", "turn on wifi", Sentences.Connectivity.WifiOn, FakeOutput(null)),
                lockedNow = false,
            )
            followUp.await() shouldBe Sentences.Connectivity.WifiOff
        }
    }
})
