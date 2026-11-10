package org.stypox.dicio.skills.contact_info

import android.util.Log
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.gar.ask.GarPickOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.calls.CallLogInfo
import org.stypox.dicio.skills.notes.isPhoneLocked
import org.stypox.dicio.skills.sms.ContactWords
import org.stypox.dicio.skills.sms.OwnContact
import org.stypox.dicio.util.getString

/**
 * "What's mom's number", "what's dad's email", "where does sarah live", "when is mom's
 * birthday", "what's my number", "add a new contact", "save this number as joe" (gar/PARITY.md,
 * batch 6), English only, no tool. Contacts are found like texts and calls find them: GAR's
 * [NameMatcher], else only contacts sharing a whole word with what was said
 * ([ContactWords.matches]), so no unrelated contact is offered; "my" is the owner ([OwnContact]).
 * GAR never writes a contact: a new one opens in the contacts app's editor with what was said
 * filled in, and the user saves it there.
 */
class ContactInfoSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Sentences.ContactInfo>,
    private val book: (SkillContext) -> ContactBook = { ctx ->
        AndroidContactBook(ctx.android) { OwnContact.of(ctx) }
    },
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
) : Skill<ContactRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, ContactRequest?> {
        val request = ContactInfoWords.request(input) ?: return Pair(AlwaysWorstScore, null)
        return Pair(data.score(ctx, input).first, request)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: ContactRequest?): SkillOutput {
        val request = inputData ?: throw IllegalArgumentException("No contact request")
        if (isLocked(ctx)) {
            return GarLockedOutput()
        }
        val book = book(ctx)
        return try {
            when (request) {
                is ContactRequest.Info -> info(book, request)
                is ContactRequest.Add -> add(book, request)
            }
        } catch (e: SecurityException) {
            MissingPermissionsSkillOutput(correspondingSkillInfo)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not read the contacts", e)
            ContactInfoOutput.Failed
        }
    }

    private suspend fun info(book: ContactBook, request: ContactRequest.Info): SkillOutput {
        if (!book.allowed) {
            return MissingPermissionsSkillOutput(correspondingSkillInfo)
        }
        if (request.who.isEmpty()) {
            val (name, values) = book.own(request.field) ?: return ContactInfoOutput.OwnUnknown
            return ContactInfoOutput.Found(name, request.field, values, own = true)
        }
        val contacts = find(book.names(), request.who)
        return when (contacts.size) {
            0 -> ContactInfoOutput.NotFound(request.who)
            1 -> found(book, contacts[0], request.field)
            else -> GarPickOutput(
                info = correspondingSkillInfo,
                options = contacts,
                question = { c ->
                    c.getString(R.string.gar_contacts_which, ContactSay.joinedOr(c, contacts.map { it.name }))
                },
                shown = { _, contact -> contact.name },
                nameOf = { it.name },
                onPick = { _, contact -> found(book, contact, request.field) },
            )
        }
    }

    private fun found(book: ContactBook, contact: ContactName, field: ContactField): SkillOutput =
        ContactInfoOutput.Found(contact.name, field, book.values(contact, field), own = false)

    private fun add(book: ContactBook, request: ContactRequest.Add): SkillOutput {
        var number = request.number
        if (request.lastCall) {
            if (!book.callLogAllowed) {
                // the call log skill's permission, asked for plainly with its name
                return MissingPermissionsSkillOutput(CallLogInfo)
            }
            val call = book.lastCall() ?: return ContactInfoOutput.NoRecentCall
            if (call.hidden || call.number.isBlank()) {
                return ContactInfoOutput.NoRecentCall
            }
            call.name?.let { return ContactInfoOutput.AlreadySaved(it) }
            number = call.number
        }
        val contact = NewContact(request.name.trim().split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }, number)
        return if (book.openEditor(contact)) ContactInfoOutput.EditorOpened(contact) else ContactInfoOutput.NoContactsApp
    }

    companion object {
        private const val TAG = "ContactInfoSkill"

        /** At most this many contacts are offered, like texts and calls. */
        const val MAX_CONTACTS = 5

        /**
         * The contacts [spoken] names: [NameMatcher]'s best, else the ones sharing a whole word
         * with it. "moms" (from "mom's" without the apostrophe) is tried as "mom" too.
         */
        fun find(contacts: List<ContactName>, spoken: String): List<ContactName> {
            val tries = listOfNotNull(spoken, spoken.removeSuffix("s").takeIf { spoken.endsWith("s") && spoken.length > 3 })
            for (said in tries) {
                when (val match = NameMatcher.match(said, contacts, ContactName::name)) {
                    is Resolution.Resolved -> return listOf(match.value)
                    is Resolution.Ambiguous -> return match.options.take(MAX_CONTACTS)
                    is Resolution.Unresolvable -> {}
                }
            }
            for (said in tries) {
                val sharing = contacts.filter { ContactWords.matches(said, it.name) }
                if (sharing.isNotEmpty()) {
                    return sharing.take(MAX_CONTACTS)
                }
            }
            return listOf()
        }
    }
}
