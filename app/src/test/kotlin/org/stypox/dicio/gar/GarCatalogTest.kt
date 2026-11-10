package org.stypox.dicio.gar

import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.tools.ConfirmText
import cc.aaro.gar.router.tools.SentenceMapping
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarCallRunner
import org.stypox.dicio.gar.call.GarSentenceBinder
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.flashlight.FlashlightInfo
import org.stypox.dicio.skills.listening.ListeningInfo
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.notify.NotifyInfo
import org.stypox.dicio.skills.online_skills.OnlineSkillsInfo
import org.stypox.dicio.skills.open.OpenInfo
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.timer.TimerInfo
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.dicio.numbers.unit.Duration as DicioDuration
import org.dicio.numbers.unit.Number as DicioNumber

private fun sourceFile(path: String): String {
    // unit tests run in the module directory, but be lenient about the repository root
    val file = sequenceOf(File(path), File("app", path)).firstOrNull { it.isFile }
        ?: throw AssertionError("Cannot find $path from ${File("").absolutePath}")
    return file.readText()
}

private val TOOLS_YAML = sourceFile("src/main/gar/tools.yml")
private val SKILL_DEFINITIONS_YAML = sourceFile("src/main/sentences/skill_definitions.yml")

private fun loadRealCatalog(): ToolCatalog = try {
    ToolCatalog.load(TOOLS_YAML, SKILL_DEFINITIONS_YAML)
} catch (e: NotImplementedError) {
    // :gar:router's loader is still a stub on this branch: bind the same files read by a
    // minimal parser, so the binding is checked anyway
    println("ToolCatalog.load is not implemented yet, using GarCatalogTest's own parser")
    MiniCatalogParser.parse(TOOLS_YAML, SKILL_DEFINITIONS_YAML)
}

/** A sample value of the type each capture type expects, distinct per position. */
private fun sampleCaptureValue(captureType: String, position: Int): Any = when (captureType) {
    "string" -> "value $position"
    "duration" -> DicioDuration(Duration.ofSeconds(60L + position))
    "number" -> DicioNumber(position.toLong())
    "date_time" -> LocalDateTime.of(2026, 10, 25, 7, position)
    else -> throw AssertionError("Unknown capture type $captureType")
}

/** A sample resolved slot value, as GarSlotResolver would produce it. */
private fun sampleSlotValue(slot: SlotSpec): Any = when (slot.kind) {
    SlotKind.DURATION -> Duration.ofMinutes(10)
    SlotKind.NUMBER -> ResolvedNumber(5.0, percent = false, isInteger = true)
    SlotKind.DATETIME ->
        ResolvedDateTime(LocalDateTime.of(2026, 10, 26, 7, 30), dateSpoken = true, timeSpoken = true)
    SlotKind.DATE -> ResolvedDate(LocalDate.of(2026, 10, 26))
    SlotKind.TIME -> ResolvedTime(LocalTime.of(7, 30))
    SlotKind.ENUM -> slot.enumValues.first()
    SlotKind.TEXT, SlotKind.CONTACT, SlotKind.APP -> "something"
}

/**
 * The capture type each slot kind's values always bind to. A number, a date, a date-time and a
 * time have none: a percentage, a date without a time and a recurring time can't be bound (see
 * [GarSentenceBinder.toCaptureValue]), so their slots belong to LLM-only tools.
 */
private val CAPTURE_TYPE_OF_KIND = mapOf(
    SlotKind.DURATION to "duration",
    SlotKind.TEXT to "string",
    SlotKind.CONTACT to "string",
    SlotKind.APP to "string",
    SlotKind.ENUM to "string",
)

private fun checkBinding(tool: ToolSpec, sentenceId: String) {
    val cls = GarSentenceBinder.sentenceClass(tool, sentenceId)
    val captures = GarSentenceBinder.captureOrder(tool, sentenceId)

    if (captures.isEmpty()) {
        // a Kotlin object
        cls.getField("INSTANCE").get(null).shouldNotBeNull().javaClass shouldBe cls
        return
    }

    val constructor = GarSentenceBinder.constructorFor(tool, sentenceId).shouldNotBeNull()
    constructor.parameterCount shouldBe captures.size

    // every slot that feeds a capture has the generated parameter type
    for (slot in tool.slots) {
        val position = captures.indexOf(slot.name)
        if (position == -1) {
            slot.captureType shouldBe null
        } else {
            withClue("slot ${slot.name}") {
                constructor.parameterTypes[position] shouldBe
                        GarSentenceBinder.captureClass(slot.captureType.shouldNotBeNull())
            }
        }
    }

    // the constructor order is the capture order: each getter returns its argument
    val args = captures.indices.map { i ->
        val type = constructor.parameterTypes[i]
        val captureType = listOf("string", "duration", "number", "date_time")
            .first { GarSentenceBinder.captureClass(it) == type }
        sampleCaptureValue(captureType, i)
    }
    val instance = constructor.newInstance(*args.toTypedArray())
    for ((i, captureId) in captures.withIndex()) {
        val getter = cls.getMethod("get" + GarSentenceBinder.pascalCase(captureId))
        getter.invoke(instance) shouldBeSameInstanceAs args[i]
    }
}

