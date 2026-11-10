package cc.aaro.gar.router.format

import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec

/**
 * FunctionGemma 270M native call syntax. Renders what FunctionGemma's chat template renders for a
 * `developer` message ([DEVELOPER_PROMPT]) plus the tools: one `<start_function_declaration>`
 * per tool with properties in alphabetical order (the template dictsorts them). The BOS token is
 * not part of the prefix; the engine adds it when tokenizing, like llama-server does.
 *
 * Output: `<start_function_call>call:NAME{key:<escape>value<escape>,...}<end_function_call>`
 * with keys in alphabetical order.
 */
class FunctionGemmaFormat(
    override val noMatch: ToolSpec = ToolSchemas.noMatch,
) : PromptFormat {
    override val family = ModelFamily.FUNCTION_GEMMA
    override val stopStrings: List<String> get() = listOf(CALL_END)

    override fun staticPrefix(tools: List<ToolSpec>): String {
        val out = StringBuilder("<start_of_turn>developer\n").append(DEVELOPER_PROMPT)
        for (tool in ToolSchemas.withNoMatch(tools, noMatch)) {
            out.append("<start_function_declaration>declaration:").append(tool.name)
                .append("{description:").append(escaped(tool.description))
                .append(",parameters:{")
            if (tool.slots.isNotEmpty()) {
                out.append("properties:{")
                sorted(tool.slots).forEachIndexed { i, slot ->
                    if (i > 0) out.append(',')
                    out.append(slot.name).append(":{description:").append(escaped(slot.description))
                    if (slot.kind == SlotKind.ENUM && slot.enumValues.isNotEmpty()) {
                        out.append(",enum:[")
                            .append(slot.enumValues.joinToString(",") { escaped(it) })
                            .append(']')
                    }
                    out.append(",type:").append(escaped("STRING")).append('}')
                }
                out.append("},")
            }
            val required = tool.slots.filter { it.required }
            if (required.isNotEmpty()) {
                out.append("required:[")
                    .append(required.joinToString(",") { escaped(it.name) })
                    .append("],")
            }
            out.append("type:").append(escaped("OBJECT")).append("}}<end_function_declaration>")
        }
        return out.append("<end_of_turn>\n").toString()
    }

    override fun suffix(utterance: String): String =
        "<start_of_turn>user\n${utterance.trim()}<end_of_turn>\n<start_of_turn>model\n"

    override fun grammar(tools: List<ToolSpec>, utterance: String): String =
        Grammar.build(tools, utterance, noMatch.name)

    /**
     * Text without `<start_function_call>` is [ParsedOutput.NoMatch] (FunctionGemma answers
     * requests it can't serve with prose); blank text is [ParsedOutput.Malformed]. Only the first
     * call counts and a missing `<end_function_call>` is tolerated, but a call cut off before its
     * closing `}` is malformed. Arguments that are undeclared, duplicated or not
     * `<escape>`-quoted strings are dropped.
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
        if (!body.startsWith("call:")) {
            return ParsedOutput.Malformed("missing 'call:'")
        }
        val reader = Reader(body, "call:".length)
        reader.skipWhitespace()
        val name = reader.readName()
        if (name.isEmpty()) {
            return ParsedOutput.Malformed("missing function name")
        }
        if (name == noMatch.name) {
            return ParsedOutput.NoMatch
        }
        val tool = tools.firstOrNull { it.name == name }
            ?: return ParsedOutput.Malformed("unknown tool '$name'")
        reader.skipWhitespace()
        if (!reader.consume("{")) {
            return ParsedOutput.Malformed("missing '{' after function name")
        }

        val values = linkedMapOf<String, String?>()
        val duplicates = mutableSetOf<String>()
        while (true) {
            reader.skipWhitespace()
            if (reader.consume("}")) {
                break
            }
            if (reader.atEnd()) {
                return ParsedOutput.Malformed("truncated call")
            }
            val key = reader.readName()
            reader.skipWhitespace()
            if (key.isEmpty() || !reader.consume(":")) {
                return ParsedOutput.Malformed("expected 'name:' in arguments")
            }
            reader.skipWhitespace()
            // null = a value that is not a string (number, boolean, nested object or list)
            val value: String? = if (reader.consume(ESCAPE)) {
                reader.readUntil(ESCAPE)
                    ?: return ParsedOutput.Malformed("unterminated $ESCAPE value")
            } else {
                if (!reader.skipBareValue()) {
                    return ParsedOutput.Malformed("truncated call")
                }
                null
            }
            if (key in values) duplicates += key
            values[key] = value
            reader.skipWhitespace()
            if (!reader.consume(",") && !reader.lookingAt("}")) {
                return if (reader.atEnd()) {
                    ParsedOutput.Malformed("truncated call")
                } else {
                    ParsedOutput.Malformed("expected ',' or '}' in arguments")
                }
            }
        }

        val declared = tool.slots.map { it.name }.toSet()
        val slots = values
            .filter { (key, value) -> key in declared && key !in duplicates && value != null }
            .mapValues { it.value!! }
        return ParsedOutput.Call(tool, slots)
    }

    private class Reader(val s: String, var pos: Int) {
        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun lookingAt(token: String) = s.startsWith(token, pos)

        fun consume(token: String): Boolean {
            if (!lookingAt(token)) return false
            pos += token.length
            return true
        }

        fun readName(): String {
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
            return s.substring(start, pos)
        }

        /** Reads up to [terminator] and skips it; null if it never comes. */
        fun readUntil(terminator: String): String? {
            val end = s.indexOf(terminator, pos)
            if (end < 0) return null
            val value = s.substring(pos, end)
            pos = end + terminator.length
            return value
        }

        /**
         * Skips a non-string value up to the next top-level ',' or '}' (or an unmatched ']', which
         * the caller then rejects); false if truncated.
         */
        fun skipBareValue(): Boolean {
            var depth = 0
            while (pos < s.length) {
                if (lookingAt(ESCAPE)) {
                    pos += ESCAPE.length
                    readUntil(ESCAPE) ?: return false
                    continue
                }
                when (s[pos]) {
                    '{', '[' -> depth++
                    '}', ']' -> if (depth == 0) return true else depth--
                    ',' -> if (depth == 0) return true
                }
                pos++
            }
            return false
        }
    }

    private object Grammar : CallGrammar() {
        override fun root(alternatives: String) =
            "<start_function_call> \"call:\" ( $alternatives ) <end_function_call>"

        override fun call(name: String, args: String?) = if (args == null) {
            literal("$name{}")
        } else {
            "${literal("$name{")} $args ${literal("}")}"
        }

        override fun key(slot: SlotSpec) = literal("${slot.name}:")

        override fun enumValue(values: List<String>) =
            values.joinToString(" | ", "<escape> ( ", " ) <escape>") { literal(it) }

        override val spanValue = "<escape> span <escape>"
        override val separator = literal(",")

        override fun orderSlots(slots: List<SlotSpec>) = sorted(slots)

        override fun encodeWord(word: String) = word
    }

    companion object {
        /** Developer turn of the Step 1 eval (gar_eval.py FG_DEV); the trigger line it was tuned on. */
        const val DEVELOPER_PROMPT =
            "You are a model that can do function calling with the following functions"

        const val CALL_START = "<start_function_call>"
        const val CALL_END = "<end_function_call>"
        private const val ESCAPE = "<escape>"

        private fun escaped(text: String) = "$ESCAPE$text$ESCAPE"

        /** The template's `dictsort` (case-insensitive; slot names are lowercase anyway). */
        private fun sorted(slots: List<SlotSpec>) = slots.sortedBy { it.name.lowercase() }
    }
}
