package cc.aaro.gar.llm

import cc.aaro.gar.router.engine.Completion
import cc.aaro.gar.router.engine.CompletionRequest
import cc.aaro.gar.router.engine.StopReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

/**
 * Runs [LlamaEngine] on the JVM with a host build of the JNI library. Skipped unless
 * GAR_HOST_JNI_LIB (from host-test/build-host-jni.sh) and GAR_TEST_MODEL (Qwen3-0.6B-Q4_K_M.gguf)
 * are set; GAR_TEST_MODEL_FG (functiongemma-270m-it-q8_0.gguf) adds the FunctionGemma checks.
 *
 * The resources hold prompts rendered by llama-server's /apply-template from the Step 1 eval tool
 * list, its grammars, and llama-server's greedy outputs for the same prompts and grammars
 * (llama.cpp v0.7.0, CPU), which the JNI layer must reproduce. For those, llama-server evaluated
 * the prefix on its own first and then only the suffix, as [LlamaEngine] does: splitting the
 * prompt into batches differently changes the logits slightly, which flips borderline
 * FunctionGemma outputs.
 */
class LlamaEngineHostTest : FunSpec({
    val library = System.getenv("GAR_HOST_JNI_LIB").orEmpty()
    val qwenModel = System.getenv("GAR_TEST_MODEL").orEmpty()
    val fgModel = System.getenv("GAR_TEST_MODEL_FG").orEmpty()
    val enabled = library.isNotEmpty() && qwenModel.isNotEmpty()
    val fgEnabled = enabled && fgModel.isNotEmpty()

    val qwen = TestPrompts("qwen3") { "<|im_start|>user\n$it<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n" }
    val fg = TestPrompts("functiongemma") { "<start_of_turn>user\n$it<end_of_turn>\n<start_of_turn>model\n" }
    val utterance = "wake me at seven fifteen"
    lateinit var engine: LlamaEngine
    val cacheDir = Files.createTempDirectory("gar-llama-test").toFile()

    fun request(
        utterance: String,
        prompts: TestPrompts = qwen,
        prefix: String = prompts.prefix,
        grammar: String? = prompts.grammar,
        stopStrings: List<String> = listOf("</tool_call>"),
        maxTokens: Int = 96,
    ) = CompletionRequest(prefix, prompts.suffix(utterance), grammar, maxTokens, stopStrings)

    // a grammar that never completes, so generation only ends at maxTokens (about a minute)
    val endless = request("tell me a story", grammar = "root ::= [a-z ,.] root", stopStrings = listOf(), maxTokens = 2000)

    beforeSpec {
        if (enabled) {
            System.setProperty(LlamaNative.LIBRARY_PATH_PROPERTY, library)
            val start = System.nanoTime()
            engine = LlamaEngine.load(File(qwenModel))
            log("load ${(System.nanoTime() - start) / 1_000_000} ms, llama.cpp ${engine.llamaVersion}, ${engine.systemInfo}")
        }
    }

    afterSpec {
        if (enabled) {
            engine.close()
        }
        cacheDir.deleteRecursively()
    }

    test("counts prefix tokens like llama-server").config(enabled = enabled) {
        engine.countTokens(qwen.prefix, addSpecial = true) shouldBe qwen.prefixTokens
        engine.countTokens("<|im_start|>user\n", addSpecial = false) shouldBe 3
    }

    test("grammar output is the tool call llama-server produced, reusing the prefix").config(enabled = enabled) {
        val results = qwen.expected.keys.map { utt ->
            val c = engine.complete(request(utt))
            log("qwen3 grammar '$utt': ${c.timings()} ${c.text.quoted()}")
            c.stopReason shouldBe StopReason.STOP_STRING
            c.text shouldBe qwen.expected[utt]
            hermesToolName(c.text) shouldNotBe null
            c
        }
        val first = results.first()
        first.cachedTokens shouldBe 0
        first.promptTokens shouldBeGreaterThan qwen.prefixTokens
        for (c in results.drop(1)) {
            c.cachedTokens shouldBe qwen.prefixTokens
            c.prefillMillis shouldBeLessThan first.prefillMillis / 4
        }
    }

    test("without a grammar the model still ends its turn").config(enabled = enabled) {
        val withStop = engine.complete(request(utterance, grammar = null))
        log("qwen3 no grammar, stop string: ${withStop.timings()} ${withStop.text.quoted()}")
        withStop.stopReason shouldBe StopReason.STOP_STRING
        hermesToolName(withStop.text) shouldBe "set_alarm"

        val toEnd = engine.complete(request(utterance, grammar = null, stopStrings = listOf()))
        log("qwen3 no grammar, no stop string: ${toEnd.timings()} ${toEnd.text.quoted()}")
        toEnd.stopReason shouldBe StopReason.END_OF_GENERATION
        toEnd.text shouldStartWith withStop.text
        toEnd.generatedTokens shouldBeGreaterThan withStop.generatedTokens
    }

    test("stops after a stop string spanning several tokens").config(enabled = enabled) {
        val c = engine.complete(request(utterance, stopStrings = listOf("\"arguments\"", "never produced")))
        log("qwen3 stop string: ${c.timings()} ${c.text.quoted()}")
        c.stopReason shouldBe StopReason.STOP_STRING
        c.text shouldBe "<tool_call>\n{\"name\": \"set_alarm\", \"arguments\""
    }

    test("stops at maxTokens").config(enabled = enabled) {
        val c = engine.complete(request(utterance, maxTokens = 3))
        log("qwen3 maxTokens 3: ${c.timings()} ${c.text.quoted()}")
        c.stopReason shouldBe StopReason.MAX_TOKENS
        c.generatedTokens shouldBe 3
        c.text shouldStartWith "<tool_call>\n"
    }

    test("cancel() stops a long generation and keeps the prefix").config(enabled = enabled) {
        coroutineScope {
            val story = async {
                engine.complete(endless)
            }
            delay(1500)
            val cancelledAt = System.nanoTime()
            engine.cancel()
            val c = story.await()
            val latencyMs = (System.nanoTime() - cancelledAt) / 1_000_000
            log("qwen3 cancel: returned $latencyMs ms after cancel(), ${c.timings()}")
            c.stopReason shouldBe StopReason.CANCELLED
            c.generatedTokens shouldBeInRange 1..1999
            latencyMs shouldBeLessThan 1000L
        }
        val next = engine.complete(request(utterance))
        next.cachedTokens shouldBe qwen.prefixTokens
        next.text shouldBe qwen.expected[utterance]
    }

    test("cancel() during the prefill drops the half evaluated prefix").config(enabled = enabled) {
        val longPrefix = "<|im_start|>system\n" + "The quick brown fox jumps over the lazy dog. ".repeat(250) + "<|im_end|>\n"
        coroutineScope {
            val call = async { engine.complete(request(utterance, prefix = longPrefix)) }
            delay(300)
            engine.cancel()
            val c = call.await()
            log("qwen3 cancel in prefill: ${c.timings()}")
            c.stopReason shouldBe StopReason.CANCELLED
            c.generatedTokens shouldBe 0
        }
        val next = engine.complete(request(utterance))
        next.cachedTokens shouldBe 0
        next.text shouldBe qwen.expected[utterance]
    }

    test("cancelling the caller's coroutine aborts the call").config(enabled = enabled) {
        val result = withTimeoutOrNull(1000) {
            engine.complete(endless)
        }
        result shouldBe null
        val start = System.nanoTime()
        val next = engine.complete(request(utterance))
        log("qwen3 call after a cancelled coroutine: ${(System.nanoTime() - start) / 1_000_000} ms wall, ${next.timings()}")
        next.cachedTokens shouldBe qwen.prefixTokens
        next.text shouldBe qwen.expected[utterance]
    }

    test("concurrent calls run one at a time, a queued call can be cancelled").config(enabled = enabled) {
        val utterances = qwen.expected.keys.toList()
        coroutineScope {
            val story = async { engine.complete(endless) }
            delay(500)
            val queued = async { engine.complete(request(utterances[0])) }
            val others = utterances.drop(1).map { utt -> async { utt to engine.complete(request(utt)) } }
            delay(200)
            queued.cancel()
            story.cancel()
            for ((utt, c) in others.map { it.await() }) {
                log("qwen3 concurrent '$utt': ${c.timings()}")
                c.stopReason shouldBe StopReason.STOP_STRING
                c.cachedTokens shouldBe qwen.prefixTokens
                c.text shouldBe qwen.expected[utt]
            }
            queued.isCancelled shouldBe true
            story.isCancelled shouldBe true
        }
    }

    test("a changed prefix is evaluated again, warmUp caches it").config(enabled = enabled) {
        // only the last tool's description changes, near the end of the prefix
        val changed = qwen.prefix.replace("Calculates the result of a math expression.", "Calculates a math expression.")
        changed shouldNotBe qwen.prefix

        val c = engine.complete(request(utterance, prefix = changed))
        log("qwen3 changed prefix: ${c.timings()}")
        c.cachedTokens shouldBe 0
        c.promptTokens shouldBeGreaterThan qwen.prefixTokens

        engine.warmUp(qwen.prefix)
        val warm = engine.complete(request(utterance))
        log("qwen3 after warmUp: ${warm.timings()}")
        warm.cachedTokens shouldBe qwen.prefixTokens
        warm.text shouldBe qwen.expected[utterance]
    }

    test("errors are reported and leave the engine usable").config(enabled = enabled) {
        shouldThrow<LlamaException> { engine.complete(request(utterance, grammar = "root ::= (")) }
        shouldThrow<LlamaException> { engine.complete(request(utterance, prefix = "x ".repeat(5000))) }
        val c = engine.complete(request(utterance))
        c.text shouldBe qwen.expected[utterance]

        shouldThrow<LlamaException> { LlamaEngine.load(File(cacheDir, "missing.gguf")) }
        val notGguf = File(cacheDir, "not-a-model.gguf").apply { writeText(qwen.prefix) }
        val e = shouldThrow<LlamaException> { LlamaEngine.load(notGguf) }
        log("load error: ${e.message}")
    }

    test("the prefix state file lets a new engine skip the prefill").config(enabled = enabled) {
        val first = LlamaEngine.load(File(qwenModel), prefixCacheDir = cacheDir)
        val cold = first.complete(request(utterance))
        // runs after the state file was written, since calls run in order
        first.countTokens("")
        first.close()
        val files = cacheDir.listFiles { f -> f.name.endsWith(".kvstate") }!!.toList()
        files shouldHaveSize 1
        log("state file ${files[0].length() / 1024} KiB")

        val second = LlamaEngine.load(File(qwenModel), prefixCacheDir = cacheDir)
        val restored = second.complete(request(utterance))
        second.close()
        log("qwen3 cold: ${cold.timings()}; restored from file: ${restored.timings()}")
        cold.cachedTokens shouldBe 0
        restored.cachedTokens shouldBe qwen.prefixTokens
        restored.text shouldBe cold.text
        restored.prefillMillis shouldBeLessThan cold.prefillMillis / 4
    }

    test("FunctionGemma output matches llama-server, cached or not").config(enabled = fgEnabled) {
        val gemma = LlamaEngine.load(File(fgModel))
        try {
            gemma.countTokens(fg.prefix, addSpecial = true) shouldBe fg.prefixTokens
            val stop = listOf("<end_function_call>")
            for ((utt, expected) in fg.expected) {
                val c = gemma.complete(request(utt, prompts = fg, stopStrings = stop))
                log("functiongemma grammar '$utt': ${c.timings()} ${c.text.quoted()}")
                c.stopReason shouldBe StopReason.STOP_STRING
                c.text shouldBe expected
            }
            // after another prefix (with the same first tokens) the prefix is evaluated again, and
            // the output is the same as with the cached prefix (needs swa_full, and the same batches)
            gemma.complete(request(utterance, prompts = fg, prefix = "<start_of_turn>developer\nHi.<end_of_turn>\n", stopStrings = stop))
            val cold = gemma.complete(request(utterance, prompts = fg, stopStrings = stop))
            log("functiongemma re-evaluated prefix: ${cold.timings()} ${cold.text.quoted()}")
            cold.cachedTokens shouldBe 0
            cold.text shouldBe fg.expected[utterance]

            val free = gemma.complete(request(utterance, prompts = fg, grammar = null, stopStrings = stop))
            log("functiongemma no grammar: ${free.timings()} ${free.text.quoted()}")
            free.text shouldContain "<start_function_call>call:"
        } finally {
            gemma.close()
        }
    }

    test("cancelling load() aborts it").config(enabled = enabled) {
        withTimeoutOrNull(100) { LlamaEngine.load(File(qwenModel)) } shouldBe null
        // the abandoned load doesn't disturb the loaded engine
        engine.complete(request(utterance)).text shouldBe qwen.expected[utterance]
    }

    test("close() frees the engine once").config(enabled = enabled) {
        val other = LlamaEngine.load(File(qwenModel))
        other.close()
        other.close()
        shouldThrow<IllegalStateException> { other.complete(request(utterance)) }
    }
})

