package cc.aaro.gar.router

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

class UtterancesTest : StringSpec({
    "normalize" {
        mapOf(
            "Set a Timer, please!" to "set a timer please",
            "What’s up?" to "what's up",
            "seven o'clock" to "seven o'clock",
            "'quoted' words" to "quoted words",
            "rock 'n' roll" to "rock n roll",
            "james' car" to "james car",
            "  multiple   spaces\n\tand tabs " to "multiple spaces and tabs",
            "7:15 p.m." to "7:15 p m",
            "3.5 or 1,000" to "3.5 or 1,000",
            "the end." to "the end",
            "e-mail" to "e mail",
            "50%" to "50",
            "Café CRÈME" to "café crème",
            "a \"quote\" and a \\ backslash" to "a quote and a backslash",
            "emoji 😀 here" to "emoji here",
            "" to "",
            "?!" to "",
        ).forEach { (input, expected) -> withClue(input) { Utterances.normalize(input) shouldBe expected } }
    }

    "apostrophe variants, combining marks, digit punctuation and other numbers" {
        mapOf(
            "it‘s" to "it's",
            "itʼs" to "it's",
            // U+02BC is a letter: only kept between two word characters, like the other apostrophes
            "rock ʼnʼ roll" to "rock n roll",
            "a ʼ b" to "a b",
            // decomposed é (e + U+0301) and Hindi with spacing and non-spacing marks stay whole
            "cafe\u0301 au lait" to "cafe\u0301 au lait",
            "हिंदी" to "हिंदी",
            "5:" to "5",
            ":5" to "5",
            "1..2" to "1 2",
            "3.5.6" to "3.5.6",
            "٣:٤٥" to "٣:٤٥",
            "90's" to "90's",
            "'90s" to "90s",
            // other numbers (No) are no letters or digits, so they are lost
            "½ hour" to "hour",
            "m²" to "m",
        ).forEach { (input, expected) -> withClue(input) { Utterances.normalize(input) shouldBe expected } }
    }

    "a + that starts a number is kept: international phone numbers" {
        mapOf(
            "+44 7700 900123" to "+44 7700 900123",
            "Call +1 (555) 010-0123." to "call +1 555 010 0123",
            "text (+44)7700" to "text +44 7700",
            "++44" to "+44",
            // not in or after a word, nor before anything but a digit
            "5+3" to "5 3",
            "a+5" to "a 5",
            "c++" to "c",
            "+ 44" to "44",
            "+" to "",
            "it's +5" to "it's +5",
        ).forEach { (input, expected) -> withClue(input) { Utterances.normalize(input) shouldBe expected } }
        // every span of it normalizes to itself, so the model can copy any of them
        val words = Utterances.words(Utterances.normalize("send +44 7700 900123 hi"))
        for (start in words.indices) {
            for (end in start + 1..words.size) {
                val span = words.subList(start, end).joinToString(" ")
                withClue(span) { Utterances.normalize(span) shouldBe span }
            }
        }
        Utterances.isSpan("+44 7700 900123", words) shouldBe true
        Utterances.isSpan("44 7700 900123", words) shouldBe false
    }

    "normalizing twice changes nothing" {
        // LlmRouter re-normalizes slot values and compares them with the normalized utterance's words
        listOf(
            "Set a Timer, please!", "What’s up?", "seven o'clock", "'quoted' words", "rock 'n' roll",
            "james' car", "7:15 p.m.", "3.5 or 1,000", "e-mail", "Café CRÈME", "emoji 😀 here", "?!",
            "it‘s", "itʼs", "rock ʼnʼ roll", "a ʼ b", "ʼtwas", "endsʼ", "cafe\u0301", "हिंदी", "5:", "1..2",
            "3.5.6", "٣:٤٥", "'90s", "½ hour", "a '' b", "x‘’y", "1,,000",
            // U+02BC next to another apostrophe
            "xʼʼy", "xʼ'y", "xʼ’y", "ʼʼtwas",
            // plus signs
            "+44 7700", "++44", "+ 44", "5+3", "a+5", "x'+5", "(+1)555", "+5+6", "xʼ+5", "ʼ+5", "+٣",
        ).forEach { input ->
            val once = Utterances.normalize(input)
            withClue(input) { Utterances.normalize(once) shouldBe once }
        }
    }

    "lowercasing doesn't depend on the default locale" {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            Utterances.normalize("TIME") shouldBe "time" // not the dotless "tıme"
        } finally {
            Locale.setDefault(default)
        }
    }

    "words and spans" {
        Utterances.words("") shouldBe listOf()
        val words = Utterances.words("set a pasta timer for ten minutes")
        words.size shouldBe 7
        Utterances.isSpan("ten minutes", words) shouldBe true
        Utterances.isSpan("set a pasta timer for ten minutes", words) shouldBe true
        Utterances.isSpan("set", words) shouldBe true
        Utterances.isSpan("minutes ten", words) shouldBe false
        Utterances.isSpan("ten minute", words) shouldBe false
        Utterances.isSpan("set a pasta timer for ten minutes please", words) shouldBe false
        Utterances.isSpan("", words) shouldBe false
        Utterances.isSpan("past", words) shouldBe false // not a word, only part of one
    }
})
