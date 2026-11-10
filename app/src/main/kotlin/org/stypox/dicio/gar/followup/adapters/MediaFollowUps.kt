package org.stypox.dicio.gar.followup.adapters

import cc.aaro.gar.router.followup.FollowUpForm
import org.stypox.dicio.gar.followup.GarAnswered
import org.stypox.dicio.gar.followup.GarFacts
import org.stypox.dicio.gar.followup.GarFollowUpAdapter
import org.stypox.dicio.gar.followup.GarFollowUpContext
import org.stypox.dicio.gar.followup.GarRememberedTurn
import org.stypox.dicio.gar.followup.GarRewrite
import org.stypox.dicio.gar.followup.GarTurn
import org.stypox.dicio.gar.followup.GarTurnKind

/**
 * Music played by name and the song GAR identified ([GarTurnKind.ANSWER]). Phase 2 of
 * gar/DESIGN.md, "Follow-ups": for now a turn without facts, which ends the topic and which no
 * follow-up reaches. Later: a Correction of what played, "play it again" while nothing plays and
 * "who sings it" (a Say from the song's artist).
 */
object MediaFollowUps : GarFollowUpAdapter {
    override val skillIds = setOf("media_play", "media_controls", "song")

    override val onLockScreen: Boolean = true

    override fun remember(answered: GarAnswered): GarTurn = GarTurn(
        skillId = answered.skillId,
        action = "",
        slots = mapOf(),
        facts = GarFacts(),
        command = answered.input,
        kind = GarTurnKind.ANSWER,
    )

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? = null
}
