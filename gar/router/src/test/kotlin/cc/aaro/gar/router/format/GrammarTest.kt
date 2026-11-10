package cc.aaro.gar.router.format

import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

internal fun testTool(name: String, vararg slots: SlotSpec) = ToolSpec(
    name = name,
    skillId = name,
    sentence = null,
    description = "Tool $name.",
    confirm = true,
    slots = slots.toList(),
    examples = listOf(),
)

internal fun text(name: String, required: Boolean = false) = SlotSpec(name, SlotKind.TEXT, required, "")
internal fun enum(name: String, vararg values: String) =
    SlotSpec(name, SlotKind.ENUM, true, "", enumValues = values.toList())

internal val timerSet = testTool("timer_set", SlotSpec("duration", SlotKind.DURATION, true, ""), text("name"))
internal val flashlight = testTool("flashlight", enum("state", "on", "off"))
internal val currentTime = testTool("current_time")
// declared c, a, b: Hermes keeps that order, FunctionGemma sorts it
internal val note = testTool("note", text("c"), text("a"), text("b"))
internal val testTools = listOf(timerSet, flashlight, currentTime, note)

private const val UTTERANCE = "set a pasta timer for ten minutes"

private fun hermes(json: String) = "<tool_call>\n$json\n</tool_call>"
private fun fg(call: String) = "<start_function_call>call:$call<end_function_call>"

private fun accepts(grammar: String, outputs: List<String>, expected: Boolean) {
    val regex = GbnfRegex.compile(grammar)
    for (output in outputs) {
        withClue(output) { regex.matches(output) shouldBe expected }
    }
}

