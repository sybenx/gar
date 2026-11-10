package cc.aaro.gar.router.format

/**
 * Test helper: turns the (non-recursive) GBNF subset GAR generates into a Java regex, so tests
 * can check which outputs a grammar accepts. Supports string literals with escapes, rule
 * references, token references (`<tool_call>`, matched as their text), groups, `|` and `?`.
 * Fails on undefined rules, recursion and unknown syntax, which doubles as a shape check.
 */
object GbnfRegex {
    fun compile(grammar: String): Regex {
        val rules = grammar.lines().filter { it.isNotBlank() }.associate { line ->
            val (name, body) = line.split(" ::= ", limit = 2)
            name to body
        }
        require("root" in rules) { "no root rule" }
        return Regex(expand("root", rules, setOf()))
    }

    private fun expand(name: String, rules: Map<String, String>, stack: Set<String>): String {
        require(name !in stack) { "recursive rule $name" }
        val body = rules[name] ?: throw IllegalArgumentException("undefined rule $name")
        return "(?:" + Parser(body, rules, stack + name).expression() + ")"
    }

    private class Parser(val s: String, val rules: Map<String, String>, val stack: Set<String>) {
        var pos = 0

        fun expression(): String {
            val alternatives = mutableListOf(sequence())
            while (peek() == '|') {
                pos++
                alternatives += sequence()
            }
            skip()
            require(pos >= s.length || s[pos] == ')') { "unexpected '${s.substring(pos)}'" }
            return alternatives.joinToString("|")
        }

        private fun sequence(): String {
            val out = StringBuilder()
            while (true) {
                val c = peek() ?: break
                if (c == '|' || c == ')') break
                var item = when (c) {
                    '"' -> literal()
                    '(' -> {
                        pos++
                        val inner = expression()
                        require(peek() == ')') { "missing )" }
                        pos++
                        "(?:$inner)"
                    }
                    '<' -> {
                        val end = s.indexOf('>', pos)
                        val token = s.substring(pos, end + 1)
                        pos = end + 1
                        Regex.escape(token)
                    }
                    else -> {
                        val start = pos
                        while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '-')) pos++
                        require(pos > start) { "unexpected '${s[pos]}' in '$s'" }
                        expand(s.substring(start, pos), rules, stack)
                    }
                }
                if (pos < s.length && s[pos] == '?') {
                    pos++
                    item = "(?:$item)?"
                }
                out.append(item)
            }
            return out.toString()
        }

        private fun literal(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (s[pos] != '"') {
                if (s[pos] == '\\') {
                    pos++
                    out.append(when (val e = s[pos]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '"', '\\' -> e
                        'x' -> s.substring(pos + 1, pos + 3).toInt(16).toChar().also { pos += 2 }
                        else -> throw IllegalArgumentException("unknown escape \\$e")
                    })
                } else {
                    out.append(s[pos])
                }
                pos++
            }
            pos++ // closing quote
            return Regex.escape(out.toString())
        }

        private fun peek(): Char? {
            skip()
            return s.getOrNull(pos)
        }

        private fun skip() {
            while (pos < s.length && s[pos] == ' ') pos++
        }
    }
}
