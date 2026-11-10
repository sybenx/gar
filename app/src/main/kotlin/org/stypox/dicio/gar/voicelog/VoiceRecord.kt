package org.stypox.dicio.gar.voicelog

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.stypox.dicio.gar.perf.GarFrameSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** How a turn started (gar/DESIGN.md, "Voice log"). */
enum class GarVoiceStart(val key: String) {
    /** The mic button on the home screen. */
    MIC_BUTTON("mic"),

    /** The assist gesture or the power button (`ACTION_ASSIST`, the VIS session). */
    ASSIST("assist"),

    /** The wake word. */
    HOTWORD("hotword"),

    /** Typed in the home screen's text field. */
    TYPED("typed"),

    /** A skill's question reopened the microphone after its answer. */
    FOLLOW_UP("follow_up"),

    /** A card's button, which sends its label as input. */
    BUTTON("button"),

    /** The microphone stayed open after a spoken answer (continued listening). */
    CONTINUED("continued"),

    /** Anything else: an error event with neither a capture nor a hint. */
    OTHER("other"),
}

/**
 * One turn of the voice log: one activation through the reply. Filled by the stages as they run
 * and written as one JSON line ([toJson]); see gar/DESIGN.md, "Voice log", for the fields. Only
 * [VoiceTurn] touches it, under its lock.
 *
 * Stage times are milliseconds since the turn started (monotonic); [wallStart] is the wall
 * clock at the start.
 */
