package org.stypox.dicio.skills.volume

import android.media.AudioManager
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.resolve.TypedSlotRewrites
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec
import org.stypox.dicio.sentences.Sentences.Volume
import org.stypox.dicio.skills.device_settings.DeviceSettingWords

/**
 * What [VolumeSkill.generateOutput] takes: either a [VolumeCommand] with resolved values (from
 * GAR's tool call, or from a Dicio match once its numbers are resolved), or a Dicio match whose
 * raw captures still need resolving ([SpokenVolume]).
 */
sealed interface VolumeInput

/**
 * A Dicio sentence match, converted to a [VolumeCommand] through GAR's slot resolution.
 *
 * @param stream the stream named in the input ([VolumeStream.namedIn]), null for the default
 * @param question the input asks about the volume ("what's the volume at"): a set sentence
 * without a level reports it instead of asking for one ([VolumeTool.specOf])
 */
data class SpokenVolume(
    val sentence: Volume,
    val stream: VolumeStream?,
    val question: Boolean = false,
) : VolumeInput

enum class VolumeAction(val slotValue: String) {
    UP("up"), DOWN("down"), MUTE("mute"), UNMUTE("unmute"), MAX("max");

    companion object {
        fun fromSlot(value: Any?): VolumeAction? = entries.firstOrNull { it.slotValue == value }
    }
}

enum class VolumeStream(val slotValue: String, val streamType: Int) {
    MUSIC("music", AudioManager.STREAM_MUSIC),
    RING("ring", AudioManager.STREAM_RING),
    ALARM("alarm", AudioManager.STREAM_ALARM),
    NOTIFICATION("notification", AudioManager.STREAM_NOTIFICATION);

    companion object {
        fun fromSlot(value: Any?): VolumeStream? = entries.firstOrNull { it.slotValue == value }

        // the words the English sentences accept before "volume" (and in "make the X louder")
        private val NAMES = mapOf(
            "ring" to RING, "ringer" to RING, "ringtone" to RING,
            "alarm" to ALARM,
            "notification" to NOTIFICATION, "notifications" to NOTIFICATION,
        )

        /**
         * The stream named in a sentence match's input ("turn up the ringer volume" -> [RING]),
         * or null for the default. The sentences only accept these words where a stream can be
         * named, so a match that contains one names the stream with it.
         */
        fun namedIn(input: String): VolumeStream? =
            input.lowercase().split(Regex("[^\\p{L}]+")).firstNotNullOfOrNull { NAMES[it] }
    }
}

/**
 * A volume change with resolved values. With a [level] (and no mute, unmute or max) the volume is
 * set to that level; [action] up/down moves it by [steps] (one step if null); with neither the
 * current volume is only reported.
 *
 * @param level a percentage, or a plain number: up to ten it is a level out of ten, above ten a
 * percentage ("volume to seven" is 70%, "volume to fifty" is 50%)
 * @param steps a number of volume steps, or a percentage of the whole range
 */
data class VolumeCommand(
    val action: VolumeAction?,
    val steps: ResolvedNumber?,
    val level: ResolvedNumber?,
    val stream: VolumeStream,
) : VolumeInput

/**
 * The slots of the `volume` and `volume_set` tools of `tools.yml` (LLM-only tools) and the
 * conversion of their resolved values.
 */
object VolumeTool {
    const val ACTION = "action"
    const val STEPS = "steps"
    const val LEVEL = "level"
    const val STREAM = "stream"

    /**
     * All the slots a volume command can have, used to resolve and ask about the captures of
     * Dicio's sentences with the same code as the model's calls. The catalog's two tools each
     * offer the model a subset of them (a unit test checks that they match these).
     */
    val SPEC = ToolSpec(
        name = "volume",
        skillId = VolumeInfo.id,
        sentence = null,
        description = "Change the volume.",
        confirm = false,
        slots = listOf(
            SlotSpec(ACTION, SlotKind.ENUM, false, "", VolumeAction.entries.map { it.slotValue }),
            SlotSpec(STEPS, SlotKind.NUMBER, false, ""),
            SlotSpec(LEVEL, SlotKind.NUMBER, false, ""),
            SlotSpec(STREAM, SlotKind.ENUM, false, "", VolumeStream.entries.map { it.slotValue }),
        ),
        examples = listOf(),
        onLockScreen = true,
    )

    /** [SPEC] with the level required: GAR asks "What level?" when there is none. */
    val SET_SPEC = SPEC.copy(
        slots = SPEC.slots.map { if (it.name == LEVEL) it.copy(required = true) else it },
    )

