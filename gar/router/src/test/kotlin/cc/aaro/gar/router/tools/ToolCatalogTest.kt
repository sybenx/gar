package cc.aaro.gar.router.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

private val DEFINITIONS = """
    skills:
      - id: timer
        specificity: high
        sentences:
          - id: set
            captures:
              - id: duration
                type: duration
              - id: name
                type: string
          - id: cancel
            captures:
              - id: name
                type: string
      - id: media
        specificity: high
        sentences:
          - id: play
          - id: pause
      - id: calculator
        specificity: medium
        sentences:
          - id: calculate
            captures:
              - id: calculation
                type: string
          - id: count
            captures:
              - id: n
                type: number
      - id: calendar
        specificity: high
        sentences:
          - id: add
            captures:
              - id: when
                type: date_time
""".trimIndent()

private val GOOD_TOOLS = """
    tools:
      - name: timer_set
        skill: timer
        sentence: set
        description: Start a countdown timer.
        slots:
          duration: {kind: duration, required: true, description: "How long, as said."}
          name: {kind: text, description: Optional name.}
        examples: [set a timer for ten minutes]
      - name: media_control
        skill: media
        sentence_from: {slot: action, map: {play: play, pause: pause}}
        description: Control the media.
        confirm: false
        slots:
          action: {kind: enum, values: [play, pause], required: true}
      - name: calendar_add
        skill: calendar
        sentence: add
        description: Add an event.
        slots:
          when: {kind: datetime, required: true}
      - name: alarm_set
        skill: alarm
        description: Set an alarm (LLM-only, no sentences).
        slots:
          time: {kind: time, required: true}
""".trimIndent()

/** Asserts that loading fails with a problem containing each of [expected]. */
private fun shouldFail(toolsYaml: String, vararg expected: String, definitions: String = DEFINITIONS) {
    val e = shouldThrow<ToolCatalogException> { ToolCatalog.load(toolsYaml, definitions) }
    for (part in expected) {
        withClue("problems: ${e.problems}") {
            e.problems.any { part in it } shouldBe true
        }
    }
}

/** One tool in a `tools:` list, from a block of YAML indented as a list item's content. */
private fun tool(body: String) = "tools:\n  - " + body.trimIndent().replace("\n", "\n    ")

