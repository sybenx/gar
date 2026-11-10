package org.stypox.dicio.skills

import cc.aaro.gar.router.resolve.SlotResolvers
import cc.aaro.gar.router.tools.ConfirmText
import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.DefaultGarSlotResolver
import org.stypox.dicio.gar.call.GarAskSlotOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarRoutedSkill
import org.stypox.dicio.gar.englishFormatter
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.date.DateInfo
import org.stypox.dicio.skills.date.DateOutput
import org.stypox.dicio.skills.date.DateSkill
import org.stypox.dicio.skills.stopwatch.ClockReading
import org.stypox.dicio.skills.stopwatch.StopwatchAction
import org.stypox.dicio.skills.stopwatch.StopwatchController
import org.stypox.dicio.skills.stopwatch.StopwatchEvent
import org.stypox.dicio.skills.stopwatch.StopwatchInfo
import org.stypox.dicio.skills.stopwatch.StopwatchOutput
import org.stypox.dicio.skills.stopwatch.StopwatchSkill
import org.stypox.dicio.skills.stopwatch.StopwatchStore
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

private fun read(path: String) =
    sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()

private val TOOLS_YAML = read("src/main/gar/tools.yml")
private val SKILL_DEFINITIONS_YAML = read("src/main/sentences/skill_definitions.yml")

/** The clock group's tools in the real catalog, and the code that depends on them. */
class ClockToolsTest : StringSpec({
    val catalog = ToolCatalog.load(TOOLS_YAML, SKILL_DEFINITIONS_YAML)

    "the stopwatch tool is LLM-only and its actions are the skill's actions" {
        val tool = catalog.byName("stopwatch").shouldNotBeNull()
        tool.skillId shouldBe StopwatchInfo.id
        tool.sentence.shouldBeNull()
        val slot = tool.slots.single()
        slot.name shouldBe StopwatchSkill.ACTION_SLOT
        slot.required shouldBe true
        slot.enumValues shouldBe StopwatchAction.entries.map { it.slotValue }
        tool.confirmText.shouldBeInstanceOf<ConfirmText.ByValue>().templates.keys shouldBe
                slot.enumValues.toSet()

        // the English sentences (one per action) only keep the timer's sentences away from the
        // stopwatch; the tool doesn't use them, so it works in languages without them
        @Suppress("UNCHECKED_CAST")
        val definitions = (Load(LoadSettings.builder().build()).loadFromString(SKILL_DEFINITIONS_YAML)
                as Map<String, List<Map<String, Any?>>>)["skills"]!!
        val stopwatch = definitions.single { it["id"] == StopwatchInfo.id }
        @Suppress("UNCHECKED_CAST")
        (stopwatch["sentences"] as List<Map<String, Any?>>).map { it["id"] } shouldBe slot.enumValues
        Sentences.Stopwatch["en"].shouldNotBeNull()
        Sentences.Stopwatch["it"].shouldBeNull()
    }

    "the stopwatch skill builds without sentences, in any language" {
        for (language in listOf("en", "it", "xx")) {
            StopwatchInfo.build(FakeSkillContext(sentencesLanguage = language))
                .shouldBeInstanceOf<StopwatchSkill>()
        }
    }

    "the date skill builds with or without sentences" {
        DateInfo.build(FakeSkillContext(sentencesLanguage = "en")).shouldBeInstanceOf<DateSkill>()
        DateInfo.build(FakeSkillContext(sentencesLanguage = "xx")).shouldBeInstanceOf<DateSkill>()
    }

    "the date skill's own date_query tool matches tools.yml" {
        val tool = catalog.byName("date_query").shouldNotBeNull()
        val own = DateSkill.DATE_QUERY_TOOL
        tool.name shouldBe own.name
        tool.skillId shouldBe own.skillId
        tool.skillId shouldBe DateInfo.id
        tool.sentence shouldBe own.sentence
        tool.confirm shouldBe own.confirm
        tool.slots.map { Triple(it.name, it.kind, it.required) } shouldBe
                own.slots.map { Triple(it.name, it.kind, it.required) }
    }

    "the model's calls run through GAR without confirmation, even with confirmation on" {
        val now = LocalDateTime.of(2026, 10, 25, 12, 0)
        fun routed(info: org.dicio.skill.skill.SkillInfo, skill: org.dicio.skill.skill.Skill<*>) =
            GarRoutedSkill(
                info, skill, confirm = true,
                resolverFactory = {
                    DefaultGarSlotResolver(SlotResolvers(ENGLISH_PARSER_FORMATTER, "en") { now.atZone(ZoneOffset.UTC) })
                },
                formatterFactory = { englishFormatter() },
            )

        val date = routed(DateInfo, DateSkill(DateInfo, Sentences.Date["en"], now = { now.atZone(ZoneOffset.UTC) }))
        val dateQuery = catalog.byName("date_query").shouldNotBeNull()
        date.generateOutput(FakeSkillContext(), GarCall(dateQuery, mapOf(), "what's the date"))
            .shouldBeInstanceOf<DateOutput>().date shouldBe LocalDate.of(2026, 10, 25)
        date.generateOutput(
            FakeSkillContext(),
            GarCall(dateQuery, mapOf("day" to "the day after tomorrow"), "what day is the day after tomorrow"),
        ).shouldBeInstanceOf<DateOutput>().date shouldBe LocalDate.of(2026, 10, 27)
        // a day GAR can't read gets its question, it never becomes today
        date.generateOutput(FakeSkillContext(), GarCall(dateQuery, mapOf("day" to "france"), "x"))
            .shouldBeInstanceOf<GarAskSlotOutput>()

        val store = object : StopwatchStore {
            var saved: String? = null
            override fun load() = saved
            override fun save(encoded: String) {
                saved = encoded
            }
        }
        val controller = StopwatchController(store, { ClockReading(1_000, 1_761_400_000_000, 7) })
        val stopwatch = routed(StopwatchInfo, StopwatchSkill(StopwatchInfo, null) { controller })
        stopwatch.generateOutput(
            FakeSkillContext(),
            GarCall(catalog.byName("stopwatch").shouldNotBeNull(), mapOf("action" to "start"), "start the stopwatch"),
        ).shouldBeInstanceOf<StopwatchOutput>().event shouldBe StopwatchEvent.Started
        controller.state.value.isRunning shouldBe true
        store.saved.shouldNotBeNull()
    }
})
