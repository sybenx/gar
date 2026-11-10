package cc.aaro.gar.router.format

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private fun rejects(text: String): String =
    withClue(text) { shouldThrow<Json.ParseException> { Json.read(text) }.message!! }

class JsonTest : StringSpec({
    "write escapes like nlohmann's dump" {
        Json.write("a\bb\u000Cc\u0001\u001F") shouldBe "\"a\\bb\\fc\\u0001\\u001f\""
        Json.write(listOf(1L, true, null, 7)) shouldBe "[1, true, null, 7]"
        Json.write(mapOf("b" to "x", "a" to listOf<Any>())) shouldBe "{\"b\": \"x\", \"a\": []}"
        shouldThrow<IllegalArgumentException> { Json.write(1.5) }
    }

    "read is strict about separators and whitespace" {
        rejects("{\"a\": 1,}") shouldContain "expected a JSON key"
        rejects("[1,]")
        rejects("[1 2]") shouldContain "expected ',' or ']'"
        rejects("{\"a\" 1}") shouldContain "expected ':'"
        rejects("{\"a\": 1} x") shouldContain "trailing text"
        // only space, tab, newline and carriage return are whitespace
        rejects("\u00A0{}")
        rejects("{}\u000B")
        Json.read(" \t\r\n{} \n").shouldBeInstanceOf<Json.Obj>().values shouldBe mapOf()
        rejects("") shouldContain "unexpected end"
        rejects("nul") shouldContain "invalid JSON literal"
    }

    "numbers follow the JSON grammar" {
        mapOf("0" to 0.0, "-0" to -0.0, "10" to 10.0, "1.5" to 1.5, "-2.25" to -2.25, "1e3" to 1000.0,
            "1E+2" to 100.0, "2.5e-1" to 0.25).forEach { (text, value) ->
            withClue(text) { Json.read(text) shouldBe value }
        }
        for (text in listOf("01", "-01", "1.", "-.5", ".5", "+1", "1e", "1.e2", "--1", "1-2", "0x10")) {
            rejects(text)
        }
    }

    "strings and their escapes" {
        Json.read("\"caf\\u00e9 \\\"x\\\" \\/ \\\\ \\b\\f\\n\\r\\t\"") shouldBe "café \"x\" / \\ \b\u000C\n\r\t"
        Json.read("\"\\ud83d\\ude00\"") shouldBe "😀"
        Json.read("\"\\u00E9\"") shouldBe "é"
        for (text in listOf("\"\\u+041\"", "\"\\u-001\"", "\"\\u004\"", "\"\\u00g1\"", "\"\\u００４１\"")) {
            withClue(text) { rejects(text) shouldContain "\\u escape" }
        }
        rejects("\"\\x41\"") shouldContain "invalid JSON escape"
        rejects("\"a\u0001b\"") shouldContain "control character"
        rejects("\"abc") shouldContain "unterminated JSON string"
    }

    "objects remember each repeated key once and keep the last value" {
        val obj = Json.read("{\"a\": 1, \"b\": 2, \"a\": 3, \"a\": 4}").shouldBeInstanceOf<Json.Obj>()
        obj.duplicateKeys shouldBe setOf("a")
        obj.values shouldBe mapOf("a" to 4.0, "b" to 2.0)
        obj.values.keys.toList() shouldBe listOf("a", "b")
    }
})
