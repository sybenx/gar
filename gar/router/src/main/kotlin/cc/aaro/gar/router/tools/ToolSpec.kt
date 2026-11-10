package cc.aaro.gar.router.tools

/**
 * One function the model can call. Built from `tools.yml` joined with Dicio's
 * `skill_definitions.yml` by [ToolCatalog.load]; see gar/DESIGN.md.
 */
data class ToolSpec(
    /** Name the model sees, `[a-z][a-z0-9_]*`, unique in the catalog. */
    val name: String,
    /** `SkillInfo.id` of the Dicio skill that runs this tool. */
    val skillId: String,
    /** How the call maps to the skill's generated sentence data, or null for LLM-only skills. */
    val sentence: SentenceMapping?,
    val description: String,
    /** Whether GAR shows its confirmation card before running a call that came from the model. */
    val confirm: Boolean,
    /** In the fixed order used for prompts and grammars. */
    val slots: List<SlotSpec>,
    /** Example utterances (English), for docs, eval seeds and training data. */
    val examples: List<String>,
    /**
     * The question on GAR's confirmation card, for the user (never shown to the model). Null to
     * fall back to [description] followed by the slot values.
     */
    val confirmText: ConfirmText? = null,
    /**
     * Whether a call the model made may run while the phone is locked. False by default, so that
     * tools touching private data or with side effects need the phone unlocked.
     */
    val onLockScreen: Boolean = false,
    /**
     * Words of which at least one must be in the utterance for a call of this tool to count
     * (normalized, see [cc.aaro.gar.router.KeywordFilter]); empty for no such check. Never shown
     * to the model.
     */
    val keywords: List<String> = listOf(),
)

/**
 * User-facing text of a tool's confirmation card. `{slot}` placeholders are replaced by the slot's
 * value as GAR shows it, or by nothing when the optional slot was left out.
 */
sealed interface ConfirmText {
    data class Template(val text: String) : ConfirmText

    /** One template per value of the enum slot [slot], e.g. one per flashlight state. */
    data class ByValue(val slot: String, val templates: Map<String, String>) : ConfirmText
}

data class SlotSpec(
    val name: String,
    val kind: SlotKind,
    val required: Boolean,
    val description: String,
    /** Allowed values, only for [SlotKind.ENUM]. */
    val enumValues: List<String> = listOf(),
    /**
     * The `skill_definitions.yml` capture type this slot feeds (`string`, `number`, `duration`,
     * `date_time`), or null for LLM-only skills and for the selector slot of [SentenceMapping.ByEnum].
     */
    val captureType: String? = null,
)

enum class SlotKind(val yamlName: String) {
    TEXT("text"),
    CONTACT("contact"),
    APP("app"),
    ENUM("enum"),
    NUMBER("number"),
    DURATION("duration"),
    DATETIME("datetime"),
    DATE("date"),
    TIME("time");

    companion object {
        fun fromYaml(name: String): SlotKind? = entries.firstOrNull { it.yamlName == name }
    }
}

sealed interface SentenceMapping {
    /** The tool always maps to one sentence id; [captureOrder] is the generated constructor order. */
    data class Fixed(val sentenceId: String, val captureOrder: List<String>) : SentenceMapping

    /**
     * An enum slot selects among capture-less sentences, e.g. `media_control(action)` ->
     * `play`/`pause`/`next`/`previous`.
     */
    data class ByEnum(val slot: String, val valueToSentenceId: Map<String, String>) : SentenceMapping
}
