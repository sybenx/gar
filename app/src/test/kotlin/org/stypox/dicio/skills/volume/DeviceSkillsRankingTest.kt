package org.stypox.dicio.skills.volume

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.calculator.CalculatorSkill
import org.stypox.dicio.skills.connectivity.ConnectivityInfo
import org.stypox.dicio.skills.connectivity.ConnectivitySkill
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.current_time.CurrentTimeSkill
import org.stypox.dicio.skills.do_not_disturb.DoNotDisturbInfo
import org.stypox.dicio.skills.do_not_disturb.DoNotDisturbSkill
import org.stypox.dicio.skills.flashlight.FlashlightInfo
import org.stypox.dicio.skills.flashlight.FlashlightSkill
import org.stypox.dicio.skills.listening.ListeningInfo
import org.stypox.dicio.skills.listening.ListeningSkill
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.media.MediaSkill
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.navigation.NavigationSkill
import org.stypox.dicio.skills.notify.NotifyInfo
import org.stypox.dicio.skills.notify.NotifySkill
import org.stypox.dicio.skills.open.OpenInfo
import org.stypox.dicio.skills.open.OpenSkill
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.search.SearchSkill
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.telephone.TelephoneSkill
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.timer.TimerSkill

/** Dicio's English skills plus the device skills, ranked like the evaluator does. */
private fun ranker(): SkillRanker {
    val yesNo = Sentences.UtilYesNo["en"]!!
    return SkillRanker(
        listOf(
            SearchSkill(SearchInfo, Sentences.Search["en"]!!),
            OpenSkill(OpenInfo, Sentences.Open["en"]!!),
            CalculatorSkill(
                CalculatorInfo, Sentences.Calculator["en"]!!, Sentences.CalculatorOperators["en"]!!,
            ),
            NavigationSkill(NavigationInfo, Sentences.Navigation["en"]!!),
            TelephoneSkill(TelephoneInfo, Sentences.Telephone["en"]!!, yesNo),
            TimerSkill(TimerInfo, Sentences.Timer["en"]!!, yesNo),
            CurrentTimeSkill(CurrentTimeInfo, Sentences.CurrentTime["en"]!!),
            MediaSkill(MediaInfo, Sentences.Media["en"]!!),
            ListeningSkill(
                ListeningInfo(UserSettingsModule.newDataStoreForPreviews()),
                Sentences.Listening["en"]!!,
            ),
            NotifySkill(NotifyInfo, Sentences.Notify["en"]!!),
            FlashlightSkill(FlashlightInfo, Sentences.Flashlight["en"]!!),
            VolumeSkill(VolumeInfo, Sentences.Volume["en"]!!),
            DoNotDisturbSkill(DoNotDisturbInfo, Sentences.DoNotDisturb["en"]!!),
            ConnectivitySkill(ConnectivityInfo, Sentences.Connectivity["en"]!!),
        ),
        MockSkill(Specificity.LOW, 0.0f),
    )
}

private fun winner(ranker: SkillRanker, input: String): String? =
    ranker.getBest(FakeSkillContext().forInput(input), input)?.skill?.correspondingSkillInfo?.id

class DeviceSkillsRankingTest : StringSpec({
    "the device sentences win" {
        val ranker = ranker()
        val cases = listOf(
            "turn up the volume" to "volume",
            "turn the volume down" to "volume",
            "make the music louder" to "volume",
            "turn up the ring volume" to "volume",
            "turn the volume up by three steps" to "volume",
            "set the volume to fifty percent" to "volume",
            "turn the volume up to seventy percent" to "volume",
            "volume to seven" to "volume",
            "mute the music" to "volume",
            "turn off the volume" to "volume",
            "unmute the volume" to "volume",
            "max volume" to "volume",
            "turn on do not disturb" to "do_not_disturb",
            "turn off do not disturb" to "do_not_disturb",
            "is do not disturb on" to "do_not_disturb",
            "turn on wifi" to "connectivity",
            "turn off the wi fi" to "connectivity",
            "open the wifi settings" to "connectivity",
            "turn on bluetooth" to "connectivity",
            "turn bluetooth off" to "connectivity",
            "open bluetooth settings" to "connectivity",
        )
        for ((input, skill) in cases) {
            withClue(input) { winner(ranker, input) shouldBe skill }
        }
    }

    "other skills keep their sentences" {
        val ranker = ranker()
        val cases = listOf(
            "pause the music" to "media",
            "play the next song" to "media",
            "stop listening" to "listening",
            "mute microphone" to "listening",
            "unmute the microphone" to "listening",
            "turn on the flashlight" to "flashlight",
            "turn off the light" to "flashlight",
            "cancel the pasta timer" to "timer",
            "mute the timer" to "timer",
            "open spotify" to "open",
            "read my notifications" to "notify",
        )
        for ((input, skill) in cases) {
            withClue(input) { winner(ranker, input) shouldBe skill }
        }
    }

    "the device skills don't take other commands" {
        val ranker = ranker()
        val inputs = listOf(
            "mute the microphone",
            "turn on the microphone",
            "turn up the brightness",
            "turn down the heat",
            "set the alarm for seven",
            "set an alarm for seven thirty",
            "change the alarm to eight",
            "turn off the alarm",
            "turn on the lights",
            "set the temperature to twenty",
            "what's the wifi password",
            "turn on airplane mode",
        )
        val device = setOf("volume", "do_not_disturb", "connectivity")
        val stolen = inputs.associateWith { winner(ranker, it) }.filterValues { it in device }
        stolen shouldBe mapOf()
    }
})
