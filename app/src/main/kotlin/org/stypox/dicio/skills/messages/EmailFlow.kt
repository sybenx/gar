package org.stypox.dicio.skills.messages

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.parseOptionIndex
import org.stypox.dicio.skills.sms.ContactWords
import org.stypox.dicio.skills.sms.OwnContact
import org.stypox.dicio.skills.sms.OwnContactAskOutput
import org.stypox.dicio.skills.sms.OwnNeed
import org.stypox.dicio.skills.sms.SmsMessage
import org.stypox.dicio.skills.sms.SmsRecipients
import org.stypox.dicio.util.StringUtils

/** A contact with all of its email addresses. */
data class EmailContact(val name: String, val addresses: List<String>)

/** Who an email goes to. For an address that is no contact, [name] is the address itself. */
data class EmailRecipient(val name: String, val address: String) {
    val isContact: Boolean get() = name != address
}

/** The contacts that match what the user said, see [EmailRecipients.choose]. */
sealed interface EmailChoice {
    data class One(val recipient: EmailRecipient) : EmailChoice

    /** Several contacts, or one contact with several addresses: the user picks one address. */
    data class Several(val contacts: List<EmailContact>) : EmailChoice

    data object NotFound : EmailChoice
}

/** An answer to "Which one?", see [EmailRecipients.pick]. */
sealed interface EmailPick {
    data class Picked(val recipient: EmailRecipient) : EmailPick

    /** The user named a contact with several addresses: ask which one. */
    data class Narrowed(val contact: EmailContact) : EmailPick
}

/**
 * Finds an email's recipient among the contacts with an email address, like the sms skill finds
 * a text's: GAR's [NameMatcher] first, then the telephone skill's contact distance among the
 * contacts that share a whole word with what was said.
 */
object EmailRecipients {
    /** At most this many contacts are offered to choose from, like the telephone skill. */
    const val MAX_CONTACTS = 5

    /** The telephone skill's ContactChooserName threshold, for answers to "Which one?". */
    private const val NAME_DISTANCE_THRESHOLD = -7

    private val TYPED_ADDRESS = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")

    fun choose(spoken: String, contacts: List<EmailContact>): EmailChoice {
        val trimmed = spoken.trim()
        if (TYPED_ADDRESS.matches(trimmed)) {
            return EmailChoice.One(EmailRecipient(trimmed, trimmed))
        }
        val withAddresses = contacts.filter { it.addresses.isNotEmpty() }
        if (trimmed.isEmpty() || withAddresses.isEmpty()) {
            return EmailChoice.NotFound
        }
        val matched = when (val match = NameMatcher.match(trimmed, withAddresses, EmailContact::name)) {
            is Resolution.Resolved -> listOf(match.value)
            is Resolution.Ambiguous -> match.options
            is Resolution.Unresolvable -> byDistance(trimmed, withAddresses)
        }
        return when (matched.size) {
            0 -> EmailChoice.NotFound
            1 -> single(matched[0])
            else -> EmailChoice.Several(matched.take(MAX_CONTACTS))
        }
    }

    /**
     * The telephone skill's choice by distance: the closest contact when no other comes within
     * 2, otherwise the closest [MAX_CONTACTS] to choose from. Only contacts that share about as
     * many letters as were said count, so that "grandma" without a grandma among the addresses
     * is not found, instead of a list of contacts that share an "a" and an "r", and only by
     * whole words ([NameMatcher.sharesWord]): "erin" is not in "Katerina Book Club".
     */
    private fun byDistance(spoken: String, contacts: List<EmailContact>): List<EmailContact> {
        val letters = spoken.count { it.isLetter() }
        val sorted = contacts.map { Pair(it, StringUtils.contactStringDistance(it.name, spoken)) }
            .filter {
                it.second < 0 && it.second <= -letters && NameMatcher.sharesWord(spoken, it.first.name)
            }
            .sortedBy { it.second }
        if (sorted.size == 1 || (sorted.size > 1 && sorted[1].second - 2 > sorted[0].second)) {
            return listOf(sorted[0].first)
        }
        return sorted.take(MAX_CONTACTS).map { it.first }
    }

    /** The contact's only address, or the contact to pick one of its addresses from. */
    fun single(contact: EmailContact): EmailChoice =
        if (contact.addresses.size == 1) {
            EmailChoice.One(EmailRecipient(contact.name, contact.addresses[0]))
        } else {
            EmailChoice.Several(listOf(contact))
        }

    /** The addresses of [contacts] in the order they are shown, each with its contact's name. */
    fun entries(contacts: List<EmailContact>): List<EmailRecipient> =
        contacts.flatMap { contact -> contact.addresses.map { EmailRecipient(contact.name, it) } }

