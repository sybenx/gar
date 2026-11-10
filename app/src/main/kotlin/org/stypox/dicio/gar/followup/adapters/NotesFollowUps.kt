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
 * Reminders, events, lists and notes ([GarTurnKind.MODIFIABLE]). Phase 2 of gar/DESIGN.md,
 * "Follow-ups": for now a turn without facts, which ends the topic and which no follow-up
 * reaches. Later: "delete it" and "move it to 6" for the reminder or event just made, its title
 * through GarRewriteText.safe.
 */
object NotesFollowUps : GarFollowUpAdapter {
    // "agenda" is AgendaFollowUps'
    override val skillIds = setOf("reminders", "calendar", "lists", "notes")

    override val onLockScreen: Boolean = false

    override fun remember(answered: GarAnswered): GarTurn = GarTurn(
        skillId = answered.skillId,
        action = "",
        slots = mapOf(),
        facts = GarFacts(),
        command = answered.input,
        kind = GarTurnKind.MODIFIABLE,
    )

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? = null
}
