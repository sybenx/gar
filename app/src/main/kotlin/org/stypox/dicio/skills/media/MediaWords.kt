package org.stypox.dicio.skills.media

import cc.aaro.gar.router.Utterances
import org.stypox.dicio.gar.conversation.GarRinging
import org.stypox.dicio.gar.media.MediaCommand
import org.stypox.dicio.gar.media.MediaFocus
import org.stypox.dicio.gar.media.MediaPlayRequest
import org.stypox.dicio.gar.media.RepeatMode
import org.stypox.dicio.sentences.Sentences.Media

/**
 * "Play taylor swift on spotify", read in English (gar/PARITY.md, batch 4). Dicio's matcher takes
 * a sentence with a word left out or swapped, so [MediaPlaySkill] only keeps a match that this
 * reads as a request to play something named: "play the next song", "play music" and "play it
 * again" stay with Dicio's media skill, "play a game" and "play my voicemail" aren't music.
 */
object MediaPlayWords {
    /** A request to play something, or null if [input] isn't one (see [MediaPlayWords]). */
    fun parse(input: String): MediaPlayRequest? {
        val words = afterVerb(withoutFillers(words(input))) ?: return null
        return request(words, splitApp = true)
    }

    /**
     * The request of the model's `media_play` call: [query] as the model cut it (it may still
     * hold the verb, or "on spotify" when it left [app] out), [app] the launcher label GAR
     * resolved. A query that only says "music" plays anything; null for one that is no request
     * to play something named: a skip ("the last song", which the router gives `media_control`
     * when the words say so), another thing than media ("a game"), or words no title starts with.
     */
    fun fromSlots(query: String, app: String?): MediaPlayRequest? {
        val said = withoutFillers(words(query))
        val words = afterVerb(said) ?: said
        request(words, splitApp = app == null)
            ?.let { return if (app != null) it.copy(app = app) else it }
        return MediaPlayRequest(said = "", query = "", app = app)
            .takeIf { trimEnd(words).all { it in GENERIC } }
    }

    /**
     * Whether [utterance] says [app] after on, in, with, using, through, via or from ("play jazz
     * on spotify"): the model fills `media_play`'s optional app with leftover words too ("put on
     * the new kendrick lamar album" -> app "album"), which GAR would then ask about.
     */
    fun saysApp(utterance: String, app: String): Boolean {
        val words = words(utterance)
        val appWords = words(app).dropWhile { it == "the" || it == "my" }
        if (appWords.isEmpty()) {
            return false
        }
        return words.indices.any { i ->
            i > 0 && words[i - 1] in APP_CONNECTORS &&
                words.drop(i).dropWhile { it == "the" || it == "my" }.take(appWords.size) == appWords
        }
    }

    private fun words(input: String): List<String> = Utterances.words(Utterances.normalize(input))

    private fun request(words: List<String>, splitApp: Boolean): MediaPlayRequest? {
        val rest = trimEnd(words)
        if (rest.isEmpty()) {
            return null
        }
        if (splitApp) {
            splitApp(rest)?.let { (what, app) ->
                return build(what, app.joinToString(" "))?.copy(whole = build(rest, null))
            }
        }
        return build(rest, null)
    }

    /**
     * The request for [words] (what to play), in [app] if not null. Without an app, words that
     * name no particular thing ("music", "something") are Dicio's play key; with one, they play
     * anything there ("play some music on spotify").
     */
    private fun build(words: List<String>, app: String?): MediaPlayRequest? {
        if (words.all { it in GENERIC }) {
            return if (app == null) null else MediaPlayRequest(said = "", query = "", app = app)
        }
        if (words.any { it in SKIP_WORDS } ||
            (words.contains("last") && words.filter { it != "last" }.all { it in GENERIC }) ||
            // "play something else", "play another song": a skip, not a title ("play another
            // brick in the wall", "play nothing else matters")
            (words.any { it in OTHER_ONE } && words.filter { it !in OTHER_ONE }.all { it in GENERIC }) ||
            words.any { it in NOT_MEDIA } ||
            words.first() in NOT_FIRST
        ) {
            return null
        }
        val said = words.joinToString(" ")
        // "some jazz" searches for jazz
        val query = (if (words.size > 1 && words.first() == "some") words.drop(1) else words)
            .joinToString(" ")
        return focused(words, said, query).copy(app = app)
    }