class VoiceRecord(
    val id: String,
    val seq: Long,
    val wallStart: Long,
    val startMono: Long,
    var start: GarVoiceStart,
) {
    var locked: Boolean? = null
    val stages = LinkedHashMap<String, Long>()

    var sttEngine: String? = null
    var sttModel: String? = null

    var capture: Capture? = null
    var prompt: Prompt? = null
    var transcript: Transcript? = null
    var wav: String? = null
    var spoken: String? = null
    var written: String? = null

    var input: String? = null
    var alternatives: Int = 0
    var editedFrom: String? = null

    val candidates = ArrayList<Candidate>()
    var check: String? = null
    var checkedSkill: String? = null
    var path: String? = null
    /** Wall and CPU time of Dicio's ranker, and whether the model loaded meanwhile. */
    var rankMillis: Long? = null
    var rankCpuMillis: Long? = null
    var rankModelLoading: Boolean = false

    var model: Model? = null
    var followUp: VoiceFollowUp? = null
    var answer: Answer? = null

    /** Continued-listening windows since the turn before that heard nothing, or only noise. */
    var continuedSilent: Int = 0
    var continuedHallucination: Int = 0

    val suspect = LinkedHashSet<String>()
    var suspectBy: String? = null

    /** When the reply was given (or the turn ended without one), monotonic. */
    var endMono: Long? = null

    /** The frames GAR's windows drew from the start to the end of the turn (GarJank). */
    var frames: GarFrameSummary? = null

    data class Capture(
        var speechStartMs: Long? = null,
        var end: String? = null,
        var speechMs: Long? = null,
        var audioMs: Long? = null,
        var clipMs: Long? = null,
        var peakDbfs: Double? = null,
        var meanDbfs: Double? = null,
        var audioCtx: Int? = null,
        var error: String? = null,
        var cancelled: Boolean = false,
    )

    data class Prompt(
        val on: Boolean,
        val tokens: Int,
        val names: List<String>,
        val owner: String?,
        val ownerIncluded: Boolean,
        val ownerSource: String?,
    )

    data class Transcript(
        val raw: String,
        val confidence: Float,
        val noSpeech: Float,
        val decodeMs: Long,
        val encodeMs: Long,
        val tokens: Int,
        val filter: String,
        val filterReason: String?,
    )

    data class Candidate(val skill: String, val score: Float)

    data class Model(
        var outcome: String,
        var tool: String? = null,
        var args: Map<String, String> = emptyMap(),
        var missing: List<String> = emptyList(),
        var rewrite: String? = null,
        var filter: String? = null,
        var filterReason: String? = null,
        var failure: String? = null,
        var ms: Long? = null,
    )

    data class Answer(
        var outcome: String,
        var skill: String? = null,
        var speech: String? = null,
        var card: String? = null,
        var permissionCard: Boolean = false,
        /** Whether the speech was said: false for a typed turn's answer, shown only. */
        var spoken: Boolean? = null,
        var errorClass: String? = null,
        var errorMessage: String? = null,
    )

    /** Marks a stage at [monoMillis], once: the first time counts. */
    fun stage(name: String, monoMillis: Long) {
        stages.putIfAbsent(name, monoMillis - startMono)
    }

    /**
     * Keeps [candidate] if it is among the [MAX_CANDIDATES] best seen; a skill keeps its best
     * score (Dicio scores the same skill again for every batch and round).
     */
    fun addCandidate(candidate: Candidate) {
        val index = candidates.indexOfFirst { it.skill == candidate.skill }
        if (index >= 0) {
            if (candidates[index].score >= candidate.score) {
                return
            }
            candidates.removeAt(index)
        }
        candidates.add(candidate)
        candidates.sortByDescending { it.score }
        while (candidates.size > MAX_CANDIDATES) {
            candidates.removeAt(candidates.size - 1)
        }
    }

    /** The reference for latencies: the end of speech, or the input for typed turns. */
    private fun latencyFrom(): Long? = stages[STAGE_SPEECH_END] ?: stages[STAGE_INPUT]

    fun latency(stage: String): Long? {
        val from = latencyFrom() ?: return null
        return stages[stage]?.let { it - from }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("id", id)
        put("seq", seq)
        put("t", formatWall(wallStart))
        put("start", start.key)
        locked?.let { put("locked", it) }
        putJsonObject("stages") { stages.forEach { (k, v) -> put(k, v) } }
        if (sttEngine != null || sttModel != null) {
            putJsonObject("stt") {
                sttEngine?.let { put("engine", it) }
                sttModel?.let { put("model", it) }
            }
        }
        capture?.let { c ->
            putJsonObject("capture") {
                c.speechStartMs?.let { put("speech_start_ms", it) }
                c.end?.let { put("end", it) }
                c.speechMs?.let { put("speech_ms", it) }
                c.audioMs?.let { put("audio_ms", it) }
                c.clipMs?.let { put("clip_ms", it) }
                putNumber("peak_dbfs", c.peakDbfs)
                putNumber("mean_dbfs", c.meanDbfs)
                c.audioCtx?.let { put("audio_ctx", it) }
                c.error?.let { put("error", it) }
                if (c.cancelled) {
                    put("cancelled", true)
                }
            }
        }
        prompt?.let { p ->
            putJsonObject("prompt") {
                put("on", p.on)
                put("tokens", p.tokens)
                putJsonArray("names") { p.names.forEach { add(JsonPrimitive(it)) } }
                p.owner?.let { put("owner", it) }
                put("owner_included", p.ownerIncluded)
                p.ownerSource?.let { put("owner_source", it) }
            }
        }
        transcript?.let { t ->
            putJsonObject("transcript") {
                put("raw", t.raw)
                putNumber("confidence", t.confidence.toDouble())
                putNumber("no_speech", t.noSpeech.toDouble())
                put("decode_ms", t.decodeMs)
                put("encode_ms", t.encodeMs)
                put("tokens", t.tokens)
                put("filter", t.filter)
                t.filterReason?.let { put("filter_reason", it) }
            }
        }
        wav?.let { put("wav", it) }
        spoken?.let { put("spoken", it) }
        written?.let { put("written", it) }
        input?.let { put("input", it) }
        if (alternatives > 1) {
            put("alternatives", alternatives)
        }
        editedFrom?.let { put("edited_from", it) }
        if (candidates.isNotEmpty() || check != null || path != null || rankMillis != null) {
            putJsonObject("match") {
                putJsonArray("candidates") {
                    candidates.forEach { c ->
                        add(buildJsonObject {
                            put("skill", c.skill)
                            putNumber("score", c.score.toDouble())
                        })
                    }
                }
                check?.let { put("check", it) }
                checkedSkill?.let { put("checked_skill", it) }
                path?.let { put("path", it) }
                rankMillis?.let { put("rank_ms", it) }
                rankCpuMillis?.let { put("rank_cpu_ms", it) }
                if (rankModelLoading) {
                    put("rank_model_loading", true)
                }
            }
        }
        model?.let { m ->
            putJsonObject("model") {
                put("outcome", m.outcome)
                m.tool?.let { put("tool", it) }
                if (m.args.isNotEmpty()) {
                    putJsonObject("args") { m.args.forEach { (k, v) -> put(k, v) } }
                }
                if (m.missing.isNotEmpty()) {
                    putJsonArray("missing") { m.missing.forEach { add(JsonPrimitive(it)) } }
                }
                m.rewrite?.let { put("rewrite", it) }
                m.filter?.let { put("filter", it) }
                m.filterReason?.let { put("filter_reason", it) }
                m.failure?.let { put("failure", it) }
                m.ms?.let { put("ms", it) }
            }
        }
        followUp?.let { f ->
            putJsonObject("follow_up") {
                put("form", f.form)
                f.turnSkill?.let { put("turn_skill", it) }
                f.turnAgeMs?.let { put("turn_age_ms", it) }
                f.rewrite?.let { put("rewrite", it) }
                put("outcome", f.outcome)
                f.deferredTo?.let { put("deferred_to", it) }
            }
        }
        answer?.let { a ->
            putJsonObject("answer") {
                put("outcome", a.outcome)
                a.skill?.let { put("skill", it) }
                a.speech?.let { put("speech", it) }
                a.spoken?.let { put("spoken", it) }
                a.card?.let { put("card", it) }
                if (a.permissionCard) {
                    put("permission_card", true)
                }
                a.errorClass?.let { put("error_class", it) }
                a.errorMessage?.let { put("error_message", it) }
            }
        }
        val latencies = listOf(
            "to_text_ms" to STAGE_TEXT,
            "to_route_ms" to STAGE_ROUTE,
            "to_first_word_ms" to STAGE_FIRST_WORD,
        ).mapNotNull { (key, stage) -> latency(stage)?.let { key to it } }
        if (latencies.isNotEmpty()) {
            putJsonObject("latency") { latencies.forEach { (k, v) -> put(k, v) } }
        }
        frames?.let { f ->
            putJsonObject("frames") {
                put("surface", f.surface)
                put("n", f.frames)
                put("janky", f.janky)
                putNumber("p50_ms", f.p50Ms)
                putNumber("p90_ms", f.p90Ms)
                putNumber("p99_ms", f.p99Ms)
                putNumber("max_ms", f.maxMs)
                put("hz", f.refreshHz)
            }
        }
        if (continuedSilent > 0) {
            put("continued_silent", continuedSilent)
        }
        if (continuedHallucination > 0) {
            put("continued_hallucination", continuedHallucination)
        }
        if (suspect.isNotEmpty()) {
            putJsonArray("suspect") { suspect.forEach { add(JsonPrimitive(it)) } }
            suspectBy?.let { put("suspect_by", it) }
        }
    }

    companion object {
        const val VERSION = 1
        const val MAX_CANDIDATES = 3

        const val STAGE_SPEECH_START = "speech_start"
        const val STAGE_SPEECH_END = "speech_end"
        const val STAGE_TEXT = "text"
        const val STAGE_INPUT = "input"
        const val STAGE_ROUTE = "route"
        const val STAGE_ANSWER = "answer"
        const val STAGE_FIRST_WORD = "first_word"
        const val STAGE_END = "end"

        /** A turn id: its start in local time, then the sequence number in this process. */
        fun idFor(wallMillis: Long, seq: Long): String =
            SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(wallMillis)) + "-$seq"

        fun formatWall(wallMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date(wallMillis))

        /**
         * Two decimals, nothing for null; NaN and infinities (no text tokens, a silent clip)
         * become JSON null, since JSON has no NaN.
         */
        private fun JsonObjectBuilder.putNumber(key: String, value: Double?) {
            if (value == null) {
                return
            }
            val element: JsonElement = if (value.isFinite()) {
                JsonPrimitive((value * 100).roundToLong() / 100.0)
            } else {
                JsonNull
            }
            put(key, element)
        }
    }
}
