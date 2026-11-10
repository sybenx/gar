package org.stypox.dicio.gar.settings

import androidx.datastore.core.DataStore
import org.stypox.dicio.R
import org.stypox.dicio.gar.stt.ContactPromptNames
import org.stypox.dicio.gar.stt.GarSttOwnNameAsk
import org.stypox.dicio.skills.sms.OwnCard

/**
 * Who the user is to GAR: one setting, "You", at the top of GAR's settings. It is kept in two
 * fields, `own_contact_name` (the contact "text myself", "call me" and contact info use) and
 * `stt_own_name` (the first name in Whisper's name hint), and [GarYou.set] writes them together,
 * so that the hint and "text myself" agree. A contact that names nobody ("Me") keeps a typed name
 * beside it.
 */
sealed interface YouChoice {
    /** The contact called [name] is the user; their first name goes into the hint. */
    data class Contact(val name: String) : YouChoice

    /** Just a name for the hint, typed; it replaces a contact unless that one is "Me". */
    data class Name(val name: String) : YouChoice

    /** GAR forgets both, and the home screen's card may ask again. */
    data object Clear : YouChoice
}

/** Who GAR thinks the user is, and where that came from, as the "You" setting shows it. */
sealed interface You {
    /**
     * GAR's own contact and its first number, if it has one: "Alex Herbert, 435-555-0100". A
     * contact that names nobody ("Me") also shows the typed [firstName]: "Me (Alex), ...".
     * [textsToProfile] is when "text myself" goes to the Contacts app's own card instead, as
     * [org.stypox.dicio.skills.sms.OwnContact.cards] has it: the contact has no number and the
     * profile has one.
     */
    data class Contact(
        val name: String,
        val number: String?,
        val firstName: String? = null,
        val check: ContactCheck = ContactCheck.FOUND,
        val textsToProfile: Boolean = false,
    ) : You

    /** A typed name, no contact: "Alex (name only)". */
    data class NameOnly(val name: String, val textsToProfile: Boolean = false) : You

    /** The Contacts app's own card: "Alex Herbert (your card in Contacts)". */
    data class Profile(val name: String) : You

    /** "Not set". */
    data object NotSet : You
}

/** What GAR found of its own contact in the phone's contacts. */
enum class ContactCheck {
    /** Read: [You.Contact.number] is its first number, or null without one. */
    FOUND,

    /** No contact by that name with a number or an address: deleted, renamed or emptied. */
    NOT_FOUND,

    /** No `READ_CONTACTS`, so GAR can't tell. */
    NO_ACCESS,

    /** The phone is locked: the number isn't shown over the lock screen. */
    LOCKED,
}

/** A contact the "You" dialog offers, with its first number and first address, null without. */
data class YouContact(val name: String, val number: String?, val email: String? = null)

object GarYou {
    /**
     * The one way to set who the user is: the "You" setting, the home screen's first-name card
     * ([org.stypox.dicio.gar.stt.GarSttOwnNameStore]) and "Which contact is you?"
     * ([org.stypox.dicio.skills.sms.AndroidOwnContact.remember]) all call it. A typed name
     * without a name in it ([GarSttOwnNameAsk.typed]) changes nothing.
     */
    suspend fun set(settings: DataStore<GarSettings>, choice: YouChoice) {
        settings.updateData { apply(it, choice) }
    }

    /** [settings] with [choice] applied, see [set]. */
    fun apply(settings: GarSettings, choice: YouChoice): GarSettings = when (choice) {
        is YouChoice.Contact -> {
            val builder = settings.toBuilder().setOwnContactName(choice.name)
            // a contact saved as "Me" names nobody: a typed name stays, or the card may ask
            ContactPromptNames.ownerFirstName(choice.name)?.let {
                builder.setSttOwnName(it).setSttOwnNameAsked(true)
            }
            builder.build()
        }
        is YouChoice.Name -> GarSttOwnNameAsk.typed(choice.name)?.let {
            val builder = settings.toBuilder()
            // a typed name only replaces a contact that names someone: "Me" keeps "text myself"
            if (namesSomeone(settings.ownContactName)) {
                builder.clearOwnContactName()
            }
            builder.setSttOwnName(it).setSttOwnNameAsked(true).build()
        } ?: settings
        YouChoice.Clear -> settings.toBuilder()
            .clearOwnContactName()
            .clearSttOwnName()
            .setSttOwnNameAsked(false)
            .build()
    }

