package org.stypox.dicio.gar

import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.stypox.dicio.gar.call.garCallFilter
import java.io.File

private fun sourceFile(vararg paths: String): String =
    paths.asSequence().map(::File).firstOrNull { it.isFile }?.readText()
        ?: throw AssertionError("Cannot find ${paths.toList()} from ${File("").absolutePath}")

private val CATALOG = ToolCatalog.load(
    sourceFile("src/main/gar/tools.yml", "app/src/main/gar/tools.yml"),
    sourceFile(
        "src/main/sentences/skill_definitions.yml", "app/src/main/sentences/skill_definitions.yml",
    ),
)

private data class EvalCase(val text: String, val tool: String, val slots: Map<String, List<String>>)

/** The positive cases of gar/eval/cases.yml (see the eval runner for the format). */
private fun positiveEvalCases(): List<EvalCase> {
    val root = Load(LoadSettings.builder().build())
        .loadFromString(sourceFile("../gar/eval/cases.yml", "gar/eval/cases.yml")) as Map<*, *>
    return (root["cases"] as List<*>).map { it as Map<*, *> }
        .filter { it["tool"] != ToolCatalog.NO_MATCH }
        .map { case ->
            EvalCase(
                text = Utterances.normalize(case["text"] as String),
                tool = case["tool"] as String,
                slots = (case["slots"] as Map<*, *>? ?: mapOf<String, Any>()).entries.associate { (k, v) ->
                    k as String to ((v as? List<*>) ?: listOf(v)).map { Utterances.normalize(it.toString()) }
                },
            )
        }
}

private fun call(tool: ToolSpec, utterance: String, vararg slots: Pair<String, String>) =
    RouterOutput.Call(tool, slots.toMap(), listOf(), Utterances.normalize(utterance))

private fun tool(name: String) = CATALOG.byName(name) ?: throw AssertionError("no tool $name")

