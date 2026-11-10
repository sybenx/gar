package cc.aaro.gar.router.followup

import cc.aaro.gar.router.followup.FollowUpForm.AboutPerson
import cc.aaro.gar.router.followup.FollowUpForm.AboutPlace
import cc.aaro.gar.router.followup.FollowUpForm.AboutSong
import cc.aaro.gar.router.followup.FollowUpForm.AddMore
import cc.aaro.gar.router.followup.FollowUpForm.Adjust
import cc.aaro.gar.router.followup.FollowUpForm.Another
import cc.aaro.gar.router.followup.FollowUpForm.AnyOf
import cc.aaro.gar.router.followup.FollowUpForm.Control
import cc.aaro.gar.router.followup.FollowUpForm.Correction
import cc.aaro.gar.router.followup.FollowUpForm.Remove
import cc.aaro.gar.router.followup.FollowUpForm.SendTo
import cc.aaro.gar.router.followup.FollowUpForm.SetTo
import cc.aaro.gar.router.followup.FollowUpForm.Switch
import cc.aaro.gar.router.followup.FollowUpForm.WhatAbout
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private fun parses(vararg cases: Pair<String, FollowUpForm>) {
    for ((input, form) in cases) {
        withClue(input) { FollowUpForms.parse(input) shouldBe form }
    }
}

private fun refuses(vararg inputs: String) {
    for (input in inputs) {
        withClue(input) { FollowUpForms.parse(input).shouldBeNull() }
    }
}

