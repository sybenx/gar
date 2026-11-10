package org.stypox.dicio.gar.call

import cc.aaro.gar.router.resolve.ResolvedDate
import cc.aaro.gar.router.resolve.ResolvedDateTime
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.resolve.ResolvedTime
import cc.aaro.gar.router.tools.SentenceMapping
import cc.aaro.gar.router.tools.ToolSpec
import java.lang.reflect.Constructor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.dicio.numbers.unit.Duration as DicioDuration
import org.dicio.numbers.unit.Number as DicioNumber

/**
 * Builds the generated sentence data (`org.stypox.dicio.sentences.Sentences.<Skill>.<Sentence>`)
 * that a Dicio skill's `generateOutput` expects, from GAR's resolved slot values. The sentences
 * compiler names classes with [pascalCase] of the skill and sentence ids, generates a Kotlin
 * object for capture-less sentences and a data class with one nullable parameter per capture
 * (in `skill_definitions.yml` order) otherwise.
 *
 * Reflection is used because the generated classes differ per skill; `proguard-rules.pro` keeps
 * them. The `:app` unit tests check that every catalog tool binds.
 */
object GarSentenceBinder {
    const val SENTENCES_CLASS = "org.stypox.dicio.sentences.Sentences"

    /** Same conversion as the sentences compiler: "turn_on" -> "TurnOn". */
    fun pascalCase(id: String): String =
        id.split("_", "-").joinToString(separator = "") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }

    /** Same conversion as the sentences compiler: "time_or_duration" -> "timeOrDuration". */
    fun camelCase(id: String): String = pascalCase(id).replaceFirstChar { it.lowercaseChar() }

    /**
     * The binary class name of a generated sentence. Uses [ToolSpec.skillId] as the
     * `skill_definitions.yml` id, which holds for all skills in the catalog.
     */
    fun sentenceClassName(skillDefinitionId: String, sentenceId: String): String =
        "$SENTENCES_CLASS\$${pascalCase(skillDefinitionId)}\$${pascalCase(sentenceId)}"

    /** The JVM type of a generated property for a capture type of `skill_definitions.yml`. */
    fun captureClass(captureType: String): Class<*> = when (captureType) {
        "string" -> String::class.java
        "duration" -> DicioDuration::class.java
        "number" -> DicioNumber::class.java
        "date_time" -> LocalDateTime::class.java
        else -> throw IllegalArgumentException("Unknown capture type $captureType")
    }

    /** All sentence ids [tool] can map to. */
    fun sentenceIds(tool: ToolSpec): List<String> = when (val mapping = tool.sentence) {
        is SentenceMapping.Fixed -> listOf(mapping.sentenceId)
        is SentenceMapping.ByEnum -> mapping.valueToSentenceId.values.distinct()
        null -> listOf()
    }

    /** The capture ids of [sentenceId] in constructor order (by-enum sentences have none). */
    fun captureOrder(tool: ToolSpec, sentenceId: String): List<String> =
        when (val mapping = tool.sentence) {
            is SentenceMapping.Fixed ->
                if (mapping.sentenceId == sentenceId) mapping.captureOrder else listOf()
            else -> listOf()
        }

    fun sentenceClass(tool: ToolSpec, sentenceId: String): Class<*> =
        Class.forName(
            sentenceClassName(tool.skillId, sentenceId),
            true,
            GarSentenceBinder::class.java.classLoader,
        )

    /** The constructor used for a sentence with captures, or null for capture-less ones. */
    fun constructorFor(tool: ToolSpec, sentenceId: String): Constructor<*>? {
        val captures = captureOrder(tool, sentenceId)
        if (captures.isEmpty()) {
            return null
        }
        return sentenceClass(tool, sentenceId).constructors
            .singleOrNull { it.parameterCount == captures.size }
            ?: throw IllegalStateException(
                "No constructor with ${captures.size} parameters for $sentenceId of ${tool.skillId}"
            )
    }

    /**
     * @param values resolved slot values by slot name (see [GarSlotResolver]); absent or null
     * values become null captures
     * @param now the reference for time-only values bound to `date_time` captures
     * @return the generated sentence object to pass to the skill
     */
    fun buildInput(
        tool: ToolSpec,
        values: Map<String, Any?>,
        now: LocalDateTime = LocalDateTime.now(),
    ): Any {
        val sentenceId = when (val mapping = tool.sentence) {
            is SentenceMapping.Fixed -> mapping.sentenceId
            is SentenceMapping.ByEnum -> {
                val value = values[mapping.slot] as? String
                mapping.valueToSentenceId[value]
                    ?: throw IllegalArgumentException("No sentence for ${mapping.slot}=$value in ${tool.name}")
            }
            null -> throw IllegalArgumentException("${tool.name} has no sentence, it is LLM-only")
        }

        val constructor = constructorFor(tool, sentenceId)
            ?: return sentenceClass(tool, sentenceId).getField("INSTANCE").get(null)!!

        val args = captureOrder(tool, sentenceId).map { captureId ->
            val slot = tool.slots.firstOrNull { it.name == captureId }
            val captureType = slot?.captureType
            if (slot == null || captureType == null) {
                null // a capture the tool doesn't expose
            } else {
                toCaptureValue(values[captureId], captureType, now)
            }
        }
        return constructor.newInstance(*args.toTypedArray())
    }

    /**
     * Converts a resolved slot value to the type the generated sentence data expects.
     *
     * @throws IllegalArgumentException for values the capture type can't hold without losing
     * what the user said: a recurring time ("every monday at seven"), a date without a time and a
     * percentage. A `date_time` capture would silently become a one-shot or midnight, so a tool
     * that needs these must be an LLM-only tool ([GarToolSkill]) that takes the resolved values.
     */
    fun toCaptureValue(value: Any?, captureType: String, now: LocalDateTime): Any? {
        if (value == null) {
            return null
        }
        return when (captureType) {
            "string" -> value as? String ?: value.toString()
            "duration" -> when (value) {
                is java.time.Duration -> DicioDuration(value)
                is DicioDuration -> value
                else -> wrongType(value, captureType)
            }
            "number" -> when (value) {
                is ResolvedNumber -> if (value.percent) {
                    lossy(value, captureType, "a percentage")
                } else if (value.isInteger) {
                    DicioNumber(value.value.toLong())
                } else {
                    DicioNumber(value.value)
                }
                is DicioNumber -> value
                else -> wrongType(value, captureType)
            }
            "date_time" -> when (value) {
                // no time said: dateTime holds the current time as a filler
                is ResolvedDateTime -> if (value.timeSpoken) {
                    value.dateTime
                } else {
                    lossy(value, captureType, "a date without a time")
                }
                is ResolvedDate -> lossy(value, captureType, "a date without a time")
                is ResolvedTime -> if (value.repeatDays.isEmpty()) {
                    nextOccurrence(value.time, now)
                } else {
                    lossy(value, captureType, "a recurring time")
                }
                is LocalDateTime -> value
                is LocalDate -> lossy(value, captureType, "a date without a time")
                is LocalTime -> nextOccurrence(value, now)
                else -> wrongType(value, captureType)
            }
            else -> throw IllegalArgumentException("Unknown capture type $captureType")
        }
    }

    /** Today at [time] if that is still ahead of [now], tomorrow otherwise. */
    fun nextOccurrence(time: LocalTime, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun lossy(value: Any, captureType: String, what: String): Nothing =
        throw IllegalArgumentException(
            "A $captureType capture can't hold $what ($value): use an LLM-only tool"
        )

    private fun wrongType(value: Any, captureType: String): Nothing =
        throw IllegalArgumentException(
            "Can't use ${value::class.java.name} for a $captureType capture"
        )
}
