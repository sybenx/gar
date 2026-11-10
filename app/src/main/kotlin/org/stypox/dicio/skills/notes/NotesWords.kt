package org.stypox.dicio.skills.notes

import cc.aaro.gar.router.Utterances

/** What the user asked of a list. A list name as said, "" for "the list" ([NotesBook.listName]). */
sealed interface ListRequest {
    /** "Add milk and eggs to my shopping list." */
    data class Add(val items: List<String>, val list: String) : ListRequest

    /** "Remove eggs from the list." */
    data class Remove(val item: String, val list: String) : ListRequest

    /** "What's on my shopping list?" */
    data class Read(val list: String) : ListRequest

    /** "Is milk on my shopping list?" */
    data class Has(val item: String, val list: String) : ListRequest

    /** "Clear my shopping list" empties it, "delete my packing list" ([delete]) removes it. */
    data class Clear(val list: String, val delete: Boolean) : ListRequest

    /** "Make a new packing list." */
    data class Create(val list: String) : ListRequest

    /** "What lists do I have?" */
    data object Lists : ListRequest
}

/** What the user asked of the notes. */
sealed interface NoteRequest {
    /** "Take a note: call the dentist"; null [text] for "take a note" alone (GAR asks). */
    data class Take(val text: String?) : NoteRequest

    /** "Read my notes"; [lastOnly] for "read my last note". */
    data class Read(val lastOnly: Boolean) : NoteRequest

    /** "Delete my last note." */
    data object DeleteLast : NoteRequest

    /** "Delete all my notes" (GAR asks first). */
    data object DeleteAll : NoteRequest
}

/** What the user asked of the facts GAR remembers. */
sealed interface FactRequest {
    /** "Remember that my locker is 42": [text] is "my locker is 42". */
    data class Remember(val text: String) : FactRequest

    /**
     * "Remember my parking spot", "remember where I parked": what to keep about [about] isn't
     * said yet, so GAR asks ("What should I remember about your parking spot?") and keeps the
     * answer with it ([FactWords.completed]).
     */
    data class AskWhat(val about: String) : FactRequest

    /**
     * "Where did I park?", "what's my locker number?" With [always], GAR answers even when it
     * remembers nothing about it ("where did I park" is only ever about a remembered fact);
     * otherwise the question is the facts skill's only when a fact answers it.
     */
    data class Recall(val question: String, val always: Boolean) : FactRequest

    /**
     * "Forget where I parked." With [always] ("forget where ...", "forget my ..."), GAR answers
     * even when it remembers nothing about it; otherwise only when a fact matches.
     */
    data class Forget(val what: String, val always: Boolean) : FactRequest

    /** "Forget everything I told you" (GAR asks first). */
    data object ForgetAll : FactRequest

    /** "What do you remember?" */
    data object ListAll : FactRequest
}

/**
 * The words of an input for GAR's notes, lists and facts: normalized like the router's input
 * (lowercase, apostrophes kept: "what's"), without leading "ok", "hey", "please", "can you" and
 * trailing "please" or "for me". English.
 */
object NotesText {
    fun of(input: String): String {
        // a comma between items ("milk, eggs and bread") is an "and" once punctuation is gone
        var words = Utterances.words(Utterances.normalize(input.replace(COMMA_LIST, " and ")))
        var changed = true
        while (changed && words.size > 1) {
            changed = false
            if (words.first() in LEADING) {
                words = words.drop(1)
                changed = true
            }
            val two = words.take(2).joinToString(" ")
            if (words.size > 2 && two in POLITE) {
                words = words.drop(2)
                changed = true
            }
            val three = words.take(3).joinToString(" ")
            if (words.size > 3 && three in POLITE) {
                words = words.drop(3)
                changed = true
            }
        }
        if (words.size > 1 && words.last() == "please") {
            words = words.dropLast(1)
        }
        if (words.size > 2 && words.takeLast(2) == listOf("for", "me")) {
            words = words.dropLast(2)
        }
        return words.joinToString(" ")
    }

