package org.stypox.dicio.skills.places

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.settings.GarSettings

/**
 * One postal address of a contact, as `ContactsContract` keeps it: its [type]
 * ([StructuredPostal.TYPE_HOME], [StructuredPostal.TYPE_WORK], [StructuredPostal.TYPE_OTHER] or
 * [StructuredPostal.TYPE_CUSTOM] with a [label]), the [address] text and whether it is the
 * contact's [primary] one.
 */
data class PostalRow(val type: Int, val label: String?, val address: String, val primary: Boolean = false)

/**
 * GAR (gar:places). Which addresses of the "You" contact's card are home or work. Google
 * Assistant takes them from the user's own card too; GAR offers them, never uses them unasked.
 */
object CardAddresses {
    /**
     * The home or work addresses in [rows], the primary one first, each once (ignoring case and
     * spaces). Home and work rows count; an "Other" or custom one only when its label says home
     * or work and not both ("Home office" is neither).
     */
    fun pick(rows: List<PostalRow>, kind: SavedPlaceKind): List<String> = rows
        .filter { kindOf(it.type, it.label) == kind }
        .sortedByDescending { it.primary }
        .mapNotNull { oneLine(it.address) }
        .distinctBy { it.lowercase().replace(SPACES, " ") }

    /** The saved place a postal row of [type] and [label] is, or null. */
    fun kindOf(type: Int, label: String?): SavedPlaceKind? = when (type) {
        StructuredPostal.TYPE_HOME -> SavedPlaceKind.HOME
        StructuredPostal.TYPE_WORK -> SavedPlaceKind.WORK
        StructuredPostal.TYPE_OTHER, StructuredPostal.TYPE_CUSTOM -> labelKind(label)
        else -> null
    }

    /** "Home", "my house" -> home; "Work", "Office", "Job" -> work; anything else or both -> null. */
    fun labelKind(label: String?): SavedPlaceKind? {
        val words = label?.lowercase()?.split(NON_LETTERS)?.filter { it.isNotEmpty() } ?: return null
        val home = words.any { it in HOME_WORDS }
        val work = words.any { it in WORK_WORDS }
        return when {
            home && !work -> SavedPlaceKind.HOME
            work && !home -> SavedPlaceKind.WORK
            else -> null
        }
    }

    /** A card's address on one line ("12 Main St\nSpringfield" -> "12 Main St, Springfield"), or null. */
    fun oneLine(address: String?): String? = address?.lines()
        ?.map { it.trim().trimEnd(',').trim() }
        ?.filter { it.isNotEmpty() }
        ?.joinToString(", ")
        ?.takeIf { it.isNotEmpty() }

    private val SPACES = Regex("\\s+")
    private val NON_LETTERS = Regex("[^\\p{L}]+")
    private val HOME_WORDS = setOf("home", "house")
    private val WORK_WORDS = setOf("work", "office", "job", "business", "workplace")
}

/** The "You" contact's postal addresses, so the places skill and setting can be tested with fakes. */
interface CardPlaces {
    /** Whether GAR may read the contacts (`READ_CONTACTS`). */
    fun hasAccess(): Boolean

    /** The [kind] addresses on the "You" contact's card, as [CardAddresses.pick] chose them. */
    suspend fun addresses(kind: SavedPlaceKind): List<String>

    companion object {
        fun of(ctx: SkillContext): CardPlaces = AndroidCardPlaces(
            ctx.android,
            EntryPointAccessors.fromApplication(ctx.android.applicationContext, GarEntryPoint::class.java)
                .garSettings(),
        )
    }
}

/**
 * [CardPlaces] from the contacts provider: the contact "You" names (`own_contact_name`, every
 * contact so called, as [org.stypox.dicio.skills.sms.AndroidOwnContact.card] reads it), then the
 * profile (the Contacts app's own card) when that contact has none of the kind, in the order
 * [org.stypox.dicio.skills.sms.OwnContact.cards] takes them.
 */
class AndroidCardPlaces(
    private val context: Context,
    private val settings: DataStore<GarSettings>,
) : CardPlaces {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun hasAccess(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun addresses(kind: SavedPlaceKind): List<String> {
        if (!hasAccess()) {
            return listOf()
        }
        val name = settings.data.first().ownContactName
        return withContext(Dispatchers.IO) {
            val own = if (name.isEmpty()) listOf() else query(
                StructuredPostal.CONTENT_URI,
                "${StructuredPostal.DISPLAY_NAME} = ?",
                arrayOf(name),
            )
            CardAddresses.pick(own, kind).ifEmpty {
                CardAddresses.pick(
                    query(
                        Uri.withAppendedPath(
                            ContactsContract.Profile.CONTENT_URI,
                            ContactsContract.Contacts.Data.CONTENT_DIRECTORY,
                        ),
                        "${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(StructuredPostal.CONTENT_ITEM_TYPE),
                    ),
                    kind,
                )
            }
        }
    }

    private fun query(uri: Uri, selection: String, args: Array<String>): List<PostalRow> = try {
        resolver.query(uri, PROJECTION, selection, args, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) rowOf(cursor)?.let(::add) }
        } ?: listOf()
    } catch (e: RuntimeException) {
        // SecurityException without READ_CONTACTS (checked above, but it can be taken back)
        Log.w(TAG, "Can't read $uri", e)
        listOf()
    }

    private fun rowOf(cursor: Cursor): PostalRow? {
        val formatted = cursor.getString(1)
        // some apps leave the formatted address empty and fill in the parts
        val address = formatted?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6))
                .filter { it.isNotBlank() }.joinToString(", ")
        if (address.isBlank()) {
            return null
        }
        return PostalRow(
            type = if (cursor.isNull(0)) StructuredPostal.TYPE_OTHER else cursor.getInt(0),
            label = cursor.getString(2),
            address = address,
            primary = cursor.getInt(7) != 0,
        )
    }

    companion object {
        private const val TAG = "AndroidCardPlaces"
        private val PROJECTION = arrayOf(
            StructuredPostal.TYPE,
            StructuredPostal.FORMATTED_ADDRESS,
            StructuredPostal.LABEL,
            StructuredPostal.STREET,
            StructuredPostal.CITY,
            StructuredPostal.REGION,
            StructuredPostal.POSTCODE,
            StructuredPostal.IS_PRIMARY,
        )
    }
}

/** What "take me to work" goes to, see [SavedPlaceSource.decide]. */
sealed interface SavedPlaceSource {
    /** The place GAR saved. */
    data class Saved(val place: SavedPlace) : SavedPlaceSource

    /**
     * Nothing saved, but the "You" card has [addresses]: GAR offers the one, or asks which
     * (GAR: voice used to offer only the first).
     */
    data class Card(val addresses: List<String>) : SavedPlaceSource

    /** "What's your work address?" */
    data object Ask : SavedPlaceSource

    /** Nothing saved and the phone is locked: the card isn't read, no address is said. */
    data object Locked : SavedPlaceSource

    companion object {
        /**
         * The saved place if there is one; else, on an unlocked phone with contacts access, the
         * card's [kind] addresses ([cardAddresses] is only called then); else the question.
         */
        suspend fun decide(
            saved: SavedPlace?,
            locked: Boolean,
            contactsAccess: Boolean,
            cardAddresses: suspend () -> List<String>,
        ): SavedPlaceSource = when {
            saved != null -> Saved(saved)
            locked -> Locked
            !contactsAccess -> Ask
            else -> cardAddresses().takeIf { it.isNotEmpty() }?.let { Card(it) } ?: Ask
        }
    }
}
