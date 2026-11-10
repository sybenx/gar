package cc.aaro.gar.router.eval

import cc.aaro.gar.router.CallFilter
import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.LlmRouter
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.engine.Completion
import cc.aaro.gar.router.engine.CompletionEngine
import cc.aaro.gar.router.engine.CompletionRequest
import cc.aaro.gar.router.engine.StopReason
import cc.aaro.gar.router.format.FunctionGemmaFormat
import cc.aaro.gar.router.format.HermesFormat
import cc.aaro.gar.router.format.ToolSchemas
import cc.aaro.gar.router.resolve.SlotPlausibilityFilter
import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.dicio.numbers.ParserFormatter
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Runs `gar/eval/cases.yml` through [LlmRouter] against a running llama-server and reports, per
 * expected tool and overall: tool accuracy, exact slot accuracy, abstention on negatives,
 * false-call rate and latency. Writes a JSON report with every case to `--out`.
 *
 * Case format (see gar/eval/cases.yml): `text`, `tool` (`no_match` for negatives), `slots`
 * (slot -> accepted value or list of accepted values) and `optional` (slots that may be filled
 * or not, with any value). A positive case's slots are exact when every expected slot has an
 * accepted value and no other slot outside `optional` is filled.
 *
 * The router gets the app's call filters (`--guards keywords,slots`, the default; `none` for
 * none): [KeywordFilter] and [SlotPlausibilityFilter] with English resolvers. Filters don't change
 * what the model generates, so the summary also scores the run as if no call had been dropped,
 * and lists what each guard dropped. Before them the router rewrites a `media_play` call for a
 * transport control into `media_control`, as in the app ([cc.aaro.gar.router.MediaControlRewrite]).
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it[0].removePrefix("--") to it[1] }
    val catalog = ToolCatalog.load(
        File(options.getValue("tools")).readText(),
        File(options.getValue("definitions")).readText(),
    )
    val cases = loadCases(File(options.getValue("cases")))
    val formatName = options.getValue("format")
    // prompt experiments on the pseudo-tool (negatives stay labeled `no_match` in the cases);
    // without options each format keeps the pseudo-tool the app uses
    fun noMatch(default: ToolSpec) = ToolSchemas.noMatchTool(
        options["no-match-name"] ?: default.name,
        options["no-match-description"] ?: default.description,
    )
    options["no-match-name"]?.let { name ->
        if (catalog.byName(name) != null) {
            System.err.println("--no-match-name '$name' clashes with a catalog tool")
            exitProcess(2)
        }
    }
    val format = when (formatName) {
        "hermes" -> HermesFormat(noMatch(HermesFormat.DEFAULT_NO_MATCH))
        "functiongemma" -> FunctionGemmaFormat(noMatch(ToolSchemas.noMatch))
        else -> {
            System.err.println("Unknown format $formatName (hermes|functiongemma)")
            exitProcess(2)
        }
    }
    for (case in cases) {
        if (case.tool != ToolCatalog.NO_MATCH && catalog.byName(case.tool) == null) {
            System.err.println("Unknown tool '${case.tool}' in case '${case.text}'")
            exitProcess(2)
        }
    }

    val guardNames = options["guards"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?.filter { it != "none" } ?: GUARDS.keys.toList()
    for (name in guardNames) {
        if (name !in GUARDS) {
            System.err.println("Unknown guard '$name' (${GUARDS.keys.joinToString()} or none)")
            exitProcess(2)
        }
    }
    val guards = guardNames.map { GUARDS.getValue(it) }

    // a loaded machine can take minutes for the first prefill (--timeout-seconds)
    val timeoutSeconds = options["timeout-seconds"]?.toLong() ?: 120
    val server = LlamaServerEngine(options.getValue("server"), Duration.ofSeconds(timeoutSeconds))
    // --replay <report.json>: the model's outputs of an earlier run instead of a server, to try
    // call filters (they don't change what the model generates) without running the model
    val replay = options["replay"]?.let { loadReplay(File(it)) }
    val engine = RecordingEngine(if (replay != null) ReplayEngine(replay.completions) else server)
    val router = LlmRouter(
        engine, format, timeoutMillis = timeoutSeconds * 1000,
        filter = guards.takeIf { it.isNotEmpty() }?.let { CallFilter.allOf(*it.toTypedArray()) },
    )
    val staticPrefix = format.staticPrefix(catalog.tools)
    if (replay != null && replay.staticPrefix != staticPrefix) {
        // the outputs came from another prompt: fine for trying filters, not for prompt changes
        System.err.println("Warning: the replayed report was recorded with another prompt")
    }
    val results = runBlocking {
        if (replay == null) {
            router.warmUp(catalog.tools)
            repeat(2) { router.route("hello there", catalog.tools) } // not scored
        }
        cases.mapIndexed { i, case ->
            engine.last = null
            (engine.delegate as? ReplayEngine)?.utterance = case.text
            val start = System.nanoTime()
            val output = router.route(case.text, catalog.tools)
            val millis = (System.nanoTime() - start) / 1e6
            score(case, output, millis, engine.last).also {
                if (!it.correct) {
                    println("[${i + 1}/${cases.size}] MISS ${case.text} -> ${it.got} ${it.slots}" +
                        (it.failure?.let { f -> " ($f)" } ?: "") +
                        (it.dropReason?.let { r -> " (${it.call?.tool?.name} dropped: $r)" } ?: ""))
                }
            }
        }
    }

    val prefixTokens = if (replay != null) null else runBlocking {
        try {
            server.post("/tokenize", buildJsonObject {
                put("content", staticPrefix)
                put("add_special", true)
            })["tokens"]?.let { (it as kotlinx.serialization.json.JsonArray).size }
        } catch (e: Exception) {
            null
        }
    }

    val model = options.getValue("model")
    val report = report(model, formatName, options.getValue("server"), staticPrefix,
        prefixTokens, guardNames, results)
    val outDir = File(options.getValue("out")).also { it.mkdirs() }
    val outFile = File(outDir, "report-$model-$formatName.json")
    outFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), report))
    printSummary(model, formatName, results)
    println("static prefix: ${prefixTokens ?: "?"} tokens; guards: " +
        guardNames.joinToString().ifEmpty { "none" })
    println("Report: ${outFile.absolutePath}")
}

