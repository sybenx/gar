package org.stypox.dicio.skills.calls

import android.app.KeyguardManager
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.spokenPhoneNumber
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences.Telephone
import org.stypox.dicio.skills.telephone.ConfirmCallOutput
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.telephone.TelephoneSkill
import org.stypox.dicio.util.PERMISSION_CALL_PHONE
import org.stypox.dicio.util.PERMISSION_READ_CONTACTS

/** Which call of the call log the user wants to call ("call back", "redial", "call mom back"). */
sealed interface CallTarget {
    /** The newest call that came in: "call back", "call the last caller". */
    data object LastIncoming : CallTarget

    /** The newest call the user made: "redial", "call the last number I dialed". */
    data object LastOutgoing : CallTarget

    /** The newest call either way: "call the last number". */
    data object Last : CallTarget

    /**
     * The newest call from [name] ("call mom back", [cameIn]) or to [name] ("call mom again"),
     * else [name] as the telephone skill finds it in the contacts.
     */
    data class Named(val name: String, val cameIn: Boolean) : CallTarget
}

/**
 * "Call back", "redial" and "call mom back" (gar/PARITY.md, batch 4): the number comes from the
 * call log, then the telephone skill's confirmation asks before calling. Used by the call log
 * skill's sentences and by the telephone skill for what follows its verb, so the model's
 * `call_contact(who: "back")` and Dicio's "call the last number" work too, with no new tool.
 * English, like GAR's other answers.
 */
object CallBack {
    /**
     * The call that [who] (what follows "call", "dial" or "ring") points at, or null when it
     * names a contact or number the usual way ("mom", "five five five one two one two").
     */
    fun parse(who: String): CallTarget? {
        var words = normalizeAnswer(who).replace("'", "").split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            return null
        }
        if (words.first() == "redial" || words.take(2) == listOf("re", "dial")) {
            val rest = words.drop(if (words.first() == "redial") 1 else 2).joinToString(" ")
            return when {
                rest.isEmpty() || rest in PRONOUNS || rest in NUMBER_PRONOUNS ||
                    LAST_REFS.matches(rest) || OUTGOING_REFS.matches(rest) -> CallTarget.LastOutgoing
                INCOMING_REFS.matches(rest) -> CallTarget.LastIncoming
                isName(rest) || spokenPhoneNumber(rest) != null ->
                    CallTarget.Named(rest, cameIn = false)
                else -> null
            }
        }

