package cc.aaro.gar.stt

/**
 * The result of one Whisper call on one clip.
 *
 * @param text segment 0's text as Whisper wrote it (punctuation, digits, casing), trimmed; can be
 * empty or a non-speech marker like "[BLANK_AUDIO]"
 * @param noSpeechProb Whisper's no-speech probability for the segment, 0 to 1
 * @param confidence exp(mean ln p) over the text tokens, 0 to 1
 * @param tokens text tokens decoded
 * @param audioCtx the encoder context the call used (see [AudioCtx])
 * @param encodeMicros time spent in the encoder
 * @param totalMicros time of the whole call
 * @param promptNames the names in the initial prompt ([NamePrompt]), empty without one
 * @param promptTokens the initial prompt's tokens
 */
data class Transcript(
    val text: String,
    val noSpeechProb: Float,
    val confidence: Float,
    val tokens: Int,
    val audioCtx: Int,
    val encodeMicros: Long,
    val totalMicros: Long,
    val promptNames: List<String> = emptyList(),
    val promptTokens: Int = 0,
)
