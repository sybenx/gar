package cc.aaro.gar.stt

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** One token per word and per punctuation mark, about what Whisper's tokenizer gives names. */
private fun countTokens(text: String): Int = Regex("[A-Za-z']+|[^\\sA-Za-z']").findAll(text).count()

class NamePromptTest : FunSpec({
    test("the prompt is a list with a leading space and a full stop") {
        NamePrompt.text(listOf("Aaron", "Mom", "Sarah Lee")) shouldBe " Aaron, Mom, Sarah Lee."
        NamePrompt.text(listOf("Aaron")) shouldBe " Aaron."
        NamePrompt.text(emptyList()) shouldBe ""
    }

    test("names are kept in order while they fit") {
        // " Aaron, Mom, Sarah Lee." is 7 tokens
        NamePrompt.fit(listOf("Aaron", "Mom", "Sarah Lee", "Erin"), 7, ::countTokens) shouldBe
            listOf("Aaron", "Mom", "Sarah Lee")
        NamePrompt.fit(listOf("Aaron", "Mom"), NamePrompt.MAX_TOKENS, ::countTokens) shouldBe
            listOf("Aaron", "Mom")
    }

    test("a name that doesn't fit is skipped, a shorter one after it still goes in") {
        // " Aaron, Mary Ann Smith." is 6 tokens, " Aaron, Mom." 4
        NamePrompt.fit(listOf("Aaron", "Mary Ann Smith", "Mom"), 5, ::countTokens) shouldBe
            listOf("Aaron", "Mom")
    }

    test("blank names and repeats are left out") {
        NamePrompt.fit(listOf(" Aaron ", "", "  ", "aaron", "AARON", "Mom"), 40, ::countTokens) shouldBe
            listOf("Aaron", "Mom")
    }

    test("each name is counted once, after a space, and counting stops when the prompt is full") {
        val counted = mutableListOf<String>()
        val fitted = NamePrompt.fit(listOf("Aaron", "Mom", "Sarah Lee", "Erin", "Zoe"), 7) {
            counted += it
            countTokens(it)
        }
        fitted shouldBe listOf("Aaron", "Mom", "Sarah Lee")
        counted shouldBe listOf(" Aaron", " Mom", " Sarah Lee")
    }

    test("the count of the names and their punctuation is the prompt's") {
        val names = listOf("Aaron", "Mom", "Sarah Lee", "Mary-Jane", "O'Brien")
        val fitted = NamePrompt.fit(names, NamePrompt.MAX_TOKENS, ::countTokens)
        fitted shouldBe names
        countTokens(NamePrompt.text(fitted)) shouldBe names.sumOf { countTokens(" $it") + 1 }
    }

    test("the cap is short: the owner and a few people, not a phone book") {
        NamePrompt.MAX_TOKENS shouldBe 16
        // " Aaron, Mom, Dad, Sarah, Erin, Mike, Lisa, Tom." is 16 tokens
        val names = listOf("Aaron", "Mom", "Dad", "Sarah", "Erin", "Mike", "Lisa", "Tom", "Zoe")
        NamePrompt.fit(names, NamePrompt.MAX_TOKENS, ::countTokens) shouldBe names.take(8)
        // the first name always goes in when it fits alone
        val long = listOf("Aaron") + (1..30).map { "Mary Ann Smith$it" }
        NamePrompt.fit(long, NamePrompt.MAX_TOKENS, ::countTokens).first() shouldBe "Aaron"
    }

    test("nothing fits in no tokens") {
        NamePrompt.fit(listOf("Aaron"), 0, ::countTokens) shouldBe emptyList()
    }

    test("a long contact list stays within the budget") {
        val names = (1..100).map { "Name$it" }
        val fitted = NamePrompt.fit(names, NamePrompt.MAX_TOKENS, ::countTokens)
        (countTokens(NamePrompt.text(fitted)) <= NamePrompt.MAX_TOKENS) shouldBe true
        fitted shouldBe names.take(fitted.size)
    }

    test("a transcript of name words only, and of one name") {
        val names = listOf("aaron", "mom", "sarah lee")
        NamePrompt.onlyNames("aaron", names) shouldBe true
        NamePrompt.onlyNames("lee sarah", names) shouldBe true
        NamePrompt.onlyNames(" aaron  mom ", names) shouldBe true
        NamePrompt.onlyNames("text aaron", names) shouldBe false
        NamePrompt.onlyNames("", names) shouldBe false
        NamePrompt.onlyNames("aaron", emptyList()) shouldBe false
        NamePrompt.oneName(" sarah  lee ", names) shouldBe true
        NamePrompt.oneName("lee sarah", names) shouldBe false
        NamePrompt.oneName("aaron mom", names) shouldBe false
        NamePrompt.oneName("aaron", emptyList()) shouldBe false
    }
})
