package org.stypox.dicio.skills.sms

import android.content.ContentResolver
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import org.dicio.numbers.ParserFormatter
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.parseOptionIndex
import org.stypox.dicio.gar.call.spokenPhoneNumber
import org.stypox.dicio.skills.telephone.Contact
import org.stypox.dicio.util.StringUtils

/** Who a text goes to. For a number that is not a contact, [name] is the number itself. */
data class SmsRecipient(val name: String, val number: String) {
    val isContact: Boolean get() = name != number
}

/** A contact with all of its phone numbers. */
data class SmsContact(val name: String, val numbers: List<String>)

/** The contacts that match what the user said. */
sealed interface RecipientChoice {
    data class One(val recipient: SmsRecipient) : RecipientChoice

    /** Several contacts, or one contact with several numbers: the user picks one number. */
    data class Several(val contacts: List<SmsContact>) : RecipientChoice

    data object NotFound : RecipientChoice

    /** "Myself", and GAR knows no card of the owner with a number: ask which contact is them. */
    data object UnknownOwner : RecipientChoice
}

/** An answer to "Which one?", see [SmsRecipients.pick]. */
sealed interface RecipientPick {
    data class Picked(val recipient: SmsRecipient) : RecipientPick

    /** The user named a contact with several numbers: ask which number. */
    data class Narrowed(val contact: SmsContact) : RecipientPick
}

/**
 * Finds the recipient of a text among the contacts. The candidates come from the telephone
 * skill's [Contact] search, sorted by Dicio's contact distance. GAR's [NameMatcher] is tried first
 * because it tolerates speech recognition splits and small mishearings ("jon" for John, "sara
 * lee" for "Sara-Lee"); when it finds nothing, the telephone skill's own choice by distance is
 * used, among the contacts that share a whole word with what was said ("sam" for Samantha): the
 * distance counts letters anywhere, so "erin" would find "Katerina Book Club". When no
 * contact matches, none is offered. A number spelled out digit by digit is texted as is.
 */
object SmsRecipients {
    /** At most this many contacts are offered to choose from, like the telephone skill. */
    const val MAX_CONTACTS = 5

    /**
     * @return the phone number in [spoken] ("five five five one two one two", "+1 555 0100"), or
     * null if it isn't one
     */
    fun phoneNumber(spoken: String): String? {
        val typed = spoken.trim()
        if (TYPED_NUMBER.matches(typed) && typed.count { it.isDigit() } >= MIN_DIGITS) {
            return typed
        }
        return spokenPhoneNumber(typed)
    }

    /**
     * @param candidates the contacts to search, best first by [distance] (lower is better)
     * @param storedNumbers the phone numbers of a contact as stored, only called for contacts
     * that may be chosen; one number stored in different ways counts once (see [SmsNumbers])
     */
    fun <C> choose(
        spoken: String,
        candidates: List<C>,
        name: (C) -> String,
        distance: (C) -> Int,
        storedNumbers: (C) -> List<String>,
    ): RecipientChoice {
        phoneNumber(spoken)?.let { return RecipientChoice.One(SmsRecipient(it, it)) }
        if (spoken.isBlank()) {
            return RecipientChoice.NotFound
        }
        val numbers = { candidate: C -> SmsNumbers.distinct(storedNumbers(candidate)) }

        val matched = when (val match = NameMatcher.match(spoken, candidates, name)) {
            is Resolution.Resolved -> listOf(match.value)
            is Resolution.Ambiguous -> match.options
            is Resolution.Unresolvable -> listOf()
        }
        val withNumbers = matched.map { SmsContact(name(it), numbers(it)) }
            .filter { it.numbers.isNotEmpty() }
        return when (withNumbers.size) {
            0 -> chooseByDistance(
                candidates.filter { NameMatcher.sharesWord(spoken, name(it)) }, name, distance, numbers,
            )
            1 -> single(withNumbers[0])
            else -> RecipientChoice.Several(withNumbers.take(MAX_CONTACTS))
        }
    }