    /** The focus the words give ([MediaFocus]), with the fields of that focus. */
    private fun focused(words: List<String>, said: String, query: String): MediaPlayRequest {
        val plain = MediaPlayRequest(said = said, query = query)
        if (words.any { it in PODCAST_WORDS }) {
            return plain.copy(focus = MediaFocus.PODCAST)
        }
        if (words.any { it == "playlist" || it == "playlists" }) {
            val name = words.filter { it !in PLAYLIST_FILLERS }
            if (name.isEmpty()) {
                return plain
            }
            return plain.copy(
                focus = MediaFocus.PLAYLIST,
                playlist = name.joinToString(" "),
                // also found by an app that searches all its music with it
                query = words.dropWhile { it == "my" || it == "the" || it == "a" }.joinToString(" "),
            )
        }
        val by = words.indexOf("by").takeIf { it > 0 && it < words.size - 1 }
        val artist = by?.let { words.subList(it + 1, words.size).joinToString(" ") }
        val before = by?.let { words.subList(0, it) } ?: words
        if (words.contains("album")) {
            val album = before.filter { it !in ALBUM_FILLERS }
            if (album.isEmpty()) {
                return plain
            }
            val name = album.joinToString(" ")
            return plain.copy(
                focus = MediaFocus.ALBUM,
                album = name,
                artist = artist,
                query = listOfNotNull(name, artist).joinToString(" "),
            )
        }
        val songStart = before.indexOfFirst { it == "song" || it == "track" }
        if (songStart >= 0 && before.subList(0, songStart).all { it == "the" || it == "a" } &&
            songStart + 1 < before.size && before[songStart + 1] !in PREPOSITIONS
        ) {
            val title = before.subList(songStart + 1, before.size).joinToString(" ")
            return plain.copy(
                focus = MediaFocus.SONG,
                title = title,
                artist = artist,
                query = listOfNotNull(title, artist).joinToString(" "),
            )
        }
        // "music by the beatles", "something by queen"
        if (artist != null && before.all { it in GENERIC }) {
            return plain.copy(focus = MediaFocus.ARTIST, artist = artist, query = artist)
        }
        // "the artist adele", "the band queen"
        val artistWord = words.indexOfFirst { it in ARTIST_WORDS }
        if (artistWord >= 0 && by == null) {
            val name = words.filterIndexed { i, word ->
                i != artistWord && !(i < artistWord && word in GENERIC)
            }
            if (name.isNotEmpty()) {
                val joined = name.joinToString(" ")
                return plain.copy(focus = MediaFocus.ARTIST, artist = joined, query = joined)
            }
        }
        return plain
    }

    /**
     * What to play and the app, when [words] end with "on|in|with|... <app>": split at the last
     * such word, with at most four words of app ("youtube music", "the spotify app"). Whether
     * those words are an app only the phone knows ([MediaPlayRequest.whole]).
     */
    private fun splitApp(words: List<String>): Pair<List<String>, List<String>>? {
        for (i in words.indices.reversed()) {
            if (i == 0 || words[i] !in APP_CONNECTORS) {
                continue
            }
            val app = words.subList(i + 1, words.size)
                .dropWhile { it == "the" || it == "my" }
                .let { if (it.lastOrNull() == "app" || it.lastOrNull() == "application") it.dropLast(1) else it }
            if (app.isEmpty() || app.size > 4 || app.joinToString(" ") in NOT_APPS) {
                return null
            }
            return Pair(words.subList(0, i), app)
        }
        return null
    }

