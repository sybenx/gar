package cc.aaro.gar.router.followup

import cc.aaro.gar.router.Utterances

/**
 * Reads a [FollowUpForm] out of what the user said (English, like GAR's conversation words). The
 * set of forms is closed: the whole input, once its fillers are gone, has to be one of them, so a
 * full command ("turn off the flashlight") or anything with more words is no form. Refused by
 * design (gar/DESIGN.md, "Follow-ups"): a bare "and" without a preposition or a day word ("and
 * denver"), "what about" with more than [MAX_REST_WORDS] words, a command verb or only a pronoun
 * ("what about you"), a bare "no" and the declines ("no thanks"), corrections about the
 * conversation ("no wait", "no that's wrong"), "send it to voicemail" (the call
 * control's), "undo that" and "put it back", and song titles ("another one bites the dust",
 * "one more time").
 */
object FollowUpForms {
    /** At most this many words after "what about", in a value or a recipient. */
    private const val MAX_REST_WORDS = 4

    /** Dropped at the start, one or more: "ok", "hey", "please", "just", "sorry". */
    private val LEADING_FILLERS = setOf("ok", "okay", "oh", "hey", "please", "just", "alright", "so", "well", "sorry")

    /** A leading one of them is dropped when the rest is a form of its own: "no, turn it back off". */
    private val LEADING_NO = setOf("no", "nope")

    /** Dropped at the start before the rest: "never mind, turn it on", "actually, make it 8". */
    private val LEADING_PHRASES = listOf(
        listOf("never", "mind"), listOf("nevermind"), listOf("actually"),
        listOf("can", "you"), listOf("could", "you"), listOf("would", "you"),
    )

    /** Dropped wherever they are. */
    private val HESITATIONS = setOf("uh", "um", "uhm", "er", "erm")

    /** Dropped at the end, one or more: "turn it off please", "cancel it now", "turn it off thanks". */
    private val TRAILING_FILLERS = setOf("please", "now", "thanks")

    /** Dropped at the end like [TRAILING_FILLERS]: Whisper's "Turn it back off. Thank you." */
    private val TRAILING_PHRASES = listOf(listOf("thank", "you"))

    private const val THING = "(?:it|that|this|them)"
    private const val PERSON = "(?:her|him|them)"

    /** Words that make a rest, a value or a recipient a command of its own. */
    private val COMMAND_VERBS = setOf(
        "call", "text", "send", "play", "open", "set", "turn", "start", "stop", "tell", "show",
        "navigate", "add", "remind", "message", "email", "find", "search", "cancel", "delete",
        "remove", "switch", "take", "make", "put", "give", "read", "shut", "dial", "ring", "pause",
        "resume", "snooze", "change", "move", "share", "forward", "reply", "undo",
    )

    /** A rest of only these words is about the conversation, not a thing: "what about you". */
    private val PRONOUNS = setOf(
        "you", "me", "it", "that", "this", "them", "him", "her", "us", "yourself", "myself", "i",
        "we", "they", "he", "she",
    )

    /** A leading one of them is the rest's preposition: "and in paris". */
    private val PREPOSITIONS = setOf("in", "at", "on", "for", "near", "around")

    /** Words that make a bare "and" a follow-up: "and tomorrow", "and the weekend". */
    private val DAY_WORDS = setOf(
        "today", "tomorrow", "tonight", "yesterday", "morning", "afternoon", "evening", "night",
        "noon", "midnight", "weekend", "week", "next", "am", "pm",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december",
        // holidays, for "what day is christmas" then "and easter"
        "christmas", "easter", "thanksgiving", "halloween", "hanukkah", "valentines", "new",
        // "how much time is left on the timer" then "and the pasta timer"
        "timer",
    )

    /** Words of an amount of time: "5 more minutes". */
    private val DURATION_UNITS = setOf(
        "second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins", "hour", "hours",
    )

