package cc.aaro.gar.router

import cc.aaro.gar.router.engine.Completion
import cc.aaro.gar.router.engine.CompletionEngine
import cc.aaro.gar.router.engine.CompletionRequest
import cc.aaro.gar.router.engine.StopReason
import cc.aaro.gar.router.format.FunctionGemmaFormat
import cc.aaro.gar.router.format.GbnfRegex
import cc.aaro.gar.router.format.HermesFormat
import cc.aaro.gar.router.format.Json
import cc.aaro.gar.router.format.ParsedOutput
import cc.aaro.gar.router.format.PromptFormat
import cc.aaro.gar.router.format.currentTime
import cc.aaro.gar.router.format.flashlight
import cc.aaro.gar.router.format.note
import cc.aaro.gar.router.format.testTool
import cc.aaro.gar.router.format.testTools
import cc.aaro.gar.router.format.text
import cc.aaro.gar.router.format.timerSet
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** Answers each request with the next scripted step. */
private class FakeEngine(vararg steps: suspend (CompletionRequest) -> Completion) : CompletionEngine {
    private val steps = steps.toMutableList()
    val requests = mutableListOf<CompletionRequest>()
    val warmUps = mutableListOf<String>()
    var failWarmUp = false

    override suspend fun complete(request: CompletionRequest): Completion {
        requests += request
        return steps.removeAt(0)(request)
    }

    override suspend fun warmUp(prefix: String) {
        warmUps += prefix
        if (failWarmUp) throw IllegalStateException("no model")
    }

    override suspend fun close() {
    }
}

private fun reply(text: String, stop: StopReason = StopReason.STOP_STRING): suspend (CompletionRequest) -> Completion =
    { Completion(text, 120, 100, 20, 5, 50, stop) }

private fun hermesCall(name: String, args: String = "{}") =
    reply("<tool_call>\n{\"name\": \"$name\", \"arguments\": $args}\n</tool_call>")