    /**
     * Understands an answer to "Which one?" about [contacts]: a contact's exact name, a position
     * in [entries] ("the second one", "two"), an address as typed, or a contact's name said
     * loosely, unless not [looseNames] (after a no, see [EmailChooserAnswer.of]). Null if it
     * picks nothing.
     */
    fun pick(
        input: String,
        contacts: List<EmailContact>,
        parserFormatter: ParserFormatter?,
        looseNames: Boolean = true,
    ): EmailPick? {
        val normalized = normalizeAnswer(input)
        contacts.singleOrNull { normalized.isNotEmpty() && normalizeAnswer(it.name) == normalized }
            ?.let { return picked(it) }

        val entries = entries(contacts)
        entries.firstOrNull { it.address.equals(input.trim(), ignoreCase = true) }
            ?.let { return EmailPick.Picked(it) }
        val index = parseOptionIndex(input, entries.size)
            ?: parserFormatter?.extractNumber(input)
                ?.preferOrdinal(true)
                ?.integerOnly(true)
                ?.parseFirstIfInteger()
                ?.let { it.toInt() - 1 }
                ?.takeIf { it in entries.indices }
        if (index != null) {
            return EmailPick.Picked(entries[index])
        }
        if (!looseNames) {
            return null
        }

        val trimmed = input.trim()
        val contact = (NameMatcher.match(trimmed, contacts, EmailContact::name) as? Resolution.Resolved)
            ?.value
            ?: contacts.map { Pair(it, StringUtils.contactStringDistance(trimmed, it.name)) }
                .filter {
                    it.second < NAME_DISTANCE_THRESHOLD && NameMatcher.sharesWord(trimmed, it.first.name)
                }
                .minByOrNull { it.second }
                ?.first
            ?: return null
        return picked(contact)
    }

    private fun picked(contact: EmailContact): EmailPick =
        if (contact.addresses.size == 1) {
            EmailPick.Picked(EmailRecipient(contact.name, contact.addresses[0]))
        } else {
            EmailPick.Narrowed(contact)
        }

    /**
     * Every contact with an email address (needs `READ_CONTACTS`), one entry per contact with its
     * addresses as stored, the same address once.
     */
    fun queryContacts(contentResolver: ContentResolver): List<EmailContact> {
        val byId = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val address = cursor.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val addresses = byId.getOrPut(id) { Pair(name, mutableListOf()) }.second
                    if (addresses.none { it.equals(address, ignoreCase = true) }) {
                        addresses.add(address)
                    }
                }
            }
        } catch (e: RuntimeException) {
            // SecurityException without READ_CONTACTS (Dicio asks for it before the skill runs)
            Log.w(TAG, "Can't read the contacts' email addresses", e)
        }
        return byId.values.map { (name, addresses) -> EmailContact(name, addresses) }
    }

    private const val TAG = "EmailRecipients"
}

/** An email to open in the mail app. */
data class EmailDraft(val recipient: EmailRecipient, val subject: String, val body: String) {
    companion object {
        /** A subject made from the text has at most this many words. */
        const val MAX_SUBJECT_WORDS = 8

        /**
         * The subject: as said after "about", or the text's first sentence, cut after
         * [MAX_SUBJECT_WORDS] words ("The report is done").
         */
        fun subjectFor(subject: String?, body: String): String {
            SmsMessage.assemble(subject)?.let { return it }
            val first = body.trim().split(SENTENCE_END).first().trimEnd('.')
            val words = first.split(WHITESPACE).filter { it.isNotEmpty() }
            return if (words.size <= MAX_SUBJECT_WORDS) {
                words.joinToString(" ")
            } else {
                words.take(MAX_SUBJECT_WORDS).joinToString(" ") + "…"
            }
        }

        private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
        private val WHITESPACE = Regex("\\s+")
    }
}

/** Opens the mail app with an email filled in; only the user sends it, there. */
interface Mailer {
    /** @return false if no mail app can show it */
    fun compose(draft: EmailDraft): Boolean
}

/**
 * The intent that opens a mail app with an email filled in, free of Android types so that its
 * construction can be unit tested: `ACTION_SENDTO` to a `mailto:` address, with the subject and
 * the text as `EXTRA_SUBJECT` and `EXTRA_TEXT`, which mail apps (Thunderbird and K-9, FairEmail,
 * Gmail) fill in.
 */
data class EmailIntent(val action: String, val data: String, val extras: Map<String, String>) {
    fun toIntent(): Intent {
        val intent = Intent(action, Uri.parse(data))
        for ((key, value) in extras) {
            intent.putExtra(key, value)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        fun compose(draft: EmailDraft) = EmailIntent(
            action = Intent.ACTION_SENDTO,
            data = mailto(draft.recipient.address),
            extras = mapOf(Intent.EXTRA_SUBJECT to draft.subject, Intent.EXTRA_TEXT to draft.body),
        )

        /** "mailto:" and [address], percent-encoded except its letters, digits and `@.+-_~`. */
        fun mailto(address: String): String = "mailto:" + address.trim().toByteArray(Charsets.UTF_8)
            .joinToString("") { byte ->
                val c = byte.toInt().toChar()
                if (byte >= 0 && (c.isLetterOrDigit() || c in "@.+-_~")) {
                    c.toString()
                } else {
                    "%%%02X".format(byte.toInt() and 0xff)
                }
            }
    }
}

class AndroidMailer(context: Context) : Mailer {
    private val context = context.applicationContext

