package org.stypox.dicio.skills.offline_translator

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.translation.TranslationInfo

/** A fake Offline Translator service that answers [answer] and records what it was asked. */
private class FakeService(private val answer: OfflineTranslation) : OfflineTranslatorClient {
    val asked = mutableListOf<Triple<String, String, String>>()

    override suspend fun translate(text: String, from: String, to: String): OfflineTranslation {
        asked.add(Triple(text, from, to))
        return answer
    }
}

private class Setup(answer: OfflineTranslation, installed: Boolean = true, val opens: Boolean = true) {
    val service = FakeService(answer)
    val opened = mutableListOf<String>()
    val skill = OfflineTranslatorSkill(
        OfflineTranslatorInfo, Sentences.Translation["en"]!!,
        isInstalled = { installed },
        client = { service },
        open = { _, text -> opened.add(text); opens },
    )

    suspend fun run(input: String) = skill.score(FakeSkillContext().forInput(input), input).let {
        it.first shouldNotBe AlwaysWorstScore
        skill.generateOutput(FakeSkillContext(), it.second)
    }
}

class OfflineTranslatorTest : StringSpec({
    "Offline Translator's codes for CLDR's" {
        for ((cldr, code) in listOf(
            "es" to "es", "es_mx" to "es", "zh" to "zh", "zh_hans" to "zh", "zh_hant" to "zh_hant",
            "iw" to "he", "fil" to "tl", "nb" to "nb", "pt_br" to "pt", "tlh" to null, "ace" to null,
        )) {
            withClue(cldr) { OfflineTranslatorLanguages.codeOf(cldr) shouldBe code }
        }
    }

    "it takes translation requests while Offline Translator is installed" {
        val setup = Setup(OfflineTranslation.Translated("buenos días"))
        val ctx = FakeSkillContext()
        for ((input, request) in listOf(
            "translate good morning to spanish" to Triple("good morning", null, "es"),
            "how do you say thank you in french" to Triple("thank you", null, "fr"),
            "translate bonjour from french to english" to Triple("bonjour", "fr", "en"),
        )) {
            withClue(input) {
                val data = setup.skill.score(ctx.forInput(input), input).second.shouldNotBeNull()
                Triple(data.text, data.from, data.to) shouldBe request
            }
        }
        // a language it doesn't have goes to the online skill; so does anything else
        for (input in listOf("translate hello to klingon", "turn on the flashlight", "what is the time")) {
            withClue(input) { setup.skill.score(ctx.forInput(input), input).first shouldBe AlwaysWorstScore }
        }
        Setup(OfflineTranslation.Unreachable, installed = false).skill
            .score(ctx.forInput("translate good morning to spanish"), "translate good morning to spanish")
            .first shouldBe AlwaysWorstScore
    }

    "the translation is spoken, from English unless the target is English" {
        val setup = Setup(OfflineTranslation.Translated("buenos días"))
        setup.run("translate good morning to spanish")
            .shouldBeInstanceOf<OfflineTranslatorOutput.Translated>().translation shouldBe "buenos días"
        setup.run("translate bonjour to english")
        setup.service.asked shouldContainExactly listOf(
            Triple("good morning", "en", "es"), Triple("bonjour", "", "en"),
        )
        setup.opened.shouldBeEmpty()
    }

    "what it can't translate is shown in Offline Translator" {
        val failed = Setup(OfflineTranslation.Failed(1, "French", "not installed"))
        failed.run("translate good morning to french")
            .shouldBeInstanceOf<OfflineTranslatorOutput.Opened>().reason shouldBe "not installed"
        failed.opened shouldContainExactly listOf("good morning")
        Setup(OfflineTranslation.Unreachable).run("translate good morning to french")
            .shouldBeInstanceOf<OfflineTranslatorOutput.Opened>().reason.shouldBeNull()
        Setup(OfflineTranslation.Unreachable, opens = false).run("translate good morning to french")
            .shouldBeInstanceOf<OfflineTranslatorOutput.Failed>()
    }

    "installed, it wins over the online translation skill and its gate" {
        val installed = Setup(OfflineTranslation.Translated("merci"))
        val others = allEnglishSkills().filter {
            it.correspondingSkillInfo.id != OfflineTranslatorInfo.id
        }
        for (enabled in listOf(UserSettingsSerializer.defaultValue.enabledSkillsMap, mapOf(TranslationInfo.id to true))) {
            val ranker = SkillRanker(
                GarOnlineSkills.forRanking(others + installed.skill, enabled),
                MockSkill(Specificity.LOW, 0.0f),
            )
            for (input in listOf("translate thank you to french", "how do you say thank you in french")) {
                withClue("$input $enabled") {
                    ranker.getBest(FakeSkillContext().forInput(input), input)
                        ?.skill?.correspondingSkillInfo?.id shouldBe OfflineTranslatorInfo.id
                }
            }
        }
    }
})
