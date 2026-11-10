package cc.aaro.gar.router.format

/**
 * Minimal JSON writing and reading for the prompt formats. Writing matches llama.cpp's `tojson`
 * (nlohmann::json `dump()` with `", "` and `": "` separators, insertion order, UTF-8 kept as is),
 * so the Hermes prompt is byte-identical to the chat template rendering. Reading is a small
 * strict parser that also reports duplicate keys, which kotlinx.serialization silently merges.
 */
internal object Json {

    /** Serializes maps (insertion order), lists, strings, booleans, numbers and null. */
    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun quote(s: String): String = StringBuilder().also { writeString(it, s) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(", ")
                    first = false
                    writeString(out, k as String)
                    out.append(": ")
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(", ")
                    write(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: $value")
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    /** Thrown by [read] on malformed input, with a short reason. */
    class ParseException(message: String) : Exception(message)

    /** A parsed JSON object that remembers keys that appeared more than once. */
    class Obj(val values: Map<String, Any?>, val duplicateKeys: Set<String>)

    /**
     * Parses one JSON value from [text] (surrounding whitespace allowed). Objects become [Obj],
     * arrays lists, numbers [Double].
     *
     * @throws ParseException if [text] is not exactly one valid JSON value
     */
    fun read(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (reader.pos != text.length) {
            throw ParseException("trailing text after JSON value")
        }
        return value
    }

    private class Reader(val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos] in " \t\n\r") pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > 32) throw ParseException("JSON nested too deeply")
            if (pos >= s.length) throw ParseException("unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber()
                else throw ParseException("unexpected character '$c' in JSON")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("invalid JSON literal")
            pos += word.length
            return value
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            // toDouble alone would also take "01", "1." and "-.5"
            val number = s.substring(start, pos)
            if (!NUMBER.matches(number)) throw ParseException("invalid JSON number")
            return number.toDouble()
        }

        private fun readObject(depth: Int): Obj {
            pos++ // {
            val values = linkedMapOf<String, Any?>()
            val duplicates = linkedSetOf<String>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return Obj(values, duplicates)
            }
            while (true) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') throw ParseException("expected a JSON key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = readValue(depth + 1)
                if (key in values) duplicates += key
                values[key] = value
                skipWhitespace()
                if (pos >= s.length) throw ParseException("unterminated JSON object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return Obj(values, duplicates)
                    else -> throw ParseException("expected ',' or '}' in JSON object")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val values = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return values
            }
            while (true) {
                skipWhitespace()
                values += readValue(depth + 1)
                skipWhitespace()
                if (pos >= s.length) throw ParseException("unterminated JSON array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return values
                    else -> throw ParseException("expected ',' or ']' in JSON array")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) throw ParseException("unterminated JSON string")
                when (val c = s[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= s.length) throw ParseException("unterminated JSON string")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                // not toIntOrNull(16), which also takes a sign and fullwidth digits
                                val hex = s.substring(pos, minOf(pos + 4, s.length))
                                if (hex.length != 4 || !hex.all { it in HEX_DIGITS }) {
                                    throw ParseException("invalid JSON \\u escape")
                                }
                                out.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("invalid JSON escape")
                        }
                    }
                    else -> if (c < ' ') {
                        throw ParseException("control character in JSON string")
                    } else {
                        out.append(c)
                    }
                }
            }
        }

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) throw ParseException("expected '$c' in JSON")
            pos++
        }
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private const val HEX_DIGITS = "0123456789abcdefABCDEF"
}
