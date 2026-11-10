package org.stypox.dicio.gar.voicelog

/**
 * What the follow-up skill made of a turn's input (gar/DESIGN.md, "Follow-ups"), written as the
 * record's `follow_up` field.
 */
data class VoiceFollowUp(
    /** `FollowUpForm.logName` ("switch_off_back"). */
    val form: String,
    /** The remembered turn's skill the form went to, null when none. */
    val turnSkill: String?,
    /** How old that turn was. */
    val turnAgeMs: Long?,
    /** The full command it became, null when there is none. */
    val rewrite: String?,
    /** One of the OUTCOME_ values below. */
    val outcome: String,
    /**
     * For [OUTCOME_DEFERRED], why: "ringing", "call", "media", "back", "reach", "in_flight".
     */
    val deferredTo: String? = null,
) {
    companion object {
        const val OUTCOME_REWRITTEN = "rewritten"
        const val OUTCOME_DIRECT = "direct"
        const val OUTCOME_SAID = "said"
        const val OUTCOME_ASKED = "asked"
        const val OUTCOME_CONFIRM = "confirm"
        const val OUTCOME_LOCKED = "locked"
        const val OUTCOME_UNMATCHED = "unmatched"
        const val OUTCOME_DEFERRED = "deferred"

        /** GAR asked what "it" was ("Turn what off?"). */
        const val FOLLOW_UP_ASKED = "follow_up_asked"

        /** Nothing took the rewrite: an adapter wrote a command no skill reads. */
        const val FOLLOW_UP_UNMATCHED = "follow_up_unmatched"

        /** The turn after a rewritten follow-up corrected or cancelled it. */
        const val FOLLOW_UP_CORRECTED = "follow_up_corrected"

        /** The user said no to the follow-up's "Cancel your 7 AM alarm?". */
        const val FOLLOW_UP_CONFIRM_DECLINED = "follow_up_confirm_declined"

        /** The voice log's names of corrections start with this ("correction_said"). */
        private const val CORRECTION_FORM = "correction"

        private val CORRECTING = setOf(
            VoiceSignals.SAID_NO, VoiceSignals.SAID_CANCEL, VoiceSignals.SAID_WRONG,
            VoiceSignals.I_SAID,
        )
        private val DECLINING = setOf(
            VoiceSignals.SAID_NO, VoiceSignals.SAID_CANCEL, VoiceSignals.SAID_STOP,
        )

        /** The suspect signal a turn's own [followUp] gives, if any. */
        fun ownSignal(followUp: VoiceFollowUp): String? = when (followUp.outcome) {
            OUTCOME_ASKED -> FOLLOW_UP_ASKED
            OUTCOME_UNMATCHED -> FOLLOW_UP_UNMATCHED
            else -> null
        }

        /**
         * The signals the next turn ([next], its [nextInput]) gives about the turn before, whose
         * follow-up was [previous]: a correction or a cancel right after a rewrite, a no to a
         * follow-up's confirmation.
         */
        fun signals(
            previous: VoiceFollowUp?,
            next: VoiceFollowUp?,
            nextInput: String?,
        ): List<String> {
            previous ?: return emptyList()
            val negative = VoiceSignals.negative(VoiceSignals.words(nextInput ?: ""))
            return when (previous.outcome) {
                OUTCOME_REWRITTEN, OUTCOME_DIRECT ->
                    if (next?.form?.startsWith(CORRECTION_FORM) == true || negative in CORRECTING) {
                        listOf(FOLLOW_UP_CORRECTED)
                    } else {
                        emptyList()
                    }
                OUTCOME_CONFIRM ->
                    if (negative in DECLINING) listOf(FOLLOW_UP_CONFIRM_DECLINED) else emptyList()
                else -> emptyList()
            }
        }
    }
}