    /** How much of an adjustment: "a little bit louder", "much brighter". */
    private val DEGREE_WORDS = setOf("a", "little", "bit", "tad", "slightly", "lot", "much", "even", "touch")

    private val ADJUSTMENTS = mapOf(
        "louder" to FollowUpForm.Adjust(true, AdjustKind.VOLUME),
        "quieter" to FollowUpForm.Adjust(false, AdjustKind.VOLUME),
        "softer" to FollowUpForm.Adjust(false, AdjustKind.VOLUME),
        "brighter" to FollowUpForm.Adjust(true, AdjustKind.BRIGHTNESS),
        "dimmer" to FollowUpForm.Adjust(false, AdjustKind.BRIGHTNESS),
        "darker" to FollowUpForm.Adjust(false, AdjustKind.BRIGHTNESS),
    )

    /** Adjustments that need a degree word or "make it": "a bit more", "make it higher". */
    private val VAGUE_ADJUSTMENTS = mapOf(
        "more" to FollowUpForm.Adjust(true, null),
        "less" to FollowUpForm.Adjust(false, null),
        "higher" to FollowUpForm.Adjust(true, null),
        "lower" to FollowUpForm.Adjust(false, null),
    )

    /** Values after "no" that decline instead of correcting: "no thanks", "no it's fine". */
    private val DECLINES = setOf(
        "thanks", "thank you", "thanks though", "dont", "do not", "its fine", "it is fine",
        "thats fine", "thats all", "that is all", "thats it", "that is it", "its ok", "its okay",
        "thats ok", "thats okay",
        "im good", "i am good", "im fine", "not now", "not right now", "never mind", "nevermind",
        "way", "problem", "worries", "need", "i dont", "i do not", "good", "fine", "ok", "okay",
    )

    /**
     * Words about the conversation rather than a value: "no wait", "no that's wrong", "i meant
     * sorry". A correction with one of them is no correction, and an adapter doesn't read a value
     * with one of them as a place.
     */
    val META_WORDS = setOf(
        "wait", "sorry", "hold", "hang", "wrong", "right", "okay", "ok", "thats", "that", "it",
        "mistake",
    )

    /**
     * Days counted from the day the user asked about last ([relativeDays]): "the next day", "the
     * day after (that)", "the following day", "the day before", "the previous day".
     */
    private val RELATIVE_DAYS = mapOf(
        "next day" to 1, "day after" to 1, "following day" to 1,
        "day before" to -1, "previous day" to -1, "day prior" to -1, "prior day" to -1,
    )

    /**
     * How many days [rest] (a [FollowUpForm.WhatAbout]'s) is from the day of the turn it follows,
     * or null when it names no such day: "the next day" and "the day after that" are 1, "the day
     * before" is -1. "The day after tomorrow" names its own day, so it is null.
     */
    fun relativeDays(rest: String): Int? =
        RELATIVE_DAYS[words(rest).filter { it !in setOf("the", "that", "this", "very") }.joinToString(" ")]

    fun parse(input: String): FollowUpForm? {
        val words = stripFillers(words(input))
        if (words.isEmpty()) {
            return null
        }
        // "no, turn it back off", "nope, cancel it": the rest is the form; "no 8 pm" corrects
        if (words.size > 1 && words.first() in LEADING_NO) {
            parseWords(stripFillers(words.drop(1)))?.takeIf { it !is FollowUpForm.Correction }?.let { return it }
        }
        return parseWords(words)
    }

    private fun parseWords(words: List<String>): FollowUpForm? {
        if (words.isEmpty()) {
            return null
        }
        val said = words.joinToString(" ")
        return switch(said) ?: adjust(words, said) ?: remove(said) ?: control(said)
            ?: setTo(said) ?: addMore(words, said) ?: aboutPerson(said) ?: sendTo(said)
            ?: aboutPlace(said) ?: aboutSong(said) ?: another(said) ?: anyOf(said)
            ?: correction(said) ?: whatAbout(said)
    }