class GrammarTest : StringSpec({
    "hermes: one call of an offered tool, fixed key order, values are word spans" {
        val grammar = HermesFormat().grammar(testTools, UTTERANCE)
        accepts(grammar, listOf(
            hermes("""{"name": "timer_set", "arguments": {"duration": "ten minutes"}}"""),
            hermes("""{"name": "timer_set", "arguments": {"duration": "ten minutes", "name": "pasta"}}"""),
            hermes("""{"name": "timer_set", "arguments": {"duration": "set a pasta timer for ten minutes"}}"""),
            hermes("""{"name": "flashlight", "arguments": {"state": "on"}}"""),
            hermes("""{"name": "current_time", "arguments": {}}"""),
            hermes("""{"name": "no_match", "arguments": {}}"""),
            hermes("""{"name": "note", "arguments": {}}"""),
            hermes("""{"name": "note", "arguments": {"a": "set"}}"""),
            hermes("""{"name": "note", "arguments": {"c": "ten", "a": "set", "b": "minutes"}}"""),
            hermes("""{"name": "note", "arguments": {"c": "ten", "b": "minutes"}}"""),
        ), true)
        accepts(grammar, listOf(
            hermes("""{"name": "timer_set", "arguments": {"duration": "10 minutes"}}"""),
            hermes("""{"name": "timer_set", "arguments": {"duration": "minutes ten"}}"""),
            hermes("""{"name": "timer_set", "arguments": {"duration": "ten minute"}}"""),
            hermes("""{"name": "timer_set", "arguments": {"duration": ""}}"""),
            hermes("""{"name": "timer_set", "arguments": {"duration": " ten"}}"""),
            hermes("""{"name": "timer_set", "arguments": {"name": "pasta", "duration": "ten minutes"}}"""),
            hermes("""{"name": "timer_set", "arguments": {}}"""),
            hermes("""{"name": "timer_set", "arguments": {"duration": "ten", "extra": "x"}}"""),
            hermes("""{"name": "flashlight", "arguments": {"state": "dim"}}"""),
            hermes("""{"name": "flashlight", "arguments": {"state": "set"}}"""),
            hermes("""{"name": "note", "arguments": {"a": "set", "c": "ten"}}"""),
            hermes("""{"name": "unknown", "arguments": {}}"""),
            hermes("""{"name": "no_match", "arguments": {"x": "set"}}"""),
            """{"name": "current_time", "arguments": {}}""",
            hermes("""{"name": "current_time", "arguments": {}}""") + " ",
        ), false)
    }

    "functiongemma: keys in alphabetical order" {
        val grammar = FunctionGemmaFormat().grammar(testTools, UTTERANCE)
        accepts(grammar, listOf(
            fg("timer_set{duration:<escape>ten minutes<escape>}"),
            fg("timer_set{duration:<escape>ten minutes<escape>,name:<escape>pasta timer<escape>}"),
            fg("flashlight{state:<escape>off<escape>}"),
            fg("current_time{}"),
            fg("no_match{}"),
            fg("note{}"),
            fg("note{a:<escape>set<escape>,b:<escape>a<escape>,c:<escape>for<escape>}"),
            fg("note{b:<escape>a<escape>}"),
        ), true)
        accepts(grammar, listOf(
            fg("timer_set{duration:<escape>10 minutes<escape>}"),
            fg("timer_set{name:<escape>pasta<escape>,duration:<escape>ten minutes<escape>}"),
            fg("timer_set{}"),
            fg("flashlight{state:<escape>dim<escape>}"),
            fg("note{c:<escape>set<escape>,a:<escape>for<escape>}"),
            fg("note{a:<escape>set<escape>,}"),
            "call:current_time{}",
        ), false)
    }

    "no words: only no_match, and no span rules" {
        for (format in listOf(HermesFormat(), FunctionGemmaFormat())) {
            val grammar = format.grammar(testTools, "")
            grammar shouldNotContain "span"
            grammar shouldNotContain "call-timer-set"
            val regex = GbnfRegex.compile(grammar)
            regex.matches(hermes("""{"name": "no_match", "arguments": {}}""")) shouldBe (format is HermesFormat)
            regex.matches(fg("no_match{}")) shouldBe (format is FunctionGemmaFormat)
            regex.matches(hermes("""{"name": "current_time", "arguments": {}}""")) shouldBe false
            regex.matches(fg("current_time{}")) shouldBe false
        }
    }

    "a renamed no_match pseudo-tool is declared last, allowed by the grammar and parsed as no match" {
        val other = ToolSchemas.noMatchTool("not_a_command", "Call this for anything else.")
        val hermesOther = HermesFormat(other)
        val fgOther = FunctionGemmaFormat(other)
        hermesOther.staticPrefix(testTools) shouldContain
            """{"type": "function", "function": {"name": "not_a_command", "description": "Call this for anything else.""""
        hermesOther.staticPrefix(testTools) shouldNotContain "no_match"
        fgOther.staticPrefix(testTools) shouldContain
            "<start_function_declaration>declaration:not_a_command{description:<escape>Call this for anything else."
        accepts(hermesOther.grammar(testTools, UTTERANCE),
            listOf(hermes("""{"name": "not_a_command", "arguments": {}}""")), true)
        accepts(hermesOther.grammar(testTools, UTTERANCE),
            listOf(hermes("""{"name": "no_match", "arguments": {}}""")), false)
        accepts(fgOther.grammar(testTools, ""), listOf(fg("not_a_command{}")), true)
        hermesOther.parse(hermes("""{"name": "not_a_command", "arguments": {}}"""), testTools) shouldBe
            ParsedOutput.NoMatch
        fgOther.parse(fg("not_a_command{}"), testTools) shouldBe ParsedOutput.NoMatch
        hermesOther.parse(hermes("""{"name": "no_match", "arguments": {}}"""), testTools)
            .shouldBeInstanceOf<ParsedOutput.Malformed>()
    }

    "tools with enum slots only don't need span rules" {
        HermesFormat().grammar(listOf(flashlight, currentTime), "turn it on") shouldNotContain "span"
    }

    "span rules grow linearly with the utterance" {
        val words = (1..40).map { "w$it" }
        val grammar = HermesFormat().grammar(listOf(timerSet), words.joinToString(" "))
        grammar.lines().count { it.startsWith("s-") } shouldBe 40
        val regex = GbnfRegex.compile(grammar)
        regex.matches(hermes("""{"name": "timer_set", "arguments": {"duration": "w17 w18 w19"}}""")) shouldBe true
        regex.matches(hermes("""{"name": "timer_set", "arguments": {"duration": "w40"}}""")) shouldBe true
        regex.matches(hermes("""{"name": "timer_set", "arguments": {"duration": "w19 w18"}}""")) shouldBe false
    }

    "quotes, backslashes and non-ASCII are escaped for GBNF and JSON" {
        // normalization removes such characters; the grammar must still be safe without it
        val words = "say \"hi\\ café 😀"
        val tools = listOf(note)
        val hermesRegex = GbnfRegex.compile(HermesFormat().grammar(tools, words))
        hermesRegex.matches(hermes("""{"name": "note", "arguments": {"c": "say \"hi\\ café 😀"}}""")) shouldBe true
        hermesRegex.matches(hermes("""{"name": "note", "arguments": {"c": "say "hi\ café"}}""")) shouldBe false
        val fgRegex = GbnfRegex.compile(FunctionGemmaFormat().grammar(tools, words))
        fgRegex.matches(fg("note{c:<escape>say \"hi\\ café 😀<escape>}")) shouldBe true
        // every line is one rule: no raw newline inside a literal
        HermesFormat().grammar(tools, "a\nb").lines().dropLast(1).all { " ::= " in it } shouldBe true
    }

    "enum values with quotes stay valid JSON" {
        val tool = testTool("pick", enum("choice", "say \"x\"", "plain"))
        val regex = GbnfRegex.compile(HermesFormat().grammar(listOf(tool), "a"))
        regex.matches(hermes("""{"name": "pick", "arguments": {"choice": "say \"x\""}}""")) shouldBe true
    }

    "an optional slot before a required one" {
        val lamp = testTool("lamp", text("room"), enum("state", "on", "off"))
        val hermesGrammar = HermesFormat().grammar(listOf(lamp), "kitchen light")
        accepts(hermesGrammar, listOf(
            hermes("""{"name": "lamp", "arguments": {"room": "kitchen", "state": "on"}}"""),
            hermes("""{"name": "lamp", "arguments": {"state": "on"}}"""),
        ), true)
        accepts(hermesGrammar, listOf(
            hermes("""{"name": "lamp", "arguments": {"room": "kitchen"}}"""),
            hermes("""{"name": "lamp", "arguments": {}}"""),
            hermes("""{"name": "lamp", "arguments": {"state": "on", "room": "kitchen"}}"""),
        ), false)
        val fgGrammar = FunctionGemmaFormat().grammar(listOf(lamp), "kitchen light")
        accepts(fgGrammar, listOf(
            fg("lamp{room:<escape>kitchen<escape>,state:<escape>on<escape>}"),
            fg("lamp{state:<escape>off<escape>}"),
        ), true)
        accepts(fgGrammar, listOf(fg("lamp{room:<escape>kitchen<escape>}"), fg("lamp{}")), false)
    }

    "two required slots" {
        val sms = testTool("sms_send", text("to", required = true), text("message", required = true))
        accepts(HermesFormat().grammar(listOf(sms), "text mom i am late"), listOf(
            hermes("""{"name": "sms_send", "arguments": {"to": "mom", "message": "i am late"}}"""),
        ), true)
        accepts(HermesFormat().grammar(listOf(sms), "text mom i am late"), listOf(
            hermes("""{"name": "sms_send", "arguments": {"to": "mom"}}"""),
            hermes("""{"name": "sms_send", "arguments": {"message": "i am late"}}"""),
            hermes("""{"name": "sms_send", "arguments": {}}"""),
        ), false)
        // FunctionGemma: alphabetical, so message first
        accepts(FunctionGemmaFormat().grammar(listOf(sms), "text mom i am late"), listOf(
            fg("sms_send{message:<escape>i am late<escape>,to:<escape>mom<escape>}"),
        ), true)
        accepts(FunctionGemmaFormat().grammar(listOf(sms), "text mom i am late"), listOf(
            fg("sms_send{to:<escape>mom<escape>}"),
            fg("sms_send{to:<escape>mom<escape>,message:<escape>i am late<escape>}"),
        ), false)
    }

    "a single word, and repeated words" {
        val single = HermesFormat().grammar(listOf(timerSet), "pasta")
        single.lines() shouldContain "span ::= s-1"
        single.lines() shouldContain "s-1 ::= \"pasta\""
        accepts(single, listOf(hermes("""{"name": "timer_set", "arguments": {"duration": "pasta"}}""")), true)
        accepts(single, listOf(hermes("""{"name": "timer_set", "arguments": {"duration": "pasta pasta"}}""")), false)

        val repeated = HermesFormat().grammar(listOf(timerSet), "ten ten minutes")
        accepts(repeated, listOf("ten ten", "ten minutes", "ten ten minutes", "ten", "minutes").map {
            hermes("""{"name": "timer_set", "arguments": {"duration": "$it"}}""")
        }, true)
        accepts(repeated, listOf("minutes ten", "ten ten ten", "minutes minutes").map {
            hermes("""{"name": "timer_set", "arguments": {"duration": "$it"}}""")
        }, false)
    }

    "control characters in GBNF literals" {
        CallGrammar.literal("a\tb\rc\u0001\n\"\\") shouldBe "\"a\\tb\\rc\\x01\\n\\\"\\\\\""
        val tool = testTool("pick", enum("choice", "a\tb", "plain"))
        GbnfRegex.compile(HermesFormat().grammar(listOf(tool), "x"))
            .matches(hermes("""{"name": "pick", "arguments": {"choice": "a\tb"}}""")) shouldBe true
        GbnfRegex.compile(FunctionGemmaFormat().grammar(listOf(tool), "x"))
            .matches(fg("pick{choice:<escape>a\tb<escape>}")) shouldBe true
    }

    "a no_match whose name clashes with an offered tool is rejected" {
        for (format in listOf(HermesFormat(ToolSchemas.noMatchTool("current_time", "x")),
            FunctionGemmaFormat(ToolSchemas.noMatchTool("current_time", "x")))) {
            shouldThrow<IllegalArgumentException> { format.grammar(testTools, UTTERANCE) }
            shouldThrow<IllegalArgumentException> { format.staticPrefix(testTools) }
            // fine when the clashing tool isn't offered
            format.grammar(listOf(timerSet), UTTERANCE) shouldContain "call-current-time ::="
        }
    }
})
