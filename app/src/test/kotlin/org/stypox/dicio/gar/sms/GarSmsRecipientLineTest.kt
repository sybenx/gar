package org.stypox.dicio.gar.sms

import android.provider.ContactsContract.CommonDataKinds.Phone
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.R
import org.stypox.dicio.skills.sms.SmsRecipient
import java.util.Locale

private val LABELS = mapOf(
    Phone.TYPE_MOBILE to "Mobile",
    Phone.TYPE_HOME to "Home",
    Phone.TYPE_WORK to "Work",
)

/** Like Phone.getTypeLabel: the custom label for TYPE_CUSTOM, else the type's name. */
private fun label(row: GarPhoneRow): String? =
    if (row.type == Phone.TYPE_CUSTOM) row.label else LABELS[row.type]

private fun format(number: String) = "($number)"

private fun line(recipient: SmsRecipient, vararg rows: GarPhoneRow) =
    GarSmsRecipientLines.line(recipient, rows.toList(), ::label, ::format, Locale.US)

class GarSmsRecipientLineTest : StringSpec({
    val mom = SmsRecipient("Mom", "555-123-4567")

    "a contact's only number shows its type" {
        line(mom, GarPhoneRow(1, "555-123-4567", Phone.TYPE_MOBILE, null)) shouldBe
            GarSmsRecipientLine.Contact("Mobile", null)
        line(mom, GarPhoneRow(1, "5551234567", Phone.TYPE_HOME, null)) shouldBe
            GarSmsRecipientLine.Contact("Home", null)
    }

    "one number stored twice is still one number" {
        line(
            mom,
            GarPhoneRow(1, "555-123-4567", Phone.TYPE_WORK, null),
            GarPhoneRow(1, "+1 555 123 4567", Phone.TYPE_WORK, null),
        ) shouldBe GarSmsRecipientLine.Contact("Work", null)
    }

    "a contact with several numbers shows the number after the type" {
        line(
            mom,
            GarPhoneRow(1, "555 000 1111", Phone.TYPE_HOME, null),
            GarPhoneRow(1, "555-123-4567", Phone.TYPE_MOBILE, null),
        ) shouldBe GarSmsRecipientLine.Contact("Mobile", "(555-123-4567)")
    }

    "another contact with the same name doesn't count as another number" {
        line(
            mom,
            GarPhoneRow(1, "555-123-4567", Phone.TYPE_MOBILE, null),
            GarPhoneRow(2, "555 999 0000", Phone.TYPE_HOME, null),
        ) shouldBe GarSmsRecipientLine.Contact("Mobile", null)
    }

    "a custom label shows as the contact has it, capitalized" {
        line(mom, GarPhoneRow(1, "555-123-4567", Phone.TYPE_CUSTOM, "cabin")) shouldBe
            GarSmsRecipientLine.Contact("Cabin", null)
        line(mom, GarPhoneRow(1, "555-123-4567", Phone.TYPE_CUSTOM, "  ")) shouldBe
            GarSmsRecipientLine.Contact(null, "(555-123-4567)")
    }

    "a contact whose numbers can't be read is still from the contacts, with the number shown" {
        line(mom) shouldBe GarSmsRecipientLine.Contact(null, "(555-123-4567)")
        line(mom, GarPhoneRow(1, "555 000 1111", Phone.TYPE_HOME, null)) shouldBe
            GarSmsRecipientLine.Contact(null, "(555-123-4567)")
    }

    "a number that is no contact" {
        val raw = SmsRecipient("5550100", "5550100")
        line(raw, GarPhoneRow(1, "5550100", Phone.TYPE_MOBILE, null)) shouldBe
            GarSmsRecipientLine.NotInContacts
        GarSmsRecipientLines.name(raw, ::format) shouldBe "(5550100)"
        GarSmsRecipientLines.name(mom, ::format) shouldBe "Mom"
        GarSmsRecipientLines.initial(raw, ::format) shouldBe GarSmsRecipientLine.NotInContacts
        GarSmsRecipientLines.initial(mom, ::format) shouldBe
            GarSmsRecipientLine.Contact(null, "(555-123-4567)")
    }

    "the line as text" {
        GarSmsRecipientLines.text(GarSmsRecipientLine.Contact("Mobile", null)) shouldBe
            GarText(R.string.gar_sms_type_from_contacts, listOf("Mobile"))
        GarSmsRecipientLines.text(GarSmsRecipientLine.Contact("Mobile", "555-123-4567")) shouldBe
            GarText(R.string.gar_sms_type_and_number, listOf("Mobile", "555-123-4567"))
        GarSmsRecipientLines.text(GarSmsRecipientLine.Contact(null, "555-123-4567")) shouldBe
            GarText(R.string.gar_sms_from_contacts_number, listOf("555-123-4567"))
        GarSmsRecipientLines.text(GarSmsRecipientLine.Contact(null, null)) shouldBe
            GarText(R.string.gar_sms_from_contacts)
        GarSmsRecipientLines.text(GarSmsRecipientLine.NotInContacts) shouldBe
            GarText(R.string.gar_sms_not_in_contacts)
    }
})
