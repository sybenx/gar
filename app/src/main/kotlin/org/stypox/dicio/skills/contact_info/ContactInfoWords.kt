package org.stypox.dicio.skills.contact_info

import org.stypox.dicio.skills.notes.NotesText
import org.stypox.dicio.skills.sms.ContactWords
import org.stypox.dicio.skills.sms.SmsRecipients

/** What of a contact the user asked for. */
enum class ContactField { NUMBER, EMAIL, ADDRESS, BIRTHDAY }

/** What the user asked of the contacts. */
sealed interface ContactRequest {
    /** "What's mom's number": [who] as said ("mom"), "" for the owner ("what's my number"). */
    data class Info(val who: String, val field: ContactField) : ContactRequest

    /**
     * "Add a new contact", "add joe to my contacts", "save five five five one two one two as
     * joe": a new contact with what was said filled in ([name] and [number], "" when not said).
     * With [lastCall] ("save this number as joe"), the number is the last call's.
     */
    data class Add(val name: String, val number: String, val lastCall: Boolean) : ContactRequest
}

/** Reads contact requests from the whole input. English. */
object ContactInfoWords {
    fun request(input: String): ContactRequest? {
        val said = NotesText.of(input)
        if (said.isEmpty()) {
            return null
        }
        val text = NotesText.apostrophesOff(said)
        add(said, text)?.let { return it }
        return info(said, text)
    }

    private fun info(said: String, text: String): ContactRequest? {
        OWN.matchEntire(text)?.let { return ContactRequest.Info("", field(it.groupValues[1]) ?: return null) }
        LIVES.matchEntire(text)?.let { return person(it.groupValues[1], ContactField.ADDRESS) }
        BIRTHDAY.matchEntire(said)?.let { return person(it.groupValues[1], ContactField.BIRTHDAY) }
        FIELD_OF.matchEntire(text)?.let { return person(it.groupValues[2], field(it.groupValues[1]) ?: return null) }
        // with apostrophes, so that "james" stays James and "mom's" is mom
        WHOSE.matchEntire(said)?.let { return person(it.groupValues[1], field(it.groupValues[2]) ?: return null) }
        return null
    }

    private fun add(said: String, text: String): ContactRequest? {
        NEW_CONTACT.matchEntire(text)?.let { match ->
            val named = match.groupValues[1]
            val number = SmsRecipients.phoneNumber(named)
            return if (number != null) {
                ContactRequest.Add("", number, lastCall = false)
            } else {
                ContactRequest.Add(named, "", lastCall = false)
            }
        }
        SAVE_LAST.matchEntire(text)?.let { return ContactRequest.Add(it.groupValues[1], "", lastCall = true) }
        SAVE_AS.matchEntire(said)?.let { match ->
            val number = SmsRecipients.phoneNumber(match.groupValues[1]) ?: return null
            return ContactRequest.Add(match.groupValues[2], number, lastCall = false)
        }
        ADD_TO_CONTACTS.matchEntire(text)?.let { match ->
            val what = match.groupValues[1]
            val named = match.groupValues[2]
            val number = SmsRecipients.phoneNumber(what)
            return when {
                number != null -> ContactRequest.Add(named, number, lastCall = false)
                what in LAST_CALL -> ContactRequest.Add(named, "", lastCall = true)
                named.isEmpty() && what !in NOT_NAMES -> ContactRequest.Add(what, "", lastCall = false)
                else -> null
            }
        }
        return null
    }

    /** "mom's" -> "mom"; "my wife" stays; "me", "my" and "myself" are the owner (""). */
    private fun person(who: String, field: ContactField): ContactRequest? {
        val name = who.trim().removeSuffix("'s").removeSuffix("'").trim()
        if (name.isEmpty() || name.split(" ").size > MAX_NAME_WORDS || name in NOT_NAMES) {
            return null
        }
        // "my wife", "my boss": a person; "my locker", "my car" are things (the facts skill's)
        val words = name.split(" ")
        if (words.first() == "my" && words.size > 1 && words.none { it.removeSuffix("'s") in PEOPLE }) {
            return null
        }
        return ContactRequest.Info(if (ContactWords.isOwn(name) || name == "my") "" else name, field)
    }

