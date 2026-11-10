package cc.aaro.gar.router

/**
 * A check [LlmRouter] runs on each call the model made, after validating its slots. A call it
 * drops becomes [RouterOutput.Dropped], which callers treat like [RouterOutput.NoMatch]. Filters
 * catch misroutes that the prompt doesn't prevent, see gar/DESIGN.md, "Call filters".
 */
fun interface CallFilter {
    fun check(call: RouterOutput.Call): CallVerdict

    companion object {
        /**
         * Runs [filters] in order; the first one that drops the call decides. A filter that throws
         * keeps the call, like in [LlmRouter], and the filters after it still run.
         */
        fun allOf(vararg filters: CallFilter): CallFilter = CallFilter { call ->
            filters.asSequence()
                .map {
                    try {
                        it.check(call)
                    } catch (e: Exception) {
                        CallVerdict.Keep
                    }
                }
                .firstOrNull { it is CallVerdict.Drop }
                ?: CallVerdict.Keep
        }
    }
}

sealed interface CallVerdict {
    data object Keep : CallVerdict

    /** [reason] says which check dropped the call and why, for logs and the eval. */
    data class Drop(val reason: String) : CallVerdict
}
