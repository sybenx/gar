package cc.aaro.gar.router.format

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec

/**
 * Builds the per-utterance GBNF grammar shared by both formats (gar/DESIGN.md, "Prompt formats"):
 * `root` allows exactly one call of one offered tool or `no_match`; arguments come in a fixed key
 * order with required keys always present and optional keys optional; enum slots take one of
 * their values; every other slot takes a contiguous word span of the utterance through the
 * O(n) rules `s-i ::= "w_i" (" " s-(i+1))?` and `span ::= s-1 | ... | s-n`. With no words, only
 * `no_match` is allowed. Special tokens (`<tool_call>`, `<escape>`, ...) are referenced as
 * llama.cpp token rules, as in the Step 1 prototypes.
 */
internal abstract class CallGrammar {
    /** The root expression around the call alternatives (already joined with `|`). */
    protected abstract fun root(alternatives: String): String

    /** One call of [name]; [args] is the arguments expression, or null for a tool without slots. */
    protected abstract fun call(name: String, args: String?): String

    /** The key part of one argument, e.g. `"\"name\": "` or `"name:"`. */
    protected abstract fun key(slot: SlotSpec): String

    /** A value restricted to [values] (one enum slot). */
    protected abstract fun enumValue(values: List<String>): String

    /** A value restricted to a word span, referencing the `span` rule. */
    protected abstract val spanValue: String

    /** The literal between two arguments. */
    protected abstract val separator: String

    /** The order in which the model writes the arguments. */
    protected abstract fun orderSlots(slots: List<SlotSpec>): List<SlotSpec>

    /** How a word of the utterance appears inside a generated value (e.g. JSON-escaped). */
    protected abstract fun encodeWord(word: String): String

    /** [noMatch] is the name of the slotless pseudo-tool for "no tool applies". */
    fun build(tools: List<ToolSpec>, utterance: String, noMatch: String = ToolCatalog.NO_MATCH): String {
        require(tools.none { it.name == noMatch }) { "no_match pseudo-tool '$noMatch' clashes with a tool" }
        val words = Utterances.words(utterance)
        val offered = if (words.isEmpty()) listOf() else tools
        val rules = mutableListOf<String>()
        val alternatives = (offered.map { ruleName(it.name) } + ruleName(noMatch))
        rules += "root ::= " + root(alternatives.joinToString(" | "))

        var needsSpan = false
        for (tool in offered) {
            val items = orderSlots(tool.slots).map { slot ->
                val value = if (slot.kind == SlotKind.ENUM) {
                    enumValue(slot.enumValues)
                } else {
                    needsSpan = true
                    spanValue
                }
                Pair(key(slot) + " " + value, slot.required)
            }
            rules += ruleName(tool.name) + " ::= " + call(tool.name, arguments(items))
        }
        rules += ruleName(noMatch) + " ::= " + call(noMatch, null)

        if (needsSpan) {
            rules += "span ::= " + words.indices.joinToString(" | ") { "s-${it + 1}" }
            for (i in words.indices) {
                val word = literal(encodeWord(words[i]))
                rules += if (i == words.size - 1) {
                    "s-${i + 1} ::= $word"
                } else {
                    "s-${i + 1} ::= $word ( \" \" s-${i + 2} )?"
                }
            }
        }
        return rules.joinToString("\n", postfix = "\n")
    }

    /**
     * Arguments in fixed order: the first emitted argument has no separator before it, so the
     * alternatives are "start at argument j" for every j reachable by skipping optional ones.
     */
    private fun arguments(items: List<Pair<String, Boolean>>): String? {
        if (items.isEmpty()) {
            return null
        }
        val alternatives = mutableListOf<String>()
        for (j in items.indices) {
            val rest = items.drop(j + 1).joinToString("") { (expr, required) ->
                if (required) " $separator $expr" else " ( $separator $expr )?"
            }
            alternatives += items[j].first + rest
            if (items[j].second) {
                break // a required argument can't be skipped
            }
        }
        val joined = alternatives.joinToString(" | ")
        return when {
            items.none { it.second } -> "( $joined )?"
            alternatives.size > 1 -> "( $joined )"
            else -> joined
        }
    }

    companion object {
        fun ruleName(toolName: String) = "call-" + toolName.replace('_', '-')

        /** A GBNF string literal (llama.cpp grammar-parser escapes; UTF-8 is kept as is). */
        fun literal(text: String): String {
            val out = StringBuilder("\"")
            for (c in text) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c < ' ') {
                        out.append("\\x").append(c.code.toString(16).padStart(2, '0'))
                    } else {
                        out.append(c)
                    }
                }
            }
            return out.append('"').toString()
        }
    }
}
