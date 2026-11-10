package cc.aaro.gar.router.format

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private fun ParsedOutput.malformed(): String {
    shouldBeInstanceOf<ParsedOutput.Malformed>()
    return (this as ParsedOutput.Malformed).reason
}

class ParseTest : StringSpec({
    val hermes = HermesFormat()
    val fg = FunctionGemmaFormat()
    val tools = testTools

    "hermes: well-formed calls" {
        hermes.parse("<tool_call>\n{\"name\": \"timer_set\", \"arguments\": {\"duration\": \"ten minutes\", " +
            "\"name\": \"pasta\"}}\n</tool_call>", tools) shouldBe
            ParsedOutput.Call(timerSet, mapOf("duration" to "ten minutes", "name" to "pasta"))
        hermes.parse("<tool_call>\n{\"name\": \"current_time\", \"arguments\": {}}\n</tool_call>", tools) shouldBe
            ParsedOutput.Call(currentTime, mapOf())
        // no arguments key at all
        hermes.parse("<tool_call>{\"name\": \"current_time\"}</tool_call>", tools) shouldBe
            ParsedOutput.Call(currentTime, mapOf())
        // JSON escapes are decoded
        hermes.parse("<tool_call>{\"name\": \"note\", \"arguments\": {\"a\": \"caf\\u00e9 \\\"x\\\"\"}}</tool_call>",
            tools) shouldBe ParsedOutput.Call(note, mapOf("a" to "café \"x\""))
    }

    "hermes: whitespace, missing closing tag, text around the call" {
        hermes.parse("  <tool_call>   {\"name\":\"flashlight\",\"arguments\":{\"state\":\"on\"}}   </tool_call>  ",
            tools) shouldBe ParsedOutput.Call(flashlight, mapOf("state" to "on"))
        hermes.parse("<tool_call>\n{\"name\": \"flashlight\", \"arguments\": {\"state\": \"off\"}}", tools) shouldBe
            ParsedOutput.Call(flashlight, mapOf("state" to "off"))
        hermes.parse("Sure!\n<tool_call>\n{\"name\": \"current_time\", \"arguments\": {}}\n</tool_call><|im_end|>",
            tools) shouldBe ParsedOutput.Call(currentTime, mapOf())
        // only the first of several calls counts
        hermes.parse("<tool_call>{\"name\": \"current_time\"}</tool_call>\n<tool_call>{\"name\": \"flashlight\"}" +
            "</tool_call>", tools) shouldBe ParsedOutput.Call(currentTime, mapOf())
    }

    "hermes: no_match and plain text are no match, blank is malformed" {
        hermes.parse("<tool_call>\n{\"name\": \"no_match\", \"arguments\": {}}\n</tool_call>", tools) shouldBe
            ParsedOutput.NoMatch
        hermes.parse("<tool_call>{\"name\": \"no_match\", \"arguments\": {\"x\": 1}}</tool_call>", tools) shouldBe
            ParsedOutput.NoMatch
        hermes.parse("I can't help with that.", tools) shouldBe ParsedOutput.NoMatch
        hermes.parse("", tools).malformed() shouldContain "empty"
        hermes.parse("  \n ", tools).malformed() shouldContain "empty"
        hermes.parse("<tool_call>\n</tool_call>", tools).malformed() shouldContain "empty tool call"
    }

    "hermes: malformed JSON, unknown tools and bad shapes" {
        hermes.parse("<tool_call>\n{\"name\": \"timer_set\", \"arguments\": {\"duration\": \"ten", tools)
            .malformed() shouldContain "invalid JSON"
        hermes.parse("<tool_call>{name: timer_set}</tool_call>", tools).malformed() shouldContain "invalid JSON"
        hermes.parse("<tool_call>{\"name\": \"x\"} trailing</tool_call>", tools).malformed() shouldContain
            "invalid JSON"
        hermes.parse("<tool_call>[1, 2]</tool_call>", tools).malformed() shouldContain "not a JSON object"
        hermes.parse("<tool_call>{\"arguments\": {}}</tool_call>", tools).malformed() shouldContain "missing tool name"
        hermes.parse("<tool_call>{\"name\": 5}</tool_call>", tools).malformed() shouldContain "missing tool name"
        hermes.parse("<tool_call>{\"name\": \"set_alarm\"}</tool_call>", tools).malformed() shouldContain
            "unknown tool 'set_alarm'"
        // a tool that exists but isn't offered this time
        hermes.parse("<tool_call>{\"name\": \"note\"}</tool_call>", listOf(timerSet)).malformed() shouldContain
            "unknown tool"
        hermes.parse("<tool_call>{\"name\": \"current_time\", \"arguments\": 5}</tool_call>", tools)
            .malformed() shouldContain "arguments"
        hermes.parse("<tool_call>{\"name\": \"current_time\", \"name\": \"flashlight\"}</tool_call>", tools)
            .malformed() shouldContain "duplicate"
    }

    "hermes: wrong types, extra and duplicate keys are dropped" {
        hermes.parse("<tool_call>{\"name\": \"timer_set\", \"arguments\": {\"duration\": 10, \"name\": \"pasta\", " +
            "\"color\": \"red\"}}</tool_call>", tools) shouldBe ParsedOutput.Call(timerSet, mapOf("name" to "pasta"))
        hermes.parse("<tool_call>{\"name\": \"timer_set\", \"arguments\": {\"duration\": \"ten\", " +
            "\"duration\": \"five\", \"name\": null}}</tool_call>", tools) shouldBe ParsedOutput.Call(timerSet, mapOf())
        hermes.parse("<tool_call>{\"name\": \"timer_set\", \"arguments\": {\"duration\": [\"ten\"], \"name\": " +
            "{\"x\": true}}}</tool_call>", tools) shouldBe ParsedOutput.Call(timerSet, mapOf())
        // arguments written as a JSON string
        hermes.parse("<tool_call>{\"name\": \"timer_set\", \"arguments\": \"{\\\"duration\\\": \\\"ten\\\"}\"}" +
            "</tool_call>", tools) shouldBe ParsedOutput.Call(timerSet, mapOf("duration" to "ten"))
        hermes.parse("<tool_call>{\"name\": \"timer_set\", \"arguments\": \"ten minutes\"}</tool_call>", tools)
            .malformed() shouldContain "arguments"
    }

    "hermes: nesting is limited, so deep garbage can't overflow the stack" {
        hermes.parse("<tool_call>{\"name\": \"note\", \"arguments\": {\"a\": " + "[".repeat(10_000), tools)
            .malformed() shouldContain "nested too deeply"
        // 33 levels parse, 34 don't
        hermes.parse("<tool_call>" + "[".repeat(33) + "]".repeat(33), tools) shouldBe
            ParsedOutput.Malformed("tool call is not a JSON object")
        hermes.parse("<tool_call>" + "[".repeat(34) + "]".repeat(34), tools).malformed() shouldContain
            "nested too deeply"
    }

    "hermes: duplicate keys, null arguments, names" {
        hermes.parse("<tool_call>{\"name\": \"timer_set\", \"arguments\": {\"duration\": \"ten minutes\"}, " +
            "\"arguments\": {\"duration\": \"five minutes\"}}</tool_call>", tools) shouldBe
            ParsedOutput.Malformed("duplicate \"arguments\"")
        // the duplicate check comes before the no_match check
        hermes.parse("<tool_call>{\"name\": \"no_match\", \"name\": \"current_time\"}</tool_call>", tools) shouldBe
            ParsedOutput.Malformed("duplicate \"name\"")
        // JSON null is like a missing key
        hermes.parse("<tool_call>{\"name\": \"current_time\", \"arguments\": null}</tool_call>", tools) shouldBe
            ParsedOutput.Call(currentTime, mapOf())
        hermes.parse("<tool_call>{\"name\": \"\"}</tool_call>", tools) shouldBe
            ParsedOutput.Malformed("unknown tool ''")
        // no_match is case-sensitive
        hermes.parse("<tool_call>{\"name\": \"No_Match\"}</tool_call>", tools) shouldBe
            ParsedOutput.Malformed("unknown tool 'No_Match'")
    }

    "hermes: strings, escapes and where the call ends" {
        hermes.parse("<tool_call>{\"name\": \"note\", \"arguments\": {\"a\": \"\\ud83d\\ude00 hi\"}}</tool_call>",
            tools) shouldBe ParsedOutput.Call(note, mapOf("a" to "😀 hi"))
        hermes.parse("<tool_call>{\"name\": \"note\", \"arguments\": {\"a\": \"très bien\"}}</tool_call>",
            tools) shouldBe ParsedOutput.Call(note, mapOf("a" to "très bien"))
        hermes.parse("<tool_call>{\"name\": \"note\", \"arguments\": {\"a\": \"line\nbreak\"}}</tool_call>", tools)
            .malformed() shouldContain "control character"
        hermes.parse("<tool_call>{\"name\": \"current_time\",}</tool_call>", tools).malformed() shouldContain
            "expected a JSON key"
        // the first </tool_call> ends the call, even inside a string
        hermes.parse("<tool_call>{\"name\": \"note\", \"arguments\": {\"a\": \"x</tool_call>y\"}}</tool_call>", tools)
            .malformed() shouldContain "unterminated JSON string"
        // without </tool_call>, the end-of-turn token is trailing text (the grammar always closes the call)
        hermes.parse("<tool_call>\n{\"name\": \"current_time\", \"arguments\": {}}<|im_end|>", tools) shouldBe
            ParsedOutput.Malformed("invalid JSON: trailing text after JSON value")
    }

    "functiongemma: well-formed calls" {
        fg.parse("<start_function_call>call:timer_set{duration:<escape>ten minutes<escape>," +
            "name:<escape>pasta<escape>}<end_function_call>", tools) shouldBe
            ParsedOutput.Call(timerSet, mapOf("duration" to "ten minutes", "name" to "pasta"))
        fg.parse("<start_function_call>call:current_time{}<end_function_call>", tools) shouldBe
            ParsedOutput.Call(currentTime, mapOf())
        // values may contain the separators
        fg.parse("<start_function_call>call:note{a:<escape>x, y}: z<escape>}<end_function_call>", tools) shouldBe
            ParsedOutput.Call(note, mapOf("a" to "x, y}: z"))
    }

    "functiongemma: whitespace, missing end tag, text around the call" {
        fg.parse("  <start_function_call> call: flashlight { state : <escape>on<escape> } <end_function_call>",
            tools) shouldBe ParsedOutput.Call(flashlight, mapOf("state" to "on"))
        fg.parse("<start_function_call>call:flashlight{state:<escape>off<escape>}", tools) shouldBe
            ParsedOutput.Call(flashlight, mapOf("state" to "off"))
        fg.parse("Okay. <start_function_call>call:current_time{}<end_function_call><start_function_response>",
            tools) shouldBe ParsedOutput.Call(currentTime, mapOf())
    }

    "functiongemma: no_match, plain text, blank" {
        fg.parse("<start_function_call>call:no_match{}<end_function_call>", tools) shouldBe ParsedOutput.NoMatch
        fg.parse("I am sorry, but I cannot call your mom.", tools) shouldBe ParsedOutput.NoMatch
        fg.parse("", tools).malformed() shouldContain "empty"
    }

    "functiongemma: malformed calls" {
        fg.parse("<start_function_call>call:timer_set{duration:<escape>ten", tools).malformed() shouldContain
            "unterminated"
        fg.parse("<start_function_call>call:timer_set{duration:<escape>ten<escape>,", tools).malformed() shouldContain
            "truncated"
        fg.parse("<start_function_call>call:timer_set{duration:<escape>ten<escape>", tools).malformed() shouldContain
            "truncated"
        fg.parse("<start_function_call>call:timer_set{duration:10", tools).malformed() shouldContain "truncated"
        fg.parse("<start_function_call>timer_set{}<end_function_call>", tools).malformed() shouldContain "call:"
        fg.parse("<start_function_call>call:{}<end_function_call>", tools).malformed() shouldContain "function name"
        fg.parse("<start_function_call>call:set_alarm{}<end_function_call>", tools).malformed() shouldContain
            "unknown tool"
        fg.parse("<start_function_call>call:timer_set(duration=\"ten\")<end_function_call>", tools)
            .malformed() shouldContain "'{'"
        fg.parse("<start_function_call>call:timer_set{<escape>ten<escape>}<end_function_call>", tools)
            .malformed() shouldContain "name:"
        fg.parse("<start_function_call>call:timer_set{duration:<escape>a<escape> name:<escape>b<escape>}" +
            "<end_function_call>", tools).malformed() shouldContain "expected ','"
    }

    "functiongemma: lenient shapes" {
        fun call(body: String) = fg.parse("<start_function_call>call:$body<end_function_call>", tools)
        call("timer_set{duration:<escape>ten<escape>,}") shouldBe
            ParsedOutput.Call(timerSet, mapOf("duration" to "ten"))
        fg.parse("<start_function_call>call:current_time{} trailing text", tools) shouldBe
            ParsedOutput.Call(currentTime, mapOf())
        // an empty bare value is no string, so it is dropped
        call("timer_set{duration:,name:<escape>pasta<escape>}") shouldBe
            ParsedOutput.Call(timerSet, mapOf("name" to "pasta"))
        call("no_match{x:<escape>y<escape>}") shouldBe ParsedOutput.NoMatch
        // only the first call counts
        fg.parse("<start_function_call>call:current_time{}<end_function_call>" +
            "<start_function_call>call:flashlight{state:<escape>on<escape>}<end_function_call>", tools) shouldBe
            ParsedOutput.Call(currentTime, mapOf())
        // the digit punctuation that normalization keeps, and non-ASCII
        call("note{a:<escape>7:15<escape>,b:<escape>1,000<escape>,c:<escape>très bien<escape>}") shouldBe
            ParsedOutput.Call(note, mapOf("a" to "7:15", "b" to "1,000", "c" to "très bien"))
    }

    "functiongemma: cut-off and unbalanced calls" {
        fg.parse("<start_function_call>call:timer_set", tools) shouldBe
            ParsedOutput.Malformed("missing '{' after function name")
        fg.parse("<start_function_call>call:<start_function_call>call:current_time{}", tools) shouldBe
            ParsedOutput.Malformed("missing function name")
        // complete, but with an unmatched bracket: not a truncated call
        fg.parse("<start_function_call>call:timer_set{duration:10],name:<escape>a<escape>}<end_function_call>",
            tools) shouldBe ParsedOutput.Malformed("expected ',' or '}' in arguments")
    }

    "functiongemma: wrong types, extra and duplicate keys are dropped" {
        fg.parse("<start_function_call>call:timer_set{color:<escape>red<escape>,duration:10," +
            "name:<escape>pasta<escape>}<end_function_call>", tools) shouldBe
            ParsedOutput.Call(timerSet, mapOf("name" to "pasta"))
        fg.parse("<start_function_call>call:timer_set{duration:{x:<escape>1<escape>,y:[1,2]}," +
            "name:<escape>a<escape>,name:<escape>b<escape>}<end_function_call>", tools) shouldBe
            ParsedOutput.Call(timerSet, mapOf())
    }

    "garbage never throws" {
        val samples = listOf("<tool_call>", "</tool_call>", "{", "<start_function_call>", "<escape>", "\u0000",
            "<tool_call>{\"name\": \"\\u12\"}", "<start_function_call>call:note{a:[<escape>", "}}}}", "\"\"\"",
            "<tool_call>" + "{\"a\": ".repeat(5_000), "<start_function_call>call:note{a:" + "[".repeat(10_000),
            "<start_function_call>call:note{a:]]]],}")
        for (s in samples) {
            hermes.parse(s, tools)
            fg.parse(s, tools)
        }
    }
})