    /**
     * The words after the verb ("play", "put on", "listen to"), or null without one. "Start" only
     * counts for a playlist, a podcast, an album or an episode ("start my workout playlist"), not
     * for "start the stopwatch".
     */
    private fun afterVerb(words: List<String>): List<String>? {
        val verb = VERBS.firstOrNull { verb ->
            words.size > verb.size && words.subList(0, verb.size) == verb
        } ?: listOf("start").takeIf {
            words.size > 1 && words[0] == "start" && words.drop(1).any { it in STARTABLE }
        } ?: return null
        var rest = words.drop(verb.size)
        // "play me some jazz", "put on some music for us"
        if (rest.size > 1 && (rest.first() == "me" || rest.first() == "us")) {
            rest = rest.drop(1)
        }
        return rest
    }

    /** [words] without polite openings ("can you", "i want to"), several of them. */
    private fun withoutFillers(words: List<String>): List<String> {
        var rest = words
        var changed = true
        while (changed && rest.isNotEmpty()) {
            changed = false
            for (filler in LEADING_FILLERS) {
                if (rest.size > filler.size && rest.subList(0, filler.size) == filler) {
                    rest = rest.drop(filler.size)
                    changed = true
                    break
                }
            }
        }
        return rest
    }

    /** [words] without "please", "for me", "now" or "again" at the end. */
    private fun trimEnd(words: List<String>): List<String> {
        var rest = words
        var changed = true
        while (changed && rest.size > 1) {
            changed = false
            for (filler in TRAILING_FILLERS) {
                if (rest.size > filler.size && rest.subList(rest.size - filler.size, rest.size) == filler) {
                    rest = rest.dropLast(filler.size)
                    changed = true
                    break
                }
            }
        }
        return rest
    }

    private fun phrases(vararg phrases: String) = phrases.map { it.split(" ") }

    private val VERBS = phrases(
        "start playing", "start streaming", "queue up", "put on", "throw on", "listen to", "play",
        "stream", "queue", "blast", "hear",
    )

    /**
     * What "start" plays ("start my discover weekly playlist"): the media_play tool's keywords
     * that name a thing, which GarMatchCheck then finds in the input.
     */
    private val STARTABLE = setOf("playlist", "playlists", "podcast", "podcasts", "album", "episode")

    private val LEADING_FILLERS = phrases(
        "ok", "okay", "hey", "please", "alright", "so", "now", "just", "um", "uh", "oh", "and",
        "can you", "could you", "would you", "will you", "can u", "i want to", "i wanna",
        "i'd like to", "i would like to", "id like to", "let me", "let's", "lets", "go ahead and",
        "i need to", "we want to", "can i", "could i", "can we",
    )

    private val TRAILING_FILLERS = phrases(
        "please", "for me", "for us", "now", "right now", "thanks", "thank you", "again",
    )

    /** Words that name no particular thing to play: Dicio's play key resumes the player. */
    private val GENERIC = setOf(
        "music", "song", "songs", "track", "tracks", "tune", "tunes", "something", "anything",
        "stuff", "it", "this", "that", "them", "media", "audio", "video", "videos", "playback",
        "sound", "sounds", "some", "the", "a", "an", "my", "any", "more", "all", "of",
        "everything", "good", "nice", "random", "whatever", "me", "us", "again", "playing",
        "player", "one", "here",
    )

    /** "play the next song", "play the previous track": Dicio's media sentences. */
    private val SKIP_WORDS = setOf("next", "previous", "prior", "following", "prev")

    /** "Play something else": [MediaControlWords] skips to the next track. */
    private val OTHER_ONE = setOf("else", "different", "another", "other")

    /** Things one plays that aren't music. */
    private val NOT_MEDIA = setOf(
        "game", "games", "voicemail", "voicemails", "message", "messages", "recording",
        "recordings", "lottery",
    )