    /**
     * The words of [input]: normalized like the model's input ([Utterances.normalize]), without
     * apostrophes ("what's" is "whats", as speech often writes it) and with a lone "s" joined to
     * the word before it ("what s" is "whats").
     */
    fun words(input: String): List<String> {
        val said = Utterances.normalize(input).replace("'", "").split(" ").filter { it.isNotEmpty() }
        val joined = mutableListOf<String>()
        for (word in said) {
            if (word == "s" && joined.isNotEmpty()) {
                joined[joined.size - 1] = joined.last() + "s"
            } else {
                joined.add(word)
            }
        }
        return joined
    }

    private fun stripFillers(input: List<String>): List<String> {
        var words = input.filter { it !in HESITATIONS }
        var changed = true
        while (changed && words.isNotEmpty()) {
            changed = false
            if (words.first() in LEADING_FILLERS) {
                words = words.drop(1)
                changed = true
            }
            LEADING_PHRASES.firstOrNull { words.size > it.size && words.take(it.size) == it }?.let {
                words = words.drop(it.size)
                changed = true
            }
        }
        changed = true
        while (changed) {
            changed = false
            if (words.size > 1 && words.last() in TRAILING_FILLERS) {
                words = words.dropLast(1)
                changed = true
            }
            TRAILING_PHRASES.firstOrNull { words.size > it.size && words.takeLast(it.size) == it }?.let {
                words = words.dropLast(it.size)
                changed = true
            }
        }
        return words
    }

    /** "Turn it off", "turn it back off"; a trailing "again" is a "back" too. */
    private fun switch(said: String): FollowUpForm? {
        Regex("^(?:turn|switch) $THING (back )?(on|off)( again)?$").find(said)?.let {
            return FollowUpForm.Switch(it.groupValues[2] == "on", back(it, 1, 3))
        }
        Regex("^shut $THING (back )?off( again)?$").find(said)?.let {
            return FollowUpForm.Switch(false, back(it, 1, 2))
        }
        Regex("^(?:turn|switch) (back )?(on|off) $THING( again)?$").find(said)?.let {
            return FollowUpForm.Switch(it.groupValues[2] == "on", back(it, 1, 3))
        }
        return null
    }

    private fun back(match: MatchResult, back: Int, again: Int): Boolean =
        match.groupValues[back].isNotEmpty() || match.groupValues[again].isNotEmpty()

    private fun adjust(words: List<String>, said: String): FollowUpForm? {
        Regex("^turn $THING (?:back )?(up|down)$").find(said)?.let {
            return FollowUpForm.Adjust(it.groupValues[1] == "up", null)
        }
        Regex("^turn (up|down) $THING$").find(said)?.let {
            return FollowUpForm.Adjust(it.groupValues[1] == "up", null)
        }
        // "make it a bit louder", "louder", "a little bit more"
        val makeIt = words.size >= 3 && words[0] == "make" && words[1] in setOf("it", "that", "this")
        val rest = if (makeIt) words.drop(2) else words
        val degree = rest.any { it in DEGREE_WORDS }
        val adjustment = rest.filter { it !in DEGREE_WORDS }.singleOrNull() ?: return null
        ADJUSTMENTS[adjustment]?.let { return it }
        return VAGUE_ADJUSTMENTS[adjustment]?.takeIf { degree || makeIt }
    }

    private fun remove(said: String): FollowUpForm? = when {
        Regex("^cancel $THING$").matches(said) -> FollowUpForm.Remove(delete = false)
        Regex("^(?:delete|remove|clear|erase) $THING$").matches(said) -> FollowUpForm.Remove(delete = true)
        Regex("^get rid of $THING$").matches(said) -> FollowUpForm.Remove(delete = true)
        Regex("^snooze(?: $THING)?(?: again)?$").matches(said) -> FollowUpForm.Snooze
        else -> null
    }

