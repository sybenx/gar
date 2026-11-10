package org.stypox.dicio.gar.voicelog

import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.stt.AudioCtx
import cc.aaro.gar.stt.Transcript
import org.stypox.dicio.gar.voicelog.VoiceRecord.Companion.STAGE_ROUTE
import org.stypox.dicio.gar.voicelog.VoiceRecord.Companion.STAGE_SPEECH_END
import org.stypox.dicio.gar.voicelog.VoiceRecord.Companion.STAGE_SPEECH_START
import org.stypox.dicio.gar.voicelog.VoiceRecord.Companion.STAGE_TEXT
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One turn of the voice log as the stages see it: each records what it did through these
 * methods, which never throw and only touch memory (the WAV and its levels go to the log's
 * background thread). Made by [VoiceTurns].
 */
class VoiceTurn internal constructor(
    private val owner: VoiceTurns,
    internal val record: VoiceRecord,
    /** The log's generation when the turn opened: Clear and turning the log off drop it. */
    internal val generation: Long,
) {
    /** Guarded by [record]. */
    internal var finished = false
    internal var written = false

    /** The turn before, waiting for this one's answer to be judged; guarded by the log's lock. */
    internal var previous: VoiceTurn? = null

    val id: String get() = record.id

    /** Runs [block] on the record under its lock; anything it throws is logged and dropped. */
    fun update(block: (VoiceRecord) -> Unit) {
        try {
            synchronized(record) {
                if (!written) {
                    block(record)
                }
            }
        } catch (t: Throwable) {
            owner.warn("Could not record a stage", t)
        }
    }

    fun stage(name: String) {
        val now = owner.monoNow()
        update { it.stage(name, now) }
    }

    // ---- capture (the Whisper input device) ----

    /** The VAD heard speech start after [capturedSamples] of capture. */
    fun speechStarted(capturedSamples: Int) = update {
        it.stage(STAGE_SPEECH_START, owner.monoNow())
        (it.capture ?: VoiceRecord.Capture().also { c -> it.capture = c }).speechStartMs =
            samplesToMillis(capturedSamples)
    }

    /**
     * Capture stopped for [end] ("vad_silence", "max_length", "user_tap", "audio_end", "no_speech",
     * "too_little", "cancel", "error") after [capturedSamples]; [clipSamples] and
     * [speechSamples] are those of the clip to transcribe, null when there is none.
     */
    fun captureEnded(end: String, capturedSamples: Int?, clipSamples: Int?, speechSamples: Int?) {
        val now = owner.monoNow()
        update {
            val capture = it.capture ?: VoiceRecord.Capture().also { c -> it.capture = c }
            if (capture.end == null) {
                capture.end = end
            }
            capturedSamples?.let { n -> capture.audioMs = samplesToMillis(n) }
            clipSamples?.let { n ->
                capture.clipMs = samplesToMillis(n)
                capture.audioCtx = AudioCtx.forSamples(n)
            }
            speechSamples?.let { n -> capture.speechMs = samplesToMillis(n) }
            if (clipSamples != null) {
                it.stage(STAGE_SPEECH_END, now)
            }
        }
    }

    /**
     * The user or the app cancelled the capture or its transcription (a tap while transcribing,
     * `stopListening`); the device sends None next. A capture that hadn't ended ends as "cancel".
     */
    fun cancelled() = update {
        val capture = it.capture ?: VoiceRecord.Capture().also { c -> it.capture = c }
        capture.end = capture.end ?: END_CANCEL
        capture.cancelled = true
    }

    /** Capture or transcription failed with [throwable]; the device sends an Error next. */
    fun captureFailed(throwable: Throwable) = update {
        val capture = it.capture ?: VoiceRecord.Capture().also { c -> it.capture = c }
        capture.end = capture.end ?: END_ERROR
        capture.error = describe(throwable)
    }

    /**
     * Whisper's result for the clip: [transcript], its [spoken] and [written] forms,
     * [rejection] the filter's reason to drop it (null: passed). [audio] is the clip, for the
     * levels and the WAV; null while recordings aren't kept.
     */
    fun transcribed(
        transcript: Transcript,
        spoken: String,
        written: String,
        rejection: String?,
        audio: FloatArray?,
    ) {
        val now = owner.monoNow()
        val promptInfo = owner.promptInfo()
        update {
            it.stage(STAGE_TEXT, now)
            it.transcript = VoiceRecord.Transcript(
                raw = transcript.text,
                confidence = transcript.confidence,
                noSpeech = transcript.noSpeechProb,
                decodeMs = transcript.totalMicros / 1000,
                encodeMs = transcript.encodeMicros / 1000,
                tokens = transcript.tokens,
                filter = if (rejection == null) "accept" else "drop",
                filterReason = rejection,
            )
            it.capture?.audioCtx = transcript.audioCtx
            it.spoken = spoken
            it.written = written
            val ownerName = promptInfo?.owner
            it.prompt = VoiceRecord.Prompt(
                on = promptInfo?.on ?: transcript.promptNames.isNotEmpty(),
                tokens = transcript.promptTokens,
                names = transcript.promptNames,
                owner = ownerName,
                ownerIncluded = ownerName != null && transcript.promptNames.any { name ->
                    name.equals(ownerName, ignoreCase = true)
                },
                ownerSource = promptInfo?.ownerSource,
            )
            if (audio != null) {
                it.wav = "${it.id}.wav"
            }
        }
        if (audio != null) {
            owner.saveAudio(this, "${record.id}.wav", audio)
        }
    }

    // ---- matching (garMatchUtterances, Dicio's ranker, GarFallback) ----

    /** Dicio scored [skill] at [score] (0 to 1) for the input. */
    fun scored(skill: String, score: Float) = update {
        it.addCandidate(VoiceRecord.Candidate(skill, score))
    }

    /** GarMatchCheck's verdict on Dicio's match by [skill]: "accept", "reject" or "off". */
    fun matchChecked(skill: String, verdict: String) = update {
        // the first rejection is the interesting one, an accepted match is the last word
        if (it.check != "reject" || verdict == "accept") {
            it.check = verdict
            it.checkedSkill = skill
        }
    }

    /** Which path won: "sentence", "follow_up_answer", "online_gate", "model" or "fallback". */
    fun routedBy(path: String) {
        val now = owner.monoNow()
        update {
            it.path = path
            it.stage(STAGE_ROUTE, now)
        }
    }

    /** The model's [output] for the input after [millis]. */
    fun modelOutput(output: RouterOutput, millis: Long) = update {
        val model = it.model ?: VoiceRecord.Model(outcome = "").also { m -> it.model = m }
        model.ms = millis
        when (output) {
            is RouterOutput.Call -> {
                model.outcome = "call"
                model.fill(output)
                model.filter = "keep"
            }
            is RouterOutput.Dropped -> {
                model.outcome = "dropped"
                model.fill(output.call)
                model.filter = "drop"
                model.filterReason = output.reason
            }
            RouterOutput.NoMatch -> model.outcome = "no_match"
            is RouterOutput.Failed -> {
                model.outcome = "failed"
                model.failure = output.reason
            }
        }
    }

    /** The model wasn't asked, for [why] ("off", "not_english", "no_tools", ...). */
    fun modelSkipped(why: String) = update {
        if (it.model == null) {
            it.model = VoiceRecord.Model(outcome = "skipped", failure = why)
        }
    }

    /** A call rewrite turned the model's call [from] into [to] (both as `tool{slot=value}`). */
    fun rewritten(from: String, to: String) = update {
        val model = it.model ?: VoiceRecord.Model(outcome = "").also { m -> it.model = m }
        model.rewrite = "$from -> $to"
    }

    /** The call was for a tool that doesn't run on the lock screen. */
    fun modelLocked() = update { it.model?.outcome = "locked" }

    // ---- follow-ups (the follow-up skill and garMatchUtterances) ----

    /**
     * What the follow-up skill made of the input; a later call replaces an earlier one (the
     * rewrite's own pass may find nothing that takes it: "unmatched").
     */
    fun followUp(followUp: VoiceFollowUp) {
        update { it.followUp = followUp }
        owner.followedUp(this, followUp)
    }

    private fun VoiceRecord.Model.fill(call: RouterOutput.Call) {
        tool = call.tool.name
        args = LinkedHashMap(call.rawSlots)
        missing = call.missingRequired
    }

    companion object {
        const val END_ERROR = "error"
        const val END_CANCEL = "cancel"

        fun samplesToMillis(samples: Int): Long = samples * 1000L / AudioCtx.SAMPLE_RATE

        fun describe(throwable: Throwable): String =
            throwable.javaClass.name + (throwable.message?.let { ": $it" } ?: "")

        /** Peak and RMS of [pcm] in dBFS; -inf for silence (written as null). */
        fun levels(pcm: FloatArray): Pair<Double, Double> {
            var peak = 0f
            var sum = 0.0
            for (x in pcm) {
                val a = abs(x)
                if (a > peak) {
                    peak = a
                }
                sum += x.toDouble() * x
            }
            val rms = if (pcm.isEmpty()) 0.0 else sqrt(sum / pcm.size)
            return Pair(20 * log10(peak.toDouble()), 20 * log10(rms))
        }
    }
}