    /** Whether the contact [name] gives a first name, unlike "Me" or "My number". */
    fun namesSomeone(name: String): Boolean = ContactPromptNames.ownerFirstName(name) != null

    /**
     * Who [settings] say the user is, in the order the hint takes them
     * ([org.stypox.dicio.gar.stt.OwnerSource]): GAR's own contact (with [card], as
     * [org.stypox.dicio.skills.sms.OwnContact.card] read it), a typed name, then [profileName],
     * the Contacts app's own card, whose numbers ([profile]) "text myself" falls back to. Without
     * [access] or while [locked], no card is read and no number shown.
     */
    fun who(
        settings: GarSettings,
        card: OwnCard?,
        profileName: String?,
        profile: OwnCard? = null,
        access: Boolean = true,
        locked: Boolean = false,
    ): You {
        val readable = access && !locked
        val profileTexts = readable && profile?.numbers?.isNotEmpty() == true
        val contact = settings.ownContactName
        return when {
            contact.isNotEmpty() -> {
                val check = when {
                    locked -> ContactCheck.LOCKED
                    !access -> ContactCheck.NO_ACCESS
                    card == null -> ContactCheck.NOT_FOUND
                    else -> ContactCheck.FOUND
                }
                val number = card?.numbers?.firstOrNull()?.takeIf { check == ContactCheck.FOUND }
                You.Contact(
                    name = contact,
                    number = number,
                    firstName = settings.sttOwnName.takeIf { it.isNotEmpty() && !namesSomeone(contact) },
                    check = check,
                    textsToProfile = number == null && profileTexts,
                )
            }
            settings.sttOwnName.isNotEmpty() -> You.NameOnly(settings.sttOwnName, profileTexts)
            !profileName.isNullOrBlank() -> You.Profile(profileName)
            else -> You.NotSet
        }
    }

    /** The "You" setting's summary of [you], with [string] as `Resources.getString`. */
    fun summary(you: You, string: (Int, Array<out Any>) -> String): String {
        fun s(id: Int, vararg args: Any) = string(id, args)
        val text = when (you) {
            is You.Contact -> {
                val name = you.firstName?.let { s(R.string.gar_you_contact_first_name, you.name, it) }
                    ?: you.name
                when (you.check) {
                    ContactCheck.FOUND -> you.number?.let { s(R.string.gar_you_contact, name, it) }
                        ?: s(R.string.gar_you_contact_no_number, name)
                    ContactCheck.NOT_FOUND -> s(R.string.gar_you_contact_not_found, name)
                    ContactCheck.NO_ACCESS -> s(R.string.gar_you_contact_no_access, name)
                    ContactCheck.LOCKED -> name
                }
            }
            is You.NameOnly -> s(R.string.gar_you_name_only, you.name)
            is You.Profile -> s(R.string.gar_you_profile, you.name)
            You.NotSet -> s(R.string.gar_you_not_set)
        }
        val textsToProfile = (you as? You.Contact)?.textsToProfile ?: (you as? You.NameOnly)?.textsToProfile
        return if (textsToProfile == true) s(R.string.gar_you_texts_to_profile, text) else text
    }

    /** Whether the "You" dialog may open: not over the lock screen, nor before that was read. */
    fun canOpen(locked: Boolean?): Boolean = locked == false

    /** Whether the dialog offers Clear: the profile is still read after it, and NotSet has nothing. */
    fun canClear(you: You?): Boolean = you is You.Contact || you is You.NameOnly

    /**
     * The contacts whose name or number has [query] in it (ignoring case and, for numbers,
     * everything but digits), in [contacts]' order; all of them for a blank [query].
     */
    fun search(contacts: List<YouContact>, query: String): List<YouContact> {
        val words = query.trim()
        if (words.isEmpty()) {
            return contacts
        }
        // "435 555" finds 435-555-0100; a query with letters is a name
        val digits = words.filter(Char::isDigit).takeIf { it.isNotEmpty() && words.none(Char::isLetter) }
        return contacts.filter { contact ->
            contact.name.contains(words, ignoreCase = true) ||
                (digits != null && contact.number?.filter(Char::isDigit)?.contains(digits) == true)
        }
    }
}
