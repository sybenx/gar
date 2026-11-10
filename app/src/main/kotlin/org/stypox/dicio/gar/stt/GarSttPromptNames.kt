package org.stypox.dicio.gar.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSttContactNames
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The names Whisper's prompt gets ([cc.aaro.gar.stt.NamePrompt]), most important first, so that
 * "send a text to aaron" isn't heard as "erin" (whisper-design.md sections 14 and 16b). The
 * user's own name is always first ([ContactPromptNames.owner]).
 *
 * Only with `READ_CONTACTS` and GAR's "Help recognise contact names" setting on; the names never
 * leave the phone. Built on the first capture ([refreshIfStale]), then again when the contacts
 * change (debounced), the setting or "You" changes, a permission was
 * granted or taken, or the names are older than [STALE_MILLIS] (the call log's recent callers).
 * The list stays the same object while nothing changed, so the engine tokenizes it once.
 */
@Singleton
class GarSttPromptNames @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: DataStore<GarSettings>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val observing = AtomicBoolean(false)

    private val _names = MutableStateFlow<List<String>>(emptyList())

    /** The names for the prompt; empty while off or without `READ_CONTACTS`. */
    val names: StateFlow<List<String>> = _names

    private val _state = MutableStateFlow<PromptNamesState?>(null)

    /**
     * What the last build found, null before the first: whether it read the contacts, and the
     * owner's name and its source. For the "What's your first name?" card and the bench.
     */
    val state: StateFlow<PromptNamesState?> = _state

    /** Guards the fields below. */
    private val lock = Any()
    private var rebuildJob: Job? = null
    private var builtAtMillis = 0L
    private var builtWith: Permissions? = null

    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) = rebuild(DEBOUNCE_MILLIS)
    }

    /**
     * Rebuilds the names in the background if they are old or the permissions changed; the first
     * call starts following the settings. Called when a capture starts, any thread.
     */
    fun refreshIfStale() {
        if (started.compareAndSet(false, true)) {
            scope.launch {
                // the first value is built by rebuild(0) below
                settings.data
                    .map { Triple(contactNamesOn(it.sttContactNames), it.ownContactName, it.sttOwnName) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { rebuild(0) }
            }
            rebuild(0)
            return
        }
        val stale = synchronized(lock) {
            builtWith != permissions() ||
                System.currentTimeMillis() - builtAtMillis > STALE_MILLIS
        }
        if (stale) {
            rebuild(0)
        }
    }

    /**
     * Rebuilds after [delayMillis], replacing a rebuild that hasn't finished: its queries can't
     * be stopped, but it publishes nothing, and this one waits for it.
     */
    private fun rebuild(delayMillis: Long) {
        synchronized(lock) {
            val previous = rebuildJob
            previous?.cancel()
            rebuildJob = scope.launch {
                previous?.join()
                delay(delayMillis)
                try {
                    build()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Could not read the contact names", e)
                }
            }
        }
    }

    private suspend fun build() {
        val permissions = permissions()
        val currentSettings = settings.data.first()
        val read = contactNamesOn(currentSettings.sttContactNames) && permissions.contacts
        val owner: List<Pair<OwnerSource, String>>
        val names = if (read) {
            observeContacts()
            owner = listOf(
                OwnerSource.SETTING to currentSettings.ownContactName,
                OwnerSource.ASKED to currentSettings.sttOwnName,
            ) + profileNames().map { OwnerSource.PROFILE to it }
            ContactPromptNames.names(
                ContactPromptNames.Sources(
                    owner = owner.map { it.second },
                    starred = starredNames(),
                    recent = if (permissions.callLog) recentCallerNames() else emptyList(),
                )
            )
        } else {
            owner = emptyList()
            emptyList()
        }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            builtAtMillis = System.currentTimeMillis()
            builtWith = permissions
        }
        // an equal list keeps the old one (StateFlow compares with equals)
        _names.value = names
        _state.value = PromptNamesState(read, ContactPromptNames.owner(owner))
    }

    private fun observeContacts() {
        if (observing.compareAndSet(false, true)) {
            try {
                context.contentResolver.registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, observer,
                )
            } catch (e: SecurityException) {
                observing.set(false)
                Log.w(TAG, "Can't follow contact changes", e)
            }
        }
    }

    private fun permissions() = Permissions(
        contacts = granted(Manifest.permission.READ_CONTACTS),
        callLog = granted(Manifest.permission.READ_CALL_LOG),
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The Contacts app's own card (the phone's profile). */
    private fun profileNames(): List<String> =
        query(ContactsContract.Profile.CONTENT_URI, ContactsContract.Contacts.DISPLAY_NAME)

    private fun starredNames(): List<String> = query(
        ContactsContract.Contacts.CONTENT_URI,
        ContactsContract.Contacts.DISPLAY_NAME,
        selection = "${ContactsContract.Contacts.STARRED} = 1",
        sortOrder = ContactsContract.Contacts.SORT_KEY_PRIMARY,
    )

    /** The names the phone app saved with the latest calls, newest first. */
    private fun recentCallerNames(): List<String> = query(
        CallLog.Calls.CONTENT_URI,
        CallLog.Calls.CACHED_NAME,
        sortOrder = CallLog.Calls.DEFAULT_SORT_ORDER,
        maxRows = RECENT_CALLS_READ,
    ).distinct().take(ContactPromptNames.MAX_RECENT)

    /** The non-blank strings of [column], at most [maxRows] rows read. */
    private fun query(
        uri: Uri,
        column: String,
        selection: String? = null,
        sortOrder: String? = null,
        maxRows: Int = Int.MAX_VALUE,
    ): List<String> = try {
        context.contentResolver.query(uri, arrayOf(column), selection, null, sortOrder)
            ?.use { cursor ->
                buildList<String> {
                    var rows = 0
                    while (rows < maxRows && cursor.moveToNext()) {
                        rows++
                        cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            } ?: listOf()
    } catch (e: RuntimeException) {
        // a permission taken away meanwhile
        Log.w(TAG, "Can't read $uri", e)
        listOf()
    }

    private data class Permissions(val contacts: Boolean, val callLog: Boolean)

    companion object {
        private const val TAG = "GarStt"

        /**
         * Whether "Help recognise contact names" is on for users who never touched it: on since
         * the Mac eval of 2026-11-02 (gar/stt/eval). On base.en, with a phone book as the device
         * reads it, WER 12.8 to 11.2 and names said right 21 to 27 of 43; the prompt breaks even
         * once the name said is in it for a third of named commands.
         */
        const val ON_BY_DEFAULT = true

        /** Contacts sync in bursts: one rebuild after they settle. */
        const val DEBOUNCE_MILLIS = 5_000L

        /** Recent callers change with every call; rebuilt at a capture after this long. */
        const val STALE_MILLIS = 10 * 60_000L

        /** Call log rows read for [ContactPromptNames.MAX_RECENT] names. */
        private const val RECENT_CALLS_READ = 100

        fun contactNamesOn(setting: GarSttContactNames): Boolean = when (setting) {
            GarSttContactNames.GAR_STT_CONTACT_NAMES_ON -> true
            GarSttContactNames.GAR_STT_CONTACT_NAMES_OFF -> false
            GarSttContactNames.GAR_STT_CONTACT_NAMES_DEFAULT,
            GarSttContactNames.UNRECOGNIZED -> ON_BY_DEFAULT
        }
    }
}

/**
 * Where the user's own name, first in the prompt, came from (in this order of preference, the
 * order the "You" setting shows them in, [org.stypox.dicio.gar.settings.GarYou.who]).
 */
enum class OwnerSource(val label: String) {
    /** GAR's own contact (`own_contact_name`), the one "text myself" uses. */
    SETTING("setting"),

    /**
     * A name typed in "You" or when GAR asked (`stt_own_name`); picking a contact sets it to
     * the contact's first name too. Before the profile, since the user chose it.
     */
    ASKED("asked"),

    /** The Contacts app's own card (`ContactsContract.Profile`). */
    PROFILE("profile"),
}

/** The user's own name as it goes into the prompt (a first name), and where it came from. */
data class PromptOwner(val name: String, val source: OwnerSource)

/**
 * What a build of the prompt's names found: [read] is whether the contacts were read (the
 * setting on and `READ_CONTACTS` granted), [owner] the user's own name, null when no source gave
 * one (always null when not [read]).
 */
data class PromptNamesState(val read: Boolean, val owner: PromptOwner?)

/**
 * Which names go into Whisper's prompt and in what order, from the contacts' display names; the
 * engine keeps as many as fit in its token budget ([cc.aaro.gar.stt.NamePrompt.fit], 16 tokens).
 *
 * First names come first, since that is how people are named in a command ("text aaron"): the
 * owner's, always first, the starred contacts', then the recent callers'. Then the short full
 * names of the same people ("call sarah lee") while room remains. No other contacts: they would
 * be whoever sorts first, names the user rarely says, and on the phone a 40-token prompt of such
 * names pulled a clear "Call Aaron." to "Call Youran" when the owner wasn't in it.
 */
object ContactPromptNames {
    /** Names handed to the engine at most; far more than fit in the prompt. */
    const val MAX_CANDIDATES = 60

    /** Recent callers taken from the call log. */
    const val MAX_RECENT = 10

    /** A full name with more words or characters is left out (only its first name goes in). */
    const val MAX_FULL_NAME_WORDS = 3
    const val MAX_FULL_NAME_CHARS = 24

    /**
     * The contacts, each list most important first, as display names: [owner] is the user's own
     * names in [OwnerSource] order (GAR's own contact, the typed name, the profile).
     */
    data class Sources(
        val owner: List<String>,
        val starred: List<String>,
        val recent: List<String>,
    )

    /** The prompt's candidate names, most important first, without repeats (ignoring case). */
    fun names(sources: Sources): List<String> {
        val important = sources.owner.mapNotNull(::cleanOwn) +
            (sources.starred + sources.recent).mapNotNull(::clean)
        val candidates = important.map(::firstName) + important.filter(::isShortFullName)
        return candidates.distinctBy { it.lowercase() }.take(MAX_CANDIDATES)
    }

    /**
     * The owner's name in the prompt: the first name of the first of [sources] (in
     * [OwnerSource] order) that has one after [clean] and isn't a placeholder ("Me", "My
     * number"), or null when none does, so that GAR asks for the name.
     */
    fun owner(sources: List<Pair<OwnerSource, String>>): PromptOwner? =
        sources.firstNotNullOfOrNull { (source, name) ->
            ownerFirstName(name)?.let { PromptOwner(it, source) }
        }

    /**
     * The first name of the owner's [displayName] after [clean], null for a placeholder ("Me",
     * "My number") or a name without letters: what "You" keeps for the hint when the user picks
     * their contact ([org.stypox.dicio.gar.settings.GarYou]).
     */
    fun ownerFirstName(displayName: String): String? = cleanOwn(displayName)?.let(::firstName)

    /**
     * [displayName] as words Whisper could write: no parts in brackets ("Mom (work)"), emoji,
     * digits or other symbols; letters, apostrophes and hyphens inside words stay. Null when
     * nothing with two letters is left (a number saved as the name).
     */
    fun clean(displayName: String): String? {
        val withoutBrackets = displayName.replace(BRACKETS, " ")
        val kept = buildString {
            for (c in withoutBrackets) {
                append(if (c.isLetter() || c == '\'' || c == '-') c else ' ')
            }
        }
        val words = kept.split(' ')
            .map { it.trim('\'', '-') }
            .filter { it.isNotEmpty() }
        val name = words.joinToString(" ")
        return name.takeIf { it.count { c -> c.isLetter() } >= 2 }
    }

    /**
     * Whether [promptNames] (the names that went into a prompt) has the owner's first name
     * [owner], ignoring case.
     */
    fun hasOwner(promptNames: List<String>, owner: String): Boolean =
        promptNames.any { it.equals(owner, ignoreCase = true) }

    /** [clean], and null for a placeholder the user saved their own number as ("Me"). */
    private fun cleanOwn(displayName: String): String? =
        clean(displayName)?.takeIf { it.lowercase() !in OWN_PLACEHOLDERS }

    private fun firstName(name: String): String = name.substringBefore(' ')

    private fun isShortFullName(name: String): Boolean =
        name.contains(' ') &&
            name.split(' ').size <= MAX_FULL_NAME_WORDS &&
            name.length <= MAX_FULL_NAME_CHARS

    /** Names people give their own contact that aren't their name, lowercase after [clean]. */
    private val OWN_PLACEHOLDERS = setOf(
        "me", "myself", "mine", "my number", "my phone", "my cell", "my mobile", "home", "owner",
    )

    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^]]*]")
}