    /**
     * The telephone skill's choice: a match with one number and no close second is taken
     * directly, otherwise the user chooses among the best [MAX_CONTACTS].
     */
    private fun <C> chooseByDistance(
        candidates: List<C>,
        name: (C) -> String,
        distance: (C) -> Int,
        numbers: (C) -> List<String>,
    ): RecipientChoice {
        val valid = mutableListOf<SmsContact>()
        var i = 0
        while (valid.size < MAX_CONTACTS && i < candidates.size) {
            val candidate = candidates[i]
            val candidateNumbers = numbers(candidate)
            ++i
            if (candidateNumbers.isEmpty()) {
                continue
            }
            if (valid.isEmpty() &&
                candidateNumbers.size == 1 &&
                // the next contact has a distance higher by 3+
                (i >= candidates.size || distance(candidates[i]) - 2 > distance(candidate))
            ) {
                return RecipientChoice.One(SmsRecipient(name(candidate), candidateNumbers[0]))
            }
            valid.add(SmsContact(name(candidate), candidateNumbers))
        }
        return when (valid.size) {
            0 -> RecipientChoice.NotFound
            1 -> single(valid[0])
            else -> RecipientChoice.Several(valid)
        }
    }

    /** The contact's only number, or the contact to pick one of its numbers from. */
    fun single(contact: SmsContact): RecipientChoice =
        if (contact.numbers.size == 1) {
            RecipientChoice.One(SmsRecipient(contact.name, contact.numbers[0]))
        } else {
            RecipientChoice.Several(listOf(contact))
        }

