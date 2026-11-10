package cc.aaro.gar.router.tools

import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.Utterances
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Parses and validates `tools.yml` against `skill_definitions.yml`, collecting every problem
 * instead of stopping at the first one. Rules (gar/DESIGN.md, "Tool catalog"):
 * - tool and slot names match [NAME_REGEX], tool names are unique and not `no_match`;
 * - `sentence` (fixed mapping): the skill and sentence exist, and the slot keys are exactly the
 *   sentence's capture ids. Every capture must be declared: the generated sentence class takes
 *   all captures, and a capture the model can't fill would silently stay null;
 * - `sentence_from` (enum mapping): the selector slot is the tool's only slot, is required, has
 *   kind `enum` with exactly the map's keys as values, and every mapped sentence exists without
 *   captures;
 * - neither: an LLM-only tool, whose skill doesn't need to be in `skill_definitions.yml`;
 * - slot kinds are compatible with the capture types they feed ([COMPATIBLE_KINDS]);
 * - slot names that FunctionGemma's chat template silently drops ([RESERVED_SLOT_NAMES]) are
 *   rejected, so that both prompt formats always see the same slots;
 * - `confirm_text` (never shown to the model) is a template whose `{slot}` placeholders name slots
 *   of the tool (other braces are rejected), or a map from each value of the tool's only enum
 *   slot to such a template, or `{slot: <enum slot>, map: {...}}` for one of several enum slots;
 * - `on_lock_screen` (default false) lets the model's calls of the tool run on a locked phone;
 * - `keywords` (optional, never shown to the model): non-blank words or phrases, stored normalized,
 *   and every example of the tool must contain one of them ([KeywordFilter] would drop it).
 */
internal class ToolCatalogLoader(toolsYaml: String, skillDefinitionsYaml: String) {
    private val problems = mutableListOf<String>()
    private val definitions: Map<String, Map<String, List<Capture>>>? =
        parseDefinitions(skillDefinitionsYaml)
    private val toolsRoot: Any? = parseYaml("tools.yml", toolsYaml)

    private data class Capture(val id: String, val type: String)

    fun load(): ToolCatalog {
        val tools = mutableListOf<ToolSpec>()
        val rawTools = (toolsRoot as? Map<*, *>)?.get("tools")
        if (toolsRoot != null && rawTools !is List<*>) {
            problems += "tools.yml: expected a top-level 'tools' list"
        }
        val seenNames = mutableSetOf<String>()
        (rawTools as? List<*>)?.forEachIndexed { index, rawTool ->
            val tool = loadTool(index, rawTool) ?: return@forEachIndexed
            if (!seenNames.add(tool.name)) {
                problems += "tool '${tool.name}': duplicate tool name"
            }
            tools += tool
        }
        if (problems.isNotEmpty()) {
            throw ToolCatalogException(problems.toList())
        }
        return ToolCatalog(tools)
    }

