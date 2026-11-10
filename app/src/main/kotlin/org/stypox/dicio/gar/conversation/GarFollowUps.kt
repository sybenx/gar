package org.stypox.dicio.gar.conversation

import org.stypox.dicio.eval.SkillWithResult

/**
 * A follow-up skill that takes almost any words as its answer, like the name of whom to call: it
 * would take a new command too. A whole sentence of Dicio's default batch that it [yieldsTo] wins
 * over it ([GarFollowUps.commandOverAnswer]).
 */
interface GarOpenAnswer {
    /**
     * Whether this skill's answer gives way to [command], Dicio's default batch's match of the
     * same input as one of its sentences ("call mom", "what time is it").
     */
    fun yieldsTo(command: SkillWithResult<*>): Boolean
}

/** A skill about the conversation itself: "stop", "never mind", "repeat that". */
interface GarConversationSkill {
    /**
     * Whether its match with [inputData] leaves a pending follow-up question open ("repeat
     * that"), instead of ending it like any other match of the default batch.
     */
    fun keepsQuestion(inputData: Any?): Boolean
}

/**
 * How a pending follow-up question gives way to a new command (gar/DESIGN.md, "Conversation"),
 * for the GAR lines in `SkillRanker.getBest`. The other half is in `garMatchUtterances`: a match
 * that GAR's match check rejected is a new command for the model, so once no alternative of the
 * input matched, it ends a pending question too.
 */
object GarFollowUps {
    /**
     * A match scored at least this is one of the skill's sentences as said, give or take
     * punctuation (typed "Stop." scores 0.98, "that's" has an apostrophe): with a word left out
     * of a sentence of up to ten words or added to it, a match scores less.
     */
    const val COMMAND_SCORE = 0.95f

    /**
     * The match of Dicio's default batch that wins over [answer], the best match of a pending
     * batch, or null when [answer] stands. Only an answer that takes almost any words
     * ([GarOpenAnswer]) gives way, and only to a whole sentence ([COMMAND_SCORE]) that it yields
     * to: "call mom" to "Who?" is a call, "mom" is the answer.
     *
     * @param defaultBest the default batch's best match for the same input, only computed for an
     * open answer
     */
    fun commandOverAnswer(
        answer: SkillWithResult<*>,
        defaultBest: () -> SkillWithResult<*>?,
    ): SkillWithResult<*>? {
        val open = answer.skill as? GarOpenAnswer ?: return null
        return defaultBest()?.takeIf {
            it.score.scoreIn01Range() >= COMMAND_SCORE && open.yieldsTo(it) &&
                // gar:followup: "call her" in reply to "Who should I text?" is the answer
                it.skill !is org.stypox.dicio.gar.followup.GarFollowUpSkill
        }
    }

    /**
     * Whether [match], a match of Dicio's default batch, keeps the pending follow-up batches,
     * which any other match of the default batch drops.
     */
    fun keepsBatches(match: SkillWithResult<*>): Boolean =
        (match.skill as? GarConversationSkill)?.keepsQuestion(match.inputData) == true
}
