package org.stypox.dicio.skills.navigation

import cc.aaro.gar.router.Utterances

/**
 * GAR. Whether an input asks where a part or a setting of the phone is (English): "where's my
 * battery" (how Vosk hears "what's my battery"), "where is my wifi", "where's the phone's
 * signal". No place to navigate to, so neither [NavigationSkill]'s "where is .where." sentence nor
 * the model's `navigate` call takes it. Only "where is" questions, and only when every word after
 * it is about the phone: "where is the nearest phone store", "where is the charging station" and
 * "navigate to the battery" are places.
 */
object NavigationWords {
    fun asksAboutPhone(input: String): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        val thing = when {
            words.firstOrNull() in WHERE_IS -> words.drop(1)
            words.firstOrNull() == "where" && words.getOrNull(1) in IS -> words.drop(2)
            else -> return false
        }.map { it.removeSuffix("'s") }.filter { it !in FILLERS }
        return thing.any { it in PHONE_THINGS } &&
            thing.all { it in PHONE_THINGS || it in DETAILS }
    }

    /**
     * Whether [input] reads as no request for directions (English), for the model's `navigate`
     * calls, which no sentence scored: narration about someone else ("you're trying to get to the
     * first half of your business", what STT made of a TV) or a question about when, why or
     * whether ([org.stypox.dicio.skills.places.PlaceWords.asksNoDirections]). Anything else the
     * model chose to navigate is kept: "what's the fastest way home", "I'm trying to get to the
     * airport", "nearest gas station".
     */
    fun refusesDirections(input: String): Boolean {
        val words = Utterances.words(Utterances.normalize(input)).dropWhile { it in LEADING_FILLERS }
        return words.isEmpty() || NARRATION.any { words.take(it.size) == it } ||
            org.stypox.dicio.skills.places.PlaceWords.asksNoDirections(words)
    }

    /**
     * Whether [input] is the navigation sentences' "how do I get|go ..." with the "how" left out
     * ("when should I go to bed", "do I go to the gym today"): Dicio's matcher skips words, so
     * the line matched without the word that makes it a question about the way.
     */
    fun skipsHow(input: String): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        val subject = (1 until words.size).firstOrNull { i ->
            words[i] == "i" && words[i - 1] in HOW_AUXILIARIES && words.getOrNull(i + 1) in GO_VERBS
        } ?: return false
        // "where can I get gas" asks for a place too
        return words.subList(0, subject - 1).none { it == "how" || it in WHERE_WORDS } &&
            words.none { it in DIRECTION_WORDS }
    }

    private val LEADING_FILLERS = setOf("hey", "ok", "okay", "so", "um", "uh", "please", "and", "now", "gar", "google")

    /**
     * Narration, not a request: what the user says about someone else, or what STT made of a TV.
     * Not "I'm trying to get to ...", which asks the way.
     */
    private val NARRATION = listOf(
        listOf("you're"), listOf("youre"), listOf("you", "are"), listOf("you", "re"),
        listOf("he's"), listOf("she's"), listOf("they're"), listOf("theyre"), listOf("they", "are"),
    )

    private val HOW_AUXILIARIES = setOf("do", "can", "would", "should")
    private val GO_VERBS = setOf("get", "go")
    private val DIRECTION_WORDS = setOf("directions", "direction", "route", "way", "navigate")
    private val WHERE_WORDS = setOf("where", "where's", "wheres")

    private val WHERE_IS = setOf("where's", "wheres")
    private val IS = setOf("is", "s")

    private val FILLERS = setOf(
        "my", "the", "your", "our", "this", "that", "a", "an", "on", "in", "of", "for", "at", "s",
        "right", "now", "current",
    )

    /** The phone, its parts and its settings. */
    private val PHONE_THINGS = setOf(
        "phone", "phones", "cellphone", "smartphone", "battery", "batteries", "charge", "charger",
        "charging", "power", "wifi", "wi", "fi", "internet", "signal", "network", "data",
        "bluetooth", "hotspot", "volume", "ringer", "ringtone", "sound", "brightness", "screen",
        "display", "flashlight", "torch", "settings", "setting", "sim", "location", "gps",
        "notifications", "notification", "camera", "microphone", "mic", "speaker", "alarm",
        "alarms", "timer", "timers",
    )

    /** Words said with them: "my battery level", "the wifi password", "my cell phone". */
    private val DETAILS = setOf(
        "level", "levels", "percentage", "percent", "status", "life", "cell", "mobile", "service",
        "password", "name", "strength", "connection", "speed", "saver", "mode", "plan", "app",
        "icon", "button", "switch",
    )
}
