package org.stypox.dicio.gar

import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.conversation.GarTurnElement
import org.stypox.dicio.gar.followup.GarFollowUpMatch
import org.stypox.dicio.gar.followup.GarFollowUpOutcome
import org.stypox.dicio.gar.followup.GarFollowUpSkill
import org.stypox.dicio.gar.followup.GarFollowUpStep
import org.stypox.dicio.gar.followup.GarRewrite
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.perf.GarMatchPriority
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.gar.voicelog.VoiceFollowUp
import org.stypox.dicio.gar.voicelog.VoiceTurn

/**
 * The matching step of `SkillEvaluator` (a GAR edit; called with its match lock held): Dicio's
 * ranker on each alternative of the input, as upstream, except that [GarFallback.matchCheck]
 * may reject a match of Dicio's default skills. A rejected match counts as no match: the pending
 * follow-up batches stay (the ranker asks before dropping them), and the next alternative is
 * tried. When no alternative matched, the model ([GarFallback.tryRoute]), then Dicio's fallback
 * skill. The ranker is read once by the caller, so all of this uses the same one.
 *
 * A rejected match is still a new command, one that the model should map, and not an answer to
 * a pending question (answers are matched before the default skills): so when no alternative
 * matched, it ends the pending question before the model gets the input, like an accepted match
 * of the default batch or a call of the model would ("what is the score of the 49ers game" after
 * "Which day in December?"). An input that no skill matched at all keeps the question, since it
 * may be a misheard answer.
 *
 * A follow-up ("turn it off", gar/DESIGN.md "Follow-ups") that the follow-up skill rewrote is
 * matched again as its full command ("turn off the flashlight"), through all of the above, with
 * the input as said as the chosen input; a direct one returns the target's own match.
 *
 * The voice log's turn (gar/DESIGN.md, "Voice log") gets the first alternative's best scores,
 * GarMatchCheck's verdict, which path won and what the follow-up skill made of the input.
 */
suspend fun garMatchUtterances(
    ctx: SkillContextInternal,
    ranker: SkillRanker,
    garFallback: GarFallback,
    utterances: List<String>,
): Pair<String, SkillWithResult<*>> {
    val turn = GarVoiceLog.turn()
    // the follow-up skill's score can't suspend to read the turn: matching holds the match lock
    GarFollowUpSkill.turn = GarTurnElement.current()
    GarFollowUpSkill.scored = null
    try {
        val matched = garMatchAlternatives(ctx, ranker, garFallback, utterances, turn, logScoring = true)
        return garFollowUp(ctx, ranker, garFallback, utterances, turn, matched)
            ?: matched
            ?: Pair(utterances[0], ranker.getFallbackSkill(ctx, utterances[0]))
                .also { turn?.routedBy("fallback") }
    } finally {
        GarFollowUpSkill.turn = null
    }
}

/**
 * [garMatchUtterances] without the follow-ups and the fallback skill: Dicio's ranker on each
 * alternative, then the model; null when neither took the input.
 *
 * @param logScoring whether the voice log records the first alternative's candidates (not for a
 * follow-up's rewrite, whose input wasn't said)
 */
private suspend fun garMatchAlternatives(
    ctx: SkillContextInternal,
    ranker: SkillRanker,
    garFallback: GarFallback,
    utterances: List<String>,
    turn: VoiceTurn?,
    logScoring: Boolean,
): Pair<String, SkillWithResult<*>>? {
    val check = garFallback.matchCheck(ctx)
    var rejectedCommand = false
    // the default batch's match that was accepted: any other match answered a question
    var acceptedDefault: SkillWithResult<*>? = null
    return utterances.withIndex().firstNotNullOfOrNull { (index, input) ->
        ctx.standardMatchHelper = MatchHelper(ctx.parserFormatter, input)
        GarVoiceLog.scoring(turn.takeIf { index == 0 && logScoring }) // the first alternative's candidates
        val best = try {
            // GAR: the ranker gets the CPU first and the voice log its times (GarMatchPriority)
            GarMatchPriority.ranking({ turn?.ranked(it.wallMillis, it.cpuMillis, it.modelLoading) }) {
            ranker.getBest(ctx, input) { match ->
                // a follow-up is checked as its full command, when that is matched
                val rejects = match.skill !is GarFollowUpSkill && check?.rejects(match, input) == true
                rejectedCommand = rejectedCommand || rejects
                turn?.matchChecked(
                    match.skill.correspondingSkillInfo.id,
                    when { check == null -> "off"; rejects -> "reject"; else -> "accept" },
                )
                if (!rejects) {
                    acceptedDefault = match
                }
                !rejects
            }
            }
        } finally {
            GarVoiceLog.scoring(null)
        }
        best?.let { skillWithResult -> Pair(input, skillWithResult) }
    }?.also { (_, match) ->
        turn?.routedBy(when {
            match.skill is GarOnlineGateSkill -> "online_gate"
            match === acceptedDefault -> "sentence"
            else -> "follow_up_answer"
        })
    } ?: run {
        if (rejectedCommand) {
            ranker.removeAllBatches()
        }
        garFallback.tryRoute(ctx, ranker, utterances)?.also { turn?.routedBy("model") }
    }
}

