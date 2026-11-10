package org.stypox.dicio.gar.ui

import androidx.compose.runtime.mutableLongStateOf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.MockSkillContext
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences.Media
import org.stypox.dicio.skills.calls.CallEntry
import org.stypox.dicio.skills.calls.CallKind
import org.stypox.dicio.skills.calls.CallLogOutput
import org.stypox.dicio.skills.media.GarMediaOutput
import org.stypox.dicio.skills.media.MediaButton
import org.stypox.dicio.skills.media.MediaOutput
import org.stypox.dicio.skills.media.MediaText
import org.stypox.dicio.skills.timer.TimerAnswer
import org.stypox.dicio.skills.timer.TimerAnswerOutput
import org.stypox.dicio.skills.timer.TimerOutput
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** The English texts the tests read; any other string comes back as its id. */
private val ENGLISH = mapOf(
    R.string.gar_chip_cancel_timer to "Cancel timer",
    R.string.gar_chip_cancel_timer_input to "cancel the timer",
    R.string.gar_chip_show_timers to "Show timers",
    R.string.gar_chip_show_timers_input to "show my timers",
    R.string.gar_chip_show_alarms_input to "show my alarms",
    R.string.gar_chip_cancel_alarm_input to "cancel my %s alarm",
    R.string.gar_chip_set_alarm_input to "set an alarm",
    R.string.gar_chip_tomorrow_input to "what about tomorrow",
    R.string.gar_chip_weekend_input to "weather this weekend",
    R.string.gar_chip_pause_input to "pause",
    R.string.gar_chip_next_input to "next song",
    R.string.gar_chip_resume_input to "resume",
    R.string.gar_chip_call_back to "Call back",
    R.string.gar_chip_call_back_input to "call %s back",
    R.string.gar_chip_reply_input to "reply",
    R.string.gar_chip_call_input to "call %s",
)

private val STRINGS = GarChipStrings { id, args -> (ENGLISH[id] ?: "#$id").format(*args) }

private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneOffset.UTC)

private fun call(name: String?, number: String = "5551234", hidden: Boolean = false) = CallEntry(
    number = number,
    name = name,
    kind = CallKind.MISSED,
    time = Instant.parse("2026-11-02T11:00:00Z"),
    hidden = hidden,
)

class GarChipsTest : StringSpec({
    "a set timer offers to cancel it and to show the timers" {
        val chips = GarChips.dicioChips(TimerOutput.Set(60_000L, mutableLongStateOf(0L), null), STRINGS)
        chips shouldBe listOf(
            GarChip("Cancel timer", "cancel the timer"),
            GarChip("Show timers", "show my timers"),
        )
    }

    "a timer started in the clock app offers to show the timers only, its other answers none" {
        GarChips.dicioChips(TimerAnswerOutput(TimerAnswer.Started(null, 120_000L)), STRINGS)
            .map { it.input } shouldBe listOf("show my timers")
        GarChips.dicioChips(TimerAnswerOutput(TimerAnswer.Stopped), STRINGS).shouldBeEmpty()
    }

    "Dicio's media answer: Pause and Next while playing, nothing for a pause or no session" {
        GarChips.dicioChips(MediaOutput(Media.Play), STRINGS).map { it.input } shouldBe
            listOf("pause", "next song")
        GarChips.dicioChips(MediaOutput(Media.Next), STRINGS).map { it.input } shouldBe
            listOf("pause", "next song")
        GarChips.dicioChips(MediaOutput(Media.Pause), STRINGS).shouldBeEmpty()
        GarChips.dicioChips(MediaOutput(null), STRINGS).shouldBeEmpty()
    }

    "GAR's media answers know playing from paused, and a card with a button gets none" {
        GarMediaOutput(MediaText(R.string.skill_media_play_anything_on, listOf("Spotify")))
            .playing shouldBe true
        GarMediaOutput(MediaText(R.string.skill_media_play_playing_on, listOf("jazz", "Spotify")))
            .playing shouldBe true
        GarMediaOutput(MediaText(R.string.skill_media_paused), speak = false).playing shouldBe false
        GarMediaOutput(MediaText(R.string.skill_media_restart)).playing.shouldBeNull()
        GarChipTable.media(STRINGS, playing = false).map { it.input } shouldBe listOf("resume")
        GarChipTable.media(STRINGS, playing = null).shouldBeEmpty()
        GarMediaOutput(
            MediaText(R.string.skill_media_controls_no_access), button = MediaButton.ALLOW_ACCESS,
        ).suggestions(MockSkillContext).shouldBeEmpty()
    }

    "outputs nobody mapped get no chips" {
        GarChips.dicioChips(TimerOutput.Cancel("Cancelled."), STRINGS).shouldBeEmpty()
    }

    "call back names the one caller the answer is about" {
        val mom = call("Mom")
        CallLogOutput.LastCaller(mom, listOf(mom), NOW).callBackName shouldBe "Mom"
        CallLogOutput.From("mom", mom, NOW).callBackName shouldBe "Mom"
        CallLogOutput.Missed(listOf(mom, call("Mom")), NOW).callBackName shouldBe "Mom"
        GarChipTable.callBack(STRINGS, "Mom") shouldBe listOf(GarChip("Call back", "call Mom back"))
    }

    "no call back without a name, for a hidden number, or for several callers" {
        CallLogOutput.LastCaller(call(null), listOf(), NOW).callBackName.shouldBeNull()
        CallLogOutput.LastCaller(call("Mom", hidden = true), listOf(), NOW).callBackName.shouldBeNull()
        CallLogOutput.LastCaller(null, listOf(), NOW).callBackName.shouldBeNull()
        CallLogOutput.From("dad", null, NOW).callBackName.shouldBeNull()
        CallLogOutput.Missed(listOf(call("Mom"), call("Dad", number = "5559876")), NOW)
            .callBackName.shouldBeNull()
        GarChipTable.callBack(STRINGS, null).shouldBeEmpty()
    }

    "messages from one sender: Reply when the app takes replies, and Call" {
        GarChipTable.messagesRead(STRINGS, "Mom", canReply = true).map { it.input } shouldBe
            listOf("reply", "call Mom")
        GarChipTable.messagesRead(STRINGS, "Mom", canReply = false).map { it.input } shouldBe
            listOf("call Mom")
        GarChipTable.messagesRead(STRINGS, null, canReply = true).shouldBeEmpty()
    }

    "the forecast offers the days it wasn't about" {
        GarChipTable.weather(STRINGS, tomorrow = true, weekend = true).map { it.input } shouldBe
            listOf("what about tomorrow", "weather this weekend")
        GarChipTable.weather(STRINGS, tomorrow = false, weekend = true).map { it.input } shouldBe
            listOf("weather this weekend")
    }

    "alarms: show or cancel the one set, set one after showing them" {
        GarChipTable.alarmSet(STRINGS, LocalTime.of(7, 0)).map { it.input } shouldBe
            listOf("show my alarms", "cancel my 7 am alarm")
        // named, so that it still cancels this alarm after the follow-ups' minute
        GarChipTable.alarmSet(STRINGS, LocalTime.of(19, 30))[1].input shouldBe
            "cancel my 7:30 pm alarm"
        GarChipTable.alarmShow(STRINGS).map { it.input } shouldBe listOf("set an alarm")
    }

    "a chip without its own input sends its label" {
        GarChip("What time is it").input shouldBe "What time is it"
    }
})
