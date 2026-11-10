package org.stypox.dicio.skills.calls

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.conversation.conversationWords
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.sentences.Sentences.CallLog
import org.stypox.dicio.skills.telephone.TelephoneSkill
import java.time.ZonedDateTime

/** What the user asked of the call log. */
sealed interface CallLogRequest {
    /** "Who called me?" */
    data object LastCaller : CallLogRequest

    /** "Did I miss any calls?" */
    data object Missed : CallLogRequest

    /** "Redial", "call back", "call mom back". */
    data class Call(val target: CallTarget) : CallLogRequest

    /** "Did mom call me", "any calls from dad", "when did mom call": [name] as said. */
    data class From(val name: String) : CallLogRequest
}

/**
 * "Who called me", "did I miss any calls", "did mom call me", "redial", "call back the last
 * number", "call mom back" (gar/PARITY.md, batch 4), English only, no tool. Dicio's matcher takes a sentence with a
 * word left out or swapped, so a match only counts when [CallLogWords] reads the whole input as
 * the same request ("who called the plumber" and "call back in five minutes" are not). Calls go
 * through the telephone skill's confirmation ([CallBack]).
 *
 * @param telephone the telephone skill, for its confirmation and its contact lookup
 * @param granted whether the app has a permission (calling back needs the telephone skill's)
 */
class CallLogSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<CallLog>,
    private val telephone: TelephoneSkill,
    private val history: (SkillContext) -> CallHistory = { AndroidCallHistory(it.android) },
    private val isLocked: (SkillContext) -> Boolean = CallBack::isLocked,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val granted: (SkillContext, Permission) -> Boolean = { ctx, permission ->
        permission !is Permission.NormalPermission ||
            ContextCompat.checkSelfPermission(ctx.android, permission.id) ==
            PackageManager.PERMISSION_GRANTED
    },
) : Skill<CallLogRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, CallLogRequest?> {
        val (score, sentence) = data.score(ctx, input)
        val request = CallLogWords.request(input)
        val same = when (sentence) {
            CallLog.LastCaller -> request == CallLogRequest.LastCaller
            CallLog.Missed -> request == CallLogRequest.Missed
            is CallLog.Call -> request is CallLogRequest.Call
            is CallLog.From -> request is CallLogRequest.From
        }
        return if (same) Pair(score, request) else Pair(AlwaysWorstScore, null)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: CallLogRequest?): SkillOutput {
        val history = history(ctx)
        if (inputData is CallLogRequest.Call) {
            return CallBack.output(
                ctx, inputData.target, telephone, history, isLocked(ctx),
                granted = { granted(ctx, it) },
            )
        }
        if (isLocked(ctx)) {
            // the call log is private: GAR reads it only on an unlocked phone
            return GarLockedOutput()
        }
        if (!history.allowed) {
            // Dicio asks for it before the skill runs, but it can be taken away in between
            return MissingPermissionsSkillOutput(correspondingSkillInfo)
        }
        val now = now()
        val calls = history.recent()
        return when (inputData) {
            CallLogRequest.Missed ->
                CallLogOutput.Missed(CallHistoryQueries.missed(calls, now.toInstant()), now)
            is CallLogRequest.From -> CallLogOutput.From(
                inputData.name,
                CallHistoryQueries.named(calls, inputData.name, cameIn = true),
                now,
            )
            else -> CallLogOutput.LastCaller(CallHistoryQueries.lastIncoming(calls), calls, now)
        }
    }
}

/**
 * Reads the call log requests from what the user said, whole: [conversationWords] (no
 * apostrophes, fillers like "ok" and "please" dropped), then one of the phrasings below. English.
 */
object CallLogWords {
    fun request(input: String): CallLogRequest? {
        val text = conversationWords(input).joinToString(" ")
        return when {
            LAST_CALLER.matches(text) -> CallLogRequest.LastCaller
            MISSED.matches(text) -> CallLogRequest.Missed
            else -> from(text) ?: callTarget(text)?.let { CallLogRequest.Call(it) }
        }
    }