/**
 * The match for a follow-up that [matched] is (gar/DESIGN.md, "Follow-ups"), or null when it is
 * none and [matched] stands. A rewrite is a new command of Dicio's default batch: the pending
 * batches go (the follow-up's own question too), and the follow-up skills score worst while it
 * is matched. A rewrite that nothing takes is an adapter's bug: the fallback skill answers the
 * input as said.
 */
private suspend fun garFollowUp(
    ctx: SkillContextInternal,
    ranker: SkillRanker,
    garFallback: GarFallback,
    utterances: List<String>,
    turn: VoiceTurn?,
    matched: Pair<String, SkillWithResult<*>>?,
): Pair<String, SkillWithResult<*>>? {
    val (input, match) = matched ?: return logDeferral(turn, utterances[0])
    val followUp = (match.inputData as? GarFollowUpMatch)?.takeIf { match.skill is GarFollowUpStep }
        ?: return logDeferral(turn, utterances[0])
    return when (val outcome = followUp.outcome) {
        is GarFollowUpOutcome.Rewritten -> {
            ranker.removeAllBatches()
            GarFollowUpSkill.rewritePass = true
            val rewritten = try {
                garMatchAlternatives(ctx, ranker, garFallback, listOf(outcome.text), turn, logScoring = false)
            } finally {
                GarFollowUpSkill.rewritePass = false
            }
            turn?.followUp(voiceFollowUp(followUp, outcome.text, unmatched = rewritten == null))
            Pair(input, rewritten?.second ?: ranker.getFallbackSkill(ctx, input).also { turn?.routedBy("fallback") })
        }
        is GarFollowUpOutcome.Direct -> {
            ranker.removeAllBatches()
            turn?.followUp(voiceFollowUp(followUp, outcome.text))
            Pair(input, outcome.match)
        }
        else -> {
            turn?.followUp(voiceFollowUp(followUp, null))
            null
        }
    }
}

/** Logs that the follow-up skill read the first alternative as a form and left it to others. */
private fun logDeferral(turn: VoiceTurn?, input: String): Nothing? {
    GarFollowUpSkill.scored
        ?.takeIf { (scored, match) -> scored == input && match.outcome is GarFollowUpOutcome.Deferred }
        ?.let { (_, match) -> turn?.followUp(voiceFollowUp(match, null)) }
    return null
}

private fun voiceFollowUp(match: GarFollowUpMatch, rewrite: String?, unmatched: Boolean = false) = VoiceFollowUp(
    form = match.form.logName,
    turnSkill = match.turn?.turn?.skillId,
    turnAgeMs = match.turn?.ageMillis,
    rewrite = rewrite ?: when (val outcome = match.outcome) {
        is GarFollowUpOutcome.Confirm -> when (val confirmed = outcome.rewrite) {
            is GarRewrite.Command -> confirmed.text
            is GarRewrite.Direct -> confirmed.text
            is GarRewrite.Say -> null
        }
        is GarFollowUpOutcome.Ask -> outcome.template
        else -> null
    },
    outcome = if (unmatched) VoiceFollowUp.OUTCOME_UNMATCHED else match.outcome.logName,
    deferredTo = (match.outcome as? GarFollowUpOutcome.Deferred)?.to,
)