    /** "what's", "whats", "what is" and "what s" (Dicio's tokens) are the same. */
    fun apostrophesOff(text: String): String = text.replace("'", "")

    private val COMMA_LIST = Regex(",\\s*(?:and\\s+)?(?=\\S)")
    private val LEADING = setOf("ok", "okay", "hey", "please", "so", "oh", "um", "uh", "alright")
    private val POLITE = setOf(
        "can you", "could you", "would you", "will you", "i want you to", "i need you to",
        "can you please", "could you please", "i'd like you to", "id like you to",
    )
}

/** Reads list requests from the whole input. English. */
object ListWords {
    fun request(input: String): ListRequest? {
        val text = NotesText.apostrophesOff(NotesText.of(input))
        if (text.isEmpty()) {
            return null
        }
        LISTS.matchEntire(text)?.let { return ListRequest.Lists }
        CREATE.matchEntire(text)?.let { return ListRequest.Create(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
        CLEAR.firstNotNullOfOrNull { it.matchEntire(text) }?.let { return ListRequest.Clear(it.groupValues[1], false) }
        DELETE.matchEntire(text)?.let { return ListRequest.Clear(it.groupValues[1], true) }
        READ.firstNotNullOfOrNull { it.matchEntire(text) }?.let { return ListRequest.Read(it.groupValues[1]) }
        SHOPPING_READ.matchEntire(text)?.let { return ListRequest.Read(NotesBook.DEFAULT_LIST) }
        HAS.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            return ListRequest.Has(match.groupValues[1], match.groupValues[2])
        }
        REMOVE.matchEntire(text)?.let { match ->
            val item = NotesBook.cleanItem(match.groupValues[1])
            return if (item.isEmpty() || item in NOT_ITEMS) null else ListRequest.Remove(item, match.groupValues[2])
        }
        val add = ADD.matchEntire(text)?.let { Pair(it.groupValues[1], it.groupValues[2]) }
            ?: ADD_AFTER.matchEntire(text)?.let { Pair(it.groupValues[2], it.groupValues[1]) }
            ?: return null
        val items = items(add.first)
        if (items.isEmpty() || items.any { it in NOT_ITEMS }) {
            return null
        }
        return ListRequest.Add(items, add.second)
    }

    /** "milk eggs and bread" can't be split without commas: "milk eggs", "bread". */
    fun items(said: String): List<String> {
        var text = said
        val kept = COMPOUNDS.filter { it in text }
        kept.forEachIndexed { i, compound -> text = text.replace(compound, "\u0000$i\u0000") }
        return text.split(" and ")
            .map { part ->
                var item = part
                kept.forEachIndexed { i, compound -> item = item.replace("\u0000$i\u0000", compound) }
                NotesBook.cleanItem(item)
            }
            .filter { it.isNotEmpty() && it != "and" }
    }

    /** A list's name before "list": up to three words, none of them a verb of these requests. */
    private const val NAME = "((?:[a-z0-9]+ ){0,3}?)"
    private const val OWNER = "(?:my|the|our|a|this|that) "

    private val LISTS = Regex(
        "(?:what|which) lists (?:do i have|have i got|are there)|(?:show|read|tell)(?: me)? (?:all )?my lists" +
            "|what are my lists"
    )
    private val CREATE = Regex(
        "(?:create|make|start|begin|add|new)(?: me)? (?:a |an )?(?:new )?list (?:called|named|for) (.+)" +
            "|(?:create|make|start|begin)(?: me)? (?:a |an )?(?:new )?((?:[a-z0-9]+ ){0,2}[a-z0-9]+) list"
    )
    private val CLEAR = listOf(
        Regex("(?:clear|empty|wipe|reset)(?: out)? $OWNER$NAME" + "list"),
        Regex(
            "(?:clear|remove|delete|erase|take|cross) (?:everything|all items|all the items|all things|" +
                "all of the items|it all) (?:from|on|in|off|of) $OWNER$NAME" + "list"
        ),
    )
    private val DELETE = Regex("(?:delete|erase|remove|get rid of|throw away) $OWNER$NAME" + "list")
    private val READ = listOf(
        Regex("(?:whats|what is|what s|what are|what do i have|whats left|what is left) (?:on|in) $OWNER$NAME" + "list"),
        Regex("(?:read|show|tell|check|open|go through|give)(?: me| out)? (?:whats on )?$OWNER$NAME" + "list"),
        Regex("(?:read|show|tell)(?: me)? (?:the items|everything|what s|whats|what is) (?:on|in) $OWNER$NAME" + "list"),
    )
    private val SHOPPING_READ = Regex(
        "what (?:do|did) (?:i|we) (?:need|have) to (?:buy|get|pick up)(?: at the store| from the store)?" +
            "|what do (?:i|we) need (?:from|at) the (?:store|shop|grocery store|supermarket)"
    )
    private val HAS = listOf(
        Regex("(?:is|are) (?:there )?(.+?) (?:on|in) $OWNER$NAME" + "list"),
        Regex("(?:do|did) (?:i|we) (?:have|put|add|already have) (.+?) (?:on|in|to) $OWNER$NAME" + "list(?: already)?"),
    )
    private val REMOVE = Regex(
        "(?:remove|delete|take|cross|scratch|strike|check|tick|erase) (.+?) (?:off|from|out of|off of) " +
            "$OWNER$NAME" + "list"
    )
    private val ADD = Regex(
        "(?:add|put|stick|throw|write|include|place|pop)(?: down)? (.+?) (?:to|on|onto|in|into|on to) " +
            "$OWNER$NAME" + "list"
    )
    private val ADD_AFTER = Regex(
        "(?:add|put|write)(?: down)? (?:to|on|onto|in|into) $OWNER$NAME" + "list (.+)"
    )

    /** Words that don't make an item: "add it to the list" names nothing. */
    private val NOT_ITEMS = setOf(
        "it", "this", "that", "them", "these", "those", "everything", "something", "anything",
        "all", "stuff", "things", "item", "items", "a reminder", "an event", "my calendar",
    )

    /** Items that keep their "and". */
    private val COMPOUNDS = listOf(
        "mac and cheese", "macaroni and cheese", "salt and pepper", "peanut butter and jelly",
        "fish and chips", "bread and butter", "half and half", "pork and beans",
        "rice and beans", "chips and salsa", "sweet and sour sauce", "cookies and cream",
    )
}

/** Reads note requests from the whole input. English. */
object NoteWords {
    fun request(input: String): NoteRequest? {
        val said = NotesText.of(input)
        val text = NotesText.apostrophesOff(said)
        if (text.isEmpty()) {
            return null
        }
        DELETE_LAST.matchEntire(text)?.let { return NoteRequest.DeleteLast }
        DELETE_ALL.matchEntire(text)?.let { return NoteRequest.DeleteAll }
        READ.matchEntire(text)?.let { match ->
            val lastOnly = match.groupValues[1].isNotEmpty() && !text.endsWith("notes")
            return NoteRequest.Read(lastOnly)
        }
        READ_OTHER.matchEntire(text)?.let { return NoteRequest.Read(lastOnly = false) }
        TAKE_ALONE.matchEntire(text)?.let { return NoteRequest.Take(null) }
        // the note's text as said, with its apostrophes
        TAKE.firstNotNullOfOrNull { it.matchEntire(said) }?.let { match ->
            val note = match.groupValues.last().trim()
            return if (note.split(" ").size < 1 || note in NOT_NOTES) null else NoteRequest.Take(note)
        }
        return null
    }

