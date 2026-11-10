package cc.aaro.gar.router.followup

/** Which setting an [FollowUpForm.Adjust] names by its word ("louder", "brighter"). */
enum class AdjustKind { VOLUME, BRIGHTNESS }

/** What an [FollowUpForm.Control] does to a running clock (a stopwatch, a timer). */
enum class ControlVerb { PAUSE, RESUME, RESET, RESTART }

/** What an [FollowUpForm.AboutPerson] asks about the person remembered last. */
enum class PersonVerb { CALL, TEXT, EMAIL, NUMBER, ADDRESS }

/** What an [FollowUpForm.AboutPlace] asks about the place remembered last ("there"). */
enum class PlaceVerb { NAVIGATE, DISTANCE, TIME_THERE, WEATHER_THERE }

/** What an [FollowUpForm.AboutSong] asks about the song remembered last. */
enum class SongVerb { PLAY_AGAIN, WHO_SINGS }

/** How a [FollowUpForm.Correction] was said: "no, 8 pm", "I said Erin", "I meant Boston", "the other one". */
enum class CorrectionKind { NO, SAID, MEANT, OTHER }

/**
 * A follow-up that only makes sense after an earlier command: "turn it off", "what about
 * tomorrow", "call her", "no, 8 pm" (gar/DESIGN.md, "Follow-ups"). [FollowUpForms.parse] reads
 * one from what the user said; the app rewrites it into the full command it stands for.
 */
sealed interface FollowUpForm {
    /** Stable name for the voice log ("switch_off_back"). */
    val logName: String

    /** "Turn it off", "turn it back on". */
    data class Switch(val on: Boolean, val back: Boolean) : FollowUpForm {
        override val logName: String
            get() = (if (on) "switch_on" else "switch_off") + if (back) "_back" else ""
    }

    /** "Turn it up", "louder" ([AdjustKind.VOLUME]), "make it brighter" ([AdjustKind.BRIGHTNESS]). */
    data class Adjust(val up: Boolean, val kind: AdjustKind?) : FollowUpForm {
        override val logName: String
            get() = (if (up) "adjust_up" else "adjust_down") +
                (kind?.let { "_" + it.name.lowercase() } ?: "")
    }

    /** "Cancel it", or "delete it" / "remove it" / "get rid of it" ([delete]). */
    data class Remove(val delete: Boolean) : FollowUpForm {
        override val logName: String get() = if (delete) "delete" else "cancel"
    }

    /** "Snooze it". */
    data object Snooze : FollowUpForm {
        override val logName: String get() = "snooze"
    }

    /** "Pause it", "resume it", "reset it", "restart it". */
    data class Control(val verb: ControlVerb) : FollowUpForm {
        override val logName: String get() = "control_" + verb.name.lowercase()
    }

    /** "Change it to 8", "make it 7": [value] as said. */
    data class SetTo(val value: String) : FollowUpForm {
        override val logName: String get() = "set_to"
    }

    /** "Add 5 more minutes", or "take 2 minutes off" ([less]): [amount] as said, without "more". */
    data class AddMore(val amount: String, val less: Boolean) : FollowUpForm {
        override val logName: String get() = if (less) "take_off" else "add_more"
    }

    /**
     * "What about tomorrow", "how about denver", "and in paris": [rest] as said, without a
     * leading [preposition] ("in", "at", "on", "for", ...); [bareAnd] when it started with "and".
     */
    data class WhatAbout(val rest: String, val preposition: String?, val bareAnd: Boolean) : FollowUpForm {
        override val logName: String get() = if (bareAnd) "and" else "what_about"
    }

    /** "Call her", "what's his number", "text him back" ([back]). */
    data class AboutPerson(val verb: PersonVerb, val back: Boolean) : FollowUpForm {
        override val logName: String
            get() = "person_" + verb.name.lowercase() + if (back) "_back" else ""
    }

    /** "Send it to mom", "send it to mom too" ([too]). */
    data class SendTo(val who: String, val too: Boolean) : FollowUpForm {
        override val logName: String get() = "send_to"
    }

    /** "Navigate there", "what time is it there". */
    data class AboutPlace(val verb: PlaceVerb) : FollowUpForm {
        override val logName: String get() = "place_" + verb.name.lowercase()
    }

    /** "Play it again", "who sings it". */
    data class AboutSong(val verb: SongVerb) : FollowUpForm {
        override val logName: String get() = "song_" + verb.name.lowercase()
    }

    /** "Another one", "one more", "another joke" ([what]). */
    data class Another(val what: String?) : FollowUpForm {
        override val logName: String get() = "another"
    }

    /**
     * "Any haircut?", "anything about the dentist", "is there any yoga": [what] as said, after
     * a search of the calendar or the like (2026-11-05). Only an adapter that searches by name
     * takes it; after any other turn it is left to the sentences and the model.
     */
    data class AnyOf(val what: String) : FollowUpForm {
        override val logName: String get() = "any"
    }

    /** [value] is null only for [CorrectionKind.OTHER]. */
    data class Correction(val value: String?, val kind: CorrectionKind) : FollowUpForm {
        override val logName: String get() = "correction_" + kind.name.lowercase()
    }
}
