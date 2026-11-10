package org.stypox.dicio.gar.followup.adapters

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.followup.FollowUpForm
import org.stypox.dicio.gar.followup.GarAnswered
import org.stypox.dicio.gar.followup.GarFacts
import org.stypox.dicio.gar.followup.GarFollowUpAdapter
import org.stypox.dicio.gar.followup.GarFollowUpContext
import org.stypox.dicio.gar.followup.GarRememberedTurn
import org.stypox.dicio.gar.followup.GarRewrite
import org.stypox.dicio.gar.followup.GarRewriteText
import org.stypox.dicio.gar.followup.GarTurn
import org.stypox.dicio.gar.followup.GarTurnKind
import org.stypox.dicio.skills.agenda.AgendaRequest
import org.stypox.dicio.skills.agenda.AgendaWords

/**
 * The agenda skill's search for an event by name ([AgendaRequest.Next], an ANSWER): another
 * name right after it searches again (2026-11-05, after "when's my next haircut appointment"
 * found nothing): "any haircut?", "no, check for haircut appointment", "what about the
 * dentist" become "when is my haircut appointment". Moving and deleting events stay bare
 * MODIFIABLE turns, as [NotesFollowUps] leaves them.
 */
object AgendaFollowUps : GarFollowUpAdapter {
    override val skillIds = setOf("agenda")

    override val onLockScreen: Boolean = false

    private const val NEXT = "next"

    override fun remember(answered: GarAnswered): GarTurn {
        val next = answered.inputData as? AgendaRequest.Next
        return GarTurn(
            skillId = answered.skillId,
            action = if (next != null) NEXT else "",
            slots = next?.let { mapOf("about" to it.about) }.orEmpty(),
            facts = GarFacts(),
            command = answered.input,
            kind = if (next != null) GarTurnKind.ANSWER else GarTurnKind.MODIFIABLE,
        )
    }

    override fun rewrite(form: FollowUpForm, turn: GarRememberedTurn, context: GarFollowUpContext): GarRewrite? {
        if (turn.turn.action != NEXT) {
            return null
        }
        val said = when (form) {
            is FollowUpForm.AnyOf -> form.what
            is FollowUpForm.Correction -> form.value
            // "what about the dentist"; "what about tomorrow" names no event
            is FollowUpForm.WhatAbout -> form.rest.takeIf { form.preposition == null || form.preposition == "with" }
            else -> null
        } ?: return null
        return about(said)?.let { GarRewrite.Command("when is my $it appointment") }
    }

    /**
     * The event's name in [said] ("check for haircut appointment" is "haircut"), or null when
     * it names none, or a day, or something that is no event ("any messages").
     */
    fun about(said: String): String? {
        val words = Utterances.normalize(said).split(" ").filter { it.isNotEmpty() }
        val name = words.drop(SEARCH_VERBS.firstOrNull { verb -> words.take(verb.size) == verb }?.size ?: 0)
        val rest = AgendaWords.keyword(name.joinToString(" "))
            .split(" ").filter { it.isNotEmpty() }.dropLastWhile { it in TRAILING }
        val about = rest.joinToString(" ")
        return about.takeIf {
            rest.isNotEmpty() && GarRewriteText.safe(it) && !AgendaWords.saysWhen(it) &&
                rest.none { word -> word in AgendaWords.NOT_EVENTS }
        }
    }

    /** "check for", "look for": the search, not the name. */
    private val SEARCH_VERBS = listOf(
        listOf("check", "for"), listOf("look", "for"), listOf("search", "for"),
        listOf("check", "my", "calendar", "for"), listOf("is", "there"), listOf("do", "i", "have"),
    )

    /** Said after the name and dropped: the rewrite says "appointment" itself. */
    private val TRAILING = setOf(
        "appointment", "appointments", "meeting", "meetings", "event", "events", "on", "in", "my",
        "calendar", "schedule", "agenda", "coming", "up", "scheduled",
    )
}
