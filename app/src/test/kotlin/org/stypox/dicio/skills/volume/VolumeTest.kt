package org.stypox.dicio.skills.volume

import android.media.AudioManager
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.stypox.dicio.eval.scoreAndWrapResult
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarAskSlotOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarQuestion
import org.stypox.dicio.gar.call.GarRoutedSkill
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.gar.garRankingWinner
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Volume
import java.io.File

private fun number(value: Double, percent: Boolean = false) =
    ResolvedNumber(value, percent, value == Math.floor(value))

/**
 * An AudioManager stand-in: one stream range for all streams, records what was changed. Like
 * AudioManager, it reads 0 while muted, setting 0 mutes, setting more unmutes, and unmuting
 * restores the [index] the stream had.
 */
class FakeVolumeControl(
    var index: Int = 10,
    var muted: Boolean = false,
    private val min: Int = 0,
    private val max: Int = 25,
    override val isFixed: Boolean = false,
    private val refuse: Boolean = false,
) : VolumeControl {
    val streams = mutableListOf<Int>()

    override fun min(stream: Int) = min
    override fun max(stream: Int) = max
    override fun current(stream: Int) = if (muted) 0 else index
    override fun isMuted(stream: Int) = muted

    override fun set(stream: Int, index: Int) {
        if (refuse) throw SecurityException("Not allowed to change Do Not Disturb state")
        streams += stream
        this.index = index
        muted = index == 0
    }

    override fun mute(stream: Int, mute: Boolean) {
        if (refuse) throw SecurityException("Not allowed to change Do Not Disturb state")
        streams += stream
        muted = mute
    }
}

private fun command(
    action: VolumeAction? = null,
    steps: ResolvedNumber? = null,
    level: ResolvedNumber? = null,
    stream: VolumeStream = VolumeStream.MUSIC,
) = VolumeCommand(action, steps, level, stream)

private fun sentence(input: String): Volume =
    Sentences.Volume["en"]!!.score(FakeSkillContext().forInput(input), input).second

private fun skill(control: FakeVolumeControl) = VolumeSkill(
    VolumeInfo,
    Sentences.Volume["en"]!!,
    formatterFactory = { englishFormatter() },
    controlFactory = { control },
)

private val CATALOG by lazy {
    fun source(path: String) = sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()
    ToolCatalog.load(
        source("src/main/gar/tools.yml"),
        source("src/main/sentences/skill_definitions.yml"),
    )
}
private val VOLUME_TOOL by lazy { CATALOG.byName("volume")!! }
private val VOLUME_SET_TOOL by lazy { CATALOG.byName("volume_set")!! }

