package org.stypox.dicio.skills.website

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.garRankingWinner
import org.stypox.dicio.sentences.Sentences
import org.dicio.skill.skill.AlwaysWorstScore
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldNotBe

class WebAddressTest : StringSpec({
    "spoken and typed addresses" {
        for ((said, display) in listOf(
            "reddit dot com" to "reddit.com",
            "wikipedia.org" to "wikipedia.org",
            "Wikipedia.org." to "wikipedia.org",
            "w w w dot example dot co dot uk" to "www.example.co.uk",
            "bbc dot co dot uk" to "bbc.co.uk",
            "news dot y combinator dot com slash item" to "news.ycombinator.com/item",
            "the website called duck duck go dot com" to "duckduckgo.com",
            "https://example.com/a/b" to "example.com/a/b",
            "github dot com slash sybenx" to "github.com/sybenx",
            "my dash site dot io" to "my-site.io",
            "grapheneos dot org" to "grapheneos.org",
        )) {
            withClue(said) { WebAddress.parse(said)?.display shouldBe display }
        }
        WebAddress.parse("reddit dot com")?.url shouldBe "https://reddit.com"
    }

    "words, times and abbreviations are no address" {
        for (said in listOf(
            "notes", "spotify", "home depot", "7.30", "e.g.", "mr. smith", "st. louis", "u.s.",
            "www.com", "example dot c", "dot com", "the train station", "p.m.", "version 2.0",
            "",
        )) {
            withClue(said) { WebAddress.parse(said).shouldBeNull() }
        }
    }

    "the model's slots have no dots: a common last word is a top level domain" {
        WebAddress.parse("wikipedia org").shouldBeNull()
        WebAddress.displayFor("wikipedia org") shouldBe "wikipedia.org"
        WebAddress.displayFor("reddit dot com") shouldBe "reddit.com"
        WebAddress.displayFor("android dev").shouldBeNull()
        WebAddress.displayFor("spotify").shouldBeNull()
        WebAddress.displayFor("google play store").shouldBeNull()
    }

    "the website sentences only keep addresses" {
        val skill = WebsiteSkill(WebsiteInfo, Sentences.Website["en"]!!)
        for (input in listOf("go to bed", "go to the store", "visit grandma", "open the page")) {
            withClue(input) {
                skill.score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
        for (input in listOf("go to reddit dot com", "visit wikipedia dot org", "open the website example dot com")) {
            withClue(input) {
                skill.score(FakeSkillContext().forInput(input), input).first shouldNotBe AlwaysWorstScore
            }
        }
    }

    "addresses rank to the website or the open skill, apps stay with the open skill" {
        for (input in listOf("go to reddit dot com", "visit wikipedia dot org", "browse to example dot com")) {
            withClue(input) { garRankingWinner(input).first shouldBe WebsiteInfo.id }
        }
        // the open skill opens an address the same way (OpenSkill's GAR line)
        for (input in listOf("open wikipedia dot org", "open reddit dot com")) {
            withClue(input) { garRankingWinner(input).first shouldBeIn listOf("open", WebsiteInfo.id) }
        }
        for (input in listOf("open spotify", "open signal")) {
            withClue(input) { garRankingWinner(input).first shouldBe "open" }
        }
        withClue("go to bed") { garRankingWinner("go to bed").first shouldNotBe WebsiteInfo.id }
    }
})