/** The app's call filter on the real catalog, see gar/DESIGN.md "Call filters". */
class GarCallFilterTest : StringSpec({
    val filter = garCallFilter(FakeSkillContext())

    "the filter keeps every positive eval case with any of its expected slot values" {
        val cases = positiveEvalCases()
        cases.map { it.tool }.toSet() shouldContainAll CATALOG.tools.filter { it.keywords.isNotEmpty() }
            .map { it.name }
        for (case in cases) {
            val tool = tool(case.tool)
            // each accepted value on its own, the other slots with their first value
            val variants = listOf(case.slots.mapValues { it.value.first() }) +
                case.slots.flatMap { (slot, values) ->
                    values.map { value -> case.slots.mapValues { it.value.first() } + (slot to value) }
                }
            for (slots in variants) {
                withClue("${case.text} -> ${case.tool} $slots") {
                    filter.check(RouterOutput.Call(tool, slots, listOf(), case.text)) shouldBe
                        CallVerdict.Keep
                }
            }
        }
    }

    "song_identify calls must ask about what plays now" { // gar:song
        val song = tool("song_identify")
        for (input in listOf(
            "who sings hello", "sing me a song", "track my package", "skip this song",
            "keep playing", "stop the music", "play the last song again", "what's the next song",
            "recommend me a good song", "who sings bohemian rhapsody",
            "what's playing at the movie theater tonight", "what are the lyrics of this song",
        )) {
            withClue(input) {
                filter.check(call(song, input)).shouldBeInstanceOf<CallVerdict.Drop>()
            }
        }
        for (input in listOf(
            "what song is this", "do you know what song this is", "who's singing",
            "what was that song again", "find out what song is playing", "shazam",
        )) {
            withClue(input) { filter.check(call(song, input)) shouldBe CallVerdict.Keep }
        }
    }

    "misroutes the eval saw are dropped" {
        val drops = listOf(
            // keywords
            call(tool("current_time"), "good morning"),
            call(tool("current_time"), "good night"),
            call(tool("date_query"), "what's the capital of france"),
            call(tool("flashlight"), "beep in two minutes", "state" to "on"),
            call(tool("flashlight"), "turn off the countdown", "state" to "off"),
            call(tool("alarm_set"), "ring sarah", "time" to "ring", "label" to "sarah"),
            call(tool("wake_word"), "wake me up at seven", "state" to "on"),
            call(tool("wake_word"), "are you there", "state" to "on"),
            // typed slots
            call(tool("date_query"), "what's the date of the next full moon", "day" to "of the next full moon"),
            call(tool("timer_set"), "kill the timer", "duration" to "kill the timer"),
            call(tool("alarm_set"), "wake me up when the rain stops", "time" to "when the rain stops"),
            call(tool("volume_set"), "set the volume to the max", "level" to "the moon"),
        )
        for (call in drops) {
            withClue("${call.utterance} -> ${call.tool.name} ${call.rawSlots}") {
                filter.check(call).shouldBeInstanceOf<CallVerdict.Drop>().reason shouldContain
                    call.tool.name
            }
        }
    }

    "calls of tools that confirm anyway are kept for recall" {
        // the sms card reads back what would be sent and asks first; reading the notifications
        // is confirmed ("send" and "message" keep "send mom i'm on my way", "read my messages")
        for (call in listOf(
            call(tool("sms_send"), "send an email to my boss", "to" to "my boss"),
            call(tool("sms_send"), "send a picture to dad", "to" to "dad", "message" to "send a picture"),
            call(tool("read_notifications"), "read my last text message"),
        )) {
            withClue("${call.utterance} -> ${call.tool.name}") { filter.check(call) shouldBe CallVerdict.Keep }
        }
    }

    "vague values of a typed slot are kept: GAR asks about them" {
        for (call in listOf(
            call(tool("reminder_add"), "remind me to water the plants later", "what" to "water the plants", "when" to "later"),
            call(tool("reminder_add"), "remind me after lunch to call john", "what" to "call john", "when" to "after lunch"),
            call(tool("timer_set"), "timer for a few minutes", "duration" to "few minutes"),
            call(tool("volume_set"), "set the volume to the minimum", "level" to "minimum"),
            call(tool("alarm_set"), "set an alarm for saturday at nine", "time" to "saturday at nine"),
            // resolved: the next day alone is asked for, "tomorrow's alarm" is the next one
            call(tool("alarm_set"), "set an alarm for tomorrow morning", "time" to "tomorrow morning"),
            call(tool("alarm_cancel"), "turn off tomorrow's alarm", "time" to "tomorrow's alarm"),
            call(tool("alarm_set"), "i need an alarm in twenty minutes", "time" to "in twenty minutes"),
            call(tool("timer_set"), "give me five more minutes", "duration" to "five more minutes"),
            call(tool("calendar_add"), "put a dentist appointment on my calendar for this weekend",
                "title" to "dentist appointment", "when" to "this weekend"),
        )) {
            withClue("${call.utterance} -> ${call.tool.name} ${call.rawSlots}") {
                filter.check(call) shouldBe CallVerdict.Keep
            }
        }
        // still dropped: no value at all, or another tool's value
        for (call in listOf(
            call(tool("timer_set"), "i need a timer", "duration" to "timer"),
            call(tool("alarm_set"), "turn off my alarm", "time" to "alarm"),
            call(tool("date_query"), "what's the weather tomorrow morning", "day" to "tomorrow morning"),
        )) {
            withClue("${call.utterance} -> ${call.tool.name} ${call.rawSlots}") {
                filter.check(call).shouldBeInstanceOf<CallVerdict.Drop>()
            }
        }
    }

    "words that mean a value are rewritten before they are judged" {
        for ((tool, slot, utterance, raw) in listOf(
            listOf("date_query", "day", "what's the date", "date"),
            listOf("date_query", "day", "what day of the week is it", "the week"),
            listOf("date_query", "day", "what's today's date", "today's date"),
            listOf("date_query", "day", "what will the date be in three days", "three days"),
            listOf("volume_set", "level", "can you make the volume thirty percent", "volume thirty percent"),
        )) {
            withClue(utterance) {
                filter.check(call(tool(tool), utterance, slot to raw)) shouldBe CallVerdict.Keep
            }
        }
    }

    "missing and ambiguous values still reach GAR's questions" {
        filter.check(call(tool("alarm_set"), "set an alarm")) shouldBe CallVerdict.Keep
        filter.check(call(tool("alarm_set"), "wake me up at seven", "time" to "seven")) shouldBe
            CallVerdict.Keep
        filter.check(call(tool("timer_set"), "set a timer")) shouldBe CallVerdict.Keep
        // the model left out the unit (eval): GAR asks how long instead of dropping the call
        filter.check(call(tool("timer_set"), "i need a timer for twelve minutes", "duration" to "twelve")) shouldBe
            CallVerdict.Keep
        // "next" is no time, but the alarm skill takes it as the next alarm (GarSlotRewrites)
        filter.check(call(tool("alarm_cancel"), "skip my next alarm", "time" to "next")) shouldBe
            CallVerdict.Keep
    }

    "the model's volume levels are read like the volume sentences read them" {
        for (level in listOf("half", "halfway", "max", "the maximum", "full volume", "seventy percent")) {
            withClue(level) {
                filter.check(call(tool("volume_set"), "set the volume to $level", "level" to level)) shouldBe
                    CallVerdict.Keep
            }
        }
    }

    "in languages without number support nothing is dropped for its slots" {
        val other = garCallFilter(FakeSkillContext(sentencesLanguage = "xx", parserFormatter = null))
        other.check(call(tool("timer_set"), "kill the timer", "duration" to "kill the timer")) shouldBe
            CallVerdict.Keep
    }
})
