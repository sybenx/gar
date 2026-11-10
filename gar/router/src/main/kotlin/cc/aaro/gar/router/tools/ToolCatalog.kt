package cc.aaro.gar.router.tools

/**
 * All tools GAR can offer to the model, in prompt order. Which of them are offered for a given
 * request is decided by the caller (only enabled, available skills), see [forSkills].
 */
class ToolCatalog(val tools: List<ToolSpec>) {

    fun byName(name: String): ToolSpec? = tools.firstOrNull { it.name == name }

    /** The tools whose skill id is in [skillIds], keeping catalog order. */
    fun forSkills(skillIds: Set<String>): List<ToolSpec> = tools.filter { it.skillId in skillIds }

    companion object {
        /** Pseudo-tool the model calls when no tool applies. Always offered, always last. */
        const val NO_MATCH = "no_match"

        /**
         * Default description of the [NO_MATCH] pseudo-tool in prompts (FunctionGemma);
         * `HermesFormat.DEFAULT_NO_MATCH` has its own, tuned for Qwen3.
         */
        const val NO_MATCH_DESCRIPTION = "Call this when no other function matches the request."

        /**
         * Parses `tools.yml` and joins it with Dicio's `skill_definitions.yml`. The validation
         * rules are documented on `ToolCatalogLoader`; in short, a sentence-backed tool declares
         * exactly the sentence's captures as slots (none may be left out).
         *
         * @throws ToolCatalogException listing every problem found (unknown skill or sentence id,
         * slot keys that don't match the sentence's captures, incompatible slot kind and capture
         * type, duplicate names, bad enum mapping, invalid names)
         */
        fun load(toolsYaml: String, skillDefinitionsYaml: String): ToolCatalog =
            ToolCatalogLoader(toolsYaml, skillDefinitionsYaml).load()
    }
}

class ToolCatalogException(val problems: List<String>) :
    Exception("Invalid GAR tool catalog:\n" + problems.joinToString("\n") { "- $it" })