    private val DELETE_LAST = Regex(
        "(?:delete|remove|erase|scratch|undo|get rid of|cancel)(?: my| the| that)? (?:last|latest|previous|newest|most recent)? ?note"
    )
    private val DELETE_ALL = Regex(
        "(?:delete|remove|erase|clear|get rid of)(?: all| every)?(?: of)?(?: my| the)? notes" +
            "|(?:delete|remove|erase|clear) (?:all|every) (?:my |the )?notes?"
    )
    private val READ = Regex(
        "(?:read|show|tell me|list|play|check|open|whats|what is|what s|what are|what was|what were)" +
            "(?: me| out| back)?(?: all)?(?: of)? (?:my|the) ((?:last|latest|newest|recent|most recent|previous) )?notes?"
    )
    private val READ_OTHER = Regex(
        "what (?:did i|have i) (?:write|written|note|noted|jot|jotted) down" +
            "|what notes do i have|do i have any notes|any notes"
    )
    private val TAKE_ALONE = Regex(
        "(?:take|make|write|create|add|start|leave|new)(?: me)? (?:a |an )?(?:new |quick )?note(?: for me)?"
    )
    private val TAKE = listOf(
        Regex(
            "(?:take|make|write|jot|create|add|leave|save|start)(?: me)?(?: down)? (?:a |an )?(?:new |quick )?" +
                "note(?: to self| for me| to myself)?(?: that| saying| about| to say| of| which says| it says| to)? (.+)"
        ),
        Regex("note to (?:self|myself) (?:that )?(.+)"),
        Regex("(?:write|jot|put) (?:this |that |it )?down (?:that )?(.+)"),
        Regex("note that (.+)"),
    )