    private fun control(said: String): FollowUpForm? {
        val verb = when {
            Regex("^(?:pause|hold|freeze) $THING$").matches(said) -> ControlVerb.PAUSE
            Regex("^(?:resume|unpause|continue|unfreeze) $THING$").matches(said) -> ControlVerb.RESUME
            Regex("^(?:start|keep) $THING (?:again|going)$").matches(said) -> ControlVerb.RESUME
            Regex("^reset $THING$").matches(said) -> ControlVerb.RESET
            Regex("^restart $THING$").matches(said) -> ControlVerb.RESTART
            Regex("^start $THING over$").matches(said) -> ControlVerb.RESTART
            else -> return null
        }
        return FollowUpForm.Control(verb)
    }

    private fun setTo(said: String): FollowUpForm? {
        val value = Regex("^(?:change|set|make|move|switch|put|push|turn) $THING (?:up |down |back )?(?:to|for) (.+)$")
            .find(said)?.groupValues?.get(1)
            // "make it 7", "make it 15 minutes": the adjustments ("make it louder") came first
            ?: Regex("^make $THING (.+)$").find(said)?.groupValues?.get(1)
            ?: return null
        return value.takeIf(::isValue)?.let { FollowUpForm.SetTo(it) }
    }

    private fun addMore(words: List<String>, said: String): FollowUpForm? {
        Regex("^(?:add|put) (.+) (?:to|on) $THING$").find(said)?.let {
            return amount(it.groupValues[1])?.let { amount -> FollowUpForm.AddMore(amount, false) }
        }
        Regex("^(?:extend|increase) $THING by (.+)$").find(said)?.let {
            return amount(it.groupValues[1])?.let { amount -> FollowUpForm.AddMore(amount, false) }
        }
        Regex("^(?:shorten|reduce|decrease) $THING by (.+)$").find(said)?.let {
            return amount(it.groupValues[1])?.let { amount -> FollowUpForm.AddMore(amount, true) }
        }
        Regex("^(?:take|knock|cut) (.+) off(?: $THING)?$").find(said)?.let {
            return amount(it.groupValues[1])?.let { amount -> FollowUpForm.AddMore(amount, true) }
        }
        Regex("^(?:take|knock|cut) off (.+)$").find(said)?.let {
            return amount(it.groupValues[1])?.let { amount -> FollowUpForm.AddMore(amount, true) }
        }
        // "add 5 more minutes", "give it 2 more minutes", "5 more minutes", "another 5 minutes"
        val rest = when {
            words.firstOrNull() == "add" -> words.drop(1)
            words.take(2) == listOf("give", "it") -> words.drop(2)
            else -> words
        }
        if (("more" in rest || rest.firstOrNull() == "another") && rest.size <= MAX_REST_WORDS) {
            return amount(rest.joinToString(" "))?.let { FollowUpForm.AddMore(it, false) }
        }
        if (words.firstOrNull() == "add") {
            return amount(rest.joinToString(" "))?.let { FollowUpForm.AddMore(it, false) }
        }
        return null
    }

    /** [said] as an amount of time ("5 more minutes" -> "5 minutes"), or null when it isn't one. */
    private fun amount(said: String): String? {
        val words = said.split(" ").filter { it.isNotEmpty() && it != "more" && it != "another" }
        return words.joinToString(" ").takeIf {
            words.size in 1..MAX_REST_WORDS && words.last() in DURATION_UNITS &&
                words.none { it in COMMAND_VERBS || it in PRONOUNS }
        }
    }

