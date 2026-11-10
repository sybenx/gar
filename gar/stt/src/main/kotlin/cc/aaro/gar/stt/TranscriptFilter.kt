package cc.aaro.gar.stt

/**
 * Decides whether a transcript is a command or something Whisper made up, after the text has been
 * turned into GAR's spoken form (lowercase words, no punctuation).
 *
 * Whisper writes stock phrases from its subtitle training data on silence or noise ("Thank you.",
 * "Thanks for watching!", "you"). The endpointer keeps most silence away from it; this catches the
 * rest: such a phrase is dropped when Whisper itself thinks there was no speech and the VAD heard
 * only a little.
 *
 * With contact names in the prompt ([NamePrompt]) Whisper can also copy them into audio it can't
 * make out: a transcript of prompt names only is dropped when Whisper wasn't sure of it, thought
 * there was no speech, or wrote a list of them (no command). A full name whose words are only in
 * the prompt as separate names ("Sarah" and "Lee" when "Sarah Lee" didn't fit) is no list.
 */
object TranscriptFilter {
    /** Above this no-speech probability a stock phrase counts as made up. */
    const val NO_SPEECH_THRESHOLD = 0.6f

    /** Below this much speech (by the VAD) a stock phrase counts as made up. */
    const val SHORT_SPEECH_MILLIS = 600

    /**
     * Below this confidence a transcript of prompt names only counts as copied from the prompt.
     * A name said on its own (the answer to "Who do you want to text?") comes out well above it.
     */
    const val PROMPT_ONLY_CONFIDENCE = 0.5f

    /**
     * Below this confidence prompt names of a few words that are not exactly one prompt name
     * ("aaron mom") count as copied; a full name said clearly comes out above it.
     */
    const val PROMPT_LIST_CONFIDENCE = 0.8f

    /**
     * Prompt names of more words than a full name has (`ContactPromptNames.MAX_FULL_NAME_WORDS`
     * in the app), or with a word repeated, are a copied list however sure Whisper is.
     */
    const val MAX_NAME_WORDS = 3

    /** What Whisper writes on silence, in spoken form. */
    val SILENCE_PHRASES: Set<String> = setOf(
        "thank you",
        "thanks for watching",
        "thank you for watching",
        "you",
        "bye",
        "so",
    )

    /**
     * Whether to pass [spoken] on as the user's input: false when it is empty, a stock phrase
     * Whisper wrote with a high no-speech probability on less than [SHORT_SPEECH_MILLIS] of speech,
     * or copied from the prompt (see the class).
     *
     * @param confidence the transcript's confidence (exp of the mean token log probability)
     * @param promptNames the names of the prompt the transcript was made with, in spoken form;
     * empty without a prompt
     */
    fun accept(
        spoken: String,
        noSpeechProb: Float,
        speechMillis: Int,
        confidence: Float = 1f,
        promptNames: List<String> = emptyList(),
    ): Boolean = rejection(spoken, noSpeechProb, speechMillis, confidence, promptNames) == null

    /** [accept] drops an empty transcript. */
    const val REJECT_EMPTY = "empty"

    /** [accept] drops a stock phrase Whisper wrote on silence. */
    const val REJECT_SILENCE_PHRASE = "silence_phrase"

    /** [accept] drops prompt names copied into unclear audio. */
    const val REJECT_PROMPT_NAMES = "prompt_names"

    /**
     * Why [accept] drops [spoken]: [REJECT_EMPTY], [REJECT_SILENCE_PHRASE] or
     * [REJECT_PROMPT_NAMES]; null when it passes. For the app's voice log.
     */
    fun rejection(
        spoken: String,
        noSpeechProb: Float,
        speechMillis: Int,
        confidence: Float = 1f,
        promptNames: List<String> = emptyList(),
    ): String? {
        val text = spoken.trim()
        if (text.isEmpty()) {
            return REJECT_EMPTY
        }
        val madeUp = noSpeechProb > NO_SPEECH_THRESHOLD &&
            speechMillis < SHORT_SPEECH_MILLIS &&
            text in SILENCE_PHRASES
        return when {
            madeUp -> REJECT_SILENCE_PHRASE
            copiedFromPrompt(text, noSpeechProb, confidence, promptNames) -> REJECT_PROMPT_NAMES
            else -> null
        }
    }

    private fun copiedFromPrompt(
        text: String,
        noSpeechProb: Float,
        confidence: Float,
        promptNames: List<String>,
    ): Boolean {
        if (promptNames.isEmpty() || !NamePrompt.onlyNames(text, promptNames)) {
            return false
        }
        // NaN (no text tokens) counts as unsure
        if (!(confidence >= PROMPT_ONLY_CONFIDENCE) || noSpeechProb > NO_SPEECH_THRESHOLD) {
            return true
        }
        if (NamePrompt.oneName(text, promptNames)) {
            return false
        }
        val words = NamePrompt.words(text)
        return words.size > MAX_NAME_WORDS ||
            words.toSet().size < words.size ||
            confidence < PROMPT_LIST_CONFIDENCE
    }

    /**
     * The utterance score to report for [confidence] (exp of the mean token log probability):
     * within 0 to 1, and 0 when it is not a number (no text tokens).
     */
    fun score(confidence: Float): Float =
        if (confidence.isNaN()) 0f else confidence.coerceIn(0f, 1f)
}
