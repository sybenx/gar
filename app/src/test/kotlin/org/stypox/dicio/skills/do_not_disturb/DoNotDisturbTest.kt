package org.stypox.dicio.skills.do_not_disturb

import cc.aaro.gar.assist.VoiceActivityResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.DoNotDisturb
import org.stypox.dicio.skills.connectivity.ActivityIntent

/**
 * Do Not Disturb as Android 15+ treats an app that targets API 35+: the app can turn on and off
 * only its own rule, while the user or other rules can keep Do Not Disturb on.
 */
class FakeDoNotDisturbControl(
    var ownRule: Boolean = false,
    var otherSource: Boolean = false,
    var access: Boolean = true,
    var assistant: Boolean = false,
    /** What Settings' voice activity does: null = it can't be started. */
    private val voiceResult: VoiceActivityResult? = VoiceActivityResult.Completed(null),
    /** Whether the voice activity really turns DND off. */
    private val voiceTurnsOff: Boolean = true,
    private val settingsOpen: Boolean = true,
    /** set() changes nothing, as if the system ignored it. */
    private val ignoreSet: Boolean = false,
) : DoNotDisturbControl {
    var voiceActivityStarted = false
    var openedSettings = false
    var openedAccessSettings = false

    override fun isOn() = ownRule || otherSource
    override fun hasAccess() = access

    override fun set(on: Boolean) {
        if (!access) throw SecurityException("Notification policy access denied")
        if (!ignoreSet) ownRule = on
    }

    override fun isActiveAssistant() = assistant

    override suspend fun turnOffWithVoiceActivity(): VoiceActivityResult? {
        voiceActivityStarted = true
        if (voiceResult is VoiceActivityResult.Completed && voiceTurnsOff) {
            ownRule = false
            otherSource = false
        }
        return voiceResult
    }

    override fun openDoNotDisturbSettings(): Boolean {
        openedSettings = true
        return settingsOpen
    }

    override fun openAccessSettings(): Boolean {
        openedAccessSettings = true
        return true
    }
}

private fun sentence(input: String): DoNotDisturb =
    Sentences.DoNotDisturb["en"]!!.score(FakeSkillContext().forInput(input), input).second