/** The call filters the app uses, by `--guards` name. */
private val GUARDS: Map<String, CallFilter> = mapOf(
    "keywords" to KeywordFilter,
    "slots" to SlotPlausibilityFilter({ SlotResolvers(ParserFormatter(Locale.ENGLISH), "en") }),
)

data class EvalCase(
    val text: String,
    val tool: String,
    /** slot -> accepted (normalized) values */
    val slots: Map<String, List<String>>,
    val optional: Set<String>,
)

data class CaseResult(
    val case: EvalCase,
    /** tool name, `no_match`, or `failed` */
    val got: String,
    val slots: Map<String, String>,
    val missingRequired: List<String>,
    val failure: String?,
    val toolCorrect: Boolean,
    val slotsExact: Boolean,
    val millis: Double,
    val completion: Completion?,
    /** Why the router's filter dropped the call (then [got] is [DROPPED]), or null. */
    val dropReason: String? = null,
    /** The model's call before filtering (also for kept calls), or null for no call. */
    val call: RouterOutput.Call? = null,
) {
    val positive get() = case.tool != ToolCatalog.NO_MATCH
    val abstained get() = got == ToolCatalog.NO_MATCH || got == FAILED || got == DROPPED
    val correct get() = if (positive) slotsExact else abstained

    /** What each guard says about [call], by guard name: the drop reason, or null to keep. */
    val guardDrops: Map<String, String?> by lazy {
        val call = call ?: return@lazy mapOf()
        GUARDS.mapValues { (_, filter) -> (filter.check(call) as? CallVerdict.Drop)?.reason }
    }

    /** This case scored as if the router had no filter. */
    val unguarded: CaseResult by lazy {
        if (got != DROPPED || call == null) this else scoreCall(case, call, millis, completion)
    }
}

