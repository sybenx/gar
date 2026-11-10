package org.stypox.dicio.skills.contact_info

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.util.Log
import androidx.core.content.ContextCompat
import org.stypox.dicio.skills.calls.AndroidCallHistory
import org.stypox.dicio.skills.calls.CallEntry
import org.stypox.dicio.skills.sms.OwnContact

/** A contact by its id and display name. */
data class ContactName(val id: Long, val name: String)

/** One phone number, email address, postal address or birthday, with its label ("Mobile"). */
data class ContactValue(val value: String, val label: String?)

/** A new contact for the contacts app's editor; "" for what wasn't said. */
data class NewContact(val name: String, val number: String)

/** The contacts and the call log as the contact info skill reads them ([AndroidContactBook]). */
interface ContactBook {
    /** Whether GAR may read the contacts (`READ_CONTACTS`). */
    val allowed: Boolean

    /** Every contact with a name. */
    fun names(): List<ContactName>

    /** What [contact] has of [field], as stored. */
    fun values(contact: ContactName, field: ContactField): List<ContactValue>

    /** The owner's name and values of [field] ("what's my number"), or null when unknown. */
    suspend fun own(field: ContactField): Pair<String, List<ContactValue>>?

    /** Whether GAR may read the call log, for "save this number". */
    val callLogAllowed: Boolean

    /** The newest call, for "save this number as joe". */
    fun lastCall(): CallEntry?

    /** Opens the contacts app's editor with [contact] filled in; false if no app can. */
    fun openEditor(contact: NewContact): Boolean
}

/**
 * The contacts provider (`READ_CONTACTS`, which the telephone and sms skills already ask for),
 * the owner as "text myself" finds them ([OwnContact]) and the call log ([AndroidCallHistory]).
 * New contacts never get written here: [openEditor] fills in the contacts app's editor
 * (`ACTION_INSERT` with `ContactsContract.Intents.Insert` extras), where the user saves.
 */
class AndroidContactBook(private val context: Context, private val ownContact: () -> OwnContact) : ContactBook {
    private val resolver = context.contentResolver

    override val allowed: Boolean
        get() = granted(Manifest.permission.READ_CONTACTS)

    override val callLogAllowed: Boolean
        get() = granted(Manifest.permission.READ_CALL_LOG)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun names(): List<ContactName> {
        val names = mutableListOf<ContactName>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = if (cursor.isNull(1)) null else cursor.getString(1)
                if (!name.isNullOrBlank()) {
                    names += ContactName(cursor.getLong(0), name)
                }
            }
        }
        return names
    }

    override fun values(contact: ContactName, field: ContactField): List<ContactValue> {
        val (uri, column, selection) = when (field) {
            ContactField.NUMBER -> Triple(Phone.CONTENT_URI, Phone.NUMBER, "${Phone.CONTACT_ID} = ?")
            ContactField.EMAIL -> Triple(Email.CONTENT_URI, Email.ADDRESS, "${Email.CONTACT_ID} = ?")
            ContactField.ADDRESS -> Triple(
                StructuredPostal.CONTENT_URI, StructuredPostal.FORMATTED_ADDRESS,
                "${StructuredPostal.CONTACT_ID} = ?",
            )
            ContactField.BIRTHDAY -> Triple(
                ContactsContract.Data.CONTENT_URI, Event.START_DATE,
                "${Event.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${Event.TYPE} = ?",
            )
        }
        val args = if (field == ContactField.BIRTHDAY) {
            arrayOf(contact.id.toString(), Event.CONTENT_ITEM_TYPE, Event.TYPE_BIRTHDAY.toString())
        } else {
            arrayOf(contact.id.toString())
        }
        val values = mutableListOf<ContactValue>()
        try {
            resolver.query(uri, arrayOf(column, "data2", "data3"), selection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val value = if (cursor.isNull(0)) continue else cursor.getString(0)?.trim()
                    if (value.isNullOrEmpty() || values.any { it.value == value }) {
                        continue
                    }
                    values += ContactValue(value, label(field, cursor.isNull(1), cursor.getInt(1), cursor.getString(2)))
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read ${field.name} of a contact", e)
        }
        return values
    }

    /** "Mobile", "Home", "Work" or the custom label, as the contacts app shows it. */
    private fun label(field: ContactField, noType: Boolean, type: Int, custom: String?): String? {
        if (noType) {
            return custom
        }
        val resources = context.resources
        return when (field) {
            ContactField.NUMBER -> Phone.getTypeLabel(resources, type, custom)
            ContactField.EMAIL -> Email.getTypeLabel(resources, type, custom)
            ContactField.ADDRESS -> StructuredPostal.getTypeLabel(resources, type, custom)
            ContactField.BIRTHDAY -> null
        }?.toString()
    }

    override suspend fun own(field: ContactField): Pair<String, List<ContactValue>>? {
        val card = ownContact().cards().firstOrNull() ?: return null
        return when (field) {
            ContactField.NUMBER -> Pair(card.name, card.numbers.map { ContactValue(it, null) })
            ContactField.EMAIL -> Pair(card.name, card.emails.map { ContactValue(it, null) })
            else -> {
                val contact = names().firstOrNull { it.name == card.name } ?: return Pair(card.name, listOf())
                Pair(card.name, values(contact, field))
            }
        }
    }

    override fun lastCall(): CallEntry? = AndroidCallHistory(context).recent().firstOrNull()

    override fun openEditor(contact: NewContact): Boolean {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION)
            .setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (contact.name.isNotEmpty()) {
            intent.putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
        }
        if (contact.number.isNotEmpty()) {
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, contact.number)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No contacts app takes new contacts", e)
            false
        }
    }

    private companion object {
        const val TAG = "ContactBook"
    }
}
