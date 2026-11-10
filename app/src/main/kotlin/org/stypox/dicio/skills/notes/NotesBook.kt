package org.stypox.dicio.skills.notes

import cc.aaro.gar.router.resolve.NameMatcher

/** What adding items to a list did: the ones [added], and the ones it [already] had. */
data class ListAdd(val list: String, val added: List<String>, val already: List<String>)

/**
 * The lists, notes and facts as plain changes of [GarNotesData], so that they can be unit tested
 * ([GarNotes] keeps and saves the result). English, like the sentences that reach them.
 */
object NotesBook {
    /** The list "the list" and "my list" mean before any list was used. */
    const val DEFAULT_LIST = "shopping"

    /** Older notes are dropped beyond this; a phone assistant's notes are short-lived. */
    const val MAX_NOTES = 200

    /** More facts than anyone tells an assistant. */
    const val MAX_FACTS = 200

    /** More items than any list needs. */
    const val MAX_ITEMS = 200

    // ---- lists

    /**
     * The list's name as kept: "grocery", "groceries" and "grocery shopping" are the shopping
     * list, "todo" and "to-do" the to do list; "" for "the list", see [listName].
     */
    fun canonicalList(said: String): String {
        val words = said.lowercase().replace("-", " ").split(" ")
            .filter { it.isNotBlank() && it !in LIST_FILLERS }
        val name = words.joinToString(" ")
        return LIST_ALIASES[name] ?: name
    }

    /** The list [said] names, or for "the list" the one used last (else [DEFAULT_LIST]). */
    fun listName(data: GarNotesData, said: String): String =
        canonicalList(said).ifEmpty { data.lastList ?: DEFAULT_LIST }

    fun list(data: GarNotesData, name: String): GarList? = data.lists.firstOrNull { it.name == name }

    /** Adds [items] to [name] (made if new), leaving out the ones it already has. */
    fun add(data: GarNotesData, name: String, items: List<String>): Pair<GarNotesData, ListAdd> {
        val old = list(data, name)?.items.orEmpty()
        val added = mutableListOf<String>()
        val already = mutableListOf<String>()
        for (item in items.map(::cleanItem).filter { it.isNotEmpty() }) {
            if ((old + added).any { sameItem(it, item) }) already += item else added += item
        }
        val changed = withList(data, GarList(name, (old + added).takeLast(MAX_ITEMS)))
        return Pair(changed, ListAdd(name, added, already))
    }

    /** Makes the empty list [name], if it doesn't exist yet. */
    fun create(data: GarNotesData, name: String): GarNotesData =
        if (list(data, name) != null) data.copy(lastList = name) else withList(data, GarList(name))

    /**
     * The item of [name] that [said] names: the same item ("egg" for "eggs"), else the only item
     * sharing a whole word with it ("milk" for "oat milk"); null if none or several.
     */
    fun findItem(data: GarNotesData, name: String, said: String): String? {
        val items = list(data, name)?.items.orEmpty()
        val wanted = cleanItem(said)
        items.firstOrNull { sameItem(it, wanted) }?.let { return it }
        return items.filter { NameMatcher.sharesWord(wanted, it) }.singleOrNull()
    }

    /** [name] without [item] (as kept, see [findItem]). */
    fun remove(data: GarNotesData, name: String, item: String): GarNotesData {
        val old = list(data, name) ?: return data
        return withList(data, old.copy(items = old.items.filter { it != item }))
    }

    /** Empties [name], keeping the list. */
    fun clear(data: GarNotesData, name: String): GarNotesData {
        val old = list(data, name) ?: return data
        return withList(data, old.copy(items = listOf()))
    }

    /** Removes the list [name]. */
    fun delete(data: GarNotesData, name: String): GarNotesData = data.copy(
        lists = data.lists.filter { it.name != name },
        lastList = data.lastList.takeIf { it != name },
    )

    private fun withList(data: GarNotesData, list: GarList): GarNotesData {
        val index = data.lists.indexOfFirst { it.name == list.name }
        val lists = if (index < 0) data.lists + list else data.lists.toMutableList().also { it[index] = list }
        return data.copy(lists = lists, lastList = list.name)
    }