private class TestPrompts(dir: String, val suffix: (String) -> String) {
    val prefix = resource("$dir/prefix.txt")
    val grammar = resource("$dir/grammar.gbnf")
    private val reference = Json.parseToJsonElement(resource("$dir/llama_server_reference.json")).jsonObject
    val prefixTokens = reference["prefix_tokens"]!!.jsonPrimitive.content.toInt()
    val expected: Map<String, String> = reference["outputs"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun resource(path: String) =
        TestPrompts::class.java.getResource("/$path")!!.readText()
}

private val HERMES_CALL = Regex("""^<tool_call>\n(\{.*\})\n</tool_call>""", RegexOption.DOT_MATCHES_ALL)

/** The tool name of a Hermes tool call, or null if [text] doesn't start with a valid one. */
private fun hermesToolName(text: String): String? {
    val json = HERMES_CALL.find(text)?.groupValues?.get(1) ?: return null
    val call = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    call["arguments"]?.jsonObject ?: return null
    return call["name"]?.jsonPrimitive?.content
}

private fun Completion.timings() =
    "prompt $promptTokens (cached $cachedTokens) in $prefillMillis ms, $generatedTokens generated in " +
        "$generateMillis ms, $stopReason"

private fun String.quoted() = "\"" + replace("\n", "\\n") + "\""

private fun log(message: String) = println("GAR-HOST-TEST $message")