    /** Not a note's text: "take a note of this" names nothing yet. */
    private val NOT_NOTES = setOf("this", "that", "it", "something", "down", "please")
}

/** Reads fact requests from the whole input, and the facts' words. English. */
object FactWords {
    fun request(input: String): FactRequest? {
        val said = NotesText.of(input)
        val text = NotesText.apostrophesOff(said)
        if (text.isEmpty()) {
            return null
        }
        LIST_ALL.matchEntire(text)?.let { match ->
            val about = match.groupValues[1]
            return if (about.isEmpty()) FactRequest.ListAll else FactRequest.Recall(about, always = true)
        }
        if (FORGET_ALL.matches(text)) {
            return FactRequest.ForgetAll
        }
        FORGET.matchEntire(text)?.let { match ->
            val what = match.groupValues[1]
            if (what in NOT_FACTS || keywords(what).none { it !in GENERIC }) {
                return null
            }
            val first = what.split(" ").first()
            return FactRequest.Forget(what, always = first in FORGET_ALWAYS)
        }
        REMEMBER.matchEntire(said)?.let { match ->
            val fact = match.groupValues[1].trim()
            return when {
                unfinished(fact) -> FactRequest.AskWhat(NotesText.apostrophesOff(fact.lowercase()))
                isFact(fact) -> FactRequest.Remember(fact)
                else -> null
            }
        }
        DO_YOU_REMEMBER.matchEntire(text)?.let { return FactRequest.Recall(it.groupValues[1], always = true) }
        // "do you know the time" is any skill's question: the facts' only when a fact answers it
        DO_YOU_KNOW.matchEntire(text)?.let { return FactRequest.Recall(it.groupValues[1], always = false) }
        WHERE_DID_I.matchEntire(text)?.let { return FactRequest.Recall(text, always = true) }
        if (QUESTION.matches(text)) {
            return FactRequest.Recall(text, always = false)
        }
        return null
    }

    /**
     * What a fact says, said back to the user: "my locker is 42" -> "Your locker is 42.", "i
     * parked on level 3" -> "You parked on level 3.".
     */
    fun secondPerson(fact: String): String {
        val words = fact.trim().split(" ").filter { it.isNotEmpty() }
        val out = words.mapIndexed { i, word ->
            val lower = word.lowercase()
            when {
                lower == "am" && i > 0 && words[i - 1].lowercase() == "i" -> "are"
                lower == "was" && i > 0 && words[i - 1].lowercase() == "i" -> "were"
                else -> PERSON[lower] ?: word
            }
        }.joinToString(" ")
        val sentence = out.replaceFirstChar { it.uppercase() }
        return if (sentence.last() in ".!?") sentence else "$sentence."
    }

