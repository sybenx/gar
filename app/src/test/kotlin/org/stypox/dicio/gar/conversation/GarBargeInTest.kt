package org.stypox.dicio.gar.conversation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.stypox.dicio.io.input.SttState

@OptIn(ExperimentalCoroutinesApi::class)
class GarBargeInTest : StringSpec({
    "two starts collect once: one Listening while speaking marks true and stops once" {
        val marks = mutableListOf<Boolean>()
        var stops = 0
        var speaking = true
        val states = MutableStateFlow<SttState?>(SttState.Loaded)
        val scope = TestScope(UnconfinedTestDispatcher())
        val collector = GarBargeInCollector(markCaptureStart = { marks.add(it) })

        collector.start(scope, states, { speaking }, { stops += 1; speaking = false }).shouldBeTrue()
        collector.start(scope, states, { speaking }, { stops += 1; speaking = false }).shouldBeFalse()

        states.value = SttState.Listening
        marks shouldBe listOf(true)
        stops shouldBe 1

        // the same capture going on is no new transition
        states.value = SttState.Listening
        marks shouldBe listOf(true)

        // the next capture, with GAR silent: marked false, speech stopped anyway (it's cheap)
        states.value = SttState.Transcribing
        states.value = SttState.Loaded
        states.value = SttState.Listening
        marks shouldBe listOf(true, false)
        stops shouldBe 2
        scope.cancel()
    }

    "nothing happens for the states around listening" {
        val marks = mutableListOf<Boolean>()
        var stops = 0
        val states = MutableStateFlow<SttState?>(null)
        val scope = TestScope(UnconfinedTestDispatcher())
        GarBargeInCollector(markCaptureStart = { marks.add(it) })
            .start(scope, states, { true }, { stops += 1 })
        for (state in listOf(SttState.Loading(true), SttState.Loaded, SttState.Transcribing, null)) {
            states.value = state
        }
        marks shouldBe listOf()
        stops shouldBe 0
        scope.cancel()
    }
})
