package org.stypox.dicio.skills.sms

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import androidx.datastore.core.DataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarYou
import org.stypox.dicio.gar.settings.YouChoice
import org.stypox.dicio.gar.settings.YouContact
import org.stypox.dicio.skills.telephone.Contact

/** The phone owner's own card: a name, and its numbers and email addresses as stored. */
data class OwnCard(val name: String, val numbers: List<String>, val emails: List<String>)

/**
 * Who "myself" is in "text myself", "call me" and "email myself", as Google Assistant does it:
 * the contact the user said is them, which GAR asks once ([OwnContactAskOutput]) and keeps in its
 * settings, or the phone's profile (the Contacts app's own card).
 */
interface OwnContact {
    /** The owner's cards: the contact the user said is them, then the profile, if any. */
    suspend fun cards(): List<OwnCard>

    /** The names of the phone's contacts, to find the one the user says is them. */
    fun contactNames(): List<String>

    /** The card of the contact called [name], or null if it has no number and no address. */
    fun card(name: String): OwnCard?

    /** Remembers that the contact [name] is the owner, until changed in GAR's "You" setting. */
    suspend fun remember(name: String)

    companion object {
        fun of(ctx: SkillContext): OwnContact = AndroidOwnContact(
            ctx.android,
            EntryPointAccessors.fromApplication(ctx.android.applicationContext, GarEntryPoint::class.java)
                .garSettings(),
        )
    }
}

/**
 * The owner's cards from the contacts provider (`READ_CONTACTS`, which also covers the profile
 * since Android 6: `READ_PROFILE` is no longer enforced and not even in the SDK anymore) and GAR's
 * settings ([GarSettings.getOwnContactName]).
 */
class AndroidOwnContact(
    resolver: () -> ContentResolver,
    private val settings: DataStore<GarSettings>,
) : OwnContact {
    constructor(context: Context, settings: DataStore<GarSettings>) :
        this({ context.contentResolver }, settings)

    // only read when the contacts are, so that tests can run remember without a Context
    private val contentResolver: ContentResolver by lazy(resolver)

    override suspend fun cards(): List<OwnCard> {
        val remembered = settings.data.first().ownContactName
        return listOfNotNull(remembered.takeIf { it.isNotEmpty() }?.let(::card), profile())
    }

    override fun contactNames(): List<String> =
        query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME)) {
            it.getString(0)
        }.filterNotNull().filter { it.isNotBlank() }.distinct()

    /** Sets "You" ([GarYou.set]), so the name hint gets the contact's first name too. */
    override suspend fun remember(name: String) {
        GarYou.set(settings, YouChoice.Contact(name))
    }

    /** The profile's name (the Contacts app's own card), null without one. */
    fun profileName(): String? = query(
        ContactsContract.Profile.CONTENT_URI,
        arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
    ) { it.getString(0) }.firstOrNull { !it.isNullOrBlank() }

    /**
     * Every contact for the "You" setting to pick from, by name (as [card] takes them), with the
     * first number and first address of the first contact so called, sorted ignoring case.
     */
    fun youContacts(): List<YouContact> {
        // Phone.NUMBER and Email.ADDRESS are DATA1; no sort order, so the first is card()'s first
        fun firsts(uri: Uri, displayName: String, data: String): Map<String, String> {
            val firsts = mutableMapOf<String, String>()
            query(uri, arrayOf(displayName, data), "$data IS NOT NULL") {
                Pair(it.getString(0), it.getString(1)?.trim())
            }.forEach { (name, value) ->
                if (name != null && !value.isNullOrEmpty()) {
                    firsts.putIfAbsent(name, value)
                }
            }
            return firsts
        }
        val numbers = firsts(Phone.CONTENT_URI, Phone.DISPLAY_NAME, Phone.NUMBER)
        val emails = firsts(Email.CONTENT_URI, Email.DISPLAY_NAME, Email.ADDRESS)
        return contactNames()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { YouContact(it, numbers[it], emails[it]) }
    }

    /** The profile's name, and the numbers and addresses of its data rows. */
    fun profile(): OwnCard? {
        val name = profileName()
        // Phone.NUMBER and Email.ADDRESS are both DATA1
        val rows = query(
            Uri.withAppendedPath(
                ContactsContract.Profile.CONTENT_URI,
                ContactsContract.Contacts.Data.CONTENT_DIRECTORY,
            ),
            arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
        ) { Pair(it.getString(0), it.getString(1)?.trim()) }
        fun values(mimeType: String) = rows.filter { it.first == mimeType }
            .mapNotNull { it.second?.takeIf(String::isNotEmpty) }
        val numbers = values(Phone.CONTENT_ITEM_TYPE)
        val emails = values(Email.CONTENT_ITEM_TYPE)
        if (numbers.isEmpty() && emails.isEmpty()) {
            return null
        }
        // without a name, a text reads the number back
        return OwnCard(name ?: numbers.firstOrNull() ?: emails[0], numbers, emails)
    }

    /**
     * Every contact called [name] as one card: when two contacts share the owner's name, their
     * numbers are offered to choose from, never the first one taken silently.
     */
    override fun card(name: String): OwnCard? {
        val ids = query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME} = ?",
            arrayOf(name),
        ) { it.getString(0) }.filterNotNull()
        val card = OwnCard(
            name,
            ids.flatMap { Contact(name, 0, it).getNumbers(contentResolver) },
            ids.flatMap(::emails).distinctBy { it.lowercase() },
        )
        return card.takeIf { it.numbers.isNotEmpty() || it.emails.isNotEmpty() }
    }

    private fun emails(id: String): List<String> =
        query(Email.CONTENT_URI, arrayOf(Email.ADDRESS), "${Email.CONTACT_ID} = ?", arrayOf(id)) {
            it.getString(0)?.trim()
        }.filterNotNull().filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    private fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        row: (Cursor) -> T,
    ): List<T> = try {
        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        } ?: listOf()
    } catch (e: RuntimeException) {
        // SecurityException without READ_CONTACTS (Dicio asks for it before the skills run)
        Log.w(TAG, "Can't read $uri", e)
        listOf()
    }

    companion object {
        private const val TAG = "AndroidOwnContact"
    }
}
