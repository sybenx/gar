@file:OptIn(ExperimentalCoroutinesApi::class)

package org.stypox.dicio.gar

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.stypox.dicio.gar.model.GarEngineHolder
import org.stypox.dicio.gar.model.PreviousLoadFailedException
import java.io.IOException

private class FakeModel(val key: String) {
    var closed = false
}

private class HolderFixture(
    scope: CoroutineScope,
    var failLoading: Boolean = false,
    shortGraceMillis: Long = 30_000,
    var keepLonger: Boolean = true,
    /** How long a load takes, in virtual time. */
    loadMillis: Long = 0,
    failClosing: Boolean = false,
) {
    val loads = mutableListOf<String>()
    val closeErrors = mutableListOf<Throwable>()
    val holder = GarEngineHolder<String, FakeModel>(
        scope = scope,
        graceMillis = 30_000,
        load = { key ->
            loads.add(key)
            delay(loadMillis)
            if (failLoading) throw IllegalStateException("bad file")
            FakeModel(key)
        },
        close = {
            it.closed = true
            if (failClosing) throw IOException("can't free the model")
        },
        onCloseError = { closeErrors.add(it) },
        shortGraceMillis = shortGraceMillis,
        keepLonger = { keepLonger },
    )
}

private fun TestScope.loaded(fixture: HolderFixture, key: String): FakeModel {
    var model: FakeModel? = null
    launchUse(fixture, key) { model = it }
    runCurrent()
    return model!!
}

private fun TestScope.launchUse(fixture: HolderFixture, key: String, block: (FakeModel) -> Unit) {
    backgroundScope.launch { fixture.holder.use(key) { block(it) } }
}