    /** "Did mom call me", "any missed calls from dad": the name, up to 4 words and no time. */
    private fun from(text: String): CallLogRequest.From? {
        val name = FROM.firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }
            ?.removePrefix("my ")
            ?: return null
        if (name.split(" ").any { it in NOT_A_CALLER } || !CallBack.isName(name)) {
            return null
        }
        return CallLogRequest.From(name)
    }

    /** "Redial ...", "call ... back", "return mom's call": the call that the words point at. */
    private fun callTarget(text: String): CallTarget? {
        if (text.startsWith("redial") || text.startsWith("re dial")) {
            return CallBack.parse(text)
        }
        RETURN_CALL.matchEntire(text)?.let { match ->
            val whose = match.groupValues[1]
            return when {
                whose.isEmpty() || RETURN_REFS.matches(whose) -> CallTarget.LastIncoming
                // "return moms call": the apostrophe is gone, the call log lookup tries "mom" too
                else -> CallBack.parse("back $whose")
            }
        }
        val words = text.split(" ")
        if (words.first() !in CALL_VERBS) {
            return null
        }
        // what the telephone skill's "call .who." would capture: "back", "mom back", "again"
        return CallBack.parse(words.drop(1).joinToString(" "))
    }

    private const val TIME =
        "( (just now|today|earlier|earlier today|this morning|this afternoon|this evening|" +
            "tonight|last night|yesterday|recently|last|a (minute|moment|second|few minutes) ago))?"

    private val LAST_CALLER = Regex(
        "who( just| last| recently)? (called|rang|phoned|was calling|has been calling|" +
            "has called|tried to call|tried calling)( me| my phone)?$TIME" +
            "|whos been calling( me)?$TIME" +
            "|who (was|is) (the|my) (last|latest|most recent) (caller|call)" +
            "|who (was|is) the (last|latest|most recent) person (who|that|to) (called|call)( me)?" +
            "|(what|whats|what is|what was) (the|my) (last|latest|most recent) (incoming |phone )?call" +
            "|(show|read|check|open)( me)? (my|the) (recent calls|last calls|call log|call history)" +
            "|(show|read)( me)? recent calls"
    )

    private val MISSED = Regex(
        "(did|have) i (miss|missed) (any |a |some )?(phone )?calls?$TIME" +
            "|(do|did) i have (any )?missed (phone )?calls?$TIME" +
            "|(have i got|i have|any|how many) missed (phone )?calls?( do i have| have i got)?$TIME" +
            "|(show|read|check|list|open)( me)?( my| the)? missed (phone )?calls?" +
            "|(what|who) (are|were) my missed (phone )?calls|whats my missed calls" +
            "|missed (phone )?calls" +
            "|(did|has|have) (anyone|anybody|someone|somebody) (call|called|rang|phoned|" +
            "tried to call|tried calling|been calling)( me)?$TIME" +
            "|who did i miss( a call from| calls from)?$TIME" +
            "|(any|were there any|are there any|have there been any) (new )?calls( for me)?$TIME"
    )

    private const val CALLED =
        "(call|called|ring|rang|phone|phoned|try to call|tried to call|tried calling|been calling)"

    /** The caller as group 1: "did mom call me", "has dad tried to call", "any calls from mom". */
    private val FROM = listOf(
        "(?:did|has|have) (.+?) $CALLED(?: me| my phone)?(?: back)?$TIME",
        "(?:did|have) i (?:miss|missed|get|got|have|had) (?:a |any |some )?(?:missed )?(?:phone )?" +
            "calls? from (.+?)$TIME",
        "(?:any|were there any|are there any|have there been any|missed) (?:new )?(?:missed )?" +
            "(?:phone )?calls? from (.+?)$TIME",
        "when did (.+?) (?:last )?$CALLED(?: me)?",
    ).map { Regex(it) }

    /** Who calls are no question about one caller: "did you call", "did anyone call me". */
    private val NOT_A_CALLER = setOf(
        "i", "you", "he", "she", "they", "it", "we", "anyone", "anybody", "someone", "somebody",
        "everyone", "nobody", "no one", "who", "that", "this", "there",
    )

    private val CALL_VERBS = setOf("call", "dial", "ring", "phone")

    /** "Return the call", "return my last missed call", "return moms call". */
    private val RETURN_CALL = Regex("return(?: (.+?))? (?:phone )?call")

    private val RETURN_REFS = Regex("(the|my|that|this|their|his|her)( last| latest| missed| most recent)*")
}