    /**
     * Splits a recipient capture that also holds the message, as in "text mom i'm on my way",
     * where the sentence "text .who." captures everything after "text": of the leading parts that
     * are a phone number or match a contact name, the one matching a contact best is the
     * recipient (the longer one on a tie, so "mary jane" beats "mary"), the rest the message. Best,
     * not longest: "mom i" still matches "Mom" loosely, which would eat the message's "i".
     *
     * @return the recipient part and the message part, or null if [spoken] can't be split
     */
    fun <C> splitMessage(spoken: String, candidates: List<C>, name: (C) -> String): Pair<String, String>? {
        val words = spoken.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < 2 || phoneNumber(spoken) != null ||
            NameMatcher.match(spoken, candidates, name) !is Resolution.Unresolvable
        ) {
            return null // all of it is the recipient
        }
        var best: Pair<Int, Double>? = null
        for (end in words.size - 1 downTo 1) {
            if (words.subList(0, end).all { it.lowercase() in NAME_FILLERS }) {
                break // "the" alone would match "Theo"
            }
            val recipient = words.subList(0, end).joinToString(" ")
            val score = if (phoneNumber(recipient) != null) {
                1.0
            } else if (NameMatcher.match(recipient, candidates, name) !is Resolution.Unresolvable) {
                candidates.maxOf { NameMatcher.similarity(recipient, name(it)) }
            } else {
                continue
            }
            if (best == null || score > best.second) {
                best = Pair(end, score) // ends go down, so on a tie the longer part stays
            }
        }
        return best?.let { (end, _) ->
            Pair(words.subList(0, end).joinToString(" "), words.subList(end, words.size).joinToString(" "))
        }
    }

    /** The numbers of [contacts] in the order they are shown, each with its contact's name. */
    fun entries(contacts: List<SmsContact>): List<SmsRecipient> =
        contacts.flatMap { contact -> contact.numbers.map { SmsRecipient(contact.name, it) } }

    /**
     * Understands an answer to "Which one?" about [contacts]: a position in [entries] ("the
     * second one", "three", "number seven"), or a contact's name.
     *
     * @param looseNames whether a name said loosely picks too ("connor" for "Sarah Connor"), not
     * only a whole name: not after a no, which looks anyone else up (see [SmsChooserAnswer.of])
     * @return what [input] picks, or null if it picks nothing
     */
    fun pick(
        input: String,
        contacts: List<SmsContact>,
        parserFormatter: ParserFormatter?,
        looseNames: Boolean = true,
    ): RecipientPick? {
        // a contact's exact name before the position: "First Bank" is that contact, not the
        // first entry (loose matching comes after, or "the second one" could match some name)
        val normalized = normalizeAnswer(input)
        contacts.singleOrNull { normalized.isNotEmpty() && normalizeAnswer(it.name) == normalized }
            ?.let { return picked(it) }

        val entries = entries(contacts)
        val index = parseOptionIndex(input, entries.size)
            ?: parserFormatter?.extractNumber(input)
                ?.preferOrdinal(true)
                ?.integerOnly(true)
                ?.parseFirstIfInteger()
                ?.let { it.toInt() - 1 }
                ?.takeIf { it in entries.indices }
        if (index != null) {
            return RecipientPick.Picked(entries[index])
        }
        if (!looseNames) {
            return null
        }

        val trimmed = input.trim()
        val contact = (NameMatcher.match(trimmed, contacts, SmsContact::name) as? Resolution.Resolved)
            ?.value
            // as in the telephone skill's ContactChooserName, e.g. a last name alone, but by
            // whole words: "no to erin" is not "Katerina Book Club"
            ?: contacts.map { Pair(it, StringUtils.contactStringDistance(trimmed, it.name)) }
                .filter {
                    it.second < NAME_DISTANCE_THRESHOLD && NameMatcher.sharesWord(trimmed, it.first.name)
                }
                .minByOrNull { it.second }
                ?.first
            ?: return null
        return picked(contact)
    }

    /** The contact's only number, or the contact to pick one of its numbers from. */
    private fun picked(contact: SmsContact): RecipientPick =
        if (contact.numbers.size == 1) {
            RecipientPick.Picked(SmsRecipient(contact.name, contact.numbers[0]))
        } else {
            RecipientPick.Narrowed(contact)
        }

    /**
     * Looks [spoken] up in the phone's contacts (needs `READ_CONTACTS`) with the telephone
     * skill's [Contact] search, see [find].
     */
    fun findInContacts(
        contentResolver: ContentResolver,
        spoken: String,
        splitMessage: Boolean,
    ): Pair<RecipientChoice, String?> {
        val numbers = HashMap<Contact, List<String>>() // one query per contact
        return find(
            spoken = spoken,
            splitMessage = splitMessage,
            search = { Contact.getFilteredSortedContacts(contentResolver, it) },
            name = Contact::name,
            distance = Contact::distance,
            numbers = { contact -> numbers.getOrPut(contact) { contact.getNumbers(contentResolver) } },
        )
    }

    /**
     * @param splitMessage whether [spoken] may also hold the message (see [splitMessage])
     * @param search the contacts sorted by distance to a name, best first
     * @return the choice, and the message split off [spoken] as said, if any
     */
    fun <C> find(
        spoken: String,
        splitMessage: Boolean,
        search: (String) -> List<C>,
        name: (C) -> String,
        distance: (C) -> Int,
        numbers: (C) -> List<String>,
    ): Pair<RecipientChoice, String?> {
        val contacts = search(spoken)
        val split = if (splitMessage) splitMessage(spoken, contacts, name) else null
        if (split != null) {
            // the distances were computed for the whole capture, sort again for the name alone
            return Pair(choose(split.first, search(split.first), name, distance, numbers), split.second)
        }
        return Pair(choose(spoken, contacts, name, distance, numbers), null)
    }

    private val WHITESPACE = Regex("\\s+")
    private val NAME_FILLERS = setOf("the", "my", "a", "an", "to", "our")
    private val TYPED_NUMBER = Regex("\\+?[0-9][0-9 ()./-]*")
    private const val MIN_DIGITS = 3

    /** The telephone skill's ContactChooserName threshold. */
    private const val NAME_DISTANCE_THRESHOLD = -7
}
