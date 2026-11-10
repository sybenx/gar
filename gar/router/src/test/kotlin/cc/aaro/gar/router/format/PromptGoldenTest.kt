package cc.aaro.gar.router.format

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

private fun resource(path: String): String =
    PromptGoldenTest::class.java.getResource("/golden/$path")?.readText()
        ?: throw IllegalStateException("missing golden resource $path")

private data class GoldenCase(val name: String, val toolset: String, val utterance: String)

private val goldenCases: List<GoldenCase> =
    ((Load(LoadSettings.builder().build()).loadFromString(resource("cases.yml")) as Map<*, *>)["cases"]
        as List<*>).map {
        val m = it as Map<*, *>
        GoldenCase(m["name"] as String, m["toolset"] as String, m["utterance"] as String)
    }

private val formats = mapOf("hermes" to HermesFormat(), "functiongemma" to FunctionGemmaFormat())

/** A parsed JSON value with its objects as lists of entries, so that comparing it checks key order. */
private fun ordered(value: Any?): Any? = when (value) {
    is Json.Obj -> value.values.map { (key, v) -> Pair(key, ordered(v)) }
    is List<*> -> value.map(::ordered)
    else -> value
}

/**
 * The prompts must be byte-identical to llama.cpp's chat template rendering (captured from
 * llama-server /apply-template, see golden/cases.yml), and the grammars must not change silently
 * (each golden grammar was accepted by llama.cpp when it was captured).
 */
class PromptGoldenTest : StringSpec({
    "there are goldens for several tool sets" {
        goldenCases shouldHaveAtLeastSize 6
        goldenCases.map { it.toolset }.toSet().size shouldBe 3
    }

    for ((formatName, format) in formats) {
        for (case in goldenCases) {
            "$formatName ${case.name}: prompt is byte-identical to llama.cpp's rendering" {
                val tools = ToolCatalog.load(resource("toolsets/${case.toolset}.yml"), "skills: []").tools
                val utterance = Utterances.normalize(case.utterance)
                val expected = resource("$formatName/${case.name}.prompt.txt")
                val prefix = format.staticPrefix(tools)
                val suffix = format.suffix(utterance)
                withClue("prefix") { expected shouldStartWith prefix }
                withClue("suffix") { expected shouldEndWith suffix }
                (prefix + suffix) shouldBe expected
            }

            "$formatName ${case.name}: the golden request has the tools ToolSchemas sends" {
                val tools = ToolCatalog.load(resource("toolsets/${case.toolset}.yml"), "skills: []").tools
                val request = Json.read(resource("$formatName/${case.name}.request.json")) as Json.Obj
                ordered(Json.read(ToolSchemas.toJsonArray(tools, format.noMatch))) shouldBe
                    ordered(request.values["tools"])
            }

            "$formatName ${case.name}: grammar matches the golden" {
                val tools = ToolCatalog.load(resource("toolsets/${case.toolset}.yml"), "skills: []").tools
                val utterance = Utterances.normalize(case.utterance)
                format.grammar(tools, utterance) shouldBe resource("$formatName/${case.name}.gbnf")
                GbnfRegex.compile(format.grammar(tools, utterance)) // well-formed, no undefined rules
            }
        }
    }

    "no_match's description depends on the format, and slots leave out an empty description" {
        fun noMatchDescription(format: PromptFormat): Any? {
            val declaration = (Json.read(ToolSchemas.toJsonArray(listOf(), format.noMatch)) as List<*>).single()
            val function = (declaration as Json.Obj).values["function"] as Json.Obj
            return function.values["description"]
        }
        noMatchDescription(FunctionGemmaFormat()) shouldBe ToolCatalog.NO_MATCH_DESCRIPTION
        noMatchDescription(HermesFormat()) shouldBe HermesFormat.DEFAULT_NO_MATCH.description
        val bare = SlotSpec("state", SlotKind.ENUM, true, "", enumValues = listOf("on", "off"))
        ToolSchemas.toJson(testTool("lamp", bare)) shouldBe "{\"type\": \"function\", \"function\": " +
            "{\"name\": \"lamp\", \"description\": \"Tool lamp.\", \"parameters\": {\"type\": \"object\", " +
            "\"properties\": {\"state\": {\"type\": \"string\", \"enum\": [\"on\", \"off\"]}}, " +
            "\"required\": [\"state\"]}}}"
    }

    "the static prefix ends right before the user turn" {
        val tools = ToolCatalog.load(resource("toolsets/single.yml"), "skills: []").tools
        HermesFormat().staticPrefix(tools) shouldEndWith "</tool_call><|im_end|>\n"
        HermesFormat().suffix("hi") shouldStartWith "<|im_start|>user\n"
        FunctionGemmaFormat().staticPrefix(tools) shouldEndWith "<end_function_declaration><end_of_turn>\n"
        FunctionGemmaFormat().suffix("hi") shouldStartWith "<start_of_turn>user\n"
    }
})