class GarCatalogTest : StringSpec({
    "the real catalog loads and every sentence-backed tool binds to a generated class" {
        val catalog = loadRealCatalog()
        catalog.tools.map { it.name } shouldContainAll listOf("timer_set", "media_control")

        for (tool in catalog.tools.filter { it.sentence != null }) {
            for (sentenceId in GarSentenceBinder.sentenceIds(tool)) {
                withClue("${tool.name}/$sentenceId") { checkBinding(tool, sentenceId) }
            }
        }
    }

    "every tool builds its skill's input from resolved values" {
        val catalog = loadRealCatalog()
        for (tool in catalog.tools.filter { it.sentence != null }) {
            val values = tool.slots.associate { it.name to sampleSlotValue(it) }
            val input = GarSentenceBinder.buildInput(tool, values)
            withClue(tool.name) {
                input.javaClass.name.startsWith(GarSentenceBinder.SENTENCES_CLASS + "$") shouldBe true
            }

            // optional slots may be missing
            val required = tool.slots.filter { it.required }.associate { it.name to sampleSlotValue(it) }
            GarSentenceBinder.buildInput(tool, required).javaClass shouldBe input.javaClass
        }
    }

    "every slot that feeds a capture has a kind whose values that capture always holds" {
        // sample values alone would miss e.g. "fifty percent" bound to a number capture, which
        // only fails on the phone, after the user confirmed; and a string capture takes any
        // value's toString()
        for (tool in loadRealCatalog().tools) {
            for (slot in tool.slots) {
                withClue("${tool.name}.${slot.name}") {
                    val captureType = CAPTURE_TYPE_OF_KIND[slot.kind]
                    if (captureType == null) {
                        // the binder's "use an LLM-only tool"
                        tool.sentence shouldBe null
                    } else if (tool.sentence != null && slot.captureType != null) {
                        slot.captureType shouldBe captureType
                    }
                }
            }
        }
    }

    "every tool without a sentence has a skill that builds its input from the slots" {
        // GarCallRunner.run fails for any other skill, after the user confirmed
        val skills = allEnglishSkills().associateBy { it.correspondingSkillInfo.id }
        val llmOnly = loadRealCatalog().tools.filter { it.sentence == null }
        llmOnly.map { it.skillId }.toSet() shouldContainAll
            listOf("alarm", "calendar", "sms", "volume", "stopwatch", "date")
        for (tool in llmOnly) {
            withClue(tool.name) {
                skills[tool.skillId].shouldNotBeNull().shouldBeInstanceOf<GarToolSkill>()
            }
        }
    }

    "every enum value of a by-enum tool selects a sentence" {
        val catalog = loadRealCatalog()
        for (tool in catalog.tools) {
            val mapping = tool.sentence as? SentenceMapping.ByEnum ?: continue
            val slot = tool.slots.first { it.name == mapping.slot }
            mapping.valueToSentenceId.keys shouldBe slot.enumValues.toSet()
            for (value in slot.enumValues) {
                val input = GarSentenceBinder.buildInput(tool, mapOf(slot.name to value))
                withClue("${tool.name} $value") {
                    input shouldBeSameInstanceAs GarSentenceBinder
                        .sentenceClass(tool, mapping.valueToSentenceId.getValue(value))
                        .getField("INSTANCE").get(null)
                }
            }
        }
    }

    "every tool belongs to one of Dicio's skills" {
        val skillIds = listOf(
            OpenInfo, CalculatorInfo, NavigationInfo, TelephoneInfo, TimerInfo, CurrentTimeInfo,
            MediaInfo, NotifyInfo, FlashlightInfo,
            ListeningInfo(UserSettingsModule.newDataStoreForPreviews()),
            org.stypox.dicio.skills.alarm.AlarmInfo, // gar:alarm
            org.stypox.dicio.skills.calendar.CalendarInfo, // gar:calendar
            org.stypox.dicio.skills.sms.SmsInfo, // gar:sms
            // gar:device
            org.stypox.dicio.skills.volume.VolumeInfo,
            org.stypox.dicio.skills.do_not_disturb.DoNotDisturbInfo,
            org.stypox.dicio.skills.connectivity.ConnectivityInfo,
            org.stypox.dicio.skills.device_settings.DeviceSettingsInfo, // gar:settings
            org.stypox.dicio.skills.stopwatch.StopwatchInfo, org.stypox.dicio.skills.date.DateInfo, // gar:clock
            org.stypox.dicio.skills.song.SongInfo, // gar:song
            org.stypox.dicio.skills.media.MediaPlayInfo, // gar:media
            org.stypox.dicio.skills.weather.WeatherInfo, // gar:weather
        ).map { it.id }
        for (tool in loadRealCatalog().tools) {
            withClue(tool.name) { tool.skillId shouldBeIn skillIds }
        }
    }

    "only tools that are harmless when the model guesses wrong skip the confirmation" {
        // read_notifications, media_control and flashlight absorbed most of the eval's false
        // calls; call_contact is always confirmed by the telephone skill itself
        loadRealCatalog().tools.filter { !it.confirm }.map { it.name } shouldContainExactlyInAnyOrder
                listOf("timer_query", "call_contact", "current_time", "calculate") +
                listOf("alarm_list") + // gar:alarm
                listOf("calendar_query") + // gar:calendar, only reads
                listOf("sms_send") + // gar:sms: the sms skill reads the text back and asks first
                // gar:device: they say what changed, or only open the system's controls
                listOf("volume", "volume_set", "do_not_disturb") +
                // gar:settings: says what changed, or opens the setting's page or controls
                listOf("device_setting") +
                listOf("stopwatch", "date_query") + // gar:clock
                listOf("song_identify") + // gar:song: names a song or listens, the card says which
                listOf("weather") // gar:weather: only reads; the online question names Open-Meteo
    }

    "only tools without private data or lasting effects run on a locked phone" {
        loadRealCatalog().tools.filter { it.onLockScreen }.map { it.name } shouldContainExactlyInAnyOrder
                listOf(
                    "timer_set", "timer_cancel", "timer_query", "media_control", "flashlight",
                    "current_time", "calculate",
                ) +
                listOf("alarm_set", "alarm_list", "alarm_cancel") + // gar:alarm
                listOf("volume", "volume_set", "do_not_disturb") + // gar:device
                // gar:settings: the skill itself asks to unlock before it opens a page, a radio's
                // controls or an access page, or switches airplane mode or battery saver
                listOf("device_setting") +
                listOf("stopwatch", "date_query") + // gar:clock
                // gar:song: what plays is on the lock screen's media controls already, and a
                // recording only goes to the service the user set up
                listOf("song_identify") +
                listOf("media_play") + // gar:media: like media_control, playing reveals nothing
                // gar:weather: a forecast reveals nothing; turning the skill on needs an unlocked phone
                listOf("weather")
    }

    "of the online skills and the sweep only the weather has a tool" { // gar:online
        // the model never routes to the others; the weather's slotless tool is offered while the
        // skill is off too, behind its gate (gar:weather, GarToolsProvider)
        val toolSkills = loadRealCatalog().tools.map { it.skillId }.toSet()
        (GarOnlineSkill.ids + OnlineSkillsInfo.id).filter { it in toolSkills } shouldBe
            listOf(GarOnlineSkill.WEATHER.id)
    }

    "the song skill's sentence check uses its tool's keywords" { // gar:song
        loadRealCatalog().tools.single { it.name == "song_identify" }.keywords shouldBe
            org.stypox.dicio.skills.song.SongWords.KEYWORDS
    }

    "every tool has a confirmation text, and it renders without placeholders left" {
        val runner = GarCallRunner(
            targetInfo = FakeSkillInfo("any"),
            target = RecordingSkill(FakeSkillInfo("any")),
            confirm = true,
            resolver = fakeResolver,
            formatter = englishFormatter(),
            yesNoData = null,
        )
        for (tool in loadRealCatalog().tools) {
            withClue(tool.name) { tool.confirmText.shouldNotBeNull() }
            val enumValues = (tool.confirmText as? ConfirmText.ByValue)
                ?.let { text -> tool.slots.first { it.name == text.slot }.enumValues }
                ?: listOf(null)
            for (enumValue in enumValues) {
                val values = tool.slots.associate { slot ->
                    slot.name to if (slot.kind == SlotKind.ENUM) enumValue else sampleSlotValue(slot)
                }
                val optionalLeftOut = values + tool.slots.filter { !it.required }.map { it.name to null }
                for (call in listOf(values, optionalLeftOut).map { GarCall(tool, mapOf(), "", it) }) {
                    val summary = runner.summary(call, speech = true)
                    withClue("${tool.name}: $summary") {
                        summary shouldNotContain "{"
                        summary shouldNotContain "  "
                        summary.endsWith("?") shouldBe true
                    }
                }
            }
        }
    }

    "values a capture can't hold fail loudly instead of losing what the user said" {
        val now = LocalDateTime.of(2026, 10, 25, 12, 0)
        GarSentenceBinder.toCaptureValue(ResolvedTime(LocalTime.of(7, 0)), "date_time", now) shouldBe
                LocalDateTime.of(2026, 10, 26, 7, 0)
        val lossy = listOf(
            ResolvedTime(LocalTime.of(7, 0), setOf(DayOfWeek.MONDAY)) to "date_time",
            ResolvedDate(LocalDate.of(2026, 10, 27)) to "date_time",
            ResolvedDateTime(LocalDateTime.of(2026, 10, 27, 12, 0), dateSpoken = true, timeSpoken = false)
                    to "date_time",
            LocalDate.of(2026, 10, 27) to "date_time",
            ResolvedNumber(50.0, percent = true, isInteger = true) to "number",
        )
        for ((value, captureType) in lossy) {
            withClue(value) {
                shouldThrow<IllegalArgumentException> {
                    GarSentenceBinder.toCaptureValue(value, captureType, now)
                }
            }
        }
    }

    "class names follow the sentences compiler" {
        GarSentenceBinder.sentenceClassName("current_time", "query") shouldBe
                "org.stypox.dicio.sentences.Sentences\$CurrentTime\$Query"
        GarSentenceBinder.sentenceClassName("flashlight", "turn_on") shouldBe
                "org.stypox.dicio.sentences.Sentences\$Flashlight\$TurnOn"
        GarSentenceBinder.pascalCase("a-b_c") shouldBe "ABC"
        GarSentenceBinder.camelCase("time_or_duration") shouldBe "timeOrDuration"
    }
})