        var back = false
        var again = false
        // "back mom", "mom back", "that number again", "them back again"
        while (words.isNotEmpty()) {
            if (words.first() == "back") {
                back = true
                words = words.drop(1)
            } else if (words.last() == "back") {
                back = true
                words = words.dropLast(1)
            } else if (words.last() == "again") {
                again = true
                words = words.dropLast(1)
            } else {
                break
            }
        }
        val rest = words.joinToString(" ")
        return when {
            rest.isEmpty() || rest in PRONOUNS -> when {
                back -> CallTarget.LastIncoming
                again -> CallTarget.LastOutgoing
                else -> null
            }
            INCOMING_REFS.matches(rest) -> CallTarget.LastIncoming
            OUTGOING_REFS.matches(rest) -> CallTarget.LastOutgoing
            LAST_REFS.matches(rest) || rest in NUMBER_PRONOUNS -> when {
                back -> CallTarget.LastIncoming
                again -> CallTarget.LastOutgoing
                else -> CallTarget.Last
            }
            (back || again) && (isName(rest) || spokenPhoneNumber(rest) != null) ->
                CallTarget.Named(rest, cameIn = back)
            else -> null
        }
    }

    /**
     * What the telephone skill answers when [who] points at the call log ([parse]), or null to
     * look [who] up in the contacts as it always did. A GAR line in `TelephoneSkill`; English
     * only, like the model's calls.
     */
    suspend fun telephoneOutput(
        ctx: SkillContext,
        telephone: TelephoneSkill,
        who: String,
    ): SkillOutput? {
        if (ctx.sentencesLanguage != ENGLISH) {
            return null
        }
        val target = parse(who) ?: return null
        return output(ctx, target, telephone, AndroidCallHistory(ctx.android), isLocked(ctx))
    }

    /**
     * The telephone skill's confirmation for the number of [target], or why there is none. The
     * call log stays closed on a locked phone ("Unlock your phone first.") and without its
     * permission (Dicio's card that asks for it); a [CallTarget.Named] then goes to the contacts
     * as the telephone skill would, and so does one the call log doesn't have. Calling needs the
     * telephone skill's permissions, which the call log skill doesn't ask for upfront: without
     * them, the telephone skill's card asks for them.
     *
     * @param contacts the telephone skill's own answer for a name
     * @param granted whether a permission is granted; the telephone skill's path always has its
     * own
     */
    suspend fun output(
        ctx: SkillContext,
        target: CallTarget,
        telephone: TelephoneSkill,
        history: CallHistory,
        locked: Boolean,
        contacts: suspend (String) -> SkillOutput =
            { name -> telephone.generateOutput(ctx, Telephone.Dial(name)) },
        granted: (Permission) -> Boolean = { true },
    ): SkillOutput {
        if (!granted(PERMISSION_CALL_PHONE)) {
            return MissingPermissionsSkillOutput(TelephoneInfo)
        }
        // the contacts' lookup reads them without asking
        val contactsOrAsk: suspend (String) -> SkillOutput = { name ->
            if (granted(PERMISSION_READ_CONTACTS)) {
                contacts(name)
            } else {
                MissingPermissionsSkillOutput(TelephoneInfo)
            }
        }
        if (target is CallTarget.Named) {
            spokenPhoneNumber(target.name)?.let {
                return ConfirmCallOutput(it, it, telephone.yesNoData)
            }
            if (locked || !history.allowed) {
                return contactsOrAsk(target.name)
            }
        } else if (locked) {
            return GarLockedOutput()
        } else if (!history.allowed) {
            return MissingPermissionsSkillOutput(CallLogInfo)
        }

        val calls = history.recent()
        val entry = when (target) {
            CallTarget.LastIncoming -> CallHistoryQueries.lastIncoming(calls)
            CallTarget.LastOutgoing -> CallHistoryQueries.lastOutgoing(calls)
            CallTarget.Last -> CallHistoryQueries.last(calls)
            is CallTarget.Named -> CallHistoryQueries.named(calls, target.name, target.cameIn)
                ?: return contactsOrAsk(target.name)
        } ?: return CallLogOutput.NothingToCall(target)

        return if (entry.hidden) {
            CallLogOutput.HiddenNumber
        } else {
            ConfirmCallOutput(
                entry.name ?: CallerText.confirmName(ctx, entry),
                entry.number,
                telephone.yesNoData,
            )
        }
    }

    fun isLocked(ctx: SkillContext): Boolean =
        ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    /** A name the user may have said: not a time ("back in five minutes", "back later"). */
    fun isName(words: String): Boolean {
        val list = words.split(" ")
        return list.size <= MAX_NAME_WORDS && list.none { it in NOT_NAME_WORDS }
    }

    private const val ENGLISH = "en"

    private const val MAX_NAME_WORDS = 4

    /** "Call them back", "call it again". */
    private val PRONOUNS = setOf("them", "him", "her", "it", "that", "this", "they", "that person")

    /** "Call that number": the newest call, as after "who called me". */
    private val NUMBER_PRONOUNS = setOf("that number", "this number", "the number")

    /** "The last", "my most recent", "last". */
    private const val ORDINAL = "((the|my|that) )?(very )?(last|latest|most recent|previous)"

    /**
     * "Whoever just called", "the person who called me earlier", "the last caller", "my missed
     * call". What follows the call is any time or reason ("about the car").
     */
    private val INCOMING_REFS = Regex(
        "(whoever|the person (who|that)|the one (who|that)|the number (that|who)|the caller who|" +
            "$ORDINAL (person|one|number) (who|that))" +
            "( just| last)? (called|rang|phoned|tried to call|tried calling)( .+)?" +
            "|$ORDINAL( missed)? caller|the caller|$ORDINAL missed call|(the|my|that) missed call"
    )

    /** "The last number I dialed", "the last person I called". */
    private val OUTGOING_REFS = Regex(
        "$ORDINAL (number|person|one|call) i (called|dialed|dialled|rang|phoned|made)"
    )

    /** "The last number", "my last call", "last number". */
    private val LAST_REFS = Regex("$ORDINAL (number|call|phone call|person|one)")

    /** Words of times and other things that aren't a contact's name. */
    private val NOT_NAME_WORDS = setOf(
        "later", "soon", "now", "today", "tonight", "tomorrow", "morning", "afternoon", "evening",
        "in", "at", "after", "before", "when", "minute", "minutes", "hour", "hours", "second",
        "seconds", "oclock", "am", "pm", "please", "number", "me", "you", "us",
    )
}