    /**
     * The words of [text] that name something, as stems ("parked" and "parking" are "park"),
     * without question words, pronouns and the like.
     */
    fun keywords(text: String): Set<String> =
        NotesText.apostrophesOff(text.lowercase()).split(" ")
            .filter { it.isNotEmpty() && it !in STOP_WORDS }
            .map(::stem)
            .toSet()

    /**
     * What a fact is about, for replacing it: the words before "is", "are", "was" or "were" ("my
     * locker is 42": "locker"), else the verb and its object before a preposition ("i parked on
     * level 3": "park", "i left my keys in the car": "leave key"). "" when there is none.
     */
    fun subject(fact: String): String {
        val words = NotesText.apostrophesOff(fact.lowercase()).split(" ").filter { it.isNotEmpty() }
        val verb = words.indexOfFirst { it in LINKING }
        val head = if (verb > 0) {
            words.take(verb)
        } else {
            val start = if (words.firstOrNull() == "i") 1 else 0
            words.drop(start).takeWhile { it !in PREPOSITIONS }
        }
        return head.filter { it !in STOP_WORDS }.map(::stem).sorted().joinToString(" ")
    }

    /** "parked", "parking" -> "park"; "left" -> "leave"; "keys" -> "key". */
    fun stem(word: String): String {
        IRREGULAR[word]?.let { return it }
        val base = when {
            word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
            else -> NotesBook.singular(word)
        }
        return base
    }

    /**
     * The fact said in answer to "What should I remember about ...?" ([FactRequest.AskWhat]):
     * "my parking spot" and "level 3" make "my parking spot is level 3", "where i parked" and "on
     * level 3" make "i parked on level 3". An answer that is a whole fact itself is kept as said.
     */
    fun completed(about: String, answer: String): String {
        val said = NotesText.of(answer)
        val rest = ANSWER_LEAD.replace(said, "").ifEmpty { said }
        val words = NotesText.apostrophesOff(rest).split(" ")
        return when {
            words.first() == "i" || words.first() in DETERMINERS && words.any { it in LINKING } -> rest
            about.startsWith("where i ") -> "i ${about.removePrefix("where i ")} $rest"
            else -> "$about is $rest"
        }
    }

    /**
     * Something to remember without what about it: "where i parked", or a thing alone ("my
     * parking spot", "this parking space": no linking verb, place, number or verb before the
     * end, unlike "my mom likes tulips").
     */
    private fun unfinished(fact: String): Boolean {
        val words = NotesText.apostrophesOff(fact.lowercase()).split(" ").filter { it.isNotEmpty() }
        if (words.size < 2 || words.any { it in LINKING }) {
            return false
        }
        if (words[0] == "where" && words[1] == "i") {
            return words.size > 2
        }
        return words[0] in DETERMINERS && words.none { it in PREPOSITIONS || it.any(Char::isDigit) } &&
            words.drop(1).dropLast(1).none { it.endsWith("s") || it.endsWith("ed") } &&
            keywords(fact).isNotEmpty()
    }

    /** A fact worth keeping: two words or more, not a question ("where i parked"), not "to ...". */
    private fun isFact(fact: String): Boolean {
        val words = NotesText.apostrophesOff(fact).split(" ").filter { it.isNotEmpty() }
        if (words.size < 2 || words.first() == "to" || words.first() == "me") {
            return false
        }
        if (words.first() in QUESTION_WORDS && words.none { it in LINKING }) {
            return false
        }
        return keywords(fact).isNotEmpty()
    }

    /** Words that name nothing on their own: "number" is any number. */
    val GENERIC = setOf(
        "number", "name", "thing", "stuff", "info", "information", "time", "day", "date", "one",
        "again", "tell", "remember", "know", "say", "said",
    )

