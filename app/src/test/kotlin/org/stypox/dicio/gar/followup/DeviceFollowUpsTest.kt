package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.AdjustKind
import cc.aaro.gar.router.followup.FollowUpForm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.followup.adapters.DeviceFollowUps
import org.stypox.dicio.gar.shouldBeTakenBy
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.device_settings.DeviceAction
import org.stypox.dicio.skills.device_settings.DeviceSetting
import org.stypox.dicio.skills.device_settings.DeviceSettingCommand
import org.stypox.dicio.skills.device_settings.DeviceSettingTool
import org.stypox.dicio.skills.device_settings.OpenSettingsPage
import org.stypox.dicio.skills.device_settings.SettingsPage
import org.stypox.dicio.skills.flashlight.FlashlightOutput
import org.stypox.dicio.skills.volume.SpokenVolume
import org.stypox.dicio.skills.volume.VolumeStream

private fun device(skillId: String, input: String, inputData: Any?, call: GarCall? = null) =
    rememberedBy(DeviceFollowUps, answered(skillId, input, inputData, FakeOutput(inputData), call = call))

private val OFF_BACK = FollowUpForm.Switch(on = false, back = true)
private val ON = FollowUpForm.Switch(on = true, back = false)

class DeviceFollowUpsTest : StringSpec({
    "the field case: turn it back off after the flashlight" {
        val turn = rememberedBy(
            DeviceFollowUps,
            answered("flashlight", "turn on the flashlight", Sentences.Flashlight.TurnOn, FlashlightOutput.Success(true)),
        )
        turn.turn.kind shouldBe GarTurnKind.REVERSIBLE
        turn.turn.facts.setting shouldBe "flashlight"
        val rewrite = rewriteOf(DeviceFollowUps, OFF_BACK, turn).command
        rewrite shouldBe "turn off the flashlight"
        shouldBeTakenBy(rewrite!!, "flashlight")
        rewriteOf(DeviceFollowUps, ON, turn).command shouldBe "turn on the flashlight"
    }

    "a flashlight that failed is not remembered" {
        DeviceFollowUps.remember(
            answered("flashlight", "turn on the flashlight", Sentences.Flashlight.TurnOn, FlashlightOutput.Error(null)),
        ).shouldBeNull()
    }

    "wifi, bluetooth and do not disturb switch back" {
        val wifi = device("connectivity", "turn off wifi", Sentences.Connectivity.WifiOff)
        rewriteOf(DeviceFollowUps, FollowUpForm.Switch(on = true, back = true), wifi).command shouldBe "turn on wifi"
        shouldBeTakenBy("turn on wifi", "connectivity")
        val bluetooth = device("connectivity", "turn on bluetooth", Sentences.Connectivity.BluetoothOn)
        rewriteOf(DeviceFollowUps, OFF_BACK, bluetooth).command shouldBe "turn off bluetooth"
        shouldBeTakenBy("turn off bluetooth", "connectivity")
        val dnd = device("do_not_disturb", "turn on do not disturb", Sentences.DoNotDisturb.TurnOn)
        rewriteOf(DeviceFollowUps, OFF_BACK, dnd).command shouldBe "turn off do not disturb"
        shouldBeTakenBy("turn off do not disturb", "do_not_disturb")
    }

    "dark mode, from a sentence or a confirmed model call" {
        val sentence = device(
            "device_settings", "turn on dark mode",
            DeviceSettingCommand(DeviceSetting.DARK_MODE, DeviceAction.ON),
        )
        rewriteOf(DeviceFollowUps, OFF_BACK, sentence).command shouldBe "turn off dark mode"
        shouldBeTakenBy("turn off dark mode", "device_settings")

        // the model's call, confirmed with "yes": the slots come from the call, not from "yes"
        val call = GarCall(
            tool = DeviceSettingTool.NUMBER_SPEC,
            rawSlots = mapOf(),
            utterance = "make everything dark",
            values = mapOf(
                DeviceSettingTool.SETTING to DeviceSetting.DARK_MODE.slotValue,
                DeviceSettingTool.ACTION to DeviceAction.ON.slotValue,
            ),
        )
        val confirmed = device("device_settings", "yes", true, call)
        confirmed.turn.facts.setting shouldBe "dark mode"
        rewriteOf(DeviceFollowUps, OFF_BACK, confirmed).command shouldBe "turn off dark mode"
    }

    "the brightness goes up, down and to a level" {
        val turn = device(
            "device_settings", "turn up the brightness",
            DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.UP),
        )
        rewriteOf(DeviceFollowUps, FollowUpForm.Adjust(up = false, kind = null), turn).command shouldBe
            "turn down the brightness"
        shouldBeTakenBy("turn down the brightness", "device_settings")
        rewriteOf(DeviceFollowUps, FollowUpForm.Adjust(up = true, kind = null), turn).command shouldBe
            "turn up the brightness"
        rewriteOf(DeviceFollowUps, FollowUpForm.SetTo("50"), turn).command shouldBe "set the brightness to 50 percent"
        shouldBeTakenBy("set the brightness to 50 percent", "device_settings")
        // the brightness has no switch, and a named adjustment is the reach's
        rewriteOf(DeviceFollowUps, OFF_BACK, turn).shouldBeNull()
        rewriteOf(DeviceFollowUps, FollowUpForm.Adjust(up = true, kind = AdjustKind.VOLUME), turn).shouldBeNull()
    }

    "the volume goes up, down, to a level, mutes and unmutes, on its stream" {
        val turn = device("volume", "turn the volume up", SpokenVolume(Sentences.Volume.VolumeUp, null))
        rewriteOf(DeviceFollowUps, FollowUpForm.Adjust(up = true, kind = null), turn).command shouldBe "turn the volume up"
        shouldBeTakenBy("turn the volume up", "volume")
        rewriteOf(DeviceFollowUps, FollowUpForm.Adjust(up = false, kind = null), turn).command shouldBe
            "turn the volume down"
        shouldBeTakenBy("turn the volume down", "volume")
        rewriteOf(DeviceFollowUps, FollowUpForm.SetTo("7"), turn).command shouldBe "set the volume to 7"
        shouldBeTakenBy("set the volume to 7", "volume")
        rewriteOf(DeviceFollowUps, FollowUpForm.Switch(on = false, back = false), turn).command shouldBe "mute the volume"
        shouldBeTakenBy("mute the volume", "volume")
        rewriteOf(DeviceFollowUps, ON, turn).command shouldBe "unmute the volume"
        shouldBeTakenBy("unmute the volume", "volume")

        val ring = device("volume", "turn up the ringer volume", SpokenVolume(Sentences.Volume.VolumeUp, VolumeStream.RING))
        rewriteOf(DeviceFollowUps, FollowUpForm.Adjust(up = false, kind = null), ring).command shouldBe
            "turn the ring volume down"
        shouldBeTakenBy("turn the ring volume down", "volume")
    }

    "the flashlight has no level; a page is no setting to switch" {
        val flashlight = device("flashlight", "turn on the flashlight", Sentences.Flashlight.TurnOn)
        rewriteOf(DeviceFollowUps, FollowUpForm.SetTo("50"), flashlight).shouldBeNull()
        rewriteOf(DeviceFollowUps, FollowUpForm.Adjust(up = true, kind = null), flashlight).shouldBeNull()
        rewriteOf(DeviceFollowUps, FollowUpForm.Remove(delete = false), flashlight).shouldBeNull()

        val page = device("device_settings", "open battery settings", OpenSettingsPage(SettingsPage.entries.first()))
        page.turn.kind shouldBe GarTurnKind.ANSWER
        rewriteOf(DeviceFollowUps, OFF_BACK, page).shouldBeNull()
    }
})
