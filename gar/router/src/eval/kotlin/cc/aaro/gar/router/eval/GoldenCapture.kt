package cc.aaro.gar.router.eval

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.engine.CompletionRequest
import cc.aaro.gar.router.format.FunctionGemmaFormat
import cc.aaro.gar.router.format.HermesFormat
import cc.aaro.gar.router.format.ParsedOutput
import cc.aaro.gar.router.format.PromptFormat
import cc.aaro.gar.router.format.ToolSchemas
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import kotlin.system.exitProcess

/**
 * Captures the prompt goldens of `src/test/resources/golden/cases.yml`: asks llama-server's
 * `/apply-template` to render each case with the model's own chat template, stores the request,
 * the rendered prompt and the format's grammar, and runs one grammar-constrained completion per
 * case to check that llama.cpp accepts the grammar and the output parses.
 *
 * Args: `--hermes <Qwen3 server url> --functiongemma <FunctionGemma server url> --dir <golden dir>`
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it[0].removePrefix("--") to it[1] }
    val dir = File(options.getValue("dir"))
    val servers = mapOf(
        "hermes" to Pair(HermesFormat(), LlamaServerEngine(options.getValue("hermes"))),
        "functiongemma" to Pair(
            FunctionGemmaFormat(), LlamaServerEngine(options.getValue("functiongemma"))
        ),
    )
    val pretty = Json { prettyPrint = true }
    val load = Load(LoadSettings.builder().build())
    val cases = (load.loadFromString(File(dir, "cases.yml").readText()) as Map<*, *>)["cases"]
        as List<*>
    var failures = 0

    runBlocking {
        for (case in cases.map { it as Map<*, *> }) {
            val name = case["name"] as String
            val toolsYaml = File(dir, "toolsets/${case["toolset"]}.yml").readText()
            val tools = ToolCatalog.load(toolsYaml, "skills: []").tools
            val utterance = Utterances.normalize(case["utterance"] as String)

            for ((formatName, server) in servers) {
                val (format, engine) = server
                val request = templateRequest(format, tools, utterance)
                val prompt = engine.post("/apply-template", request)["prompt"]!!
                    .jsonPrimitive.content
                val grammar = format.grammar(tools, utterance)
                val out = File(dir, formatName).also { it.mkdirs() }
                File(out, "$name.request.json")
                    .writeText(pretty.encodeToString(JsonObject.serializer(), request) + "\n")
                File(out, "$name.prompt.txt").writeText(prompt)
                File(out, "$name.gbnf").writeText(grammar)

                val rendered = format.staticPrefix(tools) + format.suffix(utterance)
                val same = rendered == prompt
                val parsed = try {
                    val completion = engine.complete(CompletionRequest(
                        prefix = format.staticPrefix(tools),
                        suffix = format.suffix(utterance),
                        grammar = grammar,
                        maxTokens = 96,
                        stopStrings = format.stopStrings,
                    ))
                    val output = when (val p = format.parse(completion.text, tools)) {
                        is ParsedOutput.Call -> "call ${p.tool.name} ${p.slots}"
                        else -> p.toString()
                    }
                    "$output  <=  ${completion.text.replace("\n", "\\n")}"
                } catch (e: LlamaServerException) {
                    failures++
                    "GRAMMAR REJECTED: ${e.message}"
                }
                if (!same) failures++
                println("$formatName/$name: prompt ${if (same) "matches" else "DIFFERS"}; $parsed")
            }
        }
    }
    if (failures > 0) {
        println("$failures problem(s)")
        exitProcess(1)
    }
}

private fun templateRequest(
    format: PromptFormat,
    tools: List<ToolSpec>,
    utterance: String,
): JsonObject = buildJsonObject {
    putJsonArray("messages") {
        if (format is FunctionGemmaFormat) {
            addJsonObject {
                put("role", "developer")
                put("content", FunctionGemmaFormat.DEVELOPER_PROMPT)
            }
        }
        addJsonObject {
            put("role", "user")
            put("content", utterance)
        }
    }
    put("tools", Json.parseToJsonElement(ToolSchemas.toJsonArray(tools, format.noMatch)))
    if (format is HermesFormat) {
        putJsonObject("chat_template_kwargs") { put("enable_thinking", false) }
    }
}