/**
 * Just enough of `tools.yml` + `skill_definitions.yml` parsing to bind the catalog while
 * `ToolCatalog.load` (`:gar:router`) is a stub. No validation: that is the loader's job.
 */
private object MiniCatalogParser {
    @Suppress("UNCHECKED_CAST")
    private fun parseYaml(text: String): Map<String, Any?> =
        Load(LoadSettings.builder().build()).loadFromString(text) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun parse(toolsYaml: String, skillDefinitionsYaml: String): ToolCatalog {
        // skill id -> sentence id -> captures (id to type), in file order
        val definitions = (parseYaml(skillDefinitionsYaml)["skills"] as List<Map<String, Any?>>)
            .associate { skill ->
                skill["id"] as String to (skill["sentences"] as List<Map<String, Any?>>)
                    .associate { sentence ->
                        sentence["id"] as String to
                                ((sentence["captures"] as List<Map<String, Any?>>?) ?: listOf())
                                    .map { it["id"] as String to it["type"] as String }
                    }
            }

        val tools = (parseYaml(toolsYaml)["tools"] as List<Map<String, Any?>>).map { tool ->
            val skillId = tool["skill"] as String
            val sentenceId = tool["sentence"] as String?
            val sentenceFrom = tool["sentence_from"] as Map<String, Any?>?
            val captures = sentenceId?.let { definitions.getValue(skillId).getValue(it) } ?: listOf()

            val slots = ((tool["slots"] as Map<String, Map<String, Any?>>?) ?: mapOf())
                .map { (name, slot) ->
                    SlotSpec(
                        name = name,
                        kind = SlotKind.fromYaml(slot["kind"] as String)!!,
                        required = slot["required"] as Boolean? ?: false,
                        description = slot["description"] as String? ?: "",
                        enumValues = (slot["values"] as List<Any?>?)?.map { it.toString() } ?: listOf(),
                        captureType = captures.firstOrNull { it.first == name }?.second,
                    )
                }

            ToolSpec(
                name = tool["name"] as String,
                skillId = skillId,
                sentence = when {
                    sentenceId != null ->
                        SentenceMapping.Fixed(sentenceId, captures.map { it.first })
                    sentenceFrom != null -> SentenceMapping.ByEnum(
                        slot = sentenceFrom["slot"] as String,
                        valueToSentenceId = (sentenceFrom["map"] as Map<Any?, Any?>)
                            .entries.associate { it.key.toString() to it.value.toString() },
                    )
                    else -> null
                },
                description = tool["description"] as String,
                confirm = tool["confirm"] as Boolean? ?: true,
                slots = slots,
                examples = (tool["examples"] as List<String>?) ?: listOf(),
            )
        }
        return ToolCatalog(tools)
    }
}