    private fun loadTool(index: Int, rawTool: Any?): ToolSpec? {
        val map = rawTool as? Map<*, *>
        if (map == null) {
            problems += "tools[$index]: expected a map"
            return null
        }
        val rawName = map["name"]
        val where = if (rawName is String) "tool '$rawName'" else "tools[$index]"
        fun problem(message: String) {
            problems += "$where: $message"
        }

        for (key in map.keys) {
            if (key !in TOOL_KEYS) problem("unknown key '$key'")
        }
        val name = when {
            rawName !is String -> { problem("missing 'name'"); null }
            !NAME_REGEX.matches(rawName) -> { problem("name must match $NAME_REGEX"); null }
            rawName == ToolCatalog.NO_MATCH -> { problem("'${ToolCatalog.NO_MATCH}' is reserved"); null }
            else -> rawName
        }
        val skillId = (map["skill"] as? String)?.takeIf { it.isNotBlank() }
            ?: run { problem("missing 'skill'"); null }
        val description = (map["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: run { problem("missing 'description'"); null }
        val confirm = when (val c = map["confirm"]) {
            null -> true
            is Boolean -> c
            else -> { problem("'confirm' must be true or false"); true }
        }
        val onLockScreen = when (val l = map["on_lock_screen"]) {
            null -> false
            is Boolean -> l
            else -> { problem("'on_lock_screen' must be true or false"); false }
        }
        val examples = when (val e = map["examples"]) {
            null -> listOf()
            is List<*> -> e.mapNotNull {
                it as? String ?: run { problem("examples must be strings"); null }
            }
            else -> { problem("'examples' must be a list"); listOf() }
        }
        val slots = loadSlots(map["slots"], ::problem)
        val keywords = keywords(map["keywords"], examples, ::problem)

        val sentence = map["sentence"]
        val sentenceFrom = map["sentence_from"]
        val mapping: SentenceMapping?
        var typedSlots = slots
        if (sentence != null && sentenceFrom != null) {
            problem("has both 'sentence' and 'sentence_from'")
            mapping = null
        } else if (sentence != null) {
            val result = fixedMapping(skillId, sentence, slots, ::problem)
            mapping = result?.first
            typedSlots = result?.second ?: slots
        } else if (sentenceFrom != null) {
            mapping = enumMapping(skillId, sentenceFrom, slots, ::problem)
        } else {
            mapping = null // LLM-only tool
        }

        val confirmText = map["confirm_text"]?.let { confirmText(it, slots, ::problem) }

        if (name == null || skillId == null || description == null) {
            return null
        }
        return ToolSpec(
            name = name,
            skillId = skillId,
            sentence = mapping,
            description = description,
            confirm = confirm,
            slots = typedSlots,
            examples = examples,
            confirmText = confirmText,
            onLockScreen = onLockScreen,
            keywords = keywords,
        )
    }

    /**
     * `keywords`: a list of words or phrases, normalized like utterances. The tool's examples are
     * how people ask for it, so each must contain a keyword.
     */
    private fun keywords(raw: Any?, examples: List<String>, problem: (String) -> Unit): List<String> {
        if (raw == null) {
            return listOf()
        }
        if (raw !is List<*> || raw.isEmpty()) {
            problem("'keywords' must be a non-empty list of strings")
            return listOf()
        }
        val keywords = raw.mapNotNull { keyword ->
            val normalized = (keyword as? String)?.let(Utterances::normalize)
            if (normalized.isNullOrEmpty()) {
                problem("keyword '$keyword' must be a string with at least one word")
                null
            } else {
                normalized
            }
        }
        if (keywords.toSet().size != keywords.size) {
            problem("duplicate keywords")
        }
        for (example in examples) {
            if (!KeywordFilter.containsAny(Utterances.normalize(example), keywords)) {
                problem("example '$example' contains none of the keywords")
            }
        }
        return keywords
    }

    /**
     * `confirm_text`: a template, or a map from each value of the tool's only enum slot to a
     * template, or `{slot: <enum slot>, map: {...}}` with such a map for one of several enum
     * slots. Placeholders must name slots of the tool.
     */
    private fun confirmText(raw: Any, slots: List<SlotSpec>, problem: (String) -> Unit): ConfirmText? {
        fun checkTemplate(template: Any?): String? {
            if (template !is String || template.isBlank()) {
                problem("confirm_text templates must be non-blank strings")
                return null
            }
            for (match in PLACEHOLDER_REGEX.findAll(template)) {
                val slotName = match.groupValues[1]
                if (slots.none { it.name == slotName }) {
                    problem("confirm_text placeholder '{$slotName}' is not a slot of the tool")
                }
            }
            // "{Name}" or "{ name }" would be shown on the card as it is
            if (PLACEHOLDER_REGEX.replace(template, "").any { it == '{' || it == '}' }) {
                problem("confirm_text '$template' has a '{' or '}' outside a {slot} placeholder")
            }
            return template.trim()
        }

        // {slot: <enum slot>, map: {<value>: <template>}}: by the values of one of several enum
        // slots (device_setting's action)
        val selector = (raw as? Map<*, *>)
            ?.takeIf { it.keys == setOf("slot", "map") && it["map"] is Map<*, *> }
        val selectedSlot = selector?.let { map ->
            slots.firstOrNull { it.kind == SlotKind.ENUM && it.name == map["slot"] } ?: run {
                problem("confirm_text slot '${map["slot"]}' is not an enum slot of the tool")
                return null
            }
        }
        return when (val byValue = selector?.get("map") ?: raw) {
            is String -> checkTemplate(byValue)?.let { ConfirmText.Template(it) }
            is Map<*, *> -> {
                val enumSlot = selectedSlot ?: slots.singleOrNull { it.kind == SlotKind.ENUM }
                if (enumSlot == null) {
                    problem("confirm_text by value needs exactly one enum slot, or " +
                        "{slot: <enum slot>, map: {<value>: <template>}}")
                    return null
                }
                val templates = linkedMapOf<String, String>()
                for ((value, template) in byValue) {
                    if (value !is String) {
                        problem("confirm_text keys must be strings (quote yes/no/on/off/true/false)")
                        continue
                    }
                    checkTemplate(template)?.let { templates[value] = it }
                }
                if (templates.keys != enumSlot.enumValues.toSet()) {
                    problem("confirm_text keys (${byValue.keys.joinToString()}) must be exactly the " +
                        "values of slot '${enumSlot.name}' (${enumSlot.enumValues.joinToString()})")
                    return null
                }
                ConfirmText.ByValue(enumSlot.name, templates)
            }
            else -> {
                problem("'confirm_text' must be a string or a map from enum value to string")
                null
            }
        }
    }

    private fun loadSlots(raw: Any?, problem: (String) -> Unit): List<SlotSpec> {
        if (raw == null) {
            return listOf()
        }
        if (raw !is Map<*, *>) {
            problem("'slots' must be a map from slot name to slot definition")
            return listOf()
        }
        return raw.entries.mapNotNull { (rawSlotName, rawSlot) ->
            val slotName = rawSlotName as? String
            if (slotName == null || !NAME_REGEX.matches(slotName)) {
                problem("slot name '$rawSlotName' must match $NAME_REGEX")
                return@mapNotNull null
            }
            if (slotName in RESERVED_SLOT_NAMES) {
                problem("slot name '$slotName' is reserved (FunctionGemma's chat template drops it)")
                return@mapNotNull null
            }
            val slotMap = rawSlot as? Map<*, *>
            if (slotMap == null) {
                problem("slot '$slotName' must be a map")
                return@mapNotNull null
            }
            for (key in slotMap.keys) {
                if (key !in SLOT_KEYS) problem("slot '$slotName': unknown key '$key'")
            }
            val kindName = slotMap["kind"]
            val kind = (kindName as? String)?.let { SlotKind.fromYaml(it) }
            if (kind == null) {
                problem("slot '$slotName': 'kind' must be one of " +
                    SlotKind.entries.joinToString { it.yamlName })
                return@mapNotNull null
            }
            val required = when (val r = slotMap["required"]) {
                null -> false
                is Boolean -> r
                else -> { problem("slot '$slotName': 'required' must be true or false"); false }
            }
            val description = when (val d = slotMap["description"]) {
                null -> ""
                is String -> d.trim()
                else -> { problem("slot '$slotName': 'description' must be a string"); "" }
            }
            val values = slotMap["values"]
            val enumValues = if (kind == SlotKind.ENUM) {
                enumValues(slotName, values, problem)
            } else {
                if (values != null) problem("slot '$slotName': 'values' is only allowed for kind enum")
                listOf()
            }
            SlotSpec(
                name = slotName,
                kind = kind,
                required = required,
                description = description,
                enumValues = enumValues,
            )
        }
    }

    private fun enumValues(slotName: String, raw: Any?, problem: (String) -> Unit): List<String> {
        if (raw !is List<*> || raw.isEmpty()) {
            problem("slot '$slotName': kind enum needs a non-empty 'values' list")
            return listOf()
        }
        val values = raw.mapNotNull {
            if (it is String && it.isNotBlank() && it == it.trim()) {
                it
            } else {
                problem("slot '$slotName': enum value '$it' must be a non-blank string " +
                    "without surrounding spaces (quote yes/no/on/off/true/false)")
                null
            }
        }
        if (values.toSet().size != values.size) {
            problem("slot '$slotName': duplicate enum values")
        }
        return values
    }

    /** @return the mapping and the slots with [SlotSpec.captureType] set, or null on errors */
    private fun fixedMapping(
        skillId: String?,
        rawSentence: Any,
        slots: List<SlotSpec>,
        problem: (String) -> Unit,
    ): Pair<SentenceMapping, List<SlotSpec>>? {
        if (rawSentence !is String) {
            problem("'sentence' must be a sentence id")
            return null
        }
        val sentences = sentencesOf(skillId, problem) ?: return null
        val captures = sentences[rawSentence]
        if (captures == null) {
            problem("unknown sentence '$rawSentence' in skill '$skillId' (known: " +
                sentences.keys.joinToString() + ")")
            return null
        }
        val captureIds = captures.map { it.id }
        val slotNames = slots.map { it.name }
        var ok = true
        for (slot in slots) {
            if (slot.name !in captureIds) {
                problem("slot '${slot.name}' is not a capture of sentence $skillId.$rawSentence " +
                    "(captures: ${captureIds.joinToString().ifEmpty { "none" }})")
                ok = false
            }
        }
        for (captureId in captureIds) {
            if (captureId !in slotNames) {
                problem("capture '$captureId' of sentence $skillId.$rawSentence has no slot")
                ok = false
            }
        }
        val typedSlots = slots.map { slot ->
            val capture = captures.firstOrNull { it.id == slot.name } ?: return@map slot
            val compatible = COMPATIBLE_KINDS[capture.type]
            if (compatible == null) {
                problem("capture '${capture.id}' has unknown type '${capture.type}'")
                ok = false
            } else if (slot.kind !in compatible) {
                problem("slot '${slot.name}' of kind ${slot.kind.yamlName} can't feed capture " +
                    "type '${capture.type}' (allowed: ${compatible.joinToString { it.yamlName }})")
                ok = false
            }
            slot.copy(captureType = capture.type)
        }
        return if (ok) Pair(SentenceMapping.Fixed(rawSentence, captureIds), typedSlots) else null
    }

    private fun enumMapping(
        skillId: String?,
        raw: Any,
        slots: List<SlotSpec>,
        problem: (String) -> Unit,
    ): SentenceMapping? {
        val map = raw as? Map<*, *>
        val selector = map?.get("slot") as? String
        val valueMap = map?.get("map") as? Map<*, *>
        if (map == null || selector == null || valueMap == null || map.size != 2) {
            problem("'sentence_from' must be {slot: <enum slot>, map: {<value>: <sentence id>}}")
            return null
        }
        val sentences = sentencesOf(skillId, problem)
        var ok = sentences != null
        val valueToSentence = linkedMapOf<String, String>()
        for ((rawValue, rawSentence) in valueMap) {
            if (rawValue !is String || rawSentence !is String) {
                problem("sentence_from map entries must be strings: '$rawValue' -> '$rawSentence' " +
                    "(quote yes/no/on/off/true/false)")
                ok = false
                continue
            }
            valueToSentence[rawValue] = rawSentence
            val captures = sentences?.get(rawSentence)
            if (sentences != null && captures == null) {
                problem("unknown sentence '$rawSentence' in skill '$skillId'")
                ok = false
            } else if (captures != null && captures.isNotEmpty()) {
                problem("sentence $skillId.$rawSentence has captures, so it can't be selected " +
                    "by an enum value")
                ok = false
            }
        }
        val slot = slots.firstOrNull { it.name == selector }
        if (slot == null) {
            problem("sentence_from slot '$selector' is not declared in 'slots'")
            ok = false
        } else {
            if (slot.kind != SlotKind.ENUM) {
                problem("sentence_from slot '$selector' must have kind enum")
                ok = false
            } else if (slot.enumValues.toSet() != valueToSentence.keys) {
                problem("sentence_from map keys (${valueToSentence.keys.joinToString()}) must be " +
                    "exactly the values of slot '$selector' (${slot.enumValues.joinToString()})")
                ok = false
            }
            if (!slot.required) {
                problem("sentence_from slot '$selector' must be required")
                ok = false
            }
        }
        for (other in slots) {
            if (other.name != selector) {
                problem("slot '${other.name}' has no capture to feed (sentence_from tools only " +
                    "have the selector slot)")
                ok = false
            }
        }
        return if (ok) SentenceMapping.ByEnum(selector, valueToSentence) else null
    }

    private fun sentencesOf(
        skillId: String?,
        problem: (String) -> Unit,
    ): Map<String, List<Capture>>? {
        if (skillId == null || definitions == null) {
            return null
        }
        return definitions[skillId] ?: run {
            problem("unknown skill '$skillId' (not in skill_definitions.yml)")
            null
        }
    }

    private fun parseDefinitions(yaml: String): Map<String, Map<String, List<Capture>>>? {
        val root = parseYaml("skill_definitions.yml", yaml) ?: return null
        val skills = (root as? Map<*, *>)?.get("skills") as? List<*>
        if (skills == null) {
            problems += "skill_definitions.yml: expected a top-level 'skills' list"
            return null
        }
        val result = linkedMapOf<String, Map<String, List<Capture>>>()
        for (skill in skills) {
            val skillMap = skill as? Map<*, *> ?: continue
            val id = skillMap["id"] as? String ?: continue
            val sentences = linkedMapOf<String, List<Capture>>()
            for (sentence in skillMap["sentences"] as? List<*> ?: listOf<Any>()) {
                val sentenceMap = sentence as? Map<*, *> ?: continue
                val sentenceId = sentenceMap["id"] as? String ?: continue
                sentences[sentenceId] = (sentenceMap["captures"] as? List<*> ?: listOf<Any>())
                    .mapNotNull { capture ->
                        val captureMap = capture as? Map<*, *> ?: return@mapNotNull null
                        Capture(
                            id = captureMap["id"] as? String ?: return@mapNotNull null,
                            type = captureMap["type"] as? String ?: "",
                        )
                    }
            }
            result[id] = sentences
        }
        return result
    }

    private fun parseYaml(fileName: String, yaml: String): Any? {
        return try {
            Load(LoadSettings.builder().setAllowDuplicateKeys(false).build())
                .loadFromString(yaml)
                ?: run { problems += "$fileName: empty document"; null }
        } catch (e: Exception) {
            problems += "$fileName: invalid YAML: ${e.message?.lineSequence()?.firstOrNull()}"
            null
        }
    }

    companion object {
        val NAME_REGEX = Regex("[a-z][a-z0-9_]*")

        /** Keys FunctionGemma's template skips when rendering properties (format_parameters). */
        val RESERVED_SLOT_NAMES = setOf("description", "type", "properties", "required", "nullable")

        /** Capture type (skill_definitions.yml) -> slot kinds that may feed it. */
        val COMPATIBLE_KINDS = mapOf(
            "string" to setOf(SlotKind.TEXT, SlotKind.CONTACT, SlotKind.APP, SlotKind.ENUM),
            "duration" to setOf(SlotKind.DURATION),
            "number" to setOf(SlotKind.NUMBER),
            "date_time" to setOf(SlotKind.DATETIME, SlotKind.DATE, SlotKind.TIME),
        )

        private val TOOL_KEYS = setOf(
            "name", "skill", "sentence", "sentence_from", "description", "confirm", "slots",
            "examples", "confirm_text", "on_lock_screen", "keywords",
        )

        /** `{slot}` in confirm_text templates. */
        val PLACEHOLDER_REGEX = Regex("\\{([a-z][a-z0-9_]*)\\}")
        private val SLOT_KEYS = setOf("kind", "required", "description", "values")
    }
}