class FollowUpFormsTest : StringSpec({
    "switching it on and off, back or not" {
        parses(
            "turn it off" to Switch(on = false, back = false),
            "turn it on" to Switch(on = true, back = false),
            "turn it back off" to Switch(on = false, back = true),
            "Turn it back on." to Switch(on = true, back = true),
            "switch it off" to Switch(on = false, back = false),
            "switch that back on" to Switch(on = true, back = true),
            "turn off it" to Switch(on = false, back = false),
            "turn that off" to Switch(on = false, back = false),
            "turn them on" to Switch(on = true, back = false),
            "shut it off" to Switch(on = false, back = false),
            "shut it back off" to Switch(on = false, back = true),
            "turn it off again" to Switch(on = false, back = true),
            "turn it on again" to Switch(on = true, back = true),
            "turn it back on again" to Switch(on = true, back = true),
            "switch it back on again" to Switch(on = true, back = true),
            "shut it off again" to Switch(on = false, back = true),
            "turn off it again" to Switch(on = false, back = true),
        )
    }

    "thanks after, no and sorry before" {
        parses(
            "Turn it back off. Thank you." to Switch(on = false, back = true),
            "turn it off, thanks" to Switch(on = false, back = false),
            "turn it off please thank you" to Switch(on = false, back = false),
            "no, turn it back off" to Switch(on = false, back = true),
            "nope turn it on" to Switch(on = true, back = false),
            "sorry, cancel it" to Remove(delete = false),
            "no sorry cancel it" to Remove(delete = false),
            "no, make it 8" to SetTo("8"),
            "no, the other one" to Correction(null, CorrectionKind.OTHER),
            "no i said erin thanks" to Correction("erin", CorrectionKind.SAID),
        )
        refuses("thank you", "thanks", "no thank you", "sorry", "no sorry")
    }

    "fillers, never mind and actually go" {
        parses(
            "ok turn it off" to Switch(on = false, back = false),
            "okay, turn it off please" to Switch(on = false, back = false),
            "hey can you turn it off" to Switch(on = false, back = false),
            "could you please turn it back on" to Switch(on = true, back = true),
            "um turn it uh off" to Switch(on = false, back = false),
            "just turn it off now" to Switch(on = false, back = false),
            "never mind, turn it on" to Switch(on = true, back = false),
            "nevermind turn it on" to Switch(on = true, back = false),
            "actually, make it 8" to SetTo("8"),
            "oh, what about tomorrow?" to WhatAbout("tomorrow", null, bareAnd = false),
        )
    }

    "adjusting, by its word or not" {
        parses(
            "turn it up" to Adjust(up = true, kind = null),
            "turn it down" to Adjust(up = false, kind = null),
            "turn it back up" to Adjust(up = true, kind = null),
            "turn it back down" to Adjust(up = false, kind = null),
            "turn up it" to Adjust(up = true, kind = null),
            "louder" to Adjust(up = true, kind = AdjustKind.VOLUME),
            "a bit louder" to Adjust(up = true, kind = AdjustKind.VOLUME),
            "make it louder" to Adjust(up = true, kind = AdjustKind.VOLUME),
            "make it a little bit quieter" to Adjust(up = false, kind = AdjustKind.VOLUME),
            "softer please" to Adjust(up = false, kind = AdjustKind.VOLUME),
            "brighter" to Adjust(up = true, kind = AdjustKind.BRIGHTNESS),
            "make it brighter" to Adjust(up = true, kind = AdjustKind.BRIGHTNESS),
            "make it dimmer" to Adjust(up = false, kind = AdjustKind.BRIGHTNESS),
            "a bit more" to Adjust(up = true, kind = null),
            "a little more" to Adjust(up = true, kind = null),
            "a little bit less" to Adjust(up = false, kind = null),
            "make it higher" to Adjust(up = true, kind = null),
            "make it lower" to Adjust(up = false, kind = null),
        )
        // a vague word alone is no adjustment
        refuses("more", "less", "higher", "lower")
    }

    "removing, snoozing and controlling" {
        parses(
            "cancel it" to Remove(delete = false),
            "cancel that" to Remove(delete = false),
            "delete it" to Remove(delete = true),
            "remove it" to Remove(delete = true),
            "get rid of it" to Remove(delete = true),
            "snooze it" to FollowUpForm.Snooze,
            "pause it" to Control(ControlVerb.PAUSE),
            "resume it" to Control(ControlVerb.RESUME),
            "start it again" to Control(ControlVerb.RESUME),
            "reset it" to Control(ControlVerb.RESET),
            "restart it" to Control(ControlVerb.RESTART),
            "start it over" to Control(ControlVerb.RESTART),
        )
    }

    "setting to a value" {
        parses(
            "change it to 8" to SetTo("8"),
            "change it to 8 pm" to SetTo("8 pm"),
            "make it 7" to SetTo("7"),
            "make it 15 minutes" to SetTo("15 minutes"),
            "set it to 50" to SetTo("50"),
            "move it to 6" to SetTo("6"),
            "change that to fifty percent" to SetTo("fifty percent"),
            "turn it up to 7" to SetTo("7"),
        )
        refuses("make it call mom", "change it to you")
    }

    "adding or taking away time" {
        parses(
            "add 5 more minutes" to AddMore("5 minutes", less = false),
            "add five minutes" to AddMore("five minutes", less = false),
            "5 more minutes" to AddMore("5 minutes", less = false),
            "another 5 minutes" to AddMore("5 minutes", less = false),
            "add 2 minutes to it" to AddMore("2 minutes", less = false),
            "give it 2 more minutes" to AddMore("2 minutes", less = false),
            "take 2 minutes off" to AddMore("2 minutes", less = true),
            "take off 30 seconds" to AddMore("30 seconds", less = true),
            "take 2 minutes off it" to AddMore("2 minutes", less = true),
            "extend it by 10 minutes" to AddMore("10 minutes", less = false),
        )
        refuses("add it", "add milk", "5 more")
    }

    "what about, how about and and" {
        parses(
            "what about tomorrow" to WhatAbout("tomorrow", null, bareAnd = false),
            "how about denver" to WhatAbout("denver", null, bareAnd = false),
            "and in paris?" to WhatAbout("paris", "in", bareAnd = true),
            "what about friday at 8" to WhatAbout("friday at 8", null, bareAnd = false),
            "and the weekend" to WhatAbout("the weekend", null, bareAnd = true),
            "and tomorrow?" to WhatAbout("tomorrow", null, bareAnd = true),
            "and in london" to WhatAbout("london", "in", bareAnd = true),
            "and easter?" to WhatAbout("easter", null, bareAnd = true),
            "and the pasta timer?" to WhatAbout("the pasta timer", null, bareAnd = true),
            "what about the next day?" to WhatAbout("the next day", null, bareAnd = false),
            "and the day after" to WhatAbout("the day after", null, bareAnd = true),
            "and the day after that?" to WhatAbout("the day after that", null, bareAnd = true),
            "and the following day" to WhatAbout("the following day", null, bareAnd = true),
            "what about the day before" to WhatAbout("the day before", null, bareAnd = false),
            "and what about saturday" to WhatAbout("saturday", null, bareAnd = false),
            "what about for tuesday" to WhatAbout("tuesday", "for", bareAnd = false),
        )
        refuses(
            // a bare "and" without a preposition or a day word
            "and denver", "and call mom", "and what day is it",
            // a command verb, too many words, or only a pronoun
            "how about you call mom", "what about playing some music by the beatles",
            "what about you", "how about me", "what about it", "what about us",
        )
    }

    "any and anything name what to look for" {
        parses(
            "Any haircut?" to AnyOf("haircut"),
            "anything about the dentist" to AnyOf("the dentist"),
            "is there any yoga" to AnyOf("yoga"),
            "do i have any haircut appointments" to AnyOf("haircut appointments"),
        )
        refuses("anything else", "any of them", "is there anything", "any", "anything you can do")
    }

    "relative days count from the day asked last" {
        for (rest in listOf(
            "the next day", "next day", "the day after", "the day after that", "the following day",
            "the very next day",
        )) {
            FollowUpForms.relativeDays(rest) shouldBe 1
        }
        for (rest in listOf("the day before", "the day before that", "the previous day")) {
            FollowUpForms.relativeDays(rest) shouldBe -1
        }
        for (rest in listOf("the day after tomorrow", "tomorrow", "next week", "the day", "denver")) {
            FollowUpForms.relativeDays(rest) shouldBe null
        }
    }

    "persons: call her, what's his number, back" {
        parses(
            "call her" to AboutPerson(PersonVerb.CALL, back = false),
            "call him" to AboutPerson(PersonVerb.CALL, back = false),
            "call them" to AboutPerson(PersonVerb.CALL, back = false),
            "ring her" to AboutPerson(PersonVerb.CALL, back = false),
            "call her back" to AboutPerson(PersonVerb.CALL, back = true),
            "call them back" to AboutPerson(PersonVerb.CALL, back = true),
            "redial him" to AboutPerson(PersonVerb.CALL, back = true),
            "text her" to AboutPerson(PersonVerb.TEXT, back = false),
            "message him" to AboutPerson(PersonVerb.TEXT, back = false),
            "text him back" to AboutPerson(PersonVerb.TEXT, back = true),
            "reply to her" to AboutPerson(PersonVerb.TEXT, back = true),
            "send her a text" to AboutPerson(PersonVerb.TEXT, back = false),
            "email him" to AboutPerson(PersonVerb.EMAIL, back = false),
            "what's her number" to AboutPerson(PersonVerb.NUMBER, back = false),
            "what s his number" to AboutPerson(PersonVerb.NUMBER, back = false),
            "whats their phone number" to AboutPerson(PersonVerb.NUMBER, back = false),
            "what's her address" to AboutPerson(PersonVerb.ADDRESS, back = false),
            "where does she live" to AboutPerson(PersonVerb.ADDRESS, back = false),
        )
    }

    "sending it on" {
        parses(
            "send it to mom" to SendTo("mom", too = false),
            "send it to mom too" to SendTo("mom", too = true),
            "send that to aaron as well" to SendTo("aaron", too = true),
            "forward it to my sister" to SendTo("my sister", too = false),
            "share it with dad too" to SendTo("dad", too = true),
        )
        // the call control's, and nobody new
        refuses("send it to voicemail", "send it to voice mail", "send it to her", "send it to mom saying hi")
    }

    "places, songs and another one" {
        parses(
            "navigate there" to AboutPlace(PlaceVerb.NAVIGATE),
            "take me there" to AboutPlace(PlaceVerb.NAVIGATE),
            "how do i get there" to AboutPlace(PlaceVerb.NAVIGATE),
            "how far is it" to AboutPlace(PlaceVerb.DISTANCE),
            "what time is it there" to AboutPlace(PlaceVerb.TIME_THERE),
            "what's the weather like there" to AboutPlace(PlaceVerb.WEATHER_THERE),
            "how's the weather there" to AboutPlace(PlaceVerb.WEATHER_THERE),
            "play it again" to AboutSong(SongVerb.PLAY_AGAIN),
            "who sings it" to AboutSong(SongVerb.WHO_SINGS),
            "who sang that" to AboutSong(SongVerb.WHO_SINGS),
            "who's it by" to AboutSong(SongVerb.WHO_SINGS),
            "another one" to Another(null),
            "another" to Another(null),
            "one more" to Another(null),
            "tell me another" to Another(null),
            "tell me another one" to Another(null),
            "do it again" to Another(null),
            "again" to Another(null),
            "another joke" to Another("joke"),
            "one more joke" to Another("joke"),
        )
        // song titles
        refuses("another one bites the dust", "one more time", "play another one", "play it again sam")
    }

    "corrections need a value, and declines are none" {
        parses(
            "no, 8 pm" to Correction("8 pm", CorrectionKind.NO),
            "no boston" to Correction("boston", CorrectionKind.NO),
            "nope, 8" to Correction("8", CorrectionKind.NO),
            "I said Erin" to Correction("erin", CorrectionKind.SAID),
            "no i said erin" to Correction("erin", CorrectionKind.SAID),
            "I meant Boston" to Correction("boston", CorrectionKind.MEANT),
            "i mean boston" to Correction("boston", CorrectionKind.MEANT),
            "not that one, the other" to Correction(null, CorrectionKind.OTHER),
            "not that one" to Correction(null, CorrectionKind.OTHER),
            "the other one" to Correction(null, CorrectionKind.OTHER),
            "no the other one" to Correction(null, CorrectionKind.OTHER),
        )
        refuses(
            "no", "no no", "nope", "no thanks", "no thank you", "no don't", "no do not",
            "no it's fine", "no that's all", "no not now", "no problem", "no stop", "no cancel",
            "no, call mom", "no wait", "no sorry", "no that's wrong", "no that's okay", "no hold on",
            "no hang on", "nope my mistake", "i meant sorry", "i said wait",
        )
    }

    "full commands, the conversation's phrases and undo are no forms" {
        refuses(
            "turn off the flashlight", "turn on wifi", "set an alarm for 7", "what's the weather",
            "call mom", "play dancing queen", "what time is it", "cancel my alarm",
            "stop", "stop it", "never mind", "cancel", "repeat that", "say that again", "ok",
            "thank you", "undo that", "put it back", "turn it back", "please", "",
        )
    }

    "Whisper's lowercase text without punctuation" {
        parses(
            "turn it back off" to Switch(on = false, back = true),
            "what about tomorrow" to WhatAbout("tomorrow", null, bareAnd = false),
            "no 8 pm" to Correction("8 pm", CorrectionKind.NO),
            "whats her number" to AboutPerson(PersonVerb.NUMBER, back = false),
        )
    }

    "log names" {
        Switch(on = false, back = true).logName shouldBe "switch_off_back"
        Switch(on = true, back = false).logName shouldBe "switch_on"
        Adjust(up = true, kind = AdjustKind.VOLUME).logName shouldBe "adjust_up_volume"
        Remove(delete = true).logName shouldBe "delete"
        WhatAbout("paris", "in", bareAnd = true).logName shouldBe "and"
        AboutPerson(PersonVerb.CALL, back = true).logName shouldBe "person_call_back"
        Correction(null, CorrectionKind.OTHER).logName shouldBe "correction_other"
    }
})