class GarEngineHolderTest : StringSpec({
    "unloads after the grace period, not before" {
        runTest {
            val fixture = HolderFixture(backgroundScope)
            fixture.holder.acquire()
            fixture.holder.preload("qwen")
            runCurrent()
            fixture.holder.state.value shouldBe GarEngineHolder.State.Loaded("qwen")
            val model = loaded(fixture, "qwen")

            fixture.holder.release()
            advanceTimeBy(29_999)
            model.closed shouldBe false
            advanceTimeBy(2)
            runCurrent()
            model.closed shouldBe true
            fixture.holder.state.value shouldBe GarEngineHolder.State.NotLoaded
            fixture.loads shouldBe listOf("qwen")
        }
    }

    "acquiring again during the grace period keeps the model" {
        runTest {
            val fixture = HolderFixture(backgroundScope)
            fixture.holder.acquire()
            val model = loaded(fixture, "qwen")
            fixture.holder.release()
            advanceTimeBy(20_000)
            fixture.holder.acquire()
            advanceTimeBy(60_000)
            runCurrent()
            model.closed shouldBe false
            loaded(fixture, "qwen") shouldBe model
            fixture.loads shouldBe listOf("qwen")
        }
    }

    "memory pressure only unloads an idle model" {
        runTest {
            val fixture = HolderFixture(backgroundScope)
            fixture.holder.acquire()
            val model = loaded(fixture, "qwen")
            fixture.holder.unloadIfIdle()
            model.closed shouldBe false

            fixture.holder.release()
            fixture.holder.unloadIfIdle()
            model.closed shouldBe true
        }
    }

    "a different model replaces the loaded one" {
        runTest {
            val fixture = HolderFixture(backgroundScope)
            val qwen = loaded(fixture, "qwen")
            val gemma = loaded(fixture, "gemma")
            qwen.closed shouldBe true
            gemma.closed shouldBe false
            fixture.loads shouldBe listOf("qwen", "gemma")
        }
    }

    "a failed load is reported and only retried on request" {
        runTest {
            val fixture = HolderFixture(backgroundScope, failLoading = true)
            fixture.holder.preload("broken")
            runCurrent()
            fixture.holder.state.value.shouldBeInstanceOf<GarEngineHolder.State.Failed<String>>()

            // resumes and utterances don't load the same file again
            fixture.holder.preload("broken")
            runCurrent()
            shouldThrow<PreviousLoadFailedException> { fixture.holder.use("broken") { it } }
            fixture.loads shouldBe listOf("broken")
            fixture.holder.state.value.shouldBeInstanceOf<GarEngineHolder.State.Failed<String>>()

            // another file (or the same path with new contents, see ModelSelection) loads
            fixture.failLoading = false
            loaded(fixture, "other").key shouldBe "other"

            fixture.failLoading = true
            fixture.holder.preload("broken")
            runCurrent()
            fixture.holder.retry()
            fixture.failLoading = false
            loaded(fixture, "broken").key shouldBe "broken"
            fixture.loads shouldBe listOf("broken", "other", "broken", "broken")
        }
    }

    "without a reason to keep it, the model is unloaded after the short grace period" {
        runTest {
            val fixture = HolderFixture(backgroundScope, shortGraceMillis = 6_000, keepLonger = false)
            fixture.holder.acquire()
            val model = loaded(fixture, "qwen")
            fixture.holder.release()
            advanceTimeBy(5_999)
            model.closed shouldBe false
            advanceTimeBy(2)
            runCurrent()
            model.closed shouldBe true
        }
    }

    "a process that keeps running keeps the model for the whole grace period" {
        runTest {
            val fixture = HolderFixture(backgroundScope, shortGraceMillis = 6_000, keepLonger = true)
            fixture.holder.acquire()
            val model = loaded(fixture, "qwen")
            fixture.holder.release()
            advanceTimeBy(29_999)
            model.closed shouldBe false
            advanceTimeBy(2)
            runCurrent()
            model.closed shouldBe true
        }
    }

    "preloads of the same model while one is running are merged" {
        runTest {
            val fixture = HolderFixture(backgroundScope, failLoading = true)
            // e.g. onStart and onResume right after each other
            fixture.holder.preload("broken")
            fixture.holder.preload("broken")
            runCurrent()
            fixture.loads shouldBe listOf("broken")
        }
    }

    "a start's preloads and the first command load the model once" {
        runTest {
            val fixture = HolderFixture(backgroundScope, loadMillis = 1_300)
            // the settings flow, onStart and onResume each preload, and the first command comes
            // while the model is still loading
            fixture.holder.acquire()
            fixture.holder.preload("qwen")
            fixture.holder.preload("qwen")
            var model: FakeModel? = null
            launchUse(fixture, "qwen") { model = it }
            advanceTimeBy(1_000)
            model shouldBe null // the command waits for the running load
            advanceTimeBy(1_000)
            fixture.holder.preload("qwen")
            runCurrent()
            model!!.key shouldBe "qwen"
            fixture.loads shouldBe listOf("qwen")
        }
    }

    "a model that fails to close counts as unloaded" {
        runTest {
            val fixture = HolderFixture(backgroundScope, failClosing = true)
            val a = loaded(fixture, "a")
            loaded(fixture, "b").key shouldBe "b"
            a.closed shouldBe true
            fixture.closeErrors.single().shouldBeInstanceOf<IOException>()
            fixture.holder.state.value shouldBe GarEngineHolder.State.Loaded("b")
            fixture.loads shouldBe listOf("a", "b")
        }
    }

    "a cancelled load is not a failed one" {
        runTest {
            val fixture = HolderFixture(backgroundScope, loadMillis = 10_000)
            val preload = fixture.holder.preload("a")
            advanceTimeBy(5_000)
            fixture.holder.state.value shouldBe GarEngineHolder.State.Loading("a")
            preload.cancel()
            runCurrent()
            fixture.holder.state.value shouldBe GarEngineHolder.State.NotLoaded

            val model = backgroundScope.async { fixture.holder.use("a") { it } }
            advanceTimeBy(10_001)
            model.await().key shouldBe "a"
            fixture.loads shouldBe listOf("a", "a")
        }
    }

    "releasing more often than acquiring is a bug" {
        runTest {
            val fixture = HolderFixture(backgroundScope)
            shouldThrow<IllegalStateException> { fixture.holder.release() }
                .message shouldBe "release() without acquire()"
        }
    }

    "unload closes the model even while it is acquired" {
        runTest {
            val fixture = HolderFixture(backgroundScope)
            fixture.holder.acquire()
            val model = loaded(fixture, "qwen")
            fixture.holder.unload()
            model.closed shouldBe true
            fixture.holder.state.value shouldBe GarEngineHolder.State.NotLoaded
        }
    }

    "retry only forgets a failed load" {
        runTest {
            val fixture = HolderFixture(backgroundScope)
            val model = loaded(fixture, "qwen")
            fixture.holder.retry()
            fixture.holder.state.value shouldBe GarEngineHolder.State.Loaded("qwen")
            loaded(fixture, "qwen") shouldBe model
            fixture.loads shouldBe listOf("qwen")

            // unloading with nothing loaded leaves the failure visible
            val failing = HolderFixture(backgroundScope, failLoading = true)
            failing.holder.preload("broken")
            runCurrent()
            failing.holder.unload()
            failing.holder.state.value.shouldBeInstanceOf<GarEngineHolder.State.Failed<String>>()
        }
    }

    "a short grace period longer than the grace period doesn't extend it" {
        runTest {
            val fixture = HolderFixture(backgroundScope, shortGraceMillis = 60_000)
            fixture.holder.acquire()
            val model = loaded(fixture, "qwen")
            fixture.holder.release()
            advanceTimeBy(29_999)
            model.closed shouldBe false
            advanceTimeBy(2)
            runCurrent()
            model.closed shouldBe true
        }
    }

    "preloads of different models run one after the other" {
        runTest {
            val fixture = HolderFixture(backgroundScope, loadMillis = 1_000)
            val a = fixture.holder.preload("a")
            val b = fixture.holder.preload("b")
            a shouldNotBe b
            advanceTimeBy(500)
            fixture.loads shouldBe listOf("a")
            advanceTimeBy(2_000)
            runCurrent()
            fixture.loads shouldBe listOf("a", "b")
            fixture.holder.state.value shouldBe GarEngineHolder.State.Loaded("b")
            loaded(fixture, "b").key shouldBe "b"
        }
    }
})
