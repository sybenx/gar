package org.stypox.dicio.gar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.stypox.dicio.gar.model.GarNativeLoadLock

class GarNativeLoadLockTest : StringSpec({
    "loads run one at a time, and waiting speech loads go before the language model" {
        runTest {
            val lock = GarNativeLoadLock()
            val order = mutableListOf<String>()
            val firstDone = CompletableDeferred<Unit>()
            launch {
                lock.withSpeechLoad {
                    order.add("speech 1 start")
                    firstDone.await()
                    order.add("speech 1 end")
                }
            }
            runCurrent()
            launch { lock.withLanguageModelLoad { order.add("llm") } }
            runCurrent()
            launch { lock.withSpeechLoad { order.add("speech 2") } }
            runCurrent()
            firstDone.complete(Unit)
            advanceUntilIdle()
            order shouldContainExactly listOf("speech 1 start", "speech 1 end", "speech 2", "llm")
        }
    }

    "a language model load holds off speech loads until it ends" {
        runTest {
            val lock = GarNativeLoadLock()
            val order = mutableListOf<String>()
            val llmDone = CompletableDeferred<Unit>()
            launch {
                lock.withLanguageModelLoad {
                    order.add("llm start")
                    llmDone.await()
                    order.add("llm end")
                }
            }
            runCurrent()
            launch { lock.withSpeechLoad { order.add("speech") } }
            runCurrent()
            llmDone.complete(Unit)
            advanceUntilIdle()
            order shouldContainExactly listOf("llm start", "llm end", "speech")
        }
    }
})
