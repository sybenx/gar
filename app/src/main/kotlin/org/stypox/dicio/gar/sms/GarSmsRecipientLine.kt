package org.stypox.dicio.gar.sms

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import org.stypox.dicio.R
import org.stypox.dicio.skills.sms.SmsNumbers
import org.stypox.dicio.skills.sms.SmsRecipient
import java.util.Locale

/** One phone number of the contacts provider, as stored. */
data class GarPhoneRow(val contactId: Long, val number: String, val type: Int, val label: String?)

/** What the SMS confirm card says under the recipient's name. */
sealed interface GarSmsRecipientLine {
    /**
     * @param type the number's label ("Mobile", "Work", a custom label), or null if unknown
     * @param number the number, shown when the contact has more than one, or when [type] is
     *   unknown (the lookup failed or found no such number), so the card never hides where the text
     *   goes
     */
    data class Contact(val type: String?, val number: String?) : GarSmsRecipientLine

    /** A number said or typed as is, which no contact has. */
    data object NotInContacts : GarSmsRecipientLine
}

/** A string resource and its arguments. */
data class GarText(val res: Int, val args: List<String> = listOf())

/**
 * The recipient block of the SMS confirm card: the contact's name, then "Mobile, from your
 * contacts" (the number's real label from the contacts provider), or "Mobile, 555-123-4567" when
 * the contact has several numbers, or "From your contacts, 555-123-4567" when the label can't be
 * found. A number that is no contact shows formatted as the name, with
 * "Not in your contacts".
 */
object GarSmsRecipientLines {

    /**
     * @param rows the phone numbers of the contacts named like [recipient]
     * @param typeLabel the label of a row's type, as the contacts app shows it
     * @param formatNumber the number as shown
     */
    fun line(
        recipient: SmsRecipient,
        rows: List<GarPhoneRow>,
        typeLabel: (GarPhoneRow) -> String?,
        formatNumber: (String) -> String,
        locale: Locale = Locale.getDefault(),
    ): GarSmsRecipientLine {
        if (!recipient.isContact) {
            return GarSmsRecipientLine.NotInContacts
        }
        val row = rows.firstOrNull { SmsNumbers.same(it.number, recipient.number) }
            ?: return GarSmsRecipientLine.Contact(null, formatNumber(recipient.number))
        val count = SmsNumbers.distinct(rows.filter { it.contactId == row.contactId }.map { it.number })
            .size
        val type = typeLabel(row)?.trim()?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar { it.titlecase(locale) } // a custom "mobile 2" starts the line
        return GarSmsRecipientLine.Contact(
            type = type,
            number = if (count > 1 || type == null) formatNumber(recipient.number) else null,
        )
    }

    /** The line as text. */
    fun text(line: GarSmsRecipientLine): GarText = when (line) {
        GarSmsRecipientLine.NotInContacts -> GarText(R.string.gar_sms_not_in_contacts)
        is GarSmsRecipientLine.Contact -> when {
            line.type != null && line.number != null ->
                GarText(R.string.gar_sms_type_and_number, listOf(line.type, line.number))
            line.type != null -> GarText(R.string.gar_sms_type_from_contacts, listOf(line.type))
            line.number != null ->
                GarText(R.string.gar_sms_from_contacts_number, listOf(line.number))
            else -> GarText(R.string.gar_sms_from_contacts)
        }
    }

    /** What the card shows as the name: the contact's name, or the number formatted. */
    fun name(recipient: SmsRecipient, formatNumber: (String) -> String): String =
        if (recipient.isContact) recipient.name else formatNumber(recipient.number)

    /** The line before the contacts provider answered (or when it can't be read). */
    fun initial(recipient: SmsRecipient, formatNumber: (String) -> String): GarSmsRecipientLine =
        if (recipient.isContact) {
            GarSmsRecipientLine.Contact(type = null, number = formatNumber(recipient.number))
        } else {
            GarSmsRecipientLine.NotInContacts
        }

    /**
     * The lines found lately, so that a card composed again (a new card after "change the words",
     * scrolling back) has its final line in the first frame. Only lookups that found rows are kept,
     * so a lookup without READ_CONTACTS is tried again once it is granted.
     */
    private val cache =
        object : LinkedHashMap<SmsRecipient, GarSmsRecipientLine>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<SmsRecipient, GarSmsRecipientLine>?,
            ) = size > CACHE_SIZE
        }

    /** [recipient]'s line if it was looked up lately, else null. */
    fun cached(recipient: SmsRecipient): GarSmsRecipientLine? =
        if (!recipient.isContact) {
            GarSmsRecipientLine.NotInContacts
        } else {
            synchronized(cache) { cache[recipient] }
        }

    /** [recipient]'s line from the phone's contacts (needs READ_CONTACTS); call off the main thread. */
    fun load(context: Context, recipient: SmsRecipient): GarSmsRecipientLine {
        if (!recipient.isContact) {
            return GarSmsRecipientLine.NotInContacts
        }
        cached(recipient)?.let { return it }
        val rows = try {
            queryRows(context, recipient.name)
        } catch (e: Exception) {
            Log.w(TAG, "Can't read the recipient's numbers", e)
            listOf()
        }
        val result = line(
            recipient = recipient,
            rows = rows,
            typeLabel = { Phone.getTypeLabel(context.resources, it.type, it.label)?.toString() },
            formatNumber = { formatNumber(context, it) },
            locale = context.resources.configuration.locales[0] ?: Locale.getDefault(),
        )
        if (rows.isNotEmpty()) {
            synchronized(cache) { cache[recipient] = result }
        }
        return result
    }

    private fun queryRows(context: Context, name: String): List<GarPhoneRow> {
        val rows = mutableListOf<GarPhoneRow>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            "${Phone.DISPLAY_NAME} = ? AND ${Phone.NUMBER} IS NOT NULL",
            arrayOf(name),
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val number = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            val type = cursor.getColumnIndexOrThrow(Phone.TYPE)
            val label = cursor.getColumnIndexOrThrow(Phone.LABEL)
            while (cursor.moveToNext()) {
                val value = cursor.getString(number)
                if (!value.isNullOrBlank()) {
                    rows.add(GarPhoneRow(cursor.getLong(id), value, cursor.getInt(type), cursor.getString(label)))
                }
            }
        }
        return rows
    }

    /** [number] formatted for the phone's country ("(555) 123-4567"), or as is. */
    fun formatNumber(context: Context, number: String): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val country = (telephony?.networkCountryIso?.takeIf { it.isNotEmpty() }
            ?: telephony?.simCountryIso?.takeIf { it.isNotEmpty() }
            ?: Locale.getDefault().country).uppercase(Locale.ROOT)
        return try {
            PhoneNumberUtils.formatNumber(number, country) ?: number
        } catch (e: Exception) {
            number
        }
    }

    private const val TAG = "GarSmsRecipientLine"
    private const val CACHE_SIZE = 8
}