    /** "play with the dog", "play it again", "play back the recording". */
    private val NOT_FIRST = setOf(
        "with", "along", "around", "by", "for", "to", "at", "it", "them", "him", "her", "back",
        "on", "in", "from", "off", "out",
    )

    private val APP_CONNECTORS = setOf("on", "in", "with", "using", "through", "via", "from")

    /** Words after "on" that are no app ("play it on repeat", "on the speaker"). */
    private val NOT_APPS = setOf(
        "repeat", "shuffle", "loop", "speaker", "speakers", "tv", "the tv", "phone", "bluetooth",
        "headphones", "headset", "full volume", "max", "max volume", "background", "order",
        "random", "random order", "the background", "low", "high", "volume", "my phone",
        "this phone", "the car", "car", "the kitchen", "kitchen", "spanish", "english",
        "french", "german",
    )

    private val PODCAST_WORDS = setOf("podcast", "podcasts", "episode", "episodes")

    private val PLAYLIST_FILLERS = setOf(
        "my", "the", "a", "playlist", "playlists", "called", "named", "titled",
    )

    private val ALBUM_FILLERS = setOf(
        "my", "the", "a", "album", "called", "named", "titled", "new", "latest", "whole",
    )

    private val ARTIST_WORDS = setOf("artist", "band", "singer", "rapper", "group")

    private val PREPOSITIONS = setOf(
        "from", "by", "about", "of", "in", "on", "with", "for", "that", "called", "named",
    )
}


/** What [MediaControlsSkill] does for an input. */
sealed interface MediaControlRequest {
    data class Control(val command: MediaCommand) : MediaControlRequest

    /**
     * Dicio's play, pause, next or previous, said in words its sentences miss ("keep playing",
     * "go back a song", "play something else"): its media key, which needs no notification access.
     */
    data class Key(val action: Media) : MediaControlRequest

    /** A sleep timer of [millis], or a question how long when null. */
    data class SleepSet(val millis: Long?) : MediaControlRequest

    data object SleepCancel : MediaControlRequest

    data object SleepQuery : MediaControlRequest
}

/**
 * A phrase of [MediaControlWords]: its [request], and whether it only counts while something
 * plays ([whilePlaying]: "repeat", "i like this", "thumbs up" mean the music only then).
 */
data class MediaControlMatch(val request: MediaControlRequest, val whilePlaying: Boolean = false)

/**
 * The media controls beyond Dicio's play, pause, next and previous, and the media sleep timer,
 * read in English. Each input must be one of these phrases as a whole (with polite fillers):
 * Dicio's matcher takes a sentence with a word left out, so "shuffle" would otherwise take
 * "shuffle the cards" and "repeat this song" "repeat that".
 */
object MediaControlWords {
    /**
     * The phrase [input] is, or null; [durationOf] reads a duration ("thirty seconds", "an hour
     * and a half") in milliseconds, null if the words are no duration.
     */
    fun parse(input: String, durationOf: (String) -> Long?): MediaControlMatch? {
        val phrase = phrase(input)
        if (phrase.isEmpty()) {
            return null
        }
        FIXED.firstOrNull { it.regex.matches(phrase) }
            ?.let { return MediaControlMatch(it.request, it.whilePlaying) }
        for ((regex, sign) in SEEKS) {
            val millis = regex.matchEntire(phrase)?.let { durationOf(it.groupValues[1]) } ?: continue
            if (millis in 1..MAX_SEEK_MILLIS) {
                return MediaControlMatch(MediaControlRequest.Control(MediaCommand.Seek(sign * millis)))
            }
        }
        for (regex in SLEEP_SETS) {
            val millis = regex.matchEntire(phrase)?.let { durationOf(it.groupValues[1]) } ?: continue
            if (millis > 0) {
                return MediaControlMatch(MediaControlRequest.SleepSet(millis))
            }
        }
        return null
    }

    /**
     * "Stop", "pause it": they pause what plays, but only while something plays and nothing
     * rings (the skill checks): otherwise they are the ringing timer's, or GAR's conversation's.
     */
    fun isBareStop(input: String): Boolean =
        GarRinging.isStopPhrase(input) || phrase(input) in BARE_PAUSE