    private fun aboutPerson(said: String): FollowUpForm? {
        Regex("^(?:call|ring|phone|dial) $PERSON( back| up)?$").find(said)?.let {
            return FollowUpForm.AboutPerson(PersonVerb.CALL, it.groupValues[1] == " back")
        }
        if (Regex("^(?:redial|call back) $PERSON$").matches(said)) {
            return FollowUpForm.AboutPerson(PersonVerb.CALL, back = true)
        }
        Regex("^(?:text|message|sms) $PERSON( back)?$").find(said)?.let {
            return FollowUpForm.AboutPerson(PersonVerb.TEXT, it.groupValues[1].isNotEmpty())
        }
        if (Regex("^(?:send|shoot) $PERSON an? (?:text|message)$").matches(said)) {
            return FollowUpForm.AboutPerson(PersonVerb.TEXT, back = false)
        }
        if (Regex("^(?:reply to|write back to|text back) $PERSON$").matches(said)) {
            return FollowUpForm.AboutPerson(PersonVerb.TEXT, back = true)
        }
        Regex("^(?:email|e mail) $PERSON( back)?$").find(said)?.let {
            return FollowUpForm.AboutPerson(PersonVerb.EMAIL, it.groupValues[1].isNotEmpty())
        }
        if (Regex("^send $PERSON an? (?:email|e mail)$").matches(said)) {
            return FollowUpForm.AboutPerson(PersonVerb.EMAIL, back = false)
        }
        val whose = "(?:whats|what is|what was|give me|tell me|read me) (?:her|his|their)"
        if (Regex("^$whose (?:phone |cell |mobile )?number$").matches(said)) {
            return FollowUpForm.AboutPerson(PersonVerb.NUMBER, back = false)
        }
        if (Regex("^$whose (?:home )?address$").matches(said) ||
            Regex("^where (?:does (?:she|he)|do they) live$").matches(said)
        ) {
            return FollowUpForm.AboutPerson(PersonVerb.ADDRESS, back = false)
        }
        return null
    }

    private fun sendTo(said: String): FollowUpForm? {
        val match = Regex("^(?:send|forward|share) $THING (?:to|with) (.+?)( too| as well| also)?$")
            .find(said) ?: return null
        val who = match.groupValues[1]
        val words = who.split(" ")
        // "send it to voicemail" is the call control's; "send it to her" says nobody new
        if (words.size > MAX_REST_WORDS - 1 || "voicemail" in words || "voice" in words ||
            words.any { it in COMMAND_VERBS || it == "saying" } ||
            words.all { it in PRONOUNS && it != "me" && it != "myself" }
        ) {
            return null
        }
        return FollowUpForm.SendTo(who, match.groupValues[2].isNotEmpty())
    }

    private fun aboutPlace(said: String): FollowUpForm? {
        val verb = when {
            Regex("^(?:navigate|go|drive|walk) (?:over )?there$").matches(said) ||
                Regex("^(?:take|bring|get) me (?:over )?there$").matches(said) ||
                Regex("^(?:get |give me )?directions (?:to )?there$").matches(said) ||
                Regex("^how (?:do|can) i get there$").matches(said) -> PlaceVerb.NAVIGATE
            Regex("^how far (?:away )?is (?:it|that|there)(?: from here)?$").matches(said) ||
                Regex("^how far away is (?:it|that)$").matches(said) -> PlaceVerb.DISTANCE
            Regex("^(?:what time is it|whats the time|what is the time) (?:over )?there$").matches(said) ->
                PlaceVerb.TIME_THERE
            Regex("^(?:(?:whats|what is|hows|how is) the )?weather(?: like)? (?:over )?there$").matches(said) ->
                PlaceVerb.WEATHER_THERE
            else -> return null
        }
        return FollowUpForm.AboutPlace(verb)
    }

    private fun aboutSong(said: String): FollowUpForm? = when {
        Regex("^play $THING again$").matches(said) -> FollowUpForm.AboutSong(SongVerb.PLAY_AGAIN)
        Regex("^who (?:sings|sang|performs|performed|wrote) $THING$").matches(said) ||
            Regex("^(?:who is|whos) $THING by$").matches(said) ||
            Regex("^(?:who is|whos) the (?:artist|singer)$").matches(said) ->
            FollowUpForm.AboutSong(SongVerb.WHO_SINGS)
        else -> null
    }

