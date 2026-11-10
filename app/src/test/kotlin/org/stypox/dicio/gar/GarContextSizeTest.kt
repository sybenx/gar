package org.stypox.dicio.gar

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.stypox.dicio.gar.model.GarContextSizes
import org.stypox.dicio.gar.model.GarModelManager
import org.stypox.dicio.gar.model.loadWithContextFor
import java.io.File
import java.nio.file.Files

private const val MODEL = "/models/qwen.gguf|123|456|HERMES"

private class FakeEngine(val contextSize: Int) {
    var closed = false
}

/**
 * One app start: loads like `GarModelManager.loadEngine`, for a prompt that needs [needed] tokens
 * of context (null if they can't be counted), with the sizes remembered in [file].
 */
private class StartFixture(file: File, private val needed: Int?) {
    val sizes = GarContextSizes(file)
    val loads = mutableListOf<Int>()
    val engines = mutableListOf<FakeEngine>()

    suspend fun load(): FakeEngine = loadWithContextFor(
        remembered = sizes.read(MODEL),
        load = { contextSize ->
            loads.add(contextSize)
            FakeEngine(contextSize).also { engines.add(it) }
        },
        contextNeeded = { needed },
        close = { it.closed = true },
        remember = { sizes.write(MODEL, it) },
    )
}

/** Runs [block] with a size file in a new folder, deleted afterwards. */
private inline fun withSizeFile(block: (File) -> Unit) {
    val dir = Files.createTempDirectory("gar-context").toFile()
    try {
        block(dir.resolve("gar-context-size"))
    } finally {
        dir.deleteRecursively()
    }
}

class GarContextSizeTest : StringSpec({
    "the model is loaded once per start, after the first start counted its prompt" {
        withSizeFile { file ->
            // the 26 tools need about 2270 tokens with Hermes, more than the smallest context
            val first = StartFixture(file, needed = 2816)
            val engine = first.load()
            first.loads shouldBe listOf(GarModelManager.MIN_CONTEXT_SIZE, 2816)
            first.engines[0].closed shouldBe true
            engine.contextSize shouldBe 2816
            engine.closed shouldBe false

            repeat(3) {
                val next = StartFixture(file, needed = 2816)
                next.load().contextSize shouldBe 2816
                next.loads shouldBe listOf(2816)
            }
        }
    }

    "a prompt that fits the smallest context loads once, also the first time" {
        withSizeFile { file ->
            val first = StartFixture(file, needed = GarModelManager.MIN_CONTEXT_SIZE)
            first.load()
            first.loads shouldBe listOf(GarModelManager.MIN_CONTEXT_SIZE)
            first.sizes.read(MODEL) shouldBe GarModelManager.MIN_CONTEXT_SIZE

            val next = StartFixture(file, needed = GarModelManager.MIN_CONTEXT_SIZE)
            next.load()
            next.loads shouldBe listOf(GarModelManager.MIN_CONTEXT_SIZE)
        }
    }

    "a longer prompt loads again with more context, a shorter one keeps the loaded engine" {
        withSizeFile { file ->
            GarContextSizes(file).write(MODEL, 2560)
            // e.g. an update added tools
            val longer = StartFixture(file, needed = 2816)
            longer.load().contextSize shouldBe 2816
            longer.loads shouldBe listOf(2560, 2816)
            longer.engines[0].closed shouldBe true

            // and one that removed some: this load stays, the next one is smaller
            val shorter = StartFixture(file, needed = 2304)
            shorter.load().contextSize shouldBe 2816
            shorter.loads shouldBe listOf(2816)
            shorter.sizes.read(MODEL) shouldBe 2304
            val next = StartFixture(file, needed = 2304)
            next.load()
            next.loads shouldBe listOf(2304)
        }
    }

    "a prompt that can't be counted keeps the engine and the remembered size" {
        withSizeFile { file ->
            val first = StartFixture(file, needed = null)
            first.load().closed shouldBe false
            first.loads shouldBe listOf(GarModelManager.MIN_CONTEXT_SIZE)
            first.sizes.read(MODEL).shouldBeNull()

            GarContextSizes(file).write(MODEL, 2816)
            val remembered = StartFixture(file, needed = null)
            remembered.load()
            remembered.loads shouldBe listOf(2816)
            remembered.sizes.read(MODEL) shouldBe 2816
        }
    }

    "a load cancelled while counting closes its engine" {
        val engines = mutableListOf<FakeEngine>()
        shouldThrow<CancellationException> {
            loadWithContextFor(
                remembered = null,
                load = { contextSize -> FakeEngine(contextSize).also { engines.add(it) } },
                contextNeeded = { throw CancellationException("closing the app") },
                close = { it.closed = true },
                remember = { throw AssertionError("nothing to remember") },
            )
        }
        engines.single().closed shouldBe true
    }

    "only the last model's size is remembered, and a broken file remembers nothing" {
        withSizeFile { file ->
            val sizes = GarContextSizes(file)
            sizes.read(MODEL).shouldBeNull()
            sizes.write(MODEL, 2816)
            sizes.read(MODEL) shouldBe 2816
            // the same file with another prompt family, or another model
            sizes.read(MODEL.replace("HERMES", "FUNCTION_GEMMA")).shouldBeNull()
            sizes.write("other", 2048)
            sizes.read("other") shouldBe 2048
            sizes.read(MODEL).shouldBeNull()

            for (contents in listOf("", MODEL, "$MODEL\n", "$MODEL\nmany\n", "$MODEL\n-5\n")) {
                file.writeText(contents)
                sizes.read(MODEL).shouldBeNull()
            }
            // a size that can't be written is counted again next time
            GarContextSizes(file.resolve("missing/dir")).write(MODEL, 2816)
        }
    }
})