    /**
     * The spec a Dicio match is resolved with: [SET_SPEC] for a set sentence that says no level
     * ("set the volume", "change the volume"), so GAR asks for one instead of only reporting the
     * volume; [SPEC] for the rest, which a missing level doesn't stop (a question, max, up, mute).
     */
    fun specOf(spoken: SpokenVolume): ToolSpec {
        val sentence = spoken.sentence
        return if (sentence is Volume.VolumeSet && !spoken.question && !isMaxWord(sentence.level)) {
            SET_SPEC
        } else {
            SPEC
        }
    }

    /**
     * Builds the command from GAR's resolved slot values (see GarSlotResolver), for either tool:
     * a `volume_set` call only has a level, a `volume` call only an action.
     */
    fun commandFromSlots(slots: Map<String, Any?>) = VolumeCommand(
        action = VolumeAction.fromSlot(slots[ACTION]),
        steps = slots[STEPS] as? ResolvedNumber,
        level = slots[LEVEL] as? ResolvedNumber,
        stream = VolumeStream.fromSlot(slots[STREAM]) ?: VolumeStream.MUSIC,
    )

    /**
     * The tool's raw slots for a Dicio match: what the sentence says, with the captured numbers
     * as said, for GAR to resolve.
     */
    fun rawSlotsOf(spoken: SpokenVolume): Map<String, String> {
        val slots = linkedMapOf<String, String?>()
        when (val sentence = spoken.sentence) {
            Volume.VolumeUp -> slots[ACTION] = VolumeAction.UP.slotValue
            Volume.VolumeDown -> slots[ACTION] = VolumeAction.DOWN.slotValue
            is Volume.VolumeUpTimes -> {
                slots[ACTION] = VolumeAction.UP.slotValue
                slots[STEPS] = sentence.times.takeUnless { isVagueAmount(it) }
            }
            is Volume.VolumeDownTimes -> {
                slots[ACTION] = VolumeAction.DOWN.slotValue
                slots[STEPS] = sentence.times.takeUnless { isVagueAmount(it) }
            }
            is Volume.VolumeSet -> when {
                isMaxWord(sentence.level) -> slots[ACTION] = VolumeAction.MAX.slotValue
                isHalfWord(sentence.level) -> slots[LEVEL] = TypedSlotRewrites.HALF_LEVEL
                else -> slots[LEVEL] = sentence.level
            }
            Volume.Mute -> slots[ACTION] = VolumeAction.MUTE.slotValue
            Volume.Unmute -> slots[ACTION] = VolumeAction.UNMUTE.slotValue
            Volume.Max -> slots[ACTION] = VolumeAction.MAX.slotValue
        }
        slots[STREAM] = spoken.stream?.slotValue
        // Dicio's captures keep the spaces around them
        return slots.mapNotNull { (slot, raw) ->
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { slot to it }
        }.toMap()
    }

    /**
     * "what's the volume at", "is the volume set to max": a question, not a change. Questions open
     * like the device settings' ones.
     */
    fun asksAboutVolume(input: String): Boolean =
        DeviceSettingWords.openingOf(input) != DeviceSettingWords.Opening.COMMAND

    /**
     * Whether a sentence match's [input] is about volume at all. Dicio's matcher lets a capture
     * take the place of missing words, so without this "set the temperature to twenty" would
     * match "set the volume to .level.".
     */
    fun mentionsVolume(input: String): Boolean =
        input.lowercase().split(Regex("[^\\p{L}]+")).any { it in VOLUME_WORDS }

    private fun words(raw: String?): String =
        raw?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ") ?: ""

    /** "a bit", "a little": the default single step rather than a number to ask about. */
    fun isVagueAmount(raw: String?): Boolean = words(raw) in VAGUE_AMOUNTS

    fun isMaxWord(raw: String?): Boolean = raw != null && TypedSlotRewrites.isMaxLevel(raw)

    /** "halfway", "the middle": half the range, which the number resolver can't read. */
    fun isHalfWord(raw: String?): Boolean = raw != null && TypedSlotRewrites.isHalfLevel(raw)

    private val VAGUE_AMOUNTS = setOf(
        "a bit", "a little", "a little bit", "a tad", "slightly", "some", "a notch", "one notch",
    )

    // one of these is in every English volume sentence
    private val VOLUME_WORDS = setOf(
        "volume", "sound", "louder", "quieter", "mute", "unmute", "silence",
    )
}
