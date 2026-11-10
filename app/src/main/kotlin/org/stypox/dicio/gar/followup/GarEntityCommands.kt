package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.FollowUpForm
import cc.aaro.gar.router.followup.PersonVerb
import cc.aaro.gar.router.followup.PlaceVerb

/**
 * Follow-ups about an entity of an earlier turn: "call her", "what's his number", "navigate
 * there", "what time is it there". They reach the newest fact of its kind across the remembered
 * turns ([GarConversationMemory.PERSON_MILLIS] for persons), not just the newest turn. A name goes
 * into the command only when the lookup chose that contact without asking and it is
 * [GarRewriteText.safe]; otherwise GAR asks whom the user means.
 */
object GarEntityCommands {
    fun person(form: FollowUpForm.AboutPerson, context: GarFollowUpContext, locked: Boolean): GarFollowUpMatch {
        val turn = context.memory.turns.firstOrNull {
            it.ageMillis <= GarConversationMemory.PERSON_MILLIS && it.turn.facts.person != null
        }
        if (locked) {
            // contacts stay behind the lock, as the people tools do
            return GarFollowUpMatch(form, GarFollowUpOutcome.Locked, turn)
        }
        val person = turn?.turn?.facts?.person
        val name = person?.takeIf { it.unique }?.let { GarRewriteText.words(it.name) }
        val command = name?.let { personCommand(form.verb, it) }
        return if (command == null || context.memory.inFlight) {
            GarFollowUpMatch(form, askPerson(form.verb), turn)
        } else {
            GarFollowUpMatch(form, GarFollowUpOutcome.Rewritten(command), turn)
        }
    }

    fun place(form: FollowUpForm.AboutPlace, context: GarFollowUpContext, locked: Boolean): GarFollowUpMatch {
        val turn = context.memory.turns.firstOrNull { it.turn.facts.place != null }
        val name = turn?.turn?.facts?.place?.name?.let { GarRewriteText.words(it) }
        if (name == null || context.memory.inFlight) {
            return if (form.verb == PlaceVerb.NAVIGATE) {
                GarFollowUpMatch(
                    form,
                    GarFollowUpOutcome.Ask(GarFollowUpQuestion.WHERE_TO, "navigate to {answer}"),
                    turn,
                )
            } else {
                GarFollowUpMatch(form, GarFollowUpOutcome.Deferred("reach"), turn)
            }
        }
        val command = when (form.verb) {
            PlaceVerb.TIME_THERE -> "what time is it in $name"
            PlaceVerb.WEATHER_THERE -> "what's the weather in $name"
            // places phase 2: navigation to the remembered coordinates (Direct); a name is a new
            // search, which is what the full command would do too
            PlaceVerb.NAVIGATE -> if (locked) null else "navigate to $name"
            // open question 3: straight-line distance, or nothing
            PlaceVerb.DISTANCE -> null
        } ?: return GarFollowUpMatch(
            form,
            if (locked) GarFollowUpOutcome.Locked else GarFollowUpOutcome.Deferred("reach"),
            turn,
        )
        return GarFollowUpMatch(form, GarFollowUpOutcome.Rewritten(command), turn)
    }

    /** The command for [verb] about the person called [name] (words, see [GarRewriteText.words]). */
    fun personCommand(verb: PersonVerb, name: String): String = when (verb) {
        PersonVerb.CALL -> "call $name"
        PersonVerb.TEXT -> "send a text to $name"
        PersonVerb.EMAIL -> "send an email to $name"
        PersonVerb.NUMBER -> "what's $name's number"
        PersonVerb.ADDRESS -> "what's $name's address"
    }

    private fun askPerson(verb: PersonVerb) = GarFollowUpOutcome.Ask(
        when (verb) {
            PersonVerb.CALL -> GarFollowUpQuestion.WHO_CALL
            PersonVerb.TEXT -> GarFollowUpQuestion.WHO_TEXT
            PersonVerb.EMAIL -> GarFollowUpQuestion.WHO_EMAIL
            PersonVerb.NUMBER -> GarFollowUpQuestion.WHOSE_NUMBER
            PersonVerb.ADDRESS -> GarFollowUpQuestion.WHOSE_ADDRESS
        },
        personCommand(verb, "{answer}"),
    )
}
