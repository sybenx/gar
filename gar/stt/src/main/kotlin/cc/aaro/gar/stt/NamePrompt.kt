package cc.aaro.gar.stt

/**
 * The initial prompt that makes Whisper expect the phone's contact names (whisper-design.md
 * sections 14 and 16b): "aaron" was heard as "erin".
 *
 * The prompt is the names as a comma-separated list, " Aaron, Mom, Sarah Lee.", with a leading
 * space as OpenAI's `initial_prompt` has it, so a name is the same token in the prompt as in the
 * middle of a command. whisper.cpp puts it after `<|startofprev|>`, before the start of the
 * transcript. Each prompt token costs a full pass of the decoder's output layer, so the list is
 * cut to [MAX_TOKENS].
 */
object NamePrompt {
    /**
     * The longest prompt, in tokens: the owner and the few people the user names most. On the
     * Pixel a 40-token prompt of other names cost about 100 ms more than none and pulled a clear
     * "Call Aaron." to "Call Youran" (owner not in it); "Aaron, Mom, Dad" (6 tokens) was right.
     */
    const val MAX_TOKENS = 16

    /** The prompt for [names], in this order; empty for no names. */
    fun text(names: List<String>): String =
        if (names.isEmpty()) "" else names.joinToString(", ", prefix = " ", postfix = ".")

    /**
     * The first of [names] whose prompt ([text]) takes at most [maxTokens] tokens: a name that
     * doesn't fit is skipped, a shorter one after it may still fit. Blank names and repeats
     * (ignoring case) are left out.
     *
     * [countTokens] gets each name once, after a space as in the prompt (" Sarah Lee"); the
     * prompt counts as those plus one token for each comma and the full stop, since Whisper's
     * tokenizer splits words from punctuation. That is short only when a name ends in
     * punctuation, so the caller checks the whole prompt. Stops once no name could fit.
     */
    fun fit(names: List<String>, maxTokens: Int, countTokens: (String) -> Int): List<String> {
        val kept = mutableListOf<String>()
        val seen = HashSet<String>()
        var used = 0
        for (raw in names) {
            if (maxTokens - used < MIN_NAME_TOKENS) {
                break
            }
            val name = raw.trim()
            if (name.isEmpty() || !seen.add(name.lowercase())) {
                continue
            }
            // the name and its comma or full stop
            val tokens = countTokens(" $name") + 1
            if (used + tokens <= maxTokens) {
                kept += name
                used += tokens
            }
        }
        return kept
    }

    /**
     * Whether [spoken] (GAR's spoken form: lowercase words) is made only of the words of
     * [spokenNames] (the prompt's names in spoken form). Whisper copies prompt words into audio
     * it can't make out, so such a transcript is suspect.
     */
    fun onlyNames(spoken: String, spokenNames: List<String>): Boolean {
        val words = words(spoken)
        if (words.isEmpty()) {
            return false
        }
        val nameWords = spokenNames.flatMapTo(HashSet()) { words(it) }
        return nameWords.containsAll(words)
    }

    /** Whether [spoken] is exactly one of [spokenNames], word for word. */
    fun oneName(spoken: String, spokenNames: List<String>): Boolean {
        val words = words(spoken)
        return spokenNames.any { words(it) == words }
    }

    internal fun words(text: String): List<String> =
        text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    /** A name of one token and its comma. */
    private const val MIN_NAME_TOKENS = 2

    private val WHITESPACE = Regex("\\s+")
}
