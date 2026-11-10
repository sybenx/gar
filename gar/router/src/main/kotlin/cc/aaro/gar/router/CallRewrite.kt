package cc.aaro.gar.router

import cc.aaro.gar.router.tools.ToolSpec

/**
 * Rewrites a validated call before the [CallFilter] checks it ([LlmRouter]): a call of one tool
 * whose words plainly ask for another offered tool becomes that tool's call. Like the filters, it
 * doesn't change what the model generates, so the eval scores it on recorded outputs too. A
 * rewrite that throws keeps the call. See gar/DESIGN.md, "Call filters".
 */
fun interface CallRewrite {
    /** [call] as it should run, or [call] itself; [tools] are the tools offered to the model. */
    fun rewrite(call: RouterOutput.Call, tools: List<ToolSpec>): RouterOutput.Call
}

/**
 * gar:media. With `media_play` in the prompt, Qwen3-0.6B gave it 15 of the 18 commands that
 * `media_control` took before (gar/eval/README.md, 2026-10-27), and `media_play`'s keywords then
 * dropped most of them ("pause the music" -> `media_play(query: "pause the music")`). A
 * `media_play` call whose words are a transport control becomes the `media_control` call they ask
 * for, when `media_control` is offered: the input opens with a control verb ("pause the music",
 * "skip this song", "keep playing", "go back a song"), or asks to play nothing but the next or the
 * last track ("play the last song again"). A request that names what to play ("play stop and
 * stare", "play back in black") stays `media_play`. English, like the keywords.
 */
object MediaControlRewrite : CallRewrite {
    const val MEDIA_PLAY = "media_play"
    const val MEDIA_CONTROL = "media_control"
    private const val ACTION = "action"

    override fun rewrite(call: RouterOutput.Call, tools: List<ToolSpec>): RouterOutput.Call {
        if (call.tool.name != MEDIA_PLAY) {
            return call
        }
        val control = tools.firstOrNull { it.name == MEDIA_CONTROL } ?: return call
        val action = actionOf(call.utterance) ?: return call
        if (control.slots.none { it.name == ACTION && action in it.enumValues }) {
            return call
        }
        return RouterOutput.Call(control, mapOf(ACTION to action), listOf(), call.utterance)
    }

    /**
     * The `media_control` action (play, pause, next, previous) that [utterance] asks for, or null
     * for a request to play something.
     */
    fun actionOf(utterance: String): String? {
        val words = withoutOpenings(Utterances.words(Utterances.normalize(utterance)))
        CONTROLS.firstOrNull { (phrase, _) -> startsWith(words, phrase) }?.let { return it.second }
        // "play the last song again", "put on the next track"
        val verb = PLAY_VERBS.firstOrNull { startsWith(words, it) } ?: return null
        val named = words.drop(verb.size).filter { it !in TRACK_WORDS }
        return when (named) {
            listOf("last"), listOf("previous"), listOf("prior") -> PREVIOUS
            listOf("next"), listOf("following") -> NEXT
            else -> null
        }
    }

    private fun startsWith(words: List<String>, phrase: List<String>): Boolean =
        words.size >= phrase.size && words.subList(0, phrase.size) == phrase

    private fun withoutOpenings(words: List<String>): List<String> {
        var rest = words
        while (true) {
            val opening = OPENINGS.firstOrNull { rest.size > it.size && startsWith(rest, it) }
                ?: return rest
            rest = rest.drop(opening.size)
        }
    }

    private const val PLAY = "play"
    private const val PAUSE = "pause"
    private const val NEXT = "next"
    private const val PREVIOUS = "previous"

    private fun phrases(vararg phrases: String) = phrases.map { it.split(" ") }

    private val OPENINGS = phrases(
        "ok", "okay", "hey", "please", "so", "um", "uh", "oh", "and", "just", "now", "alright",
        "can you", "could you", "would you", "will you", "can u", "go ahead and", "i want to",
        "i wanna", "i'd like to", "let's", "lets",
    )

    /** What a control opens with ("paws" is how STT hears "pause"), the longer ones first. */
    private val CONTROLS: List<Pair<List<String>, String>> = listOf(
        "keep playing" to PLAY, "keep going" to PLAY, "carry on" to PLAY, "un pause" to PLAY,
        "go back" to PREVIOUS, "resume" to PLAY, "unpause" to PLAY, "continue" to PLAY,
        "pause" to PAUSE, "paws" to PAUSE, "stop" to PAUSE, "hold" to PAUSE, "halt" to PAUSE,
        "freeze" to PAUSE, "next" to NEXT, "skip" to NEXT, "previous" to PREVIOUS,
    ).map { (phrase, action) -> Pair(phrase.split(" "), action) }

    private val PLAY_VERBS = phrases("put on", "go to", "switch to", "jump to", "play")

    /** Words around "last" or "next" that name no particular thing to play. */
    private val TRACK_WORDS = setOf(
        "the", "a", "my", "song", "track", "tune", "one", "music", "video", "episode", "again",
        "please", "in", "queue", "that", "this",
    )
}
