package org.stypox.dicio.skills.knowledge

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.garRankingWinner
import org.stypox.dicio.gar.online.GarHttp
import java.io.IOException
import java.time.LocalDate

/** A Wikipedia that answers from [pages] (url -> body), recording what was asked. */
private class FakeWiki(private val pages: Map<String, String>) : GarHttp {
    val asked = mutableListOf<String>()
    var offline = false

    override suspend fun get(url: String): String? {
        asked.add(url)
        if (offline) {
            throw IOException("offline")
        }
        return pages[url]
    }
}

private fun summaryUrl(title: String) = "${Wikipedia.SUMMARY_URL}$title?redirect=true"
private fun searchUrl(query: String) = "${Wikipedia.SEARCH_URL}${Wikipedia.encode(query)}&limit=1"

private fun summaryJson(
    title: String,
    extract: String,
    description: String? = null,
    type: String = "standard",
) = """{"type":"$type","title":"$title",""" +
    (description?.let { "\"description\":\"$it\"," } ?: "") +
    """"extract":"$extract","content_urls":{"mobile":{"page":"https://en.m.wikipedia.org/wiki/${title.replace(' ', '_')}"}}}"""

private val EVEREST = summaryJson(
    "Mount Everest",
    "Mount Everest is the highest mountain on Earth above sea level. It lies in the Mahalangur " +
        "Himal sub-range of the Himalayas. Its height was most recently measured in 2020 as " +
        "8,848.86 m.",
    "Earth's highest mountain",
)
private val SWIFT = summaryJson(
    "Taylor Swift", "Taylor Alison Swift is an American singer-songwriter.",
    "American singer-songwriter (born 1989)",
)
private val EINSTEIN = summaryJson(
    "Albert Einstein", "Albert Einstein was a German-born theoretical physicist.",
    "German-born theoretical physicist (1879\u20131955)",
)
private val MERCURY = summaryJson(
    "Mercury", "Mercury most commonly refers to: the planet, the element.", type = "disambiguation",
)

private fun skill(http: GarHttp) =
    KnowledgeSkill(KnowledgeInfo, wikipedia = { Wikipedia(http) }, today = { LocalDate.of(2026, 11, 2) })

