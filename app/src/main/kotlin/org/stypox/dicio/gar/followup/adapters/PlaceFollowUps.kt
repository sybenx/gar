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
 * Places, navigation and sharing the location ([GarTurnKind.ANSWER]). Phase 2 of gar/DESIGN.md,
 * "Follow-ups": for now a turn without facts, which ends the topic and which no follow-up
 * reaches. Later: Correction (the next result), SendTo, and AboutPlace through a Direct
 * navigation to the remembered coordinates.
 */
object PlaceFollowUps : GarFollowUpAdapter {
    override val skillIds = setOf("places", "navigation", "share_location")

    override val onLockScreen: Boolean = false

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
