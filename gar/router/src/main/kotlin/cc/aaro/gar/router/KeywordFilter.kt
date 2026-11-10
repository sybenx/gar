package cc.aaro.gar.router

/**
 * Guard B: drops a call of a tool with [cc.aaro.gar.router.tools.ToolSpec.keywords] when the
 * utterance contains none of them. For tools whose requests always name what they are about
 * ("time", "alarm", "volume", ...) but that the model also calls for unrelated requests ("good
 * morning" -> `current_time`, "what's the capital of france" -> `date_query`).
 *
 * A keyword matches consecutive words of the normalized utterance; each of its words matches the
 * same word or the word with a common English ending ([ENDINGS]: "alarm" matches "alarms",
 * "disturb" matches "disturbed", "loud" matches "louder"). Keywords are English, like GAR's slot
 * resolution and follow-up answers; the router only runs for English.
 */
object KeywordFilter : CallFilter {
    override fun check(call: RouterOutput.Call): CallVerdict {
        val keywords = call.tool.keywords
        return if (keywords.isEmpty() || containsAny(call.utterance, keywords)) {
            CallVerdict.Keep
        } else {
            CallVerdict.Drop("none of the keywords of ${call.tool.name} in '${call.utterance}'")
        }
    }

    /** Whether the normalized [utterance] contains one of the normalized [keywords]. */
    fun containsAny(utterance: String, keywords: List<String>): Boolean {
        val words = Utterances.words(utterance)
        return keywords.any { keyword ->
            val keywordWords = Utterances.words(keyword)
            keywordWords.isNotEmpty() && (0..words.size - keywordWords.size).any { start ->
                keywordWords.indices.all { wordMatches(words[start + it], keywordWords[it]) }
            }
        }
    }

    private fun wordMatches(word: String, keyword: String): Boolean =
        word == keyword || (word.startsWith(keyword) && word.substring(keyword.length) in ENDINGS)

    /**
     * Endings a keyword word may have in the utterance. Kept short on purpose: "time" must not
     * match "timer", nor "late" "later".
     */
    private val ENDINGS = setOf("s", "es", "'s", "d", "ed", "er", "est", "ing")
}
