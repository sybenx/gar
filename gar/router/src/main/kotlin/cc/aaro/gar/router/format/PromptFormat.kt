package cc.aaro.gar.router.format

import cc.aaro.gar.router.tools.ToolSpec

/**
 * How one model family is prompted, constrained and parsed. Implementations:
 * [HermesFormat] (Qwen3) and [FunctionGemmaFormat]. Prompts must be byte-identical to what
 * llama.cpp's chat template renders for the same tools and user message.
 */
interface PromptFormat {
    val family: ModelFamily

    /**
     * The pseudo-tool the model calls when no tool applies, declared after [tools][staticPrefix].
     * Its name must not clash with an offered tool ([staticPrefix] and [grammar] throw
     * IllegalArgumentException); parsing a call of it gives [ParsedOutput.NoMatch].
     */
    val noMatch: ToolSpec get() = ToolSchemas.noMatch

    /** Static prompt part with all [tools] declared (plus the [noMatch] pseudo-tool, last). */
    fun staticPrefix(tools: List<ToolSpec>): String

    /** Per-request prompt part: the user turn with [utterance] and the assistant turn opener. */
    fun suffix(utterance: String): String

    /**
     * GBNF grammar allowing exactly one call to one of [tools] or [noMatch], with slot values
     * restricted to enum values or to contiguous word spans of [utterance] (already normalized).
     */
    fun grammar(tools: List<ToolSpec>, utterance: String): String

    /** Strings that end generation (the call's closing token). */
    val stopStrings: List<String>

    /** Parses generated text. Never throws. */
    fun parse(raw: String, tools: List<ToolSpec>): ParsedOutput
}

enum class ModelFamily { HERMES, FUNCTION_GEMMA }

sealed interface ParsedOutput {
    /** [slots] holds raw slot strings exactly as generated, only for keys the tool declares. */
    data class Call(val tool: ToolSpec, val slots: Map<String, String>) : ParsedOutput
    data object NoMatch : ParsedOutput
    data class Malformed(val reason: String) : ParsedOutput
}
