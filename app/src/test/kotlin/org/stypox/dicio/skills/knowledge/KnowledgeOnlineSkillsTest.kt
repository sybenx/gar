package org.stypox.dicio.skills.knowledge

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.online.GarOnlineAnswers
import org.stypox.dicio.gar.online.GarOnlineAskOutput
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.online.GarOnlineSettingsImpl
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.currency.CurrencyInfo
import org.stypox.dicio.skills.dictionary.DictionaryInfo

/** gar:knowledge: GAR's own online skills follow the online rule like Dicio's. */
class KnowledgeOnlineSkillsTest : StringSpec({
    "off on a new install, and off on an older one that never had them" {
        val ids = listOf(KnowledgeInfo.id, DictionaryInfo.id, CurrencyInfo.id)
        for (id in ids) {
            withClue(id) { UserSettingsSerializer.defaultValue.enabledSkillsMap[id] shouldBe false }
        }
        // an install from before: the user had turned all of Dicio's online skills on
        val older = UserSettings.getDefaultInstance().toBuilder()
            .putAllEnabledSkills(listOf("weather", "search", "lyrics", "translation", "Joke").associateWith { true })
            .build()
        val read = UserSettingsSerializer.withGarOnlineSkillsOff(older)
        for (id in ids) {
            withClue(id) { read.enabledSkillsMap[id] shouldBe false }
        }
        read.enabledSkillsMap["weather"] shouldBe true
        // a choice stays
        val chosen = read.toBuilder().putEnabledSkills(KnowledgeInfo.id, true).build()
        UserSettingsSerializer.withGarOnlineSkillsOff(chosen).enabledSkillsMap[KnowledgeInfo.id] shouldBe true
        UserSettingsSerializer.withGarOnlineSkillsOff(chosen) shouldBeSameInstanceAs chosen
    }

    "the gate asks once, naming Wikipedia" {
        val settings = GarOnlineSettingsImpl(
            UserSettingsModule.newDataStoreForPreviews(), GarSettingsModule.newDataStoreForPreviews(),
        )
        val skill = KnowledgeSkill(KnowledgeInfo)
        val gate = GarOnlineGateSkill(GarOnlineSkill.KNOWLEDGE, skill, isDeviceLocked = { false }) {
            settings
        }
        val question = KnowledgeQuestions.parse("who is taylor swift")
        // the gate scores like the skill
        gate.score(FakeSkillContext(), "who is taylor swift").second shouldBe question
        gate.generateOutput(FakeSkillContext(), question)
            .shouldBeInstanceOf<GarOnlineAskOutput>().online shouldBe GarOnlineSkill.KNOWLEDGE
    }

    "answers to the question may name the skill" {
        GarOnlineAnswers.classify("yes turn on wikipedia", GarOnlineSkill.KNOWLEDGE) shouldBe true
        GarOnlineAnswers.classify("yes download it", GarOnlineSkill.DICTIONARY) shouldBe true
        GarOnlineAnswers.classify("no keep currency off", GarOnlineSkill.CURRENCY) shouldBe false
        // another skill's name is no answer
        GarOnlineAnswers.classify("yes turn on wikipedia", GarOnlineSkill.CURRENCY).shouldBeNull()
    }
})