class DoNotDisturbTest : StringSpec({
    "query" {
        DoNotDisturbLogic.query(FakeDoNotDisturbControl()) shouldBe DoNotDisturbResult.IS_OFF
        DoNotDisturbLogic.query(FakeDoNotDisturbControl(otherSource = true)) shouldBe
                DoNotDisturbResult.IS_ON
        // no access needed to ask
        DoNotDisturbLogic.query(FakeDoNotDisturbControl(ownRule = true, access = false)) shouldBe
                DoNotDisturbResult.IS_ON
    }

    "turning on" {
        val control = FakeDoNotDisturbControl()
        DoNotDisturbLogic.turnOn(control) shouldBe DoNotDisturbResult.TURNED_ON
        control.ownRule shouldBe true

        DoNotDisturbLogic.turnOn(FakeDoNotDisturbControl(otherSource = true)) shouldBe
                DoNotDisturbResult.ALREADY_ON
        DoNotDisturbLogic.turnOn(FakeDoNotDisturbControl(ignoreSet = true)) shouldBe
                DoNotDisturbResult.FAILED
    }

    "turning on without access opens the page to allow it" {
        val control = FakeDoNotDisturbControl(access = false)
        DoNotDisturbLogic.turnOn(control) shouldBe DoNotDisturbResult.NO_ACCESS
        control.openedAccessSettings shouldBe true
        control.isOn() shouldBe false
    }

    "turning off GAR's own Do Not Disturb needs nothing else" {
        val control = FakeDoNotDisturbControl(ownRule = true, assistant = true)
        DoNotDisturbLogic.turnOff(control) shouldBe DoNotDisturbResult.TURNED_OFF
        control.voiceActivityStarted shouldBe false
        control.openedSettings shouldBe false

        DoNotDisturbLogic.turnOff(FakeDoNotDisturbControl()) shouldBe DoNotDisturbResult.ALREADY_OFF
    }

    "as the active assistant, Settings' voice activity turns off other Do Not Disturb" {
        val control = FakeDoNotDisturbControl(ownRule = true, otherSource = true, assistant = true)
        DoNotDisturbLogic.turnOff(control) shouldBe DoNotDisturbResult.TURNED_OFF
        control.voiceActivityStarted shouldBe true
        control.openedSettings shouldBe false

        // also without policy access
        val noAccess = FakeDoNotDisturbControl(otherSource = true, access = false, assistant = true)
        DoNotDisturbLogic.turnOff(noAccess) shouldBe DoNotDisturbResult.TURNED_OFF
    }

    "when the voice activity fails or doesn't help, the settings open" {
        for (control in listOf(
            FakeDoNotDisturbControl(otherSource = true, assistant = true, voiceResult = null),
            FakeDoNotDisturbControl(
                otherSource = true,
                assistant = true,
                voiceResult = VoiceActivityResult.Aborted("nope"),
            ),
            FakeDoNotDisturbControl(
                otherSource = true,
                assistant = true,
                voiceResult = VoiceActivityResult.Failed("timed out"),
            ),
            // completed, but a rule kept it on
            FakeDoNotDisturbControl(otherSource = true, assistant = true, voiceTurnsOff = false),
        )) {
            DoNotDisturbLogic.turnOff(control) shouldBe DoNotDisturbResult.OFF_IN_SETTINGS
            control.voiceActivityStarted shouldBe true
            control.openedSettings shouldBe true
        }
    }

    "when GAR isn't the assistant, the settings open" {
        val control = FakeDoNotDisturbControl(ownRule = true, otherSource = true)
        DoNotDisturbLogic.turnOff(control) shouldBe DoNotDisturbResult.OFF_IN_SETTINGS
        control.ownRule shouldBe false // GAR's own rule is ended anyway
        control.voiceActivityStarted shouldBe false
        control.openedSettings shouldBe true

        DoNotDisturbLogic.turnOff(
            FakeDoNotDisturbControl(otherSource = true, settingsOpen = false)
        ) shouldBe DoNotDisturbResult.FAILED
    }

    "intents" {
        DoNotDisturbIntents.VOICE_OFF shouldBe ActivityIntent(
            "android.settings.VOICE_CONTROL_DO_NOT_DISTURB_MODE",
            mapOf("android.settings.extra.do_not_disturb_mode_enabled" to false),
        )
        DoNotDisturbIntents.SETTINGS.map { it.action } shouldBe listOf(
            "android.settings.ZEN_MODE_PRIORITY_SETTINGS",
            "android.settings.SOUND_SETTINGS",
        )
        DoNotDisturbIntents.ACCESS_SETTINGS.action shouldBe
                "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"
    }

    "no Dicio permission: access is checked with NotificationManager when acting" {
        // the secure setting Dicio's SecurePermission reads is empty on Android 15+
        DoNotDisturbInfo.neededPermissions shouldBe listOf()
    }

    "the English sentences" {
        sentence("turn on do not disturb") shouldBe DoNotDisturb.TurnOn
        sentence("enable do not disturb mode") shouldBe DoNotDisturb.TurnOn
        sentence("put my phone in do not disturb") shouldBe DoNotDisturb.TurnOn
        sentence("turn do not disturb off") shouldBe DoNotDisturb.TurnOff
        sentence("get me out of do not disturb") shouldBe DoNotDisturb.TurnOff
        sentence("is do not disturb on") shouldBe DoNotDisturb.Query
        sentence("am i on do not disturb") shouldBe DoNotDisturb.Query
    }

    "a change is said differently from a state" {
        DoNotDisturbOutput.messageFor(DoNotDisturbResult.TURNED_ON) shouldNotBe
                DoNotDisturbOutput.messageFor(DoNotDisturbResult.IS_ON)
        DoNotDisturbOutput.messageFor(DoNotDisturbResult.TURNED_OFF) shouldNotBe
                DoNotDisturbOutput.messageFor(DoNotDisturbResult.IS_OFF)
        DoNotDisturbResult.entries.map { DoNotDisturbOutput.messageFor(it) }.toSet().size shouldBe
                DoNotDisturbResult.entries.size
    }

    "the skill runs the logic on the control" {
        val control = FakeDoNotDisturbControl(otherSource = true)
        val skill = DoNotDisturbSkill(
            DoNotDisturbInfo,
            Sentences.DoNotDisturb["en"]!!,
            controlFactory = { control },
        )
        skill.generateOutput(FakeSkillContext(), DoNotDisturb.Query)
            .shouldBeInstanceOf<DoNotDisturbOutput>().result shouldBe DoNotDisturbResult.IS_ON
        skill.generateOutput(FakeSkillContext(), DoNotDisturb.TurnOff)
            .shouldBeInstanceOf<DoNotDisturbOutput>().result shouldBe
                DoNotDisturbResult.OFF_IN_SETTINGS
    }
})