private const val FAILED = "failed"
private const val DROPPED = "dropped"

private val prettyJson = Json { prettyPrint = true }

fun loadCases(file: File): List<EvalCase> {
    val root = Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
        .loadFromString(file.readText()) as Map<*, *>
    return (root["cases"] as List<*>).map { raw ->
        val map = raw as Map<*, *>
        EvalCase(
            text = map["text"] as String,
            tool = map["tool"] as String,
            slots = (map["slots"] as Map<*, *>? ?: mapOf<String, Any>()).entries.associate {
                (k, v) -> k as String to when (v) {
                    is List<*> -> v.map { Utterances.normalize(it.toString()) }
                    else -> listOf(Utterances.normalize(v.toString()))
                }
            },
            optional = (map["optional"] as List<*>? ?: listOf<Any>()).map { it as String }.toSet(),
        )
    }
}

private fun score(case: EvalCase, output: RouterOutput, millis: Double, completion: Completion?) =
    when (output) {
        is RouterOutput.Call -> scoreCall(case, output, millis, completion)
        is RouterOutput.Dropped -> CaseResult(case, DROPPED, output.call.rawSlots,
            output.call.missingRequired, null, case.tool == ToolCatalog.NO_MATCH, false, millis,
            completion, dropReason = output.reason, call = output.call)
        RouterOutput.NoMatch -> CaseResult(case, ToolCatalog.NO_MATCH, mapOf(), listOf(), null,
            case.tool == ToolCatalog.NO_MATCH, false, millis, completion)
        is RouterOutput.Failed -> CaseResult(case, FAILED, mapOf(), listOf(), output.reason,
            false, false, millis, completion)
    }

private fun scoreCall(case: EvalCase, call: RouterOutput.Call, millis: Double, completion: Completion?): CaseResult {
    val toolCorrect = call.tool.name == case.tool
    val slotsExact = toolCorrect &&
        case.slots.all { (key, accepted) -> call.rawSlots[key] in accepted } &&
        call.rawSlots.keys.all { it in case.slots || it in case.optional }
    return CaseResult(case, call.tool.name, call.rawSlots, call.missingRequired, null,
        toolCorrect, slotsExact, millis, completion, call = call)
}

private fun pct(n: Int, d: Int) = if (d == 0) "-" else String.format(Locale.ROOT, "%5.1f%%", 100.0 * n / d)

private fun percentile(values: List<Double>, p: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * p).toInt()]
}