    private fun another(said: String): FollowUpForm? {
        if (said == "one more time") {
            return null // a song, or "say that one more time"
        }
        if (Regex("^(?:(?:tell|give|show) me )?(?:another|one more)(?: one)?$").matches(said) ||
            Regex("^(?:do $THING )?again$").matches(said)
        ) {
            return FollowUpForm.Another(null)
        }
        val what = Regex("^(?:(?:tell|give|show) me )?(?:another|one more) ([a-z]+(?: [a-z]+)?)$")
            .find(said)?.groupValues?.get(1) ?: return null
        val words = what.split(" ")
        return what.takeIf { words.none { it in COMMAND_VERBS || it in PRONOUNS } }
            ?.let { FollowUpForm.Another(it) }
    }

    private fun anyOf(said: String): FollowUpForm? {
        val what = Regex(
            "^(?:(?:is|are) there |do i have |have i got )?(?:any|anything)" +
                "(?: (?:about|for|with|called|named))? (.+)$"
        ).find(said)?.groupValues?.get(1) ?: return null
        // "anything else" is the conversation's; "any of them" and "anything you can do" name
        // nothing to look for
        val words = what.split(" ")
        return what.takeIf {
            isValue(it) && it != "else" && words.first() != "of" &&
                words.none { word -> word in META_WORDS || word in PRONOUNS }
        }?.let { FollowUpForm.AnyOf(it) }
    }

    private fun correction(said: String): FollowUpForm? {
        if (Regex("^(?:no )?not (?:that|this) one(?: the other(?: one)?)?$").matches(said) ||
            Regex("^(?:no )?the other(?: one)?$").matches(said)
        ) {
            return FollowUpForm.Correction(null, CorrectionKind.OTHER)
        }
        Regex("^(?:no |nope )?i said (.+)$").find(said)?.let {
            return correctionValue(it.groupValues[1], CorrectionKind.SAID)
        }
        Regex("^(?:no |nope )?i (?:meant|mean) (.+)$").find(said)?.let {
            return correctionValue(it.groupValues[1], CorrectionKind.MEANT)
        }
        Regex("^(?:no|nope) (.+)$").find(said)?.let {
            val value = it.groupValues[1]
            val first = value.substringBefore(' ')
            // "no no", "no don't", "no not now" and "no thanks" decline
            if (first in setOf("no", "nope", "not", "dont", "nah") || value in DECLINES) {
                return null
            }
            return correctionValue(value, CorrectionKind.NO)
        }
        return null
    }

    /** [value] as a correction, unless it is about the conversation ("no wait", "no sorry"). */
    private fun correctionValue(value: String, kind: CorrectionKind): FollowUpForm? =
        value.takeIf { isValue(it) && it.split(" ").none { word -> word in META_WORDS } }
            ?.let { FollowUpForm.Correction(it, kind) }

    private fun whatAbout(said: String): FollowUpForm? {
        val match = Regex("^(?:and )?(?:what|how) about (.+)$").find(said)
        val bareAnd = match == null
        val rest = match?.groupValues?.get(1)
            ?: Regex("^and (.+)$").find(said)?.groupValues?.get(1)
            ?: return null
        val words = rest.split(" ")
        if (words.size > MAX_REST_WORDS || words.any { it in COMMAND_VERBS } ||
            words.all { it in PRONOUNS }
        ) {
            return null
        }
        val preposition = words.first().takeIf { it in PREPOSITIONS && words.size > 1 }
        // "and the day after", but not "and what day is it"
        if (bareAnd && preposition == null &&
            words.none { word -> word in DAY_WORDS || word.any { it.isDigit() } } && relativeDays(rest) == null
        ) {
            return null
        }
        val value = if (preposition == null) rest else words.drop(1).joinToString(" ")
        return FollowUpForm.WhatAbout(value, preposition, bareAnd)
    }

    /** A value to set or correct to: a few words, no command of its own, not only a pronoun. */
    private fun isValue(value: String): Boolean {
        val words = value.split(" ").filter { it.isNotEmpty() }
        return words.size in 1..MAX_REST_WORDS && words.none { it in COMMAND_VERBS } &&
            !words.all { it in PRONOUNS }
    }
}
