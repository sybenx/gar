package org.stypox.dicio.skills.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import org.stypox.dicio.gar.call.normalizeAnswer
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** What happened to a call, for the calls GAR reads: voicemails and blocked calls are left out. */
enum class CallKind {
    /** Answered here or on another device. */
    INCOMING,
    MISSED,
    /** Declined by the user. */
    DECLINED,
    OUTGOING;

    val cameIn: Boolean get() = this != OUTGOING
}

/**
 * One row of the phone's call log.
 *
 * @param number the number as the call log has it, empty when the caller hid it
 * @param name the contact's name the phone app saved with the call, null for unknown numbers
 * @param formattedNumber the number as the phone app shows it, if it saved one
 * @param hidden the caller hid the number (private, unknown or payphone): it can't be called back
 * @param isNew a missed call the user hasn't looked at yet in the phone app
 */
data class CallEntry(
    val number: String,
    val name: String?,
    val kind: CallKind,
    val time: Instant,
    val formattedNumber: String? = null,
    val hidden: Boolean = false,
    val isNew: Boolean = false,
)

/** The phone's call log, newest call first. */
interface CallHistory {
    /** Whether GAR may read the call log (`READ_CALL_LOG`). */
    val allowed: Boolean

    /** The latest calls, newest first, at most [RECENT_CALLS]; empty when GAR can't read them. */
    fun recent(): List<CallEntry>

    companion object {
        const val RECENT_CALLS = 50
    }
}

/** What GAR looks for in the call log (English, like every GAR answer). */
object CallHistoryQueries {
    /** Missed calls this long ago still count as missed when the phone app showed them already. */
    val MISSED_WINDOW: Duration = Duration.ofHours(24)

    fun lastIncoming(calls: List<CallEntry>): CallEntry? = calls.firstOrNull { it.kind.cameIn }

    fun lastOutgoing(calls: List<CallEntry>): CallEntry? =
        calls.firstOrNull { it.kind == CallKind.OUTGOING }

    fun last(calls: List<CallEntry>): CallEntry? = calls.firstOrNull()

    /**
     * The calls the user missed: the ones the phone app still marks as new, and every missed call
     * of the last [MISSED_WINDOW], newest first.
     */
    fun missed(calls: List<CallEntry>, now: Instant): List<CallEntry> = calls.filter {
        it.kind == CallKind.MISSED && (it.isNew || !it.time.isBefore(now.minus(MISSED_WINDOW)))
    }

    /**
     * The newest call from (or, with [cameIn] false, to) the contact the user named: every word
     * said is a word of the name the phone app saved ("mom" is "Mom", "john" is "John Smith").
     * A possessive "s" that lost its apostrophe ("moms call") is tried without the "s" too.
     */
    fun named(calls: List<CallEntry>, said: String, cameIn: Boolean): CallEntry? {
        val words = nameWords(said)
        if (words.isEmpty()) {
            return null
        }
        val candidates = calls.filter { it.kind.cameIn == cameIn && !it.hidden && it.name != null }
        candidates.firstOrNull { nameWords(it.name!!).containsAll(words) }?.let { return it }
        val last = words.last()
        if (last.length > 2 && last.endsWith("s")) {
            val withoutS = words.dropLast(1) + last.dropLast(1)
            return candidates.firstOrNull { nameWords(it.name!!).containsAll(withoutS) }
        }
        return null
    }

    /** Lowercase words without accents or punctuation: "Zoë-Anne" is "zoe", "anne". */
    fun nameWords(name: String): List<String> =
        normalizeAnswer(
            Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        ).replace("'", "").split(" ").filter { it.isNotEmpty() }

    /** The digits of [number], or null when it has fewer than four. */
    fun lastDigits(number: String, count: Int = 4): String? =
        number.filter { it in '0'..'9' }.takeIf { it.length >= count }?.takeLast(count)
}

/** The call log of the phone, read through `CallLog.Calls` (`READ_CALL_LOG`). */
class AndroidCallHistory(private val context: Context) : CallHistory {
    override val allowed: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    override fun recent(): List<CallEntry> {
        if (!allowed) {
            return listOf()
        }
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, CallHistory.RECENT_CALLS.toString())
            .build()
        val entries = mutableListOf<CallEntry>()
        try {
            context.contentResolver.query(
                uri,
                PROJECTION,
                "${CallLog.Calls.TYPE} IN (${TYPES.keys.joinToString(",")})",
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val name = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val formatted = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_FORMATTED_NUMBER)
                val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val date = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val presentation = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER_PRESENTATION)
                val new = cursor.getColumnIndexOrThrow(CallLog.Calls.NEW)
                while (cursor.moveToNext() && entries.size < CallHistory.RECENT_CALLS) {
                    val kind = TYPES[cursor.getInt(type)] ?: continue
                    val raw = cursor.getString(number).orEmpty()
                    val hidden = cursor.getInt(presentation) != CallLog.Calls.PRESENTATION_ALLOWED ||
                        raw.isBlank()
                    entries.add(
                        CallEntry(
                            number = if (hidden) "" else raw,
                            name = cursor.getString(name)?.takeIf { it.isNotBlank() },
                            kind = kind,
                            time = Instant.ofEpochMilli(cursor.getLong(date)),
                            formattedNumber = if (hidden) null else
                                cursor.getString(formatted)?.takeIf { it.isNotBlank() }
                                    ?: format(raw),
                            hidden = hidden,
                            isNew = cursor.getInt(new) != 0,
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't read the call log", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unexpected call log columns", e)
        }
        return entries
    }

    private fun format(number: String): String? = try {
        PhoneNumberUtils.formatNumber(number, Locale.getDefault().country)
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "AndroidCallHistory"

        private val PROJECTION = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.CACHED_FORMATTED_NUMBER,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.NEW,
        )

        /** The call log's types GAR reads; voicemails and blocked calls aren't calls to the user. */
        private val TYPES = mapOf(
            CallLog.Calls.INCOMING_TYPE to CallKind.INCOMING,
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE to CallKind.INCOMING,
            CallLog.Calls.MISSED_TYPE to CallKind.MISSED,
            CallLog.Calls.REJECTED_TYPE to CallKind.DECLINED,
            CallLog.Calls.OUTGOING_TYPE to CallKind.OUTGOING,
        )
    }
}