    /** An item as kept: without "some", "a", "an" or "the" before it. */
    fun cleanItem(said: String): String {
        var words = said.trim().split(" ").filter { it.isNotBlank() }
        while (words.size > 1 && words.first() in ITEM_FILLERS) {
            words = words.drop(1)
        }
        return words.joinToString(" ")
    }

    /** "eggs" and "egg", "tomatoes" and "tomato" are one item. */
    fun sameItem(a: String, b: String): Boolean =
        itemKey(a) == itemKey(b)

    private fun itemKey(item: String): String = cleanItem(item.lowercase()).split(" ")
        .joinToString(" ") { singular(it) }

    /** A crude English singular, enough for comparing items and fact words. */
    fun singular(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && (word.endsWith("oes") || word.endsWith("ches") ||
            word.endsWith("shes") || word.endsWith("xes")) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    // ---- notes

    fun addNote(data: GarNotesData, text: String, now: Long): GarNotesData =
        data.copy(notes = (data.notes + GarNote(text, now)).takeLast(MAX_NOTES))

    fun removeLastNote(data: GarNotesData): GarNotesData = data.copy(notes = data.notes.dropLast(1))

    fun clearNotes(data: GarNotesData): GarNotesData = data.copy(notes = listOf())

    // ---- facts

    /**
     * Remembers [text], replacing what was remembered about the same thing: "my locker is 43"
     * replaces "my locker is 42", "i parked on level 5" replaces "i parked on level 3".
     */
    fun remember(data: GarNotesData, text: String, now: Long): GarNotesData {
        val subject = FactWords.subject(text)
        val kept = data.facts.filter { subject.isEmpty() || FactWords.subject(it.text) != subject }
        return data.copy(facts = (kept + GarFact(text, now)).takeLast(MAX_FACTS))
    }

    /**
     * The facts that answer [question], best first: the most of its words they share, then the
     * newest. Only words that name something count ([FactWords.keywords], not
     * [FactWords.GENERIC]); a fact needs one of them, or when [strict] (a question that may be
     * meant for another skill: "what's the weather like in paris" is no question about "the
     * weather station is in the garage") all of one or two words and half of more ("how do i
     * turn on wifi" is no question about "my wifi password is hunter2").
     */
    fun recall(data: GarNotesData, question: String, strict: Boolean = false): List<GarFact> {
        val asked = FactWords.keywords(question).filter { it !in FactWords.GENERIC }
        if (asked.isEmpty()) {
            return listOf()
        }
        val needed = when {
            !strict -> 1
            asked.size <= 2 -> asked.size
            else -> (asked.size + 1) / 2
        }
        return data.facts
            .map { fact ->
                val words = FactWords.keywords(fact.text)
                Pair(fact, asked.count { word -> words.any { sameStem(it, word) } })
            }
            .filter { (_, shared) -> shared >= needed }
            .sortedWith(
                compareByDescending<Pair<GarFact, Int>> { it.second }.thenByDescending { it.first.saved }
            )
            .map { it.first }
    }

    /** Stems of one word: the same, or one starting the other from 4 letters ("stor", "store"). */
    fun sameStem(a: String, b: String): Boolean {
        if (a == b) return true
        val (shorter, longer) = if (a.length <= b.length) Pair(a, b) else Pair(b, a)
        return shorter.length >= 4 && longer.startsWith(shorter)
    }

    fun forget(data: GarNotesData, fact: GarFact): GarNotesData =
        data.copy(facts = data.facts.filter { it != fact })

    fun forgetAll(data: GarNotesData): GarNotesData = data.copy(facts = listOf())

    private val LIST_FILLERS = setOf("my", "the", "our", "a", "an", "list", "lists")
    private val LIST_ALIASES = mapOf(
        "grocery" to DEFAULT_LIST,
        "groceries" to DEFAULT_LIST,
        "grocery shopping" to DEFAULT_LIST,
        "shop" to DEFAULT_LIST,
        "todo" to "to do",
        "to dos" to "to do",
        "todos" to "to do",
    )
    private val ITEM_FILLERS = setOf("some", "a", "an", "the", "more")
}
