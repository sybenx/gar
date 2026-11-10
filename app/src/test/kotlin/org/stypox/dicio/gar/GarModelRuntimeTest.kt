package org.stypox.dicio.gar

import cc.aaro.gar.router.format.ModelFamily
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.stypox.dicio.gar.model.GarEngineHolder
import org.stypox.dicio.gar.model.GarLoadGuard
import org.stypox.dicio.gar.model.GarModelManager
import org.stypox.dicio.gar.model.ModelCrashedException
import org.stypox.dicio.gar.model.ModelSelection
import org.stypox.dicio.gar.model.canRoute
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

class GarModelRuntimeTest : StringSpec({
    "a model whose loads keep stopping the app is not loaded again until a retry" {
        val dir = Files.createTempDirectory("gar-guard").toFile()
        try {
            val marker = dir.resolve("gar-loading")
            val guard = GarLoadGuard(marker)

            // a normal load leaves nothing behind
            guard.begin("a")
            guard.end()
            marker.exists() shouldBe false

            // the process dies twice while loading "a" (no end())
            guard.begin("a")
            GarLoadGuard(marker).begin("a")
            GarLoadGuard(marker).unfinished("a") shouldBe 2
            shouldThrow<ModelCrashedException> { GarLoadGuard(marker).begin("a") }
            GarLoadGuard(marker).refuses("a") shouldBe true

            // another file (or the same path with new contents) is not blocked
            GarLoadGuard(marker).unfinished("b") shouldBe 0
            GarLoadGuard(marker).refuses("b") shouldBe false

            GarLoadGuard(marker).clear()
            GarLoadGuard(marker).begin("a")
            GarLoadGuard(marker).end()
            GarLoadGuard(marker).unfinished("a") shouldBe 0
            GarLoadGuard(marker).refuses("a") shouldBe false
        } finally {
            dir.deleteRecursively()
        }
    }

    "Dicio's matches aren't checked while the selected model can't load" {
        val dir = Files.createTempDirectory("gar-route").toFile()
        try {
            val selected = ModelSelection(dir.resolve("a.gguf"), ModelFamily.HERMES)
            val other = ModelSelection(dir.resolve("b.gguf"), ModelFamily.HERMES)
            val failed = GarEngineHolder.State.Failed(selected, ModelCrashedException(2))
            canRoute(GarEngineHolder.State.NotLoaded, selected) { false } shouldBe true
            canRoute(GarEngineHolder.State.Loaded(selected), selected) { false } shouldBe true
            // the guard's marker counts the load that runs now
            canRoute(GarEngineHolder.State.Loading(selected), selected) { true } shouldBe true
            canRoute(failed, selected) { false } shouldBe false
            // after a restart: the guard refuses before anything tries to load it
            canRoute(GarEngineHolder.State.NotLoaded, selected) { true } shouldBe false
            canRoute(GarEngineHolder.State.Loaded(other), selected) { true } shouldBe false
            // another model failed: this one may load
            val otherFailed = GarEngineHolder.State.Failed(other, failed.throwable)
            canRoute(otherFailed, selected) { false } shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }

    "a load that dies once is retried" {
        val dir = Files.createTempDirectory("gar-guard").toFile()
        try {
            val marker = dir.resolve("gar-loading")
            GarLoadGuard(marker).begin("a") // killed while loading, e.g. in the background
            GarLoadGuard(marker).begin("a")
            GarLoadGuard(marker).end()
            GarLoadGuard(marker).unfinished("a") shouldBe 0
        } finally {
            dir.deleteRecursively()
        }
    }

    "the context fits the prompt prefix, an utterance and the answer" {
        // the real catalog: ~1000 tokens (Hermes), ~770 (FunctionGemma)
        GarModelManager.contextSizeFor(998) shouldBe 2048
        GarModelManager.contextSizeFor(772) shouldBe 2048
        // 1700 + 256 + 96 = 2052 -> next multiple of 256
        GarModelManager.contextSizeFor(1700) shouldBe 2304
    }

    "imports that are too big for the phone are rejected" {
        val gb = 1L shl 30
        val mb = 1L shl 20
        GarModelManager.importProblem(400 * mb, 8 * gb, 10 * gb) shouldBe null
        GarModelManager.importProblem(2 * gb + 1, 8 * gb, 10 * gb) shouldBe
                GarModelManager.ImportProblem.TOO_LARGE
        GarModelManager.importProblem(400 * mb, 8 * gb, 420 * mb) shouldBe
                GarModelManager.ImportProblem.NO_SPACE
        // unknown total memory: only the free space counts
        GarModelManager.importProblem(5 * gb, 0, 10 * gb) shouldBe null
    }

    "a marker the guard can't read counts as no unfinished load" {
        val dir = Files.createTempDirectory("gar-guard").toFile()
        try {
            val marker = dir.resolve("gar-loading")
            marker.writeText("not a number")
            GarLoadGuard(marker).unfinished("a") shouldBe 0
            GarLoadGuard(marker).begin("a")
            marker.readText() shouldBe "1\na"

            // the marker is about the last file that began loading
            GarLoadGuard(marker).begin("a")
            GarLoadGuard(marker).begin("b")
            GarLoadGuard(marker).unfinished("a") shouldBe 0
            GarLoadGuard(marker).unfinished("b") shouldBe 1
        } finally {
            dir.deleteRecursively()
        }
    }

    "loading doesn't fail when the marker can't be written" {
        val dir = Files.createTempDirectory("gar-guard").toFile()
        try {
            val notADirectory = dir.resolve("file").apply { writeText("") }
            val guard = GarLoadGuard(notADirectory.resolve("gar-loading"))
            guard.begin("a")
            guard.unfinished("a") shouldBe 0
            guard.end()
        } finally {
            dir.deleteRecursively()
        }
    }

    "the number of unfinished loads allowed is configurable" {
        val dir = Files.createTempDirectory("gar-guard").toFile()
        try {
            val marker = dir.resolve("gar-loading")
            GarLoadGuard(marker, maxUnfinished = 1).begin("a")
            shouldThrow<ModelCrashedException> { GarLoadGuard(marker, maxUnfinished = 1).begin("a") }
                .message.shouldNotBeNull() shouldContain "1 times"
        } finally {
            dir.deleteRecursively()
        }
    }

    "a re-imported file is another model for the load guard" {
        val dir = Files.createTempDirectory("gar-route").toFile()
        try {
            val file = dir.resolve("custom.gguf").apply { writeText("GGUF one") }
            val old = ModelSelection(file, ModelFamily.HERMES)
            old.id shouldBe "${file.absolutePath}|8|${file.lastModified()}"
            // its loads stopped the app twice
            val guard = GarLoadGuard(dir.resolve("gar-loading"))
            guard.begin(old.id)
            guard.begin(old.id)

            file.writeText("GGUF longer")
            val new = ModelSelection(file, ModelFamily.HERMES)
            new shouldNotBe old
            new.id shouldNotBe old.id
            canRoute(GarEngineHolder.State.NotLoaded, old) { guard.refuses(old.id) } shouldBe false
            canRoute(GarEngineHolder.State.NotLoaded, new) { guard.refuses(new.id) } shouldBe true
            val failed = GarEngineHolder.State.Failed(old, ModelCrashedException(2))
            canRoute(failed, new) { guard.refuses(new.id) } shouldBe true

            // same length, other time: e.g. a file copied over with the same size
            file.setLastModified(file.lastModified() - 10_000) shouldBe true
            ModelSelection(file, ModelFamily.HERMES).id shouldNotBe new.id
        } finally {
            dir.deleteRecursively()
        }
    }

    "context sizes at the boundaries" {
        // prefix + 256 for the utterance + 96 for the answer, in steps of 256, at least 2048
        GarModelManager.contextSizeFor(0) shouldBe 2048
        GarModelManager.contextSizeFor(1696) shouldBe 2048
        GarModelManager.contextSizeFor(1697) shouldBe 2304
        // the 26 tools of Step 3 with Hermes
        GarModelManager.contextSizeFor(2270) shouldBe 2816
    }

    "import limits at the boundaries" {
        val gb = 1L shl 30
        val mb = 1L shl 20
        val huge = 1000 * gb
        GarModelManager.importProblem(8 * gb / 4, 8 * gb, huge) shouldBe null
        GarModelManager.importProblem(8 * gb / 4 + 1, 8 * gb, huge) shouldBe
            GarModelManager.ImportProblem.TOO_LARGE
        // 64 MB stay free
        GarModelManager.importProblem(gb - 64 * mb, 0, gb) shouldBe null
        GarModelManager.importProblem(gb - 64 * mb + 1, 0, gb) shouldBe
            GarModelManager.ImportProblem.NO_SPACE
        GarModelManager.importProblem(1, 0, 10 * mb) shouldBe GarModelManager.ImportProblem.NO_SPACE
        // too large for the memory, whatever the space
        GarModelManager.importProblem(3 * gb, 8 * gb, mb) shouldBe
            GarModelManager.ImportProblem.TOO_LARGE
    }

    "file headers are read across short reads" {
        val oneByteAtATime = object : InputStream() {
            private val bytes = ByteArrayInputStream("GGUF and more".toByteArray())
            override fun read(): Int = bytes.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                bytes.read(b, off, minOf(len, 1))
        }
        GarModelManager.readHeader(oneByteAtATime, 4).decodeToString() shouldBe "GGUF"
        GarModelManager.readHeader(ByteArrayInputStream(byteArrayOf()), 4).size shouldBe 0
        GarModelManager.isGguf(byteArrayOf()) shouldBe false
    }
})