private fun printSummary(model: String, format: String, results: List<CaseResult>) {
    val positives = results.filter { it.positive }
    val negatives = results.filter { !it.positive }
    println()
    println("GAR router eval: $model ($format), ${results.size} cases")
    println(String.format(Locale.ROOT, "%-20s %4s %8s %8s", "expected tool", "n", "tool", "slots"))
    for ((tool, rows) in positives.groupBy { it.case.tool }) {
        println(String.format(Locale.ROOT, "%-20s %4d %8s %8s", tool, rows.size,
            pct(rows.count { it.toolCorrect }, rows.size), pct(rows.count { it.slotsExact }, rows.size)))
    }
    println(String.format(Locale.ROOT, "%-20s %4d %8s %8s", "no_match (abstain)", negatives.size,
        pct(negatives.count { it.abstained }, negatives.size), ""))
    println()
    println("positives: tool accuracy ${pct(positives.count { it.toolCorrect }, positives.size)}, " +
        "exact slots ${pct(positives.count { it.slotsExact }, positives.size)}, " +
        "abstained ${positives.count { it.abstained }}/${positives.size}")
    println("negatives: abstention ${pct(negatives.count { it.abstained }, negatives.size)}, " +
        "false calls ${pct(negatives.count { !it.abstained }, negatives.size)}")
    println("overall: correct decision and slots ${pct(results.count { it.correct }, results.size)}, " +
        "failed (malformed/engine) ${results.count { it.got == FAILED }}")

    val unguarded = results.map { it.unguarded }
    val unguardedPositives = unguarded.filter { it.positive }
    val unguardedNegatives = unguarded.filter { !it.positive }
    println("without the router's filter: tool accuracy " +
        "${pct(unguardedPositives.count { it.toolCorrect }, unguardedPositives.size)}, exact slots " +
        "${pct(unguardedPositives.count { it.slotsExact }, unguardedPositives.size)}, abstention " +
        "${pct(unguardedNegatives.count { it.abstained }, unguardedNegatives.size)}, overall " +
        pct(unguarded.count { it.correct }, unguarded.size))
    // what each guard would drop on its own, over every call the model made
    for (guard in GUARDS.keys) {
        val dropped = results.filter { it.guardDrops[guard] != null }
        val onNegatives = dropped.filter { !it.positive }
        val onPositives = dropped.filter { it.positive }
        val wrong = onPositives.filter { it.unguarded.toolCorrect }
        println("guard $guard drops ${dropped.size} calls: ${onNegatives.size} on negatives, " +
            "${onPositives.size} on positives (${wrong.size} of the expected tool)")
        for (r in onPositives) {
            println("  ${if (r.unguarded.toolCorrect) "LOST" else "misroute"}: ${r.case.text} -> " +
                "${r.call?.tool?.name} ${r.call?.rawSlots} (${r.guardDrops[guard]})")
        }
    }
    val ms = results.map { it.millis }
    println(String.format(Locale.ROOT,
        "latency per request: mean %.0f ms, p50 %.0f ms, p90 %.0f ms, max %.0f ms " +
            "(server prefill mean %.0f ms, generation mean %.0f ms)",
        ms.average(), percentile(ms, 0.5), percentile(ms, 0.9), ms.maxOrNull() ?: 0.0,
        results.mapNotNull { it.completion?.prefillMillis?.toDouble() }.average(),
        results.mapNotNull { it.completion?.generateMillis?.toDouble() }.average()))
}

private fun report(
    model: String,
    format: String,
    server: String,
    staticPrefix: String,
    staticPrefixTokens: Int?,
    guards: List<String>,
    results: List<CaseResult>,
) =
    buildJsonObject {
        val positives = results.filter { it.positive }
        val negatives = results.filter { !it.positive }
        put("model", model)
        put("format", format)
        put("server", server)
        put("static_prefix", staticPrefix)
        staticPrefixTokens?.let { put("static_prefix_tokens", it) }
        putJsonArray("guards") { guards.forEach { add(it) } }
        put("date", LocalDateTime.now().toString())
        putJsonObject("summary") {
            put("cases", results.size)
            put("positives", positives.size)
            put("negatives", negatives.size)
            put("tool_accuracy", positives.count { it.toolCorrect }.toDouble() / positives.size)
            put("exact_slot_accuracy", positives.count { it.slotsExact }.toDouble() / positives.size)
            put("abstention_accuracy", negatives.count { it.abstained }.toDouble() / negatives.size)
            put("false_call_rate", negatives.count { !it.abstained }.toDouble() / negatives.size)
            put("abstained_on_positives", positives.count { it.abstained })
            put("failed", results.count { it.got == FAILED })
            put("latency_mean_ms", results.map { it.millis }.average())
            put("latency_p50_ms", percentile(results.map { it.millis }, 0.5))
            put("latency_p90_ms", percentile(results.map { it.millis }, 0.9))
            put("dropped", results.count { it.got == DROPPED })
        }
        putJsonObject("summary_without_filter") {
            val unguarded = results.map { it.unguarded }
            val uPositives = unguarded.filter { it.positive }
            val uNegatives = unguarded.filter { !it.positive }
            put("tool_accuracy", uPositives.count { it.toolCorrect }.toDouble() / uPositives.size)
            put("exact_slot_accuracy", uPositives.count { it.slotsExact }.toDouble() / uPositives.size)
            put("abstention_accuracy", uNegatives.count { it.abstained }.toDouble() / uNegatives.size)
            put("false_call_rate", uNegatives.count { !it.abstained }.toDouble() / uNegatives.size)
            put("correct", unguarded.count { it.correct }.toDouble() / unguarded.size)
        }
        putJsonObject("per_tool") {
            for ((tool, rows) in positives.groupBy { it.case.tool }) {
                putJsonObject(tool) {
                    put("n", rows.size)
                    put("tool_accuracy", rows.count { it.toolCorrect }.toDouble() / rows.size)
                    put("exact_slot_accuracy", rows.count { it.slotsExact }.toDouble() / rows.size)
                }
            }
        }
        putJsonArray("cases") {
            for (r in results) addJsonObject {
                put("text", r.case.text)
                put("expected_tool", r.case.tool)
                put("got", r.got)
                putJsonObject("slots") { r.slots.forEach { (k, v) -> put(k, v) } }
                putJsonArray("missing_required") { r.missingRequired.forEach { add(it) } }
                r.failure?.let { put("failure", it) }
                r.call?.let { call ->
                    put("call", call.tool.name)
                    putJsonObject("call_slots") { call.rawSlots.forEach { (k, v) -> put(k, v) } }
                }
                r.dropReason?.let { put("drop_reason", it) }
                putJsonObject("guard_drops") {
                    r.guardDrops.forEach { (guard, reason) -> reason?.let { put(guard, it) } }
                }
                put("tool_correct", r.toolCorrect)
                put("slots_exact", r.slotsExact)
                put("correct", r.correct)
                put("millis", r.millis)
                r.completion?.let { c ->
                    put("raw", c.text)
                    put("prompt_tokens", c.promptTokens)
                    put("cached_tokens", c.cachedTokens)
                    put("generated_tokens", c.generatedTokens)
                    put("prefill_ms", c.prefillMillis)
                    put("generate_ms", c.generateMillis)
                }
            }
        }
    }

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: String) =
    add(kotlinx.serialization.json.JsonPrimitive(value))