/** Counts static prefix renderings. */
private class CountingFormat(private val delegate: PromptFormat) : PromptFormat by delegate {
    var prefixes = 0
    override fun staticPrefix(tools: List<ToolSpec>): String = delegate.staticPrefix(tools).also { prefixes++ }
}

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
class LlmRouterTest : StringSpec({
    "builds the request from the normalized utterance and returns the call" {
        val engine = FakeEngine(hermesCall("timer_set", """{"duration": "ten minutes", "name": "pasta"}"""))
        val format = HermesFormat()
        val router = LlmRouter(engine, format, maxTokens = 64)
        val output = router.route("  Set a PASTA timer, for ten minutes!", testTools)

        output shouldBe RouterOutput.Call(
            tool = timerSet,
            rawSlots = mapOf("duration" to "ten minutes", "name" to "pasta"),
            missingRequired = listOf(),
            utterance = "set a pasta timer for ten minutes",
        )
        val request = engine.requests.single()
        request.prefix shouldBe format.staticPrefix(testTools)
        request.suffix shouldBe format.suffix("set a pasta timer for ten minutes")
        request.grammar shouldBe format.grammar(testTools, "set a pasta timer for ten minutes")
        request.maxTokens shouldBe 64
        request.stopStrings shouldBe listOf("</tool_call>")
    }

    "works the same with FunctionGemma" {
        val engine = FakeEngine(reply("<start_function_call>call:flashlight{state:<escape>on<escape>}" +
            "<end_function_call>"))
        val router = LlmRouter(engine, FunctionGemmaFormat())
        router.route("turn on the torch", testTools) shouldBe
            RouterOutput.Call(flashlight, mapOf("state" to "on"), listOf(), "turn on the torch")
        engine.requests.single().stopStrings shouldBe listOf("<end_function_call>")
    }

    "slot values are normalized and must be word spans of the utterance" {
        val engine = FakeEngine(
            hermesCall("timer_set", """{"duration": "Ten Minutes.", "name": "spaghetti"}"""),
            hermesCall("timer_set", """{"duration": "10 minutes"}"""),
            hermesCall("timer_set", """{"duration": "minutes ten"}"""),
            hermesCall("note", """{"c": "set a", "a": "", "b": "   "}"""),
        )
        val router = LlmRouter(engine, HermesFormat())
        val utterance = "set a pasta timer for ten minutes"
        router.route(utterance, testTools) shouldBe
            RouterOutput.Call(timerSet, mapOf("duration" to "ten minutes"), listOf(), utterance)
        router.route(utterance, testTools) shouldBe
            RouterOutput.Call(timerSet, mapOf(), listOf("duration"), utterance)
        router.route(utterance, testTools) shouldBe
            RouterOutput.Call(timerSet, mapOf(), listOf("duration"), utterance)
        router.route(utterance, testTools) shouldBe
            RouterOutput.Call(note, mapOf("c" to "set a"), listOf(), utterance)
    }

    "slot values are compared with the utterance's words after the same normalization" {
        val engine = FakeEngine(
            hermesCall("timer_set", """{"duration": "7:15"}"""),
            hermesCall("note", """{"c": "What’s On"}"""),
            hermesCall("note", """{"c": "TRÈS BIEN"}"""),
            hermesCall("note", """{"c": "1,000"}"""),
            hermesCall("note", """{"c": "1,000"}"""),
        )
        val router = LlmRouter(engine, HermesFormat())
        router.route("set an alarm for 7:15", testTools) shouldBe
            RouterOutput.Call(timerSet, mapOf("duration" to "7:15"), listOf(), "set an alarm for 7:15")
        router.route("what’s on", testTools) shouldBe
            RouterOutput.Call(note, mapOf("c" to "what's on"), listOf(), "what's on")
        router.route("add a note Très Bien", testTools) shouldBe
            RouterOutput.Call(note, mapOf("c" to "très bien"), listOf(), "add a note très bien")
        router.route("one thousand", testTools) shouldBe
            RouterOutput.Call(note, mapOf(), listOf(), "one thousand")
        router.route("pay 1,000", testTools) shouldBe
            RouterOutput.Call(note, mapOf("c" to "1,000"), listOf(), "pay 1,000")
    }

    "a typed international number keeps its +, in the prompt and in the slot" {
        val engine = FakeEngine(hermesCall("note", """{"c": "+44 7700 900123"}"""))
        val router = LlmRouter(engine, HermesFormat())
        router.route("Text +44 7700 900123", testTools) shouldBe
            RouterOutput.Call(note, mapOf("c" to "+44 7700 900123"), listOf(), "text +44 7700 900123")
        engine.requests.single().suffix shouldBe HermesFormat().suffix("text +44 7700 900123")
    }

    "every span the grammar allows passes validation" {
        for (utterance in listOf(
            "note rock ʼnʼ roll", "What’s up, it‘s 7:15!", "ʼtwas a cafe\u0301 night", "add 1,000 m² ½", "a ' b",
            "text +44 7700 900123, 5+3",
        )) {
            val normalized = Utterances.normalize(utterance)
            val words = Utterances.words(normalized)
            val spans = words.indices.flatMap { start ->
                (start + 1..words.size).map { end -> words.subList(start, end).joinToString(" ") }
            }
            val outputs = spans.map {
                "<tool_call>\n{\"name\": \"note\", \"arguments\": {\"c\": ${Json.quote(it)}}}\n</tool_call>"
            }
            val grammar = GbnfRegex.compile(HermesFormat().grammar(testTools, normalized))
            val router = LlmRouter(FakeEngine(*outputs.map { reply(it) }.toTypedArray()), HermesFormat())
            for ((span, output) in spans.zip(outputs)) {
                withClue("$utterance: $span") {
                    grammar.matches(output) shouldBe true
                    (router.route(utterance, testTools) as RouterOutput.Call).rawSlots shouldBe mapOf("c" to span)
                }
            }
        }
    }

    "slots keep the tool's order, not the model's" {
        val sms = testTool("sms_send", text("to", required = true), text("message", required = true))
        val engine = FakeEngine(
            hermesCall("timer_set", """{"name": "pasta", "duration": "ten minutes"}"""),
            hermesCall("sms_send", """{"message": "nothing", "to": "nobody"}"""),
        )
        val router = LlmRouter(engine, HermesFormat())
        val timer = router.route("set a pasta timer for ten minutes", testTools)
        timer.shouldBeInstanceOf<RouterOutput.Call>().rawSlots.keys.toList() shouldBe listOf("duration", "name")
        val text = router.route("text mom", listOf(sms))
        text.shouldBeInstanceOf<RouterOutput.Call>().missingRequired shouldBe listOf("to", "message")
    }

    "functiongemma values are the raw text between escapes, validated the same way" {
        val engine = FakeEngine(
            reply("<start_function_call>call:timer_set{duration:<escape>Ten Minutes<escape>," +
                "name:<escape>spaghetti<escape>}<end_function_call>"),
            reply("<start_function_call>call:flashlight{state:<escape> on <escape>}<end_function_call>"),
            reply("<start_function_call>call:flashlight{state:<escape>On<escape>}<end_function_call>"),
            reply("<start_function_call>call:timer_set", StopReason.MAX_TOKENS),
        )
        val router = LlmRouter(engine, FunctionGemmaFormat())
        router.route("set a pasta timer for ten minutes", testTools) shouldBe RouterOutput.Call(
            timerSet, mapOf("duration" to "ten minutes"), listOf(), "set a pasta timer for ten minutes",
        )
        router.route("lights on", testTools) shouldBe
            RouterOutput.Call(flashlight, mapOf("state" to "on"), listOf(), "lights on")
        router.route("lights on", testTools) shouldBe
            RouterOutput.Call(flashlight, mapOf(), listOf("state"), "lights on")
        // cut off right after the name, as at the token limit
        router.route("set a timer", testTools) shouldBe
            RouterOutput.Failed("missing '{' after function name (output hit the 96 token limit)")
    }

    "enum values must be one of the declared values" {
        val engine = FakeEngine(
            hermesCall("flashlight", """{"state": "dim"}"""),
            hermesCall("flashlight", """{"state": " off "}"""),
            hermesCall("flashlight", """{"state": "ON"}"""),
        )
        val router = LlmRouter(engine, HermesFormat())
        router.route("lights", testTools) shouldBe RouterOutput.Call(flashlight, mapOf(), listOf("state"), "lights")
        router.route("lights", testTools) shouldBe
            RouterOutput.Call(flashlight, mapOf("state" to "off"), listOf(), "lights")
        router.route("lights", testTools) shouldBe RouterOutput.Call(flashlight, mapOf(), listOf("state"), "lights")
    }

    "no match from the model, plain text and empty utterances" {
        val engine = FakeEngine(hermesCall("no_match"), reply("Paris is the capital of France."))
        val router = LlmRouter(engine, HermesFormat())
        router.route("what's the capital of france", testTools) shouldBe RouterOutput.NoMatch
        router.route("what's the capital of france", testTools) shouldBe RouterOutput.NoMatch
        router.route("", testTools) shouldBe RouterOutput.NoMatch
        router.route(" ?! ...", testTools) shouldBe RouterOutput.NoMatch
        router.route("what time is it", listOf()) shouldBe RouterOutput.NoMatch
        engine.requests.size shouldBe 2 // empty utterances and empty tool sets never reach the engine
    }

    "malformed output fails, and says when the token limit was hit" {
        val engine = FakeEngine(
            reply("<tool_call>\n{\"name\": \"set_alarm\", \"arguments\": {}}\n</tool_call>"),
            reply("<tool_call>\n{\"name\": \"timer_set\", \"arguments\": {\"duration\": \"ten", StopReason.MAX_TOKENS),
            reply(""),
        )
        val router = LlmRouter(engine, HermesFormat(), maxTokens = 12)
        (router.route("set an alarm", testTools) as RouterOutput.Failed).reason shouldContain "unknown tool"
        (router.route("set a timer", testTools) as RouterOutput.Failed).reason shouldContain "12 token limit"
        router.route("set a timer", testTools).shouldBeInstanceOf<RouterOutput.Failed>()
    }

    "engine errors and cancelled generations fail without throwing" {
        val error = IllegalStateException("native crash")
        val engine = FakeEngine({ throw error }, reply("", StopReason.CANCELLED))
        val router = LlmRouter(engine, HermesFormat())
        val failed = router.route("what time is it", testTools)
        failed.shouldBeInstanceOf<RouterOutput.Failed>()
        failed.cause shouldBe error
        failed.reason shouldContain "native crash"
        (router.route("what time is it", testTools) as RouterOutput.Failed).reason shouldContain "cancelled"
    }

    "times out after the configured time" {
        runTest {
            val engine = FakeEngine({ delay(60_000); throw AssertionError("not reached") }, { awaitCancellation() })
            val router = LlmRouter(engine, HermesFormat())
            val start = testScheduler.currentTime
            (router.route("what time is it", testTools) as RouterOutput.Failed).reason shouldContain "timed out"
            testScheduler.currentTime - start shouldBe 20_000L

            val quick = LlmRouter(engine, HermesFormat(), timeoutMillis = 500)
            (quick.route("what time is it", testTools) as RouterOutput.Failed).reason shouldContain "500 ms"
        }
    }

    "cancellation of the caller propagates" {
        runTest {
            val started = CompletableDeferred<Unit>()
            val engine = FakeEngine({ started.complete(Unit); awaitCancellation() })
            val router = LlmRouter(engine, HermesFormat())
            val job = async { router.route("what time is it", testTools) }
            started.await()
            job.cancel()
            yield()
            job.isCancelled shouldBe true
            shouldThrow<CancellationException> { job.await() }
        }
    }

    "route and warmUp never return to a cancelled caller" {
        // a cancelled job ends cancelled whatever its body returns, so check that the body stops
        runTest {
            val started = CompletableDeferred<Unit>()
            val engine = FakeEngine({ started.complete(Unit); awaitCancellation() })
            var result: RouterOutput? = null
            val job = launch { result = LlmRouter(engine, HermesFormat()).route("what time is it", testTools) }
            started.await()
            job.cancelAndJoin()
            result shouldBe null

            val warming = CompletableDeferred<Unit>()
            val slowWarmUp = object : CompletionEngine by engine {
                override suspend fun warmUp(prefix: String) {
                    warming.complete(Unit)
                    awaitCancellation()
                }
            }
            var warmedUp = false
            val warmUp = launch {
                LlmRouter(slowWarmUp, HermesFormat()).warmUp(testTools)
                warmedUp = true
            }
            warming.await()
            warmUp.cancelAndJoin()
            warmedUp shouldBe false
        }
    }

    "the engine's own cancellation is a failure, not a cancellation of the caller" {
        runTest {
            val engine = FakeEngine(
                { throw CancellationException("engine closed") },
                { withTimeout(10) { awaitCancellation() } }, // an inner timeout of the engine
            )
            val router = LlmRouter(engine, HermesFormat())
            val closed = router.route("hi", testTools)
            closed.shouldBeInstanceOf<RouterOutput.Failed>()
            closed.reason shouldContain "engine closed"
            router.route("hi", testTools).shouldBeInstanceOf<RouterOutput.Failed>()

            val failingWarmUp = object : CompletionEngine by engine {
                override suspend fun warmUp(prefix: String) = throw CancellationException("closed")
            }
            LlmRouter(failingWarmUp, HermesFormat()).warmUp(testTools) // no exception
        }
    }

    "warmUp evaluates the static prefix and swallows errors" {
        val engine = FakeEngine()
        val router = LlmRouter(engine, HermesFormat())
        router.warmUp(testTools)
        engine.warmUps shouldBe listOf(HermesFormat().staticPrefix(testTools))
        engine.failWarmUp = true
        router.warmUp(testTools) // no exception
    }

    "a failed warmUp leaves the router working, with the prefix cached" {
        val engine = FakeEngine(hermesCall("current_time"))
        engine.failWarmUp = true
        val format = CountingFormat(HermesFormat())
        val router = LlmRouter(engine, format)
        router.warmUp(testTools)
        router.route("what time is it", testTools) shouldBe
            RouterOutput.Call(currentTime, mapOf(), listOf(), "what time is it")
        format.prefixes shouldBe 1
    }

    "a broken format fails the route without throwing, and a failed prefix isn't cached" {
        val bug = IllegalStateException("bad grammar")
        val badGrammar = object : PromptFormat by HermesFormat() {
            override fun grammar(tools: List<ToolSpec>, utterance: String): String = throw bug
        }
        val unused = FakeEngine()
        val failed = LlmRouter(unused, badGrammar).route("what time is it", testTools)
        failed.shouldBeInstanceOf<RouterOutput.Failed>()
        failed.cause shouldBe bug
        failed.reason shouldContain "bad grammar"
        unused.requests shouldBe listOf()

        var prefixes = 0
        val flakyPrefix = object : PromptFormat by HermesFormat() {
            override fun staticPrefix(tools: List<ToolSpec>): String {
                prefixes++
                if (prefixes == 1) throw IllegalStateException("first prefix")
                return HermesFormat().staticPrefix(tools)
            }
        }
        val engine = FakeEngine(hermesCall("current_time"))
        val router = LlmRouter(engine, flakyPrefix)
        router.route("what time is it", testTools).shouldBeInstanceOf<RouterOutput.Failed>()
        router.route("what time is it", testTools) shouldBe
            RouterOutput.Call(currentTime, mapOf(), listOf(), "what time is it")
        prefixes shouldBe 2
        engine.requests.single().prefix shouldBe HermesFormat().staticPrefix(testTools)
    }

    "the stop reason and the parse result are checked in order" {
        val currentTimeCall = "<tool_call>\n{\"name\": \"current_time\", \"arguments\": {}}"
        val filtered = mutableListOf<RouterOutput.Call>()
        val engine = FakeEngine(
            reply(currentTimeCall, StopReason.MAX_TOKENS),
            reply("$currentTimeCall\n</tool_call>", StopReason.CANCELLED),
            reply("<tool_call>\n{\"name\": \"set_alarm\", \"arguments\": {}}\n</tool_call>",
                StopReason.END_OF_GENERATION),
            reply(""),
        )
        val router = LlmRouter(engine, HermesFormat(), maxTokens = 12, filter = { filtered += it; CallVerdict.Keep })
        // a call cut off right before its closing tag still counts
        router.route("what time is it", testTools) shouldBe
            RouterOutput.Call(currentTime, mapOf(), listOf(), "what time is it")
        // a cancelled generation fails, even when its text is a valid call
        router.route("what time is it", testTools) shouldBe RouterOutput.Failed("generation cancelled")
        // the token limit is only mentioned when it was hit
        router.route("set an alarm", testTools) shouldBe RouterOutput.Failed("unknown tool 'set_alarm'")
        router.route("what time is it", testTools).shouldBeInstanceOf<RouterOutput.Failed>()
        filtered.size shouldBe 1 // the filter only sees calls
    }

    "the prefix cache tells tool sets apart by order and content" {
        val engine = FakeEngine(hermesCall("no_match"), hermesCall("no_match"), hermesCall("no_match"))
        val format = CountingFormat(HermesFormat())
        val router = LlmRouter(engine, format)
        router.route("hello", testTools)
        router.route("hello", testTools.reversed())
        format.prefixes shouldBe 2
        engine.requests.last().prefix shouldBe HermesFormat().staticPrefix(testTools.reversed())
        router.route("hello", testTools.map { if (it == currentTime) it.copy(description = "x") else it })
        format.prefixes shouldBe 3
    }

    "the static prefix is rendered once per tool set" {
        val engine = FakeEngine(hermesCall("no_match"), hermesCall("no_match"), hermesCall("no_match"))
        val format = CountingFormat(HermesFormat())
        val router = LlmRouter(engine, format)
        router.warmUp(testTools)
        router.route("hello", testTools)
        router.route("hello", testTools.toList())
        format.prefixes shouldBe 1
        router.route("hello", listOf(currentTime))
        format.prefixes shouldBe 2
        engine.requests.last().prefix shouldBe HermesFormat().staticPrefix(listOf(currentTime))
    }

    "the filter sees each validated call and turns the calls it drops into Dropped" {
        val engine = FakeEngine(
            hermesCall("timer_set", """{"duration": "ten minutes", "name": "spaghetti"}"""),
            hermesCall("current_time"),
            hermesCall("no_match"),
        )
        val seen = mutableListOf<RouterOutput.Call>()
        val router = LlmRouter(engine, HermesFormat(), filter = { call ->
            seen += call
            if (call.tool == currentTime) CallVerdict.Drop("no time word") else CallVerdict.Keep
        })
        val timer = RouterOutput.Call(timerSet, mapOf("duration" to "ten minutes"), listOf(), "set a timer for ten minutes")
        router.route("set a timer for ten minutes", testTools) shouldBe timer
        val dropped = RouterOutput.Call(currentTime, mapOf(), listOf(), "good morning")
        router.route("good morning", testTools) shouldBe RouterOutput.Dropped(dropped, "no time word")
        router.route("hello", testTools) shouldBe RouterOutput.NoMatch
        seen shouldBe listOf(timer, dropped) // validated (no "spaghetti"), never for no_match
    }

    "a filter that throws keeps the call" {
        val engine = FakeEngine(hermesCall("current_time"))
        val router = LlmRouter(engine, HermesFormat(), filter = { throw IllegalStateException("bug") })
        router.route("what time is it", testTools) shouldBe
            RouterOutput.Call(currentTime, mapOf(), listOf(), "what time is it")
    }

    "allOf runs the filters in order and the first drop decides" {
        val calls = mutableListOf<String>()
        fun filter(name: String, verdict: CallVerdict) = CallFilter { calls += name; verdict }
        val call = RouterOutput.Call(currentTime, mapOf(), listOf(), "hi")
        CallFilter.allOf(filter("a", CallVerdict.Keep), filter("b", CallVerdict.Drop("b")),
            filter("c", CallVerdict.Drop("c"))).check(call) shouldBe CallVerdict.Drop("b")
        calls shouldBe listOf("a", "b")
        CallFilter.allOf(filter("d", CallVerdict.Keep)).check(call) shouldBe CallVerdict.Keep
        CallFilter.allOf().check(call) shouldBe CallVerdict.Keep
    }

    "a filter that throws inside allOf is skipped, and the filters after it still run" {
        // the app runs its privacy guard for song_identify last
        val call = RouterOutput.Call(currentTime, mapOf(), listOf(), "what time is it")
        val broken = CallFilter { throw IllegalStateException("bug") }
        val dropping = CallFilter { CallVerdict.Drop("b") }
        CallFilter.allOf(broken, dropping).check(call) shouldBe CallVerdict.Drop("b")
        CallFilter.allOf(CallFilter { CallVerdict.Drop("a") }, broken).check(call) shouldBe CallVerdict.Drop("a")
        CallFilter.allOf(broken).check(call) shouldBe CallVerdict.Keep
        val router = LlmRouter(FakeEngine(hermesCall("current_time")), HermesFormat(),
            filter = CallFilter.allOf(broken, dropping))
        router.route("what time is it", testTools) shouldBe RouterOutput.Dropped(call, "b")
    }

    "the keyword filter drops a call whose utterance has none of the tool's keywords" {
        val timed = currentTime.copy(keywords = listOf("time"))
        val engine = FakeEngine(hermesCall("current_time"), hermesCall("current_time"))
        val router = LlmRouter(engine, HermesFormat(), filter = KeywordFilter)
        val dropped = router.route("Good morning!", listOf(timed))
        dropped.shouldBeInstanceOf<RouterOutput.Dropped>()
        dropped.reason shouldContain "current_time"
        dropped.reason shouldContain "'good morning'"
        router.route("what time is it", listOf(timed)) shouldBe
            RouterOutput.Call(timed, mapOf(), listOf(), "what time is it")
    }

    "parse results map onto router outputs" {
        // ParsedOutput.Call keeps raw values; the router validates them
        val format = object : PromptFormat by HermesFormat() {
            override fun parse(raw: String, tools: List<ToolSpec>) =
                ParsedOutput.Call(timerSet, mapOf("duration" to "ten minutes", "extra" to "x"))
        }
        val router = LlmRouter(FakeEngine(reply("anything")), format)
        router.route("ten minutes", testTools) shouldBe
            RouterOutput.Call(timerSet, mapOf("duration" to "ten minutes"), listOf(), "ten minutes")
    }
})