class ToolCatalogTest : StringSpec({
    "the real tools.yml and skill_definitions.yml load" {
        val catalog = ToolCatalog.load(
            File("../../app/src/main/gar/tools.yml").readText(),
            File("../../app/src/main/sentences/skill_definitions.yml").readText(),
        )
        // the full catalog in prompt order: Dicio's skills, then the GAR Step 3 skill groups and
        // Step 4's song
        catalog.tools.map { it.name } shouldContainExactly listOf(
            "timer_set", "timer_cancel", "timer_query", "call_contact", "open_app", "media_control",
            "flashlight", "current_time", "navigate", "calculate", "read_notifications",
            "wake_word", "alarm_set", "alarm_list", "alarm_cancel", "calendar_add",
            "calendar_query", "reminder_add", "sms_send", "volume", "volume_set", "do_not_disturb",
            "device_setting", // gar:settings: took the wifi and bluetooth tools' place
            "stopwatch", "date_query", "song_identify",
            "media_play", // gar:media
            "weather", // gar:weather
        )
        val timer = catalog.byName("timer_set")!!
        timer.sentence shouldBe SentenceMapping.Fixed("set", listOf("duration", "name"))
        timer.slots[0].description shouldBe "How long the timer runs, exactly as the user said it."
        catalog.byName("flashlight")!!.sentence shouldBe
            SentenceMapping.ByEnum("state", mapOf("on" to "turn_on", "off" to "turn_off"))
        catalog.forSkills(setOf("timer")).map { it.name } shouldContainExactly
            listOf("timer_set", "timer_cancel", "timer_query")
    }

    "confirm texts and lock screen permission" {
        val catalog = ToolCatalog.load(
            """
            tools:
              - name: timer_set
                skill: timer
                sentence: set
                description: Start a countdown timer.
                confirm_text: "Set a {name} timer for {duration}?"
                on_lock_screen: true
                slots:
                  duration: {kind: duration, required: true}
                  name: {kind: text}
              - name: media_control
                skill: media
                sentence_from: {slot: action, map: {play: play, pause: pause}}
                description: Control the media.
                confirm_text: {play: "Resume playback?", pause: "Pause playback?"}
                slots:
                  action: {kind: enum, values: [play, pause], required: true}
            """.trimIndent(),
            DEFINITIONS,
        )
        catalog.tools[0].confirmText shouldBe ConfirmText.Template("Set a {name} timer for {duration}?")
        catalog.tools[0].onLockScreen shouldBe true
        catalog.tools[1].confirmText shouldBe ConfirmText.ByValue(
            "action", mapOf("play" to "Resume playback?", "pause" to "Pause playback?")
        )
        catalog.tools[1].onLockScreen shouldBe false

        shouldFail(tool("""
            name: timer_set
            skill: timer
            sentence: set
            description: Start.
            confirm_text: "Set {when}?"
            on_lock_screen: sometimes
            slots:
              duration: {kind: duration, required: true}
              name: {kind: text}
        """), "placeholder '{when}' is not a slot", "'on_lock_screen' must be true or false")
        shouldFail(tool("""
            name: media_control
            skill: media
            sentence_from: {slot: action, map: {play: play, pause: pause}}
            description: Control.
            confirm_text: {play: "Play?"}
            slots:
              action: {kind: enum, values: [play, pause], required: true}
        """), "confirm_text keys (play) must be exactly the values of slot 'action'")
        shouldFail(tool("""
            name: timer_set
            skill: timer
            sentence: set
            description: Start.
            confirm_text: {a: b}
            slots:
              duration: {kind: duration, required: true}
              name: {kind: text}
        """), "confirm_text by value needs exactly one enum slot")
    }

    "a good catalog loads with defaults and capture types" {
        val catalog = ToolCatalog.load(GOOD_TOOLS, DEFINITIONS)
        catalog.tools shouldHaveSize 4
        val timer = catalog.tools[0]
        timer shouldBe ToolSpec(
            name = "timer_set",
            skillId = "timer",
            sentence = SentenceMapping.Fixed("set", listOf("duration", "name")),
            description = "Start a countdown timer.",
            confirm = true,
            slots = listOf(
                SlotSpec("duration", SlotKind.DURATION, true, "How long, as said.", captureType = "duration"),
                SlotSpec("name", SlotKind.TEXT, false, "Optional name.", captureType = "string"),
            ),
            examples = listOf("set a timer for ten minutes"),
        )
        val media = catalog.tools[1]
        media.confirm shouldBe false
        media.sentence shouldBe SentenceMapping.ByEnum("action", mapOf("play" to "play", "pause" to "pause"))
        media.slots[0].captureType shouldBe null
        media.slots[0].enumValues shouldBe listOf("play", "pause")
        catalog.tools[2].slots[0].captureType shouldBe "date_time"
        catalog.tools[3].sentence shouldBe null
        catalog.tools[3].slots[0].captureType shouldBe null
        catalog.byName("nope") shouldBe null
    }

    "slot order follows the yaml, captureOrder follows the definition" {
        val catalog = ToolCatalog.load(tool("""
            name: timer_set
            skill: timer
            sentence: set
            description: d
            slots:
              name: {kind: text}
              duration: {kind: duration, required: true}
        """), DEFINITIONS)
        catalog.tools[0].slots.map { it.name } shouldContainExactly listOf("name", "duration")
        catalog.tools[0].sentence shouldBe SentenceMapping.Fixed("set", listOf("duration", "name"))
    }

    "invalid yaml and wrong top level" {
        shouldFail("tools: [", "tools.yml: invalid YAML")
        shouldFail("other: []", "expected a top-level 'tools' list")
        shouldFail("", "tools.yml: empty document")
        shouldFail(GOOD_TOOLS, "skill_definitions.yml: expected a top-level 'skills' list", definitions = "x: 1")
        shouldFail(tool("""
            name: a
            name: b
            skill: alarm
            description: d
        """), "invalid YAML")
    }

    "names" {
        shouldFail(tool("""
            skill: alarm
            description: d
        """), "tools[0]: missing 'name'")
        shouldFail(tool("""
            name: Bad-Name
            skill: alarm
            description: d
        """), "name must match")
        shouldFail(tool("""
            name: no_match
            skill: alarm
            description: d
        """), "'no_match' is reserved")
        shouldFail("""
            tools:
              - {name: a, skill: alarm, description: d}
              - {name: a, skill: alarm, description: d}
        """.trimIndent(), "tool 'a': duplicate tool name")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots:
              Bad: {kind: text}
        """), "slot name 'Bad' must match")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots:
              description: {kind: text}
        """), "slot name 'description' is reserved")
    }

    "missing and malformed fields" {
        shouldFail(tool("""
            name: a
            description: d
        """), "missing 'skill'")
        shouldFail(tool("""
            name: a
            skill: alarm
        """), "missing 'description'")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            confirm: maybe
            examples: nope
            colour: blue
        """), "'confirm' must be true or false", "'examples' must be a list", "unknown key 'colour'")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots: [x]
        """), "'slots' must be a map")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots:
              x: {kind: banana, required: yes}
              y: {kind: text, required: "no", wat: 1}
              z: text
        """), "slot 'x': 'kind' must be one of", "slot 'y': 'required' must be true or false",
            "slot 'y': unknown key 'wat'", "slot 'z' must be a map")
    }

    "keywords are normalized, never required, and must cover the tool's examples" {
        val catalog = ToolCatalog.load(tool("""
            name: current_time
            skill: alarm
            description: Tell the time.
            keywords: [Time, "o’clock", wi fi]
            examples: ["What's the TIME?", "is it five o'clock yet", switch off the wi fi]
        """), DEFINITIONS)
        catalog.tools.single().keywords shouldBe listOf("time", "o'clock", "wi fi")
        ToolCatalog.load(GOOD_TOOLS, DEFINITIONS).tools.all { it.keywords.isEmpty() } shouldBe true

        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            keywords: [time, clock]
            examples: [what time is it, good morning]
        """), "example 'good morning' contains none of the keywords")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            keywords: time
        """), "'keywords' must be a non-empty list")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            keywords: []
        """), "'keywords' must be a non-empty list")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            keywords: [time, "?!", 3, Time]
        """), "keyword '?!' must be a string with at least one word", "keyword '3'",
            "duplicate keywords")
    }

    "a comma in an unquoted flow-mapping description is caught" {
        // YAML splits it into a second key; this was in the first tools.yml
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots:
              x: {kind: text, description: The name, exactly as said.}
        """), "slot 'x': unknown key 'exactly as said.'")
    }

    "enum values" {
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots:
              x: {kind: enum}
        """), "kind enum needs a non-empty 'values' list")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots:
              x: {kind: enum, values: [a, a, true, " b"]}
        """), "duplicate enum values", "enum value 'true' must be a non-blank string",
            "enum value ' b' must be")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots:
              x: {kind: text, values: [a]}
        """), "'values' is only allowed for kind enum")
    }

    "fixed sentence mapping" {
        shouldFail(tool("""
            name: a
            skill: nope
            sentence: set
            description: d
        """), "unknown skill 'nope'")
        shouldFail(tool("""
            name: a
            skill: timer
            sentence: nope
            description: d
        """), "unknown sentence 'nope' in skill 'timer'")
        shouldFail(tool("""
            name: a
            skill: timer
            sentence: set
            description: d
            slots:
              duration: {kind: duration}
              extra: {kind: text}
        """), "slot 'extra' is not a capture of sentence timer.set",
            "capture 'name' of sentence timer.set has no slot")
        shouldFail(tool("""
            name: a
            skill: timer
            sentence: set
            sentence_from: {slot: x, map: {}}
            description: d
        """), "has both 'sentence' and 'sentence_from'")
        shouldFail(tool("""
            name: a
            skill: timer
            sentence: [set]
            description: d
        """), "'sentence' must be a sentence id")
    }

    "slot kinds must fit the capture types" {
        shouldFail(tool("""
            name: a
            skill: timer
            sentence: set
            description: d
            slots:
              duration: {kind: text}
              name: {kind: duration}
        """), "slot 'duration' of kind text can't feed capture type 'duration'",
            "slot 'name' of kind duration can't feed capture type 'string'")
        shouldFail(tool("""
            name: a
            skill: calculator
            sentence: count
            description: d
            slots:
              n: {kind: text}
        """), "can't feed capture type 'number'")
        shouldFail(tool("""
            name: a
            skill: calendar
            sentence: add
            description: d
            slots:
              when: {kind: duration}
        """), "can't feed capture type 'date_time'")
        shouldFail(tool("""
            name: a
            skill: x
            sentence: s
            description: d
            slots:
              c: {kind: text}
        """), "capture 'c' has unknown type 'weird'", definitions = """
            skills:
              - id: x
                sentences:
                  - id: s
                    captures: [{id: c, type: weird}]
        """.trimIndent())
        // every compatible pair loads
        for ((type, kinds) in mapOf(
            "string" to listOf("text", "contact", "app"),
            "number" to listOf("number"),
            "duration" to listOf("duration"),
            "date_time" to listOf("datetime", "date", "time"),
        )) {
            for (kind in kinds) {
                ToolCatalog.load(tool("""
                    name: a
                    skill: x
                    sentence: s
                    description: d
                    slots:
                      c: {kind: $kind}
                """), "skills: [{id: x, sentences: [{id: s, captures: [{id: c, type: $type}]}]}]")
            }
        }
    }

    "enum sentence mapping" {
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: action}
            description: d
        """), "'sentence_from' must be {slot")
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: action, map: {play: play, stop: stop}}
            description: d
            slots:
              action: {kind: enum, values: [play, stop], required: true}
        """), "unknown sentence 'stop' in skill 'media'")
        shouldFail(tool("""
            name: a
            skill: timer
            sentence_from: {slot: action, map: {go: set}}
            description: d
            slots:
              action: {kind: enum, values: [go], required: true}
        """), "sentence timer.set has captures")
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: action, map: {play: play, pause: pause}}
            description: d
            slots:
              action: {kind: enum, values: [play], required: true}
        """), "must be exactly the values of slot 'action'")
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: action, map: {play: play}}
            description: d
            slots:
              action: {kind: text, required: true}
        """), "sentence_from slot 'action' must have kind enum")
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: action, map: {play: play}}
            description: d
            slots:
              action: {kind: enum, values: [play]}
        """), "sentence_from slot 'action' must be required")
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: nope, map: {play: play}}
            description: d
        """), "sentence_from slot 'nope' is not declared")
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: action, map: {play: play}}
            description: d
            slots:
              action: {kind: enum, values: [play], required: true}
              volume: {kind: number}
        """), "slot 'volume' has no capture to feed")
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: action, map: {true: play}}
            description: d
            slots:
              action: {kind: enum, values: ["true"], required: true}
        """), "sentence_from map entries must be strings")
    }

    "malformed entries, examples and descriptions" {
        shouldFail("tools: [just a string]", "tools[0]: expected a map")
        shouldFail(tool("""
            name: 5
            skill: alarm
            description: d
        """), "tools[0]: missing 'name'")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            examples: [hello, 5]
        """), "tool 'a': examples must be strings")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            slots:
              x: {kind: text, description: [a, b]}
        """), "slot 'x': 'description' must be a string")
        for (reserved in listOf("description", "type", "properties", "required", "nullable")) {
            shouldFail(tool("""
                name: a
                skill: alarm
                description: d
                slots:
                  $reserved: {kind: text}
            """), "slot name '$reserved' is reserved")
        }
        // an empty list is an empty catalog
        ToolCatalog.load("tools: []", DEFINITIONS).tools shouldBe listOf()
    }

    "confirm text shapes" {
        fun confirmTool(confirmText: String) = tool("""
            name: media_control
            skill: media
            sentence_from: {slot: action, map: {play: play, pause: pause}}
            description: Control the media.
            confirm_text: $confirmText
            slots:
              action: {kind: enum, values: [play, pause], required: true}
        """)
        ToolCatalog.load(tool("""
            name: timer_set
            skill: timer
            sentence: set
            description: d
            confirm_text: "  Set a {name} timer?  "
            slots:
              duration: {kind: duration, required: true}
              name: {kind: text}
        """), DEFINITIONS).tools.single().confirmText shouldBe ConfirmText.Template("Set a {name} timer?")
        shouldFail(confirmTool("\"  \""), "confirm_text templates must be non-blank strings")
        shouldFail(confirmTool("[a]"), "'confirm_text' must be a string or a map from enum value to string")
        shouldFail(confirmTool("{true: \"Play?\", pause: \"Pause?\"}"), "confirm_text keys must be strings",
            "confirm_text keys (true, pause) must be exactly the values of slot 'action'")
        shouldFail(tool("""
            name: a
            skill: alarm
            description: d
            confirm_text: {on: "On?", off: "Off?"}
            slots:
              x: {kind: enum, values: ["on", "off"], required: true}
              y: {kind: enum, values: [a, b]}
        """), "confirm_text by value needs exactly one enum slot")
        // braces that aren't a {slot} placeholder would show on the card as they are
        shouldFail(confirmTool("\"Set a {Name} timer for { duration }?\""), "'{' or '}' outside a {slot} placeholder")
        shouldFail(confirmTool("{play: \"Play {}?\", pause: \"Pause?\"}"), "'{' or '}' outside a {slot} placeholder")
    }

    "confirm_text by the values of one of several enum slots names the slot" { // gar:settings
        fun twoEnums(confirmText: String) = tool("""
            name: a
            skill: alarm
            description: d
            confirm_text: $confirmText
            slots:
              x: {kind: enum, values: [lamp, fan], required: true}
              y: {kind: enum, values: ["on", "off"], required: true}
        """)
        ToolCatalog.load(
            twoEnums("{slot: y, map: {\"on\": \"Turn on the {x}?\", \"off\": \"Turn off the {x}?\"}}"),
            DEFINITIONS,
        ).tools.single().confirmText shouldBe ConfirmText.ByValue(
            "y", mapOf("on" to "Turn on the {x}?", "off" to "Turn off the {x}?")
        )
        shouldFail(twoEnums("{slot: z, map: {\"on\": \"On?\", \"off\": \"Off?\"}}"),
            "confirm_text slot 'z' is not an enum slot of the tool")
        shouldFail(twoEnums("{slot: y, map: {\"on\": \"On?\"}}"),
            "confirm_text keys (on) must be exactly the values of slot 'y'")
        shouldFail(twoEnums("{slot: x, map: {lamp: \"Lamp {w}?\", fan: \"Fan?\"}}"),
            "confirm_text placeholder '{w}' is not a slot of the tool")
    }

    "skill definitions: bad documents, entries without an id, captures without a type" {
        shouldFail(GOOD_TOOLS, "skill_definitions.yml: invalid YAML", definitions = "skills: [")
        shouldFail(GOOD_TOOLS, "skill_definitions.yml: empty document", definitions = "")
        shouldFail(tool("""
            name: a
            skill: x
            sentence: s
            description: d
        """), "unknown skill 'x'", definitions = "skills: [{sentences: [{id: s}]}]")
        shouldFail(tool("""
            name: a
            skill: x
            sentence: s
            description: d
            slots:
              c: {kind: text}
        """), "capture 'c' has unknown type ''",
            definitions = "skills: [{id: x, sentences: [{id: s, captures: [{id: c}]}]}]")
    }

    "more sentence mappings" {
        // a fixed sentence without captures needs no slots
        ToolCatalog.load(tool("""
            name: a
            skill: media
            sentence: play
            description: d
        """), DEFINITIONS).tools.single().sentence shouldBe SentenceMapping.Fixed("play", listOf())
        // an enum slot may feed a string capture
        ToolCatalog.load(tool("""
            name: a
            skill: x
            sentence: s
            description: d
            slots:
              c: {kind: enum, values: [a, b]}
        """), "skills: [{id: x, sentences: [{id: s, captures: [{id: c, type: string}]}]}]")
            .tools.single().slots.single().captureType shouldBe "string"
        // an unknown skill is one problem, not one per sentence
        val e = shouldThrow<ToolCatalogException> {
            ToolCatalog.load(tool("""
                name: a
                skill: nope
                sentence_from: {slot: action, map: {play: play, pause: pause}}
                description: d
                slots:
                  action: {kind: enum, values: [play, pause], required: true}
            """), DEFINITIONS)
        }
        e.problems shouldBe listOf("tool 'a': unknown skill 'nope' (not in skill_definitions.yml)")
        shouldFail(tool("""
            name: a
            skill: media
            sentence_from: {slot: action, map: {play: play}, extra: 1}
            description: d
            slots:
              action: {kind: enum, values: [play], required: true}
        """), "'sentence_from' must be {slot: <enum slot>, map: {<value>: <sentence id>}}")
    }

    "an example may contain a keyword with an ending" {
        ToolCatalog.load(tool("""
            name: alarm_list
            skill: alarm
            description: d
            keywords: [alarm]
            examples: [show my alarms]
        """), DEFINITIONS).tools.single().keywords shouldBe listOf("alarm")
    }

    "all problems are reported together" {
        val e = shouldThrow<ToolCatalogException> {
            ToolCatalog.load("""
                tools:
                  - {name: A, skill: timer, description: d}
                  - {name: b, skill: timer, sentence: nope, description: d}
                  - {name: c, skill: nope, sentence: set, description: d}
            """.trimIndent(), DEFINITIONS)
        }
        e.problems shouldHaveSize 3
        e.message!! shouldContain "Invalid GAR tool catalog:\n- "
    }
})