    /** The answer to "For how long?": a duration alone, maybe after "for" ("for an hour"). */
    fun sleepAnswer(input: String, durationOf: (String) -> Long?): Long? {
        val words = phrase(input).split(" ").filter { it.isNotEmpty() }
        val start = ANSWER_OPENINGS.firstOrNull { opening ->
            words.size > opening.size && words.subList(0, opening.size) == opening
        }?.size ?: 0
        val rest = words.drop(start)
        if (rest.isEmpty()) {
            return null
        }
        return durationOf(rest.joinToString(" "))?.takeIf { it > 0 }
    }

    /**
     * [input] normalized, without apostrophes ("dont"), without polite openings ("ok can you")
     * and closings ("please", "thanks").
     */
    private fun phrase(input: String): String {
        var words = Utterances.words(Utterances.normalize(input)).map { it.replace("'", "") }
        var changed = true
        while (changed && words.size > 1) {
            changed = false
            OPENINGS.firstOrNull { words.size > it.size && words.subList(0, it.size) == it }?.let {
                words = words.drop(it.size)
                changed = true
            }
            CLOSINGS.firstOrNull {
                words.size > it.size && words.subList(words.size - it.size, words.size) == it
            }?.let {
                words = words.dropLast(it.size)
                changed = true
            }
        }
        return words.joinToString(" ")
    }

    private fun phrases(vararg phrases: String) = phrases.map { it.split(" ") }

    private val OPENINGS = phrases(
        "ok", "okay", "hey", "please", "alright", "so", "now", "just", "um", "uh", "oh", "and",
        "can you", "could you", "would you", "will you", "can u", "go ahead and",
    )

    private val CLOSINGS = phrases("please", "now", "thanks", "thank you", "for me")

    private val ANSWER_OPENINGS = phrases(
        "set it for", "set it to", "make it", "lets say", "how about", "for", "in", "about",
        "say", "maybe",
    )

    private val BARE_PAUSE = setOf("pause", "pause it", "pause this", "pause that")

    /** The longest seek: longer than any track would ever need. */
    private const val MAX_SEEK_MILLIS = 6 * 60 * 60 * 1000L

    // parts of the patterns below
    private const val THIS = "(?:the |this |that |my |the current |this current )?"
    private const val TRACK = "(?:song|track|tune|video|episode|podcast|chapter|music|audio)"
    private const val MEDIA = "(?:music|audio|song|songs|podcast|podcasts|playback|media|sound|" +
        "radio|video|player|audiobook|book|episode|tunes|stream)"
    private const val THE_TRACK = "(?:the|this|that|my|the current) $TRACK"
    private const val TURN = "(?:turn|switch|put|set)"
    private const val QUEUE = "(?:playlist|album|queue|list|songs|tracks|music)"
    /** What one resumes beyond Dicio's nouns ("resume the music" is its own). */
    private const val RESUMABLE = "(?:playback|podcast|episode|audiobook|radio|playlist|chapter)"

    private class Phrase(
        val regex: Regex,
        val request: MediaControlRequest,
        val whilePlaying: Boolean,
    )

    private fun phrasesFor(
        request: MediaControlRequest,
        vararg patterns: String,
        whilePlaying: Boolean = false,
    ): List<Phrase> = patterns.map { Phrase(Regex(it), request, whilePlaying) }

    private fun control(command: MediaCommand) = MediaControlRequest.Control(command)