    private val LIST_ALL = Regex(
        "what (?:do you|did i tell you to|did i ask you to|have i told you to|have i asked you to|" +
            "should you|are you supposed to) remember(?: about (.+))?" +
            "|what (?:have i|did i) (?:told|tell) you(?: about (.+))?" +
            "|what do you know about me"
    )
    private val FORGET_ALL = Regex(
        "forget (?:everything|all)(?: of it| of that)?(?: (?:i|that i) (?:told|tell|asked) you(?: to remember)?)?" +
            "|forget all (?:the|my) (?:facts|things i told you)"
    )
    private val FORGET = Regex("(?:forget|stop remembering)(?: about)? (.+)")
    private val REMEMBER = Regex(
        "(?:remember|memorize|memorise|don't forget|dont forget|do not forget|keep in mind)(?: that)? (.+)"
    )
    private val DO_YOU_REMEMBER = Regex("do you (?:remember|recall) (.+)")
    private val DO_YOU_KNOW = Regex("do you know (.+)")

    /** "my parking spot", "this parking space": a thing, see [unfinished]. */
    private val DETERMINERS = setOf("my", "the", "this", "our", "your")

    /** "it's on level 3": the answer without "it's". */
    private val ANSWER_LEAD = Regex("^(?:it is|it's|its|it s|it was|that is|that's|thats|that s) ")
    private val WHERE_DID_I = Regex(
        "where (?:did|have|had) i (?:park|parked|leave|left|put|keep|kept|hide|hid|hidden|store|stored)(?: .+)?" +
            "|where (?:is|s|are) (?:my|the) (?:car|parking|parking spot|parking space|parked car)" +
            "|where (?:am i|was i) parked"
    )
    private val QUESTION = Regex(
        "(?:what|whats|where|wheres|when|whens|who|whos|which|how|hows)(?: .+)?" +
            "|(?:do|does|did|is|are|was|were) (?:i|my|you know my) .+"
    )

    /** "forget where i parked", "forget my locker number": only ever about a fact. */
    private val FORGET_ALWAYS = setOf("where", "what", "my", "i", "that i", "which")

    /** "forget it", "forget that": the conversation's, never a fact. */
    private val NOT_FACTS = setOf("it", "that", "this", "about it", "it all", "everything", "me", "you")

    private val QUESTION_WORDS = setOf("where", "what", "when", "who", "why", "which", "how", "whats", "wheres")
    private val LINKING = setOf("is", "are", "was", "were", "s")
    private val PREPOSITIONS = setOf(
        "on", "in", "at", "under", "behind", "near", "by", "next", "to", "for", "with", "inside",
        "outside", "between", "beside", "above", "below", "into", "onto", "from", "of",
    )
    private val PERSON = mapOf(
        "i" to "you", "me" to "you", "my" to "your", "mine" to "yours", "myself" to "yourself",
        "i'm" to "you're", "im" to "you're", "i've" to "you've", "ive" to "you've",
        "i'll" to "you'll", "i'd" to "you'd", "we" to "we", "our" to "our",
    )
    private val IRREGULAR = mapOf(
        "left" to "leave", "kept" to "keep", "hid" to "hide", "hidden" to "hide", "told" to "tell",
        "put" to "put", "parked" to "park", "parking" to "park", "lent" to "lend", "bought" to "buy",
        "stored" to "store", "storing" to "store", "spot" to "spot", "space" to "spot",
        "kids" to "kid", "children" to "kid",
    )
    private val STOP_WORDS = setOf(
        "i", "me", "my", "mine", "myself", "you", "your", "we", "our", "us", "it", "its", "the", "a",
        "an", "is", "are", "was", "were", "be", "been", "am", "s", "do", "does", "did", "have", "has",
        "had", "what", "whats", "where", "wheres", "when", "whens", "who", "whos", "which", "how",
        "hows", "why", "that", "this", "there", "on", "in", "at", "of", "to", "for", "with", "by",
        "and", "or", "im", "ive", "ill", "id", "again", "remember", "know", "please", "tell", "can",
        "could", "will", "would", "should", "about", "from", "up", "so", "just", "now", "still",
        "currently", "usually", "like", "any", "some", "all", "right", "now",
    )
}
