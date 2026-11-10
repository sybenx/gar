package cc.aaro.gar.router

import cc.aaro.gar.router.engine.CompletionEngine
import cc.aaro.gar.router.engine.CompletionRequest
import cc.aaro.gar.router.engine.StopReason
import cc.aaro.gar.router.format.ParsedOutput
import cc.aaro.gar.router.format.PromptFormat
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Maps one transcript to one tool call with raw slot strings, or to no match. Slot values are
 * NOT resolved here; see the `resolve` package.
 *
 * @param timeoutMillis how long one [route] call may take (prefill included) before it gives up
 * with [RouterOutput.Failed]
 * @param filter checks each validated call; a call it drops becomes [RouterOutput.Dropped]. A
 * filter that throws keeps the call.
 * @param rewrite rewrites each validated call before [filter] checks it; one that throws keeps the
 * call
 */
class LlmRouter(
    private val engine: CompletionEngine,
    private val format: PromptFormat,
    private val maxTokens: Int = 96,
    private val timeoutMillis: Long = 20_000,
    private val filter: CallFilter? = null,
    private val rewrite: CallRewrite? = MediaControlRewrite,
) {
    // the static prefix only changes when the offered tools change
    @Volatile
    private var prefixCache: Pair<List<ToolSpec>, String>? = null

    /**
     * Never throws, except [CancellationException] when the calling coroutine is cancelled.
     *
     * @param utterance the best STT alternative (normalized internally: lowercase, collapsed
     * whitespace, punctuation other than apostrophes removed, see [Utterances.normalize])
     * @param tools the tools to offer, in catalog order (`no_match` is added by the format)
     */
    suspend fun route(utterance: String, tools: List<ToolSpec>): RouterOutput {
        val normalized = Utterances.normalize(utterance)
        if (normalized.isEmpty() || tools.isEmpty()) {
            return RouterOutput.NoMatch // the grammar would only allow no_match anyway
        }

        val completion = try {
            val request = CompletionRequest(
                prefix = prefixFor(tools),
                suffix = format.suffix(normalized),
                grammar = format.grammar(tools, normalized),
                maxTokens = maxTokens,
                stopStrings = format.stopStrings,
            )
            withTimeoutOrNull(timeoutMillis) { engine.complete(request) }
                ?: return RouterOutput.Failed("timed out after $timeoutMillis ms")
        } catch (e: CancellationException) {
            // rethrow only if the caller was cancelled; the engine's own cancellation (closed
            // while generating, an inner timeout) is a failure like any other
            currentCoroutineContext().ensureActive()
            return RouterOutput.Failed("engine cancelled: $e", e)
        } catch (e: Exception) {
            return RouterOutput.Failed("engine error: $e", e)
        }
        if (completion.stopReason == StopReason.CANCELLED) {
            return RouterOutput.Failed("generation cancelled")
        }

        return when (val parsed = format.parse(completion.text, tools)) {
            ParsedOutput.NoMatch -> RouterOutput.NoMatch
            is ParsedOutput.Malformed -> RouterOutput.Failed(
                if (completion.stopReason == StopReason.MAX_TOKENS) {
                    "${parsed.reason} (output hit the $maxTokens token limit)"
                } else {
                    parsed.reason
                }
            )
            is ParsedOutput.Call -> filtered(rewritten(validate(parsed, normalized), tools))
        }
    }

    private fun rewritten(call: RouterOutput.Call, tools: List<ToolSpec>): RouterOutput.Call = try {
        rewrite?.rewrite(call, tools) ?: call
    } catch (e: Exception) {
        call // a broken rewrite must not cost commands
    }

    private fun filtered(call: RouterOutput.Call): RouterOutput {
        val verdict = try {
            filter?.check(call) ?: CallVerdict.Keep
        } catch (e: Exception) {
            CallVerdict.Keep // a broken filter must not cost commands
        }
        return when (verdict) {
            CallVerdict.Keep -> call
            is CallVerdict.Drop -> RouterOutput.Dropped(call, verdict.reason)
        }
    }

    /**
     * Pre-evaluates the static prefix for [tools] so the first [route] call is fast. Errors are
     * swallowed here: the following [route] call reports them.
     */
    suspend fun warmUp(tools: List<ToolSpec>) {
        try {
            engine.warmUp(prefixFor(tools))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the engine's own cancellation is swallowed
        } catch (e: Exception) {
            // route() will run into the same problem and return Failed
        }
    }

    /**
     * Keeps only slots whose value is one of the enum values (enum slots) or a word span of the
     * utterance (all others), so the model can't invent text the user didn't say. Values are
     * normalized like the utterance, so "Ten Minutes" counts as the span "ten minutes".
     */
    private fun validate(parsed: ParsedOutput.Call, utterance: String): RouterOutput.Call {
        val words = Utterances.words(utterance)
        val slots = linkedMapOf<String, String>()
        for (slot in parsed.tool.slots) {
            val raw = parsed.slots[slot.name] ?: continue
            if (slot.kind == SlotKind.ENUM) {
                val value = raw.trim()
                if (value in slot.enumValues) {
                    slots[slot.name] = value
                }
            } else {
                val value = Utterances.normalize(raw)
                if (Utterances.isSpan(value, words)) {
                    slots[slot.name] = value
                }
            }
        }
        return RouterOutput.Call(
            tool = parsed.tool,
            rawSlots = slots,
            missingRequired = parsed.tool.slots
                .filter { it.required && it.name !in slots }
                .map { it.name },
            utterance = utterance,
        )
    }

    private fun prefixFor(tools: List<ToolSpec>): String {
        prefixCache?.let { (cachedTools, prefix) -> if (cachedTools == tools) return prefix }
        return format.staticPrefix(tools).also { prefixCache = Pair(tools, it) }
    }
}

sealed interface RouterOutput {
    /**
     * [rawSlots] holds only valid values (declared key, value is an enum value or a word span of
     * the utterance). [missingRequired] lists required slots the model didn't fill.
     */
    data class Call(
        val tool: ToolSpec,
        val rawSlots: Map<String, String>,
        val missingRequired: List<String>,
        val utterance: String,
    ) : RouterOutput

    data object NoMatch : RouterOutput

    /**
     * A valid [call] that the router's [CallFilter] dropped as a misroute, for [reason]. Treated
     * like [NoMatch] by callers; kept apart so logs and the eval can tell what was dropped.
     */
    data class Dropped(val call: Call, val reason: String) : RouterOutput

    /** Malformed output, engine error or timeout. Treated like no match by callers. */
    data class Failed(val reason: String, val cause: Throwable? = null) : RouterOutput
}