    /** Phrases without a duration, in order: the first that matches counts. */
    private val FIXED: List<Phrase> = listOf(
        // Dicio's play, pause, next and previous in words its media sentences don't have (they
        // take "resume the music", "hold the music", "skip the song", "the next one", "go back to
        // the previous track" themselves)
        phrasesFor(
            MediaControlRequest.Key(Media.Play),
            "resume",
            "resume (?:the |my )?$RESUMABLE",
            "resume (?:playing|listening)(?: to)?(?: (?:the |my )?(?:$TRACK|$RESUMABLE))?",
            "keep playing(?: (?:the |my )?(?:$TRACK|$RESUMABLE))?",
            "continue (?:the |my )?(?:$TRACK|$RESUMABLE)",
            "continue (?:playing|listening)(?: to)? (?:the |my )?$RESUMABLE",
            "continue listening(?: to (?:the |my )?$TRACK)?",
            "unpause(?: (?:it|this))?",
        ),
        phrasesFor(
            MediaControlRequest.Key(Media.Pause),
            "(?:pause|hold|stop) (?:the |this |my )?(?:$MEDIA|$TRACK) for a (?:second|sec|moment|minute|bit)",
            "paws(?: (?:it|this|the $MEDIA|the $TRACK))?",
        ),
        phrasesFor(
            MediaControlRequest.Key(Media.Next),
            "next one",
            "skip (?:this one|it|forward)",
            "play (?:something|anything) (?:else|different)",
            "play (?:another|a different|some other|something other|a new) (?:$TRACK|one)",
        ),
        phrasesFor(
            MediaControlRequest.Key(Media.Previous),
            "previous one",
            "(?:go |skip |jump )?back (?:a|one) (?:song|track|tune)",
            "go back to the (?:previous|last) one",
        ),
        // shuffle off before on: "turn shuffle off", "no shuffle"
        phrasesFor(
            control(MediaCommand.Shuffle(false)),
            "$TURN off (?:the )?shuffle(?: mode)?",
            "$TURN (?:the )?shuffle(?: mode)? off",
            "shuffle(?: mode)? off",
            "(?:disable|deactivate|stop|end|cancel|unset) (?:the )?shuffle(?: mode)?",
            "stop shuffling(?: (?:it|them|the songs|the music))?",
            "(?:dont|do not) shuffle(?: (?:it|them|the songs|the music|the playlist))?",
            "no (?:more )?shuffl(?:e|ing)",
            "unshuffle(?: (?:it|them|the songs|the music|the playlist|the queue))?",
            "play (?:it |them |the songs |the music |the playlist )?in order",
        ),
        phrasesFor(
            control(MediaCommand.Shuffle(true)),
            "$TURN on (?:the )?shuffle(?: mode)?",
            "$TURN (?:the )?shuffle(?: mode)? on",
            "shuffle(?: mode)? on",
            "(?:enable|activate|start) (?:the )?shuffle(?: mode)?",
            "start shuffling",
            "shuffle(?: (?:the|this|my|all|all the|all my))?(?: $QUEUE)?",
            "shuffle (?:it|them)",
            "shuffle play",
            "(?:play|put) (?:it |them |the music |the songs |the playlist )?(?:on|in) shuffle(?: mode)?",
            "(?:play|put) (?:it |them |the music |the songs )?in (?:a )?random order",
            "randomi[sz]e (?:it|them|the order|the songs|the music|the playlist|the queue)",
        ),
        // repeat off before the others: "turn repeat off", "stop repeating this song"
        phrasesFor(
            control(MediaCommand.Repeat(RepeatMode.OFF)),
            "$TURN off (?:the )?(?:repeat|loop|looping)(?: mode)?",
            "$TURN (?:the )?(?:repeat|loop|looping)(?: mode)? off",
            "(?:repeat|loop)(?: mode)? off",
            "(?:disable|deactivate|stop|end|cancel|unset) (?:the )?(?:repeat|loop|looping)(?: mode)?",
            "stop (?:repeating|looping)(?: (?:it|this|that|$THIS$TRACK|the $QUEUE))?",
            "(?:dont|do not) (?:repeat|loop)(?: (?:it|this|$THIS$TRACK|the $QUEUE))?",
            "no (?:more )?(?:repeat|repeating|loop|looping)",
        ),
        phrasesFor(
            control(MediaCommand.Repeat(RepeatMode.ONE)),
            "(?:repeat|loop) $THIS$TRACK",
            "(?:repeat|loop) (?:this|one|this one)",
            "loop it",
            "(?:put|set|play|keep) $THIS$TRACK on (?:repeat|loop)",
            "(?:put|set|play|keep) (?:it|this|this one) on (?:repeat|loop)",
            "$TURN on (?:repeat|loop) (?:one|for this $TRACK|for $THIS$TRACK)",
            "(?:repeat|loop) (?:the )?(?:same|current) $TRACK",
            "keep (?:repeating|looping) $THIS$TRACK",
        ),
        // "repeat" alone is GAR's conversation's when nothing plays (it doesn't take it either)
        phrasesFor(
            control(MediaCommand.Repeat(RepeatMode.ONE)),
            "repeat", "loop", "on repeat",
            whilePlaying = true,
        ),
        phrasesFor(
            control(MediaCommand.Repeat(RepeatMode.ALL)),
            "(?:repeat|loop) (?:all|everything|it all)",
            "(?:repeat|loop) (?:all|the|this|my|the whole|this whole|the entire|all the|all my) $QUEUE",
            "(?:put|set|play|keep) (?:the|this|my|the whole) $QUEUE on (?:repeat|loop)",
            "$TURN on (?:the )?(?:repeat|loop|looping)(?: mode| all)?",
            "$TURN (?:the )?(?:repeat|loop|looping)(?: mode)? on",
            "(?:repeat|loop)(?: mode)? on",
            "(?:enable|activate|start) (?:the )?(?:repeat|loop|looping)(?: mode)?",
        ),
        phrasesFor(
            control(MediaCommand.Restart),
            "(?:restart|replay) $THIS$TRACK",
            "restart (?:it|this|that)",
            "start $THIS$TRACK (?:over|again|from the (?:beginning|start|top))",
            "start (?:it|this|that) (?:over|again|from the (?:beginning|start|top))",
            "(?:play|take) (?:it|this|that|$THIS$TRACK) from the (?:beginning|start|top)",
            "play (?:it|this|that|this $TRACK|the $TRACK|that $TRACK) (?:again|one more time|over)",
            "(?:(?:go|skip|jump|rewind|rewind it|rewind $THIS$TRACK) )?(?:back )?to the " +
                "(?:beginning|start)(?: of $THIS$TRACK)?",
            "from the (?:top|beginning|start)",
            "rewind $THIS$TRACK",
        ),
        // dislike before like: "i don't like this song"
        phrasesFor(
            control(MediaCommand.Rate(false)),
            "(?:i )?(?:dont|do not|really dont|really do not) (?:like|love) (?:this|that|the current) $TRACK",
            "(?:i )?(?:really )?(?:hate|dislike) (?:this|that|the current) $TRACK",
            "dislike $THE_TRACK",
            "thumbs down (?:for |on |to )?$THIS$TRACK",
            "(?:give|put) (?:it|this|that|$THIS$TRACK) a thumbs down",
        ),
        phrasesFor(
            control(MediaCommand.Rate(false)),
            "(?:i )?(?:dont|do not|really dont|really do not) (?:like|love) (?:this|that|it)",
            "(?:i )?(?:really )?(?:hate|dislike) (?:this|that|it)",
            "dislike", "thumbs down",
            whilePlaying = true,
        ),
        phrasesFor(
            control(MediaCommand.Rate(true)),
            "(?:i )?(?:really )?(?:like|love) (?:this|that|the current) $TRACK",
            "(?:like|love) $THE_TRACK",
            "thumbs up (?:for |on |to )?$THIS$TRACK",
            "(?:give|put) (?:it|this|that|$THIS$TRACK) a thumbs up",
            "(?:heart|favorite|favourite|star) (?:it|this|that|$THE_TRACK)",
            "(?:add|save) (?:it|this|that|$THIS$TRACK) to (?:my |the )?(?:liked songs|likes|" +
                "favorites|favourites|library|collection|liked)",
            "save $THE_TRACK",
        ),
        phrasesFor(
            control(MediaCommand.Rate(true)),
            "(?:i )?(?:really )?(?:like|love) (?:this|that|this one)",
            "thumbs up",
            whilePlaying = true,
        ),
        // the sleep timer without a duration
        phrasesFor(
            MediaControlRequest.SleepCancel,
            "(?:cancel|stop|delete|remove|clear|end|kill|disable|deactivate|turn off|switch off|" +
                "get rid of|reset) (?:the |my |this )?sleep timer",
            "(?:turn|switch) (?:the |my )?sleep timer off",
            "no (?:more )?sleep timer",
        ),
        phrasesFor(
            MediaControlRequest.SleepQuery,
            "how (?:long|much time)(?: is| s)? (?:left|remaining) (?:on|in|for) (?:the |my )?sleep timer",
            "how (?:long|much time) (?:until|till|before) (?:the |my )?(?:$MEDIA|sleep timer) " +
                "(?:stops|ends|turns off|goes off|pauses|is over)",
            "when (?:will|does|is) (?:the |my )?(?:$MEDIA|sleep timer) (?:stop|end|turn off|" +
                "pause|going to stop|gonna stop|going to end)",
            "(?:is|are) there (?:a |any )?sleep timers?(?: (?:set|on|running|going))?",
            "is (?:the |my |a )?sleep timer (?:set|on|running|going)",
            "(?:whats|what is) (?:left on )?(?:the |my )?sleep timer",
            "(?:check|show) (?:the |my )?sleep timer",
            "do i have (?:a |any )?sleep timer(?: (?:set|on|running|going))?",
        ),
        phrasesFor(
            MediaControlRequest.SleepSet(null),
            "(?:set|start|put on|turn on|make|create|add|begin|activate)(?: me)? (?:a |the |my )?sleep timer",
            "sleep timer",
        ),
    ).flatten()

