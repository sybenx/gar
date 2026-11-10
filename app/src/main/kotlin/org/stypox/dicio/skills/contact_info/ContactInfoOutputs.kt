package org.stypox.dicio.skills.contact_info

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** How GAR says contacts' details. English, like the sentences. */
object ContactSay {
    /**
     * A phone number for TTS: digit by digit in the groups it is written in ("(801) 555-1234"
     * -> "8 0 1, 5 5 5, 1 2 3 4"), so that it isn't read as one big number.
     */
    fun spokenNumber(ctx: SkillContext, number: String): String {
        val groups = number.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        val spoken = groups.joinToString(", ") { it.toList().joinToString(" ") }
        return if (number.trimStart().startsWith("+")) ctx.getString(R.string.gar_contacts_plus, spoken) else spoken
    }

    /** "May 3" for a stored "1990-05-03" or "--05-03"; the value as stored if neither. */
    fun birthday(ctx: SkillContext, stored: String): String {
        val format = DateTimeFormatter.ofPattern("MMMM d", ctx.locale)
        return try {
            if (stored.startsWith("--")) {
                MonthDay.parse(stored).atYear(2000).format(format)
            } else {
                LocalDate.parse(stored.take(10)).format(format)
            }
        } catch (e: DateTimeParseException) {
            stored
        }
    }

    /** A postal address on one line. */
    fun address(stored: String): String = stored.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    fun spoken(ctx: SkillContext, field: ContactField, value: String): String = when (field) {
        ContactField.NUMBER -> spokenNumber(ctx, value)
        ContactField.BIRTHDAY -> birthday(ctx, value)
        ContactField.ADDRESS -> address(value)
        ContactField.EMAIL -> value
    }

    fun shown(ctx: SkillContext, field: ContactField, value: String): String = when (field) {
        ContactField.BIRTHDAY -> birthday(ctx, value)
        ContactField.ADDRESS -> address(value)
        else -> value
    }

    /** "A, B or C". */
    fun joinedOr(ctx: SkillContext, items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> ctx.getString(R.string.gar_agenda_or, items.dropLast(1).joinToString(", "), items.last())
    }

    /** "numbers", "email addresses". */
    fun fieldsName(ctx: SkillContext, field: ContactField): String = ctx.getString(
        when (field) {
            ContactField.NUMBER -> R.string.gar_contacts_fields_number
            ContactField.EMAIL -> R.string.gar_contacts_fields_email
            ContactField.ADDRESS -> R.string.gar_contacts_fields_address
            ContactField.BIRTHDAY -> R.string.gar_contacts_fields_birthday
        }
    )

    fun fieldName(ctx: SkillContext, field: ContactField): String = ctx.getString(
        when (field) {
            ContactField.NUMBER -> R.string.gar_contacts_field_number
            ContactField.EMAIL -> R.string.gar_contacts_field_email
            ContactField.ADDRESS -> R.string.gar_contacts_field_address
            ContactField.BIRTHDAY -> R.string.gar_contacts_field_birthday
        }
    )
}

sealed interface ContactInfoOutput : SkillOutput {

    /**
     * What [name] has of [field] ([own] for the owner: "your number"): the first two spoken with
     * their labels, all of them on the card.
     */
    class Found(
        val name: String,
        val field: ContactField,
        val values: List<ContactValue>,
        val own: Boolean,
    ) : ContactInfoOutput {
        override fun getSpeechOutput(ctx: SkillContext): String {
            val fieldName = ContactSay.fieldName(ctx, field)
            val whose = if (own) {
                ctx.getString(R.string.gar_contacts_your, fieldName)
            } else {
                ctx.getString(R.string.gar_contacts_whose, name, fieldName)
            }
            return when (values.size) {
                0 -> if (own) {
                    ctx.getString(R.string.gar_contacts_own_none, fieldName)
                } else {
                    ctx.getString(R.string.gar_contacts_none, name, fieldName)
                }
                1 -> ctx.getString(R.string.gar_contacts_is, whose, ContactSay.spoken(ctx, field, values[0].value))
                else -> {
                    val spoken = values.take(SPOKEN).joinToString(". ") { value ->
                        val said = ContactSay.spoken(ctx, field, value.value)
                        if (value.label == null) said else "${value.label}: $said"
                    }
                    val fields = ContactSay.fieldsName(ctx, field)
                    if (own) {
                        ctx.getString(R.string.gar_contacts_you_have, values.size, fields, spoken)
                    } else {
                        ctx.getString(R.string.gar_contacts_several, name, values.size, fields, spoken)
                    }
                }
            }
        }

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = name)
                if (values.isEmpty()) {
                    Body(text = getSpeechOutput(ctx))
                }
                for (value in values) {
                    val shown = ContactSay.shown(ctx, field, value.value)
                    Body(text = if (value.label == null) shown else "${value.label}: $shown")
                }
            }
        }

        private companion object {
            const val SPOKEN = 2
        }
    }

    class NotFound(val who: String) : ContactInfoOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_contacts_not_found, who)
    }

    data object OwnUnknown : ContactInfoOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_contacts_own_unknown)
    }

    /** The contacts app's editor opened with [contact] filled in. */
    class EditorOpened(val contact: NewContact) : ContactInfoOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = when {
            contact.name.isNotEmpty() && contact.number.isNotEmpty() -> ctx.getString(
                R.string.gar_contacts_new_name_number, contact.name, ContactSay.spokenNumber(ctx, contact.number),
            )
            contact.name.isNotEmpty() -> ctx.getString(R.string.gar_contacts_new_name, contact.name)
            contact.number.isNotEmpty() ->
                ctx.getString(R.string.gar_contacts_new_number, ContactSay.spokenNumber(ctx, contact.number))
            else -> ctx.getString(R.string.gar_contacts_new)
        }
    }

    data object NoContactsApp : ContactInfoOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_contacts_no_app)
    }

    data object NoRecentCall : ContactInfoOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_contacts_no_recent_call)
    }

    /** "Save this number": the last call's number is already [name]'s. */
    class AlreadySaved(val name: String) : ContactInfoOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_contacts_already_saved, name)
    }

    data object Failed : ContactInfoOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(R.string.gar_contacts_failed)
    }
}