class KnowledgeTest : StringSpec({
    "the question forms and their subjects" {
        for ((input, question) in listOf(
            "who is taylor swift" to KnowledgeQuestion("taylor swift", KnowledgeKind.SUMMARY),
            "who's ada lovelace" to KnowledgeQuestion("ada lovelace", KnowledgeKind.SUMMARY),
            "what is a black hole" to KnowledgeQuestion("black hole", KnowledgeKind.SUMMARY),
            "what's the capital of france" to
                KnowledgeQuestion("capital of france", KnowledgeKind.SUMMARY),
            "hey can you tell me who invented the telephone" to null,
            "can you tell me who was nikola tesla" to
                KnowledgeQuestion("nikola tesla", KnowledgeKind.SUMMARY),
            "tell me about the eiffel tower" to
                KnowledgeQuestion("eiffel tower", KnowledgeKind.SUMMARY),
            "tell me more about black holes please" to
                KnowledgeQuestion("black holes", KnowledgeKind.SUMMARY),
            "what do you know about the beatles" to
                KnowledgeQuestion("beatles", KnowledgeKind.SUMMARY),
            "how tall is mount everest" to
                KnowledgeQuestion("mount everest", KnowledgeKind.MEASURE, "tall"),
            "how long is a marathon" to
                KnowledgeQuestion("marathon", KnowledgeKind.MEASURE, "long"),
            "how old is taylor swift" to KnowledgeQuestion("taylor swift", KnowledgeKind.AGE),
            "when was albert einstein born" to
                KnowledgeQuestion("albert einstein", KnowledgeKind.BORN),
            "when did elvis presley die" to KnowledgeQuestion("elvis presley", KnowledgeKind.DIED),
            "when did elvis pass away" to KnowledgeQuestion("elvis", KnowledgeKind.DIED),
            "when was the eiffel tower built" to
                KnowledgeQuestion("eiffel tower", KnowledgeKind.WHEN, "built"),
            "when did world war two end" to
                KnowledgeQuestion("world war two", KnowledgeKind.WHEN, "end"),
        )) {
            withClue(input) { KnowledgeQuestions.parse(input) shouldBe question }
        }
    }

    "questions for the phone, small talk and other skills are no knowledge question" {
        for (input in listOf(
            "what is the time", "what's the time", "what's the weather", "what is 5 plus 5",
            "what is five plus five", "what's my battery", "what's up", "what's new", "who are you",
            "what is your name", "who is calling", "what's playing", "what's that song",
            "what's on my calendar", "what's next", "what is the meaning of life",
            "what's the date today", "what is the square root of sixty four", "how long is the timer",
            "how long is left on the eggs", "how old are you", "how far is tokyo",
            "what's the exchange rate for pesos", "what does serendipity mean",
            "how do you spell necessary", "who's singing", "what's on tv",
            "what's in the family chat", "what's the wake word", "what's the word for dog",
            "who was the last caller", "who's been calling me", "what is it", "who is this",
            "what's the forecast for tomorrow", "what's the volume", "what's for dinner",
            "who won the game", "what time is it", "what is the next holiday",
            "how hot is it outside", "what's the score", "what's open nearby",
            "what is mom's number", "what's 5 miles in km", "what is five",
        )) {
            withClue(input) { KnowledgeQuestions.parse(input).shouldBeNull() }
        }
    }

    "it scores only its questions, below a full match of another skill" {
        val skill = KnowledgeSkill(KnowledgeInfo)
        val ctx = FakeSkillContext()
        skill.score(ctx, "who is taylor swift").first shouldBe FloatScore(0.86f)
        skill.score(ctx, "what's the weather").first shouldBe AlwaysWorstScore
    }

    "ranked with every skill: the questions go to the knowledge gate, the rest stays" {
        for (input in listOf(
            "who is taylor swift", "what is a black hole", "what's the capital of france",
            "how tall is mount everest", "when was albert einstein born", "tell me about the moon",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe KnowledgeInfo.id }
        }
        for ((input, skill) in listOf(
            "what time is it" to "current_time", "what's my battery" to "battery",
            "who are you" to "small_talk", "how do you spell necessary" to "spelling",
            "what is two plus two" to "calculator", "what is hello in spanish" to "translation",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
    }

    "the page with the subject's title, else the first search result" {
        val wiki = FakeWiki(mapOf(
            summaryUrl("Mount_everest") to EVEREST,
            searchUrl("eiffel tower") to """{"pages":[{"key":"Eiffel_Tower","title":"Eiffel Tower"}]}""",
            summaryUrl("Eiffel_Tower") to summaryJson("Eiffel Tower", "A lattice tower in Paris."),
        ))
        Wikipedia(wiki).find("mount everest")?.title shouldBe "Mount Everest"
        wiki.asked shouldContainExactly listOf(summaryUrl("Mount_everest"))
        Wikipedia(wiki).find("eiffel tower")?.title shouldBe "Eiffel Tower"
        Wikipedia(wiki).find("frobnicator").shouldBeNull()
        // only the subject goes out, encoded
        Wikipedia.encode("rock & roll") shouldBe "rock%20%26%20roll"
    }

    "a disambiguation page comes back as one when nothing else matches" {
        val wiki = FakeWiki(mapOf(summaryUrl("Mercury") to MERCURY))
        Wikipedia(wiki).find("mercury")?.disambiguation shouldBe true
    }

    "what it says: one or two sentences, the measurement, the years" {
        val everest = Wikipedia.parseSummary(EVEREST)!!
        WikiAnswers.spokenText(everest, KnowledgeQuestion("mount everest", KnowledgeKind.SUMMARY)) shouldBe
            "Mount Everest is the highest mountain on Earth above sea level. It lies in the " +
            "Mahalangur Himal sub-range of the Himalayas."
        WikiAnswers.spokenText(everest, KnowledgeQuestion("mount everest", KnowledgeKind.MEASURE, "tall")) shouldBe
            "Mount Everest is the highest mountain on Earth above sea level. Its height was most " +
            "recently measured in 2020 as 8,848.86 meters."
        WikiAnswers.lifeYears("American singer-songwriter (born 1989)") shouldBe Pair(1989, null)
        WikiAnswers.lifeYears("German-born theoretical physicist (1879\u20131955)") shouldBe Pair(1879, 1955)
        WikiAnswers.lifeYears("Earth's highest mountain").shouldBeNull()
        WikiAnswers.sentences("J. R. R. Tolkien was a writer. He wrote books in the U.S. Army. Yes.") shouldBe
            listOf("J. R. R. Tolkien was a writer.", "He wrote books in the U.S. Army.", "Yes.")
    }

    "the skill's answers" {
        val wiki = FakeWiki(mapOf(
            summaryUrl("Mount_everest") to EVEREST, summaryUrl("Taylor_swift") to SWIFT,
            summaryUrl("Albert_einstein") to EINSTEIN, summaryUrl("Mercury") to MERCURY,
        ))
        val skill = skill(wiki)
        val ctx = FakeSkillContext()
        suspend fun answer(input: String) =
            skill.generateOutput(ctx, KnowledgeQuestions.parse(input)!!)

        answer("how tall is mount everest").shouldBeInstanceOf<KnowledgeOutput.Answer>().spoken
            .shouldBeInstanceOf<KnowledgeSpoken.Text>().text shouldContain "8,848.86 meters"
        answer("when was taylor swift born").shouldBeInstanceOf<KnowledgeOutput.Answer>().spoken shouldBe
            KnowledgeSpoken.Born("Taylor Swift", Pair(1989, null))
        answer("how old is taylor swift").shouldBeInstanceOf<KnowledgeOutput.Answer>().spoken shouldBe
            KnowledgeSpoken.Age("Taylor Swift", Pair(1989, null), 2026)
        answer("when did albert einstein die").shouldBeInstanceOf<KnowledgeOutput.Answer>().spoken shouldBe
            KnowledgeSpoken.Died("Albert Einstein", Pair(1879, 1955))
        answer("what is mercury").shouldBeInstanceOf<KnowledgeOutput.Ambiguous>().summary.title shouldBe "Mercury"
        answer("who is zzyzx quux") shouldBe KnowledgeOutput.NotFound("zzyzx quux")
        wiki.offline = true
        answer("who is taylor swift") shouldBe KnowledgeOutput.Unreachable
    }
})
