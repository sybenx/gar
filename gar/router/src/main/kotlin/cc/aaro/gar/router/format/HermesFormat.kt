package cc.aaro.gar.router.format

import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec

/**
 * Qwen3 (and other Hermes/ChatML tool-calling models) with thinking disabled. Renders what
 * Qwen3's chat template renders with `enable_thinking=false` and no system message: the tools as
 * JSON lines in the system turn, then the user turn and an empty think block.
 *
 * Abstention comes from the [DEFAULT_NO_MATCH] description alone. In the 2026-10-25 eval, system
 * messages (a phone command parser that calls `no_match` for questions and chit-chat) cost
 * Qwen3-0.6B 2 to 5 of 119 commands, while the description lost none.
 *
 * Output: `<tool_call>\n{"name": "...", "arguments": {...}}\n</tool_call>`.
 */
class HermesFormat(
    override val noMatch: ToolSpec = DEFAULT_NO_MATCH,
) : PromptFormat {
    override val family = ModelFamily.HERMES
    override val stopStrings: List<String> get() = listOf(CALL_END)

    override fun staticPrefix(tools: List<ToolSpec>): String {
        val out = StringBuilder(SYSTEM_START)
        for (tool in ToolSchemas.withNoMatch(tools, noMatch)) {
            out.append('\n').append(ToolSchemas.toJson(tool))
        }
        return out.append(SYSTEM_END).toString()
    }

    override fun suffix(utterance: String): String =
        "<|im_start|>user\n$utterance<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    override fun grammar(tools: List<ToolSpec>, utterance: String): String =
        Grammar.build(tools, utterance, noMatch.name)

    /**
     * Text without `<tool_call>` is [ParsedOutput.NoMatch] (the model answered in prose, which
     * means it found no tool); blank text is [ParsedOutput.Malformed]. Only the first call
     * counts. A missing `</tool_call>` is tolerated, a duplicate `name` or `arguments` key is not.
     * Arguments that are undeclared, not strings or duplicated are dropped (a dropped required
     * slot is then asked for).
     */
    override fun parse(raw: String, tools: List<ToolSpec>): ParsedOutput {
        val start = raw.indexOf(CALL_START)
        if (start < 0) {
            return if (raw.isBlank()) ParsedOutput.Malformed("empty output") else ParsedOutput.NoMatch
        }
        var body = raw.substring(start + CALL_START.length)
        val end = body.indexOf(CALL_END)
        if (end >= 0) {
            body = body.substring(0, end)
        }
        body = body.trim()
        if (body.isEmpty()) {
            return ParsedOutput.Malformed("empty tool call")
        }

        val call = try {
            Json.read(body)
        } catch (e: Json.ParseException) {
            return ParsedOutput.Malformed("invalid JSON: ${e.message}")
        }
        if (call !is Json.Obj) {
            return ParsedOutput.Malformed("tool call is not a JSON object")
        }
        for (key in listOf("name", "arguments")) {
            if (key in call.duplicateKeys) {
                return ParsedOutput.Malformed("duplicate \"$key\"")
            }
        }
        val name = call.values["name"] as? String
            ?: return ParsedOutput.Malformed("missing tool name")
        if (name == noMatch.name) {
            return ParsedOutput.NoMatch
        }
        val tool = tools.firstOrNull { it.name == name }
            ?: return ParsedOutput.Malformed("unknown tool '$name'")

        val arguments = when (val a = call.values["arguments"]) {
            null -> Json.Obj(mapOf(), setOf())
            is Json.Obj -> a
            // some models write the arguments as a JSON string
            is String -> try {
                Json.read(a) as? Json.Obj
            } catch (e: Json.ParseException) {
                null
            } ?: return ParsedOutput.Malformed("arguments are not a JSON object")
            else -> return ParsedOutput.Malformed("arguments are not a JSON object")
        }
        val declared = tool.slots.map { it.name }.toSet()
        val slots = arguments.values
            .filter { (key, value) ->
                key in declared && key !in arguments.duplicateKeys && value is String
            }
            .mapValues { it.value as String }
        return ParsedOutput.Call(tool, slots)
    }

    private object Grammar : CallGrammar() {
        override fun root(alternatives: String) =
            "<tool_call> \"\\n\" ( $alternatives ) \"\\n\" </tool_call>"

        override fun call(name: String, args: String?): String {
            val head = literal("{\"name\": ${Json.quote(name)}, \"arguments\": {")
            return if (args == null) {
                literal("{\"name\": ${Json.quote(name)}, \"arguments\": {}}")
            } else {
                "$head $args ${literal("}}")}"
            }
        }

        override fun key(slot: SlotSpec) = literal(Json.quote(slot.name) + ": ")

        override fun enumValue(values: List<String>) =
            values.joinToString(" | ", "( ", " )") { literal(Json.quote(it)) }

        override val spanValue = "${literal("\"")} span ${literal("\"")}"
        override val separator = literal(", ")

        override fun orderSlots(slots: List<SlotSpec>) = slots

        override fun encodeWord(word: String) = Json.quote(word).let { it.substring(1, it.length - 1) }
    }

    companion object {
        const val CALL_START = "<tool_call>"
        const val CALL_END = "</tool_call>"

        /**
         * `no_match` with a description tuned for Qwen3-0.6B on gar/eval (2026-10-25): naming
         * questions and chit-chat raised abstention on out-of-scope requests from 41% to 73% with
         * no loss of commands; "general" keeps questions that are commands ("how long until the
         * pasta is done") on their tools. FunctionGemma keeps [ToolSchemas.noMatch]: this text
         * didn't help it.
         */
        val DEFAULT_NO_MATCH: ToolSpec = ToolSchemas.noMatchTool(
            ToolCatalog.NO_MATCH,
            "Call this for general questions, chit-chat and anything that no other function does.",
        )

        private const val SYSTEM_START = "<|im_start|>system\n# Tools\n\nYou may call one or " +
            "more functions to assist with the user query.\n\nYou are provided with function " +
            "signatures within <tools></tools> XML tags:\n<tools>"
        private const val SYSTEM_END = "\n</tools>\n\nFor each function call, return a json " +
            "object with function name and arguments within <tool_call></tool_call> XML tags:\n" +
            "<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n" +
            "</tool_call><|im_end|>\n"
    }
}