class VolumeTest : StringSpec({
    "a level up to ten is out of ten, above ten and with percent it is a percentage" {
        VolumeLogic.levelIndex(number(7.0), 0, 25) shouldBe 18 // 70% of 25 = 17.5
        VolumeLogic.levelIndex(number(10.0), 0, 25) shouldBe 25
        VolumeLogic.levelIndex(number(50.0), 0, 25) shouldBe 13
        VolumeLogic.levelIndex(number(50.0, percent = true), 0, 15) shouldBe 8
        VolumeLogic.levelIndex(number(5.0, percent = true), 0, 25) shouldBe 1
        VolumeLogic.levelIndex(number(5.5), 0, 10) shouldBe 6
        VolumeLogic.levelIndex(number(0.0), 0, 25) shouldBe 0
        // clamped to the stream's range
        VolumeLogic.levelIndex(number(0.0), 1, 7) shouldBe 1
        VolumeLogic.levelIndex(number(150.0, percent = true), 0, 25) shouldBe 25
        VolumeLogic.levelIndex(number(-3.0), 0, 25) shouldBe 0
        // a fraction ("half") is a share of the range, not a level out of ten
        VolumeLogic.levelIndex(number(0.5), 0, 25) shouldBe 13
        VolumeLogic.levelIndex(number(0.25), 0, 20) shouldBe 5
    }

    "steps are a count of at least one, or a percentage of the range" {
        VolumeLogic.stepCount(null, 25) shouldBe 1
        VolumeLogic.stepCount(number(3.0), 25) shouldBe 3
        VolumeLogic.stepCount(number(0.0), 25) shouldBe 1
        VolumeLogic.stepCount(number(40.0), 25) shouldBe 25
        VolumeLogic.stepCount(number(20.0, percent = true), 25) shouldBe 5
        VolumeLogic.stepCount(number(1.0, percent = true), 25) shouldBe 1
    }

    "the level is said as a percentage of the maximum" {
        VolumeLogic.percentOf(0, 25) shouldBe 0
        VolumeLogic.percentOf(13, 25) shouldBe 52
        VolumeLogic.percentOf(25, 25) shouldBe 100
        VolumeLogic.percentOf(3, 7) shouldBe 43
        VolumeLogic.percentOf(3, 0) shouldBe 0
    }

    "up and down move by steps within the range" {
        val control = FakeVolumeControl(index = 10)
        VolumeLogic.apply(control, command(VolumeAction.UP)) shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 44, muted = false)
        control.index shouldBe 11
        VolumeLogic.apply(control, command(VolumeAction.DOWN, steps = number(3.0)))
        control.index shouldBe 8
        VolumeLogic.apply(control, command(VolumeAction.UP, steps = number(100.0)))
        control.index shouldBe 25
        VolumeLogic.apply(control, command(VolumeAction.DOWN, steps = number(40.0, percent = true)))
        control.index shouldBe 15
        control.streams.toSet() shouldBe setOf(AudioManager.STREAM_MUSIC)
    }

    "turning down to zero reports muted, turning up unmutes" {
        val control = FakeVolumeControl(index = 1)
        VolumeLogic.apply(control, command(VolumeAction.DOWN, steps = number(2.0))) shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 0, muted = true)

        // up from the level the stream had before it was muted, not from 0
        val muted = FakeVolumeControl(index = 10, muted = true)
        VolumeLogic.apply(muted, command(VolumeAction.UP)) shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 44, muted = false)
        muted.muted shouldBe false
        muted.index shouldBe 11
    }

    "down on a muted stream keeps it muted at its level" {
        val control = FakeVolumeControl(index = 10, muted = true)
        VolumeLogic.apply(control, command(VolumeAction.DOWN)) shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 0, muted = true)
        control.index shouldBe 10
        VolumeLogic.apply(control, command(VolumeAction.UNMUTE)) shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 40, muted = false)
    }

    "unmute makes a stream at zero audible" {
        val control = FakeVolumeControl(index = 0, muted = true)
        VolumeLogic.apply(control, command(VolumeAction.UNMUTE)) shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 4, muted = false)
        control.index shouldBe 1

        val alarm = FakeVolumeControl(index = 0, min = 1, max = 7)
        VolumeLogic.apply(alarm, command(VolumeAction.UNMUTE, stream = VolumeStream.ALARM))
        alarm.index shouldBe 1
    }

    "a level, max, mute and unmute" {
        val control = FakeVolumeControl(index = 3, muted = true)
        VolumeLogic.apply(control, command(level = number(50.0, percent = true)))
        control.index shouldBe 13
        control.muted shouldBe false

        // a level wins over up/down: "turn the volume up to seventy percent"
        VolumeLogic.apply(control, command(VolumeAction.UP, level = number(70.0, percent = true)))
        control.index shouldBe 18

        VolumeLogic.apply(control, command(VolumeAction.MUTE)) shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 0, muted = true)
        VolumeLogic.apply(control, command(VolumeAction.UNMUTE)) shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 72, muted = false)
        VolumeLogic.apply(control, command(VolumeAction.MAX, level = number(2.0)))
        control.index shouldBe 25
    }

    "without action or level the volume is only reported" {
        val control = FakeVolumeControl(index = 5)
        VolumeLogic.apply(control, command(steps = number(3.0), stream = VolumeStream.RING)) shouldBe
                VolumeResult.Level(VolumeStream.RING, 20, muted = false)
        control.streams shouldBe listOf()
    }

    "the named stream is changed" {
        for ((stream, streamType) in listOf(
            VolumeStream.MUSIC to AudioManager.STREAM_MUSIC,
            VolumeStream.RING to AudioManager.STREAM_RING,
            VolumeStream.ALARM to AudioManager.STREAM_ALARM,
            VolumeStream.NOTIFICATION to AudioManager.STREAM_NOTIFICATION,
        )) {
            for (command in listOf(
                command(VolumeAction.UP, stream = stream),
                command(VolumeAction.MUTE, stream = stream),
                command(level = number(3.0), stream = stream),
            )) {
                val control = FakeVolumeControl()
                VolumeLogic.apply(control, command)
                withClue(command) { control.streams shouldBe listOf(streamType) }
            }
        }
    }

    "Android refusing the change and a fixed volume are reported" {
        VolumeLogic.apply(
            FakeVolumeControl(refuse = true),
            command(VolumeAction.MUTE, stream = VolumeStream.RING),
        ) shouldBe VolumeResult.NotAllowed(VolumeStream.RING)
        VolumeLogic.apply(FakeVolumeControl(isFixed = true), command(VolumeAction.UP)) shouldBe
                VolumeResult.Fixed
    }

    "the model's slots become a command" {
        VolumeTool.commandFromSlots(mapOf()) shouldBe command()
        VolumeTool.commandFromSlots(
            mapOf("action" to "down", "steps" to number(2.0), "level" to null, "stream" to "ring")
        ) shouldBe command(VolumeAction.DOWN, steps = number(2.0), stream = VolumeStream.RING)
        VolumeTool.commandFromSlots(mapOf("level" to number(30.0, percent = true))) shouldBe
                command(level = number(30.0, percent = true))
    }

    "Dicio's matches become the tool's raw slots" {
        fun raw(sentence: Volume, stream: VolumeStream? = null) =
            VolumeTool.rawSlotsOf(SpokenVolume(sentence, stream))

        raw(Volume.VolumeUp) shouldBe mapOf("action" to "up")
        raw(Volume.VolumeDownTimes(" three "), VolumeStream.RING) shouldBe
                mapOf("action" to "down", "steps" to "three", "stream" to "ring")
        raw(Volume.VolumeUpTimes("a little bit")) shouldBe mapOf("action" to "up")
        raw(Volume.VolumeSet("fifty percent"), VolumeStream.ALARM) shouldBe
                mapOf("level" to "fifty percent", "stream" to "alarm")
        raw(Volume.VolumeSet("the max")) shouldBe mapOf("action" to "max")
        raw(Volume.VolumeSet(" half ")) shouldBe mapOf("level" to "fifty percent")
        raw(Volume.Mute, VolumeStream.NOTIFICATION) shouldBe
                mapOf("action" to "mute", "stream" to "notification")
        raw(Volume.Unmute) shouldBe mapOf("action" to "unmute")
        raw(Volume.Max) shouldBe mapOf("action" to "max")
    }

    "the stream named in the input" {
        VolumeStream.namedIn("turn up the volume") shouldBe null
        VolumeStream.namedIn("make the music louder") shouldBe null
        VolumeStream.namedIn("turn the ringer volume down") shouldBe VolumeStream.RING
        VolumeStream.namedIn("set the alarm volume to ten") shouldBe VolumeStream.ALARM
        VolumeStream.namedIn("mute notifications") shouldBe VolumeStream.NOTIFICATION
    }

    "only inputs about volume match" {
        val skill = skill(FakeVolumeControl())
        for (input in listOf("set the temperature to twenty", "turn up the brightness")) {
            withClue(input) {
                skill.score(FakeSkillContext().forInput(input), input).first shouldBe
                        AlwaysWorstScore
            }
        }
    }

    "the English sentences" {
        val cases = listOf(
            "turn up the volume" to mapOf("action" to "up"),
            "volume down" to mapOf("action" to "down"),
            "make the music louder" to mapOf("action" to "up"),
            "turn up the ring volume" to mapOf("action" to "up", "stream" to "ring"),
            "turn the volume up by three steps" to mapOf("action" to "up", "steps" to "three"),
            "lower the alarm volume by two" to
                    mapOf("action" to "down", "steps" to "two", "stream" to "alarm"),
            "set the volume to fifty percent" to mapOf("level" to "fifty percent"),
            "turn the volume up to seventy percent" to mapOf("level" to "seventy percent"),
            "volume to seven" to mapOf("level" to "seven"),
            "set the notification volume to twenty percent" to
                    mapOf("level" to "twenty percent", "stream" to "notification"),
            "mute the music" to mapOf("action" to "mute"),
            "turn off the ring volume" to mapOf("action" to "mute", "stream" to "ring"),
            "unmute the volume" to mapOf("action" to "unmute"),
            "max volume" to mapOf("action" to "max"),
            "turn the volume all the way up" to mapOf("action" to "max"),
        )
        for ((input, slots) in cases) {
            val (score, spoken) = skill(FakeVolumeControl())
                .score(FakeSkillContext().forInput(input), input)
            withClue("$input ($score)") {
                VolumeTool.rawSlotsOf(spoken as SpokenVolume) shouldBe slots
                score.scoreIn01Range() shouldBeGreaterThan 0.85f
            }
        }
        sentence("turn the volume up by three steps").shouldBeInstanceOf<Volume.VolumeUpTimes>()
        sentence("volume to seven").shouldBeInstanceOf<Volume.VolumeSet>()
    }

    "a Dicio match is resolved and run" {
        val control = FakeVolumeControl(index = 10)
        val input = "set the volume to fifty percent"
        val ctx = FakeSkillContext().forInput(input)
        val skill = skill(control)
        val (_, data) = skill.score(ctx, input)
        skill.generateOutput(ctx, data).shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 52, muted = false)
        control.index shouldBe 13
    }

    "half is half of the range, on both paths" {
        val control = FakeVolumeControl(index = 3)
        val input = "set the volume to half"
        val ctx = FakeSkillContext().forInput(input)
        val skill = skill(control)
        skill.generateOutput(ctx, skill.score(ctx, input).second)
            .shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 52, muted = false)

        // the model's "half", which the number resolver reads as 0.5
        val routed = GarRoutedSkill(
            VolumeInfo,
            skill(FakeVolumeControl(index = 3)),
            confirm = false,
            formatterFactory = { englishFormatter() },
        )
        routed.generateOutput(
            FakeSkillContext(),
            GarCall(VOLUME_SET_TOOL, mapOf("level" to "half"), ""),
        ).shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 52, muted = false)
    }

    "a number GAR can't read is asked for, and the answer runs the command" {
        val control = FakeVolumeControl(index = 10)
        val input = "set the volume to loud"
        val ctx = FakeSkillContext().forInput(input)
        val skill = skill(control)
        val ask = skill.generateOutput(ctx, skill.score(ctx, input).second)
            .shouldBeInstanceOf<GarAskSlotOutput>()
        ask.slot.name shouldBe "level"
        control.streams shouldBe listOf()

        val plan = ask.getInteractionPlan(ctx).shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
        val answerSkill = plan.nextSkills[0]
        val answerCtx = FakeSkillContext().forInput("eighty percent")
        val output = answerSkill.scoreAndWrapResult(answerCtx, "eighty percent").generateOutput(answerCtx)
        output.shouldBeInstanceOf<GarFollowUpOutput>().output
            .shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 80, muted = false)
    }

    "a set sentence without a level asks what level, and the answer sets it" {
        for (input in listOf("set the volume", "change the volume", "adjust the volume")) {
            val control = FakeVolumeControl(index = 10)
            val ctx = FakeSkillContext().forInput(input)
            val skill = skill(control)
            val (score, data) = skill.score(ctx, input)
            withClue("$input ($score)") {
                garRankingWinner(input).first shouldBe VolumeInfo.id
                data.shouldBeInstanceOf<SpokenVolume>().sentence.shouldBeInstanceOf<Volume.VolumeSet>()
                // "What level?", the question for a number slot named level
                val ask = skill.generateOutput(ctx, data).shouldBeInstanceOf<GarAskSlotOutput>()
                ask.slot.name shouldBe "level"
                ask.slot.kind shouldBe SlotKind.NUMBER
                ask.question shouldBe GarQuestion.Value(null)
                control.streams shouldBe listOf()

                val plan = ask.getInteractionPlan(ctx)
                    .shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
                val answerCtx = FakeSkillContext().forInput("fifty percent")
                plan.nextSkills[0].scoreAndWrapResult(answerCtx, "fifty percent")
                    .generateOutput(answerCtx)
                    .shouldBeInstanceOf<GarFollowUpOutput>().output
                    .shouldBeInstanceOf<VolumeOutput>().result shouldBe
                        VolumeResult.Level(VolumeStream.MUSIC, 52, muted = false)
                control.index shouldBe 13
            }
        }

        // "max" answers it too, as the volume sentences read it
        val control = FakeVolumeControl(index = 10)
        val ctx = FakeSkillContext().forInput("set the volume")
        val skill = skill(control)
        val ask = skill.generateOutput(ctx, skill.score(ctx, "set the volume").second)
            .shouldBeInstanceOf<GarAskSlotOutput>()
        val plan = ask.getInteractionPlan(ctx).shouldBeInstanceOf<InteractionPlan.StartSubInteraction>()
        val answerCtx = FakeSkillContext().forInput("max")
        plan.nextSkills[0].scoreAndWrapResult(answerCtx, "max").generateOutput(answerCtx)
            .shouldBeInstanceOf<GarFollowUpOutput>().output
            .shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 100, muted = false)
    }

    "only a set sentence without a level asks for one: a question reports the volume" {
        VolumeTool.specOf(SpokenVolume(Volume.VolumeSet(null), null)) shouldBe VolumeTool.SET_SPEC
        VolumeTool.specOf(SpokenVolume(Volume.VolumeSet(" the "), VolumeStream.RING)) shouldBe
                VolumeTool.SET_SPEC
        VolumeTool.specOf(SpokenVolume(Volume.VolumeSet("fifty percent"), null)) shouldBe
                VolumeTool.SET_SPEC
        VolumeTool.specOf(SpokenVolume(Volume.VolumeSet(null), null, question = true)) shouldBe
                VolumeTool.SPEC
        VolumeTool.specOf(SpokenVolume(Volume.VolumeSet("the max"), null)) shouldBe VolumeTool.SPEC
        VolumeTool.specOf(SpokenVolume(Volume.VolumeUp, null)) shouldBe VolumeTool.SPEC
        VolumeTool.specOf(SpokenVolume(Volume.Max, null)) shouldBe VolumeTool.SPEC
        VolumeTool.SET_SPEC.slots.filter { it.required }.map { it.name } shouldBe listOf("level")
        VolumeTool.SPEC.slots.filter { it.required } shouldBe listOf()

        for (input in listOf(
            "what's the volume", "what is the volume at", "is the volume set to max",
            "how loud is the volume",
        )) {
            withClue(input) { VolumeTool.asksAboutVolume(input) shouldBe true }
        }
        for (input in listOf(
            "set the volume", "change the volume", "can you set the volume",
            "please change the volume", "volume to seven", "turn up the volume",
        )) {
            withClue(input) { VolumeTool.asksAboutVolume(input) shouldBe false }
        }

        val control = FakeVolumeControl(index = 10)
        skill(control).generateOutput(
            FakeSkillContext(),
            SpokenVolume(Volume.VolumeSet(null), null, question = true),
        ).shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 40, muted = false)
        control.streams shouldBe listOf()
    }

    "a named stream is changed on the fast path" {
        val control = FakeVolumeControl(index = 10)
        val input = "turn the alarm volume down by two"
        val ctx = FakeSkillContext().forInput(input)
        val skill = skill(control)
        skill.generateOutput(ctx, skill.score(ctx, input).second)
            .shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.ALARM, 32, muted = false)
        control.streams shouldBe listOf(AudioManager.STREAM_ALARM)
    }

    "the model's calls of the volume tools run with resolved values" {
        val control = FakeVolumeControl(index = 10)
        val routed = GarRoutedSkill(
            VolumeInfo,
            skill(control),
            confirm = true,
            formatterFactory = { englishFormatter() },
        )
        routed.generateOutput(
            FakeSkillContext(),
            GarCall(VOLUME_TOOL, mapOf("action" to "up"), ""),
        ).shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 44, muted = false)
        routed.generateOutput(
            FakeSkillContext(),
            GarCall(VOLUME_SET_TOOL, mapOf("level" to "thirty percent"), ""),
        ).shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 32, muted = false)
        // the model often drops "percent": a plain number above ten is a percentage anyway
        routed.generateOutput(
            FakeSkillContext(),
            GarCall(VOLUME_SET_TOOL, mapOf("level" to "eighty"), ""),
        ).shouldBeInstanceOf<VolumeOutput>().result shouldBe
                VolumeResult.Level(VolumeStream.MUSIC, 80, muted = false)
        control.streams.toSet() shouldBe setOf(AudioManager.STREAM_MUSIC)

        // a level that isn't a number is asked for
        routed.generateOutput(
            FakeSkillContext(),
            GarCall(VOLUME_SET_TOOL, mapOf("level" to "up"), ""),
        ).shouldBeInstanceOf<GarAskSlotOutput>().slot.name shouldBe "level"
    }

    "the catalog's volume tools use the slots used for Dicio's matches" {
        val spec = VolumeTool.SPEC.slots.associateBy { it.name }
        for (tool in listOf(VOLUME_TOOL, VOLUME_SET_TOOL)) {
            withClue(tool.name) {
                for (slot in tool.slots) {
                    val specSlot = spec[slot.name].shouldNotBeNull()
                    slot.kind shouldBe specSlot.kind
                    specSlot.enumValues.containsAll(slot.enumValues) shouldBe true
                }
                tool.skillId shouldBe VolumeInfo.id
                tool.sentence shouldBe null
                tool.confirm shouldBe false
                tool.onLockScreen shouldBe true
            }
        }
    }
})