    private fun field(said: String): ContactField? = when {
        said.contains("birthday") -> ContactField.BIRTHDAY
        said.contains("address") && !said.contains("email") && !said.contains("e mail") -> ContactField.ADDRESS
        said.contains("mail") -> ContactField.EMAIL
        said.contains("number") || said.contains("cell") || said.contains("mobile") || said.contains("phone") ->
            ContactField.NUMBER
        else -> null
    }

    private const val FIELD =
        "(phone number|number|cell number|cell phone number|cell phone|mobile number|cell|mobile|" +
            "home number|work number|telephone number|phone|email address|email|e mail address|e mail|" +
            "mail address|address|home address|work address|street address|birthday)"

    private const val ASK =
        "(?:whats|what s|what's|what is|what are|tell me|give me|get me|get|read me|read|show me|show|" +
            "look up|find|find me|do you know|do i have|do you have|i need|send me)"

    private val OWN = Regex("$ASK my (?:own )?$FIELD")
    private val LIVES = Regex("where (?:does|do) (.+?) live(?: now)?")
    private val BIRTHDAY = Regex("when(?: is|'s| s|s) (.+?)(?:'s)? birthday")
    private val FIELD_OF = Regex("$ASK (?:the )?$FIELD (?:for|of) (.+)")
    private val WHOSE = Regex("$ASK (.+?) $FIELD")

    private val NEW_CONTACT = Regex(
        "(?:add|create|make|new|start|save)(?: a)?(?: new)? contact(?: (?:for|called|named|with) (.+))?"
    )
    private val SAVE_LAST = Regex(
        "(?:save|add|store) (?:this|that|the|the last|my last|the last callers|the last caller s) " +
            "(?:number|phone number|caller|call)(?: (?:as|to|under|for) (.+?))?(?: (?:in|to) my contacts)?"
    )
    private val SAVE_AS = Regex("(?:save|store|add) (.+?) (?:as|under) (.+?)(?: (?:in|to) my contacts)?")
    private val ADD_TO_CONTACTS = Regex(
        "(?:add|save|put|store) (.+?) (?:to|in|into) (?:my |the )?contacts(?: list)?(?: (?:as|under) (.+))?"
    )

    /** The number "this number" means: the last call's. */
    private val LAST_CALL = setOf(
        "this number", "that number", "the number", "the last number", "my last number",
        "the last caller", "the last call", "this caller", "that caller",
    )

    private val NOT_NAMES = setOf(
        "it", "this", "that", "them", "the", "a", "an", "your", "his", "her", "their", "our",
        "the wifi", "the wi fi", "the wifi password", "the account", "the order", "the tracking",
        "the case", "the confirmation", "the reference", "the flight", "the serial", "the model",
        "the room", "the gate", "the seat", "the table", "the license plate", "the zip",
    )

    /** Who "my ..." can be in a contact's name. */
    private val PEOPLE = setOf(
        "wife", "husband", "partner", "girlfriend", "boyfriend", "fiance", "fiancee", "mom", "mother",
        "mum", "dad", "father", "sister", "brother", "son", "daughter", "kid", "kids", "grandma",
        "grandpa", "grandmother", "grandfather", "aunt", "uncle", "cousin", "niece", "nephew",
        "boss", "doctor", "dentist", "friend", "neighbor", "neighbour", "landlord", "babysitter",
        "plumber", "mechanic", "lawyer", "therapist", "coworker", "roommate", "work", "office",
        "parents", "in", "law",
    )

    /** "the number for mom's work" is fine, a whole sentence isn't a name. */
    private const val MAX_NAME_WORDS = 4
}