    override fun compose(draft: EmailDraft): Boolean = try {
        context.startActivity(EmailIntent.compose(draft).toIntent())
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No mail app", e)
        false
    }

    companion object {
        private const val TAG = "AndroidMailer"
    }
}

/**
 * The way from "email sarah saying the report is done" to the mail app: find the recipient among
 * the contacts' email addresses, ask for what is missing (who, which address, what it says), then
 * open the mail app with everything filled in. Nothing is sent from GAR: the user presses send in
 * the mail app.
 */
class EmailFlow(
    private val findContacts: (SkillContext) -> List<EmailContact> = { ctx ->
        EmailRecipients.queryContacts(ctx.android.contentResolver)
    },
    val mailerFor: (SkillContext) -> Mailer = { AndroidMailer(it.android) },
    private val isDeviceLocked: (SkillContext) -> Boolean = { ctx ->
        ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    },
    private val ownContact: (SkillContext) -> OwnContact = { OwnContact.of(it) },
) {
    /** @param who the recipient as said, [subject] and [what] as said; any may be missing */
    suspend fun start(ctx: SkillContext, who: String?, subject: String?, what: String?): SkillOutput =
        if (isDeviceLocked(ctx)) {
            GarLockedOutput() // "Unlock your phone first."
        } else {
            withSpokenRecipient(ctx, who, subject, SmsMessage.assemble(what), nested = false)
        }

    suspend fun withSpokenRecipient(
        ctx: SkillContext,
        spoken: String?,
        subject: String?,
        message: String?,
        nested: Boolean,
    ): SkillOutput {
        // "no, to Aaron" in reply to "Who?" is Aaron
        val name = spoken?.let(ContactWords::corrected)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return EmailAskOutput(this, EmailQuestion.RECIPIENT, null, subject, message, null, nested)
        ContactWords.ownRest(name)?.takeIf { it.isEmpty() || (message == null && subject == null) }
            ?.let { rest -> return withOwner(ctx, subject, message ?: SmsMessage.assemble(rest), nested) }
        val contacts = findContacts(ctx)
        // "email sarah the report is done": the name and the text in one capture
        val split = if (message == null && subject == null) {
            SmsRecipients.splitMessage(name, contacts.filter { it.addresses.isNotEmpty() }, EmailContact::name)
        } else {
            null
        }
        val recipientName = split?.first ?: name
        val text = message ?: split?.second?.let(SmsMessage::assemble)
        val choice = EmailRecipients.choose(recipientName, contacts)
        return withChoice(ctx, choice, recipientName, subject, text, nested)
    }

    /**
     * "Email myself": the owner's card with an address, or "Which contact is you?" when GAR
     * knows none ([OwnContact]).
     */
    private suspend fun withOwner(
        ctx: SkillContext,
        subject: String?,
        message: String?,
        nested: Boolean,
    ): SkillOutput {
        val own = ownContact(ctx)
        val card = own.cards().firstOrNull { it.emails.isNotEmpty() }
            ?: return OwnContactAskOutput(
                info = EmailInfo,
                own = own,
                then = { c, owner -> withSpokenRecipient(c, owner, subject, message, nested = true) },
                cancelled = ::EmailCancelledOutput,
                needs = OwnNeed.EMAIL,
                nested = nested,
            )
        val choice = EmailRecipients.single(EmailContact(card.name, card.emails))
        return withChoice(ctx, choice, card.name, subject, message, nested)
    }

    private fun withChoice(
        ctx: SkillContext,
        choice: EmailChoice,
        name: String,
        subject: String?,
        message: String?,
        nested: Boolean,
    ): SkillOutput = when (choice) {
        is EmailChoice.One -> withRecipient(ctx, choice.recipient, subject, message, nested)
        is EmailChoice.Several -> EmailChooseOutput(this, choice.contacts, subject, message, nested)
        EmailChoice.NotFound ->
            EmailAskOutput(this, EmailQuestion.RECIPIENT, null, subject, message, name, nested)
    }

    fun withRecipient(
        ctx: SkillContext,
        recipient: EmailRecipient,
        subject: String?,
        message: String?,
        nested: Boolean,
    ): SkillOutput =
        if (message == null) {
            EmailAskOutput(this, EmailQuestion.MESSAGE, recipient, subject, null, null, nested)
        } else {
            open(ctx, EmailDraft(recipient, EmailDraft.subjectFor(subject, message), message))
        }

    /** Opens the mail app with [draft] filled in. */
    fun open(ctx: SkillContext, draft: EmailDraft): SkillOutput =
        EmailOpenedOutput(this, draft, mailerFor(ctx).compose(draft))
}