/** An earlier run: its prompt and the raw model output by case text. */
private class Replay(val staticPrefix: String?, val completions: Map<String, Completion>)

/**
 * The prompt and the raw model outputs of an eval report. A case that failed in the engine has
 * no output, so it fails in the replay too, as an engine error, not as an empty output.
 */
private fun loadReplay(file: File): Replay {
    val report = Json.parseToJsonElement(file.readText()).jsonObject
    val cases = report.getValue("cases").jsonArray.map { it.jsonObject }.filter { "raw" in it }
    val completions = cases.associate { case ->
        fun int(key: String) = case[key]?.jsonPrimitive?.content?.toDouble()?.toLong() ?: 0
        case.getValue("text").jsonPrimitive.content to Completion(
            text = case.getValue("raw").jsonPrimitive.content,
            promptTokens = int("prompt_tokens").toInt(),
            cachedTokens = int("cached_tokens").toInt(),
            generatedTokens = int("generated_tokens").toInt(),
            prefillMillis = int("prefill_ms"),
            generateMillis = int("generate_ms"),
            stopReason = StopReason.STOP_STRING,
        )
    }
    return Replay(report["static_prefix"]?.jsonPrimitive?.content, completions)
}

/** Answers each case with the output recorded for its text; cases without one fail. */
private class ReplayEngine(private val completions: Map<String, Completion>) : CompletionEngine {
    var utterance: String? = null

    override suspend fun complete(request: CompletionRequest): Completion =
        completions[utterance] ?: throw IllegalStateException("no recorded output for '$utterance'")

    override suspend fun warmUp(prefix: String) {}
    override suspend fun close() {}
}

/** Remembers the last completion so the report can show raw model output and timings. */
private class RecordingEngine(val delegate: CompletionEngine) : CompletionEngine {
    @Volatile
    var last: Completion? = null

    override suspend fun complete(request: CompletionRequest): Completion =
        delegate.complete(request).also { last = it }

    override suspend fun warmUp(prefix: String) = delegate.warmUp(prefix)
    override suspend fun close() = delegate.close()
}
