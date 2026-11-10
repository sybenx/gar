package cc.aaro.gar.router.format

import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec

/**
 * OpenAI-style function declarations derived from [ToolSpec]s: what llama.cpp's chat templates
 * receive as `tools`. Every slot is `{"type": "string", "description": ..., "enum": [...]}`
 * (description left out when empty, enum only for enum slots), `required` lists the required
 * slots in slot order and is left out when empty.
 */
object ToolSchemas {
    /** The default `no_match` pseudo-tool, offered last in every prompt. */
    val noMatch = noMatchTool(ToolCatalog.NO_MATCH, ToolCatalog.NO_MATCH_DESCRIPTION)

    /** A slotless pseudo-tool the model calls when no tool applies (see [PromptFormat.noMatch]). */
    fun noMatchTool(name: String, description: String) = ToolSpec(
        name = name,
        skillId = "",
        sentence = null,
        description = description,
        confirm = false,
        slots = listOf(),
        examples = listOf(),
    )

    /**
     * [tools] followed by [noMatch]: the declarations a prompt contains, in order.
     *
     * @throws IllegalArgumentException if [noMatch]'s name is the name of one of [tools]
     */
    fun withNoMatch(tools: List<ToolSpec>, noMatch: ToolSpec = this.noMatch): List<ToolSpec> {
        require(tools.none { it.name == noMatch.name }) {
            "no_match pseudo-tool '${noMatch.name}' clashes with a tool"
        }
        return tools + noMatch
    }

    /** One declaration as JSON, serialized exactly like llama.cpp's `tojson` filter. */
    fun toJson(tool: ToolSpec): String = Json.write(toMap(tool))

    /** All declarations of a prompt (including [noMatch]) as a JSON array. */
    fun toJsonArray(tools: List<ToolSpec>, noMatch: ToolSpec = this.noMatch): String =
        withNoMatch(tools, noMatch).joinToString(", ", "[", "]") { toJson(it) }

    internal fun toMap(tool: ToolSpec): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        for (slot in tool.slots) {
            properties[slot.name] = linkedMapOf<String, Any?>("type" to "string").apply {
                if (slot.description.isNotEmpty()) put("description", slot.description)
                if (slot.kind == SlotKind.ENUM) put("enum", slot.enumValues)
            }
        }
        val parameters = linkedMapOf<String, Any?>("type" to "object", "properties" to properties)
        val required = tool.slots.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) {
            parameters["required"] = required
        }
        return linkedMapOf(
            "type" to "function",
            "function" to linkedMapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to parameters,
            ),
        )
    }
}