    /** Seek phrases with the duration as group 1, and their direction; back first. */
    private val SEEKS: List<Pair<Regex, Long>> = listOf(
        "(?:skip|jump|go|move|scroll|seek) (?:back|backward|backwards)(?: by)? (.+)" to -1L,
        "(?:rewind|back up|back)(?: by)? (.+?)(?: (?:back|backward|backwards))?" to -1L,
        "(?:skip|jump|go|move) (.+?) (?:back|backward|backwards)" to -1L,
        "replay the last (.+)" to -1L,
        "(?:skip|jump|go|move|scroll|seek|fast forward|forward) (?:ahead|forward|forwards)" +
            "(?: by)? (.+)" to 1L,
        "(?:fast forward|forward|advance|skip|jump)(?: by)? (?:the next )?(.+?)" +
            "(?: (?:ahead|forward|forwards))?" to 1L,
    ).map { (pattern, sign) -> Pair(Regex(pattern), sign) }

    /** Sleep timer phrases with the duration as group 1. */
    private val SLEEP_SETS: List<Regex> = listOf(
        "(?:(?:set|start|put on|turn on|make|create|add|begin|activate)(?: me)? )?" +
            "(?:a |the |my )?sleep timer(?: (?:for|of|in|to))? (.+)",
        "(?:(?:set|start|put on|make|create|add)(?: me)? )?(?:a |an )?(.+?) sleep timer",
        "(?:change|set|update|reset|make) (?:the |my )?sleep timer to (.+)",
        "(?:stop|pause|end|kill|cut|turn off|shut off|switch off|fade out|fade) " +
            "(?:the |my |this )?$MEDIA (?:playing )?(?:in|after) (.+)",
        "(?:stop|end|quit) playing (?:the |my )?(?:$MEDIA )?(?:in|after) (.+)",
        "turn (?:the |my |this )?$MEDIA off (?:in|after) (.+)",
    ).map { Regex(it) }
}
