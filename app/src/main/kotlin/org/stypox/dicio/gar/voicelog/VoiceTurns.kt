package org.stypox.dicio.gar.voicelog

import org.stypox.dicio.gar.voicelog.VoiceRecord.Companion.STAGE_ANSWER
import org.stypox.dicio.gar.voicelog.VoiceRecord.Companion.STAGE_END
import org.stypox.dicio.gar.voicelog.VoiceRecord.Companion.STAGE_FIRST_WORD
import org.stypox.dicio.gar.voicelog.VoiceRecord.Companion.STAGE_INPUT
import org.stypox.dicio.gar.perf.GarFrameSummary
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

/** What the voice log records about Whisper's name prompt, read when a transcript arrives. */
data class VoicePromptInfo(val on: Boolean, val owner: String?, val ownerSource: String?)

/** The input the evaluator got, as far as the voice log cares (see [VoiceTurns.input]). */
sealed interface VoiceInput {
    data class Final(val utterances: List<String>) : VoiceInput
    data object None : VoiceInput
    data class Error(val throwable: Throwable) : VoiceInput
}

/**
 * Follows the turns of the voice log (gar/DESIGN.md, "Voice log") and writes each as one JSON
 * line through [files]. Every method returns at once and never throws: the files, the levels and
 * the JSON are left to [io], one background thread.
 *
 * A turn opens when the Whisper device starts capturing for GAR ([captureStarted]) or, for other
 * input, when the evaluator gets it ([input]); how it started comes from the last [hint] (the mic
 * button, the assist gesture, the wake word, typing, a reopened microphone), else it was a
 * card's button. A capture without a hint is another app's dictation and has no turn. A turn
 * ends with the answer ([answered], [failed]) or without input. A turn with input is then held
 * until the next turn's input, and judged once that turn is answered, which may mark it suspect
 * ([VoiceSignals]); or it is written after [holdMillis]. A turn without input is written at once
 * and holds nothing. [clear] and turning the log off start a new generation: the turns of the
 * old one, in flight or not, are never written.
 *
 * @param schedule runs a task on [io] after a delay (the end of the hold)
 * @param log one summary line per turn, without what was said (logcat)
 */
class VoiceTurns(
    private val files: VoiceLogFiles,
    private val io: Executor,
    private val schedule: (Long, Runnable) -> Unit,
    private val log: (String) -> Unit,
    private val warnLog: (String, Throwable?) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monoClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val isLocked: () -> Boolean = { false },
    private val sttModel: () -> String? = { null },
    private val promptInfoSource: () -> VoicePromptInfo? = { null },
    /** The frames drawn between two [monoClock] times, see [VoiceRecord.frames]. */
    private val frames: (fromMono: Long, toMono: Long) -> GarFrameSummary? = { _, _ -> null },
    private val holdMillis: Long = HOLD_MILLIS,
    private val hintMillis: Long = HINT_MILLIS,
    /** Each line once it is in the log, on [io] (the starter chips' index). */
    private val written: (String) -> Unit = {},
) {
    @Volatile
    var enabled = false
        set(value) {
            if (!value) {
                synchronized(lock) { forget() }
            }
            field = value
            if (!value) {
                // off keeps no audio: the clips go now, the text log stays until Clear
                safe(Unit) { io.execute { safe(Unit) { if (!enabled) files.deleteWavs() } } }
            }
        }

    private val lock = Any()
    private var seq = 0L
    /** Changed under [lock]; read on [io] too, so that a write queued before [clear] is dropped. */
    @Volatile
    private var generation = 0L
    private var hint: Pair<GarVoiceStart, Long>? = null
    /** Opened by the Whisper device, waiting for the evaluator to get its event. */
    private var capturing: VoiceTurn? = null
    /** Answered with speech, waiting for the first word. */
    private var speaking: VoiceTurn? = null
    /** Finished with input, waiting for the next turn's signals. */
    private var held: VoiceTurn? = null
    /** The user bubble the user tapped to edit, until the edit is sent. */
    private var editing: String? = null
    /** Continued-listening windows that heard nothing, or noise, since the last turn opened. */
    private var silentWindows = 0
    private var hallucinatedWindows = 0
    private var warned = false

    /** The monotonic clock; 0 if it fails, so that the turn's own methods never throw. */
    internal fun monoNow(): Long = try {
        monoClock()
    } catch (t: Throwable) {
        warn("Could not read the clock", t)
        0L
    }

    internal fun promptInfo(): VoicePromptInfo? = try {
        promptInfoSource()
    } catch (t: Throwable) {
        warn("Could not read the prompt's owner", t)
        null
    }

    internal fun warn(message: String, throwable: Throwable?) {
        // once per process: a broken log must not flood logcat
        if (!warned) {
            warned = true
            try {
                warnLog(message, throwable)
            } catch (t: Throwable) {
                // nothing left to tell
            }
        }
    }

    private inline fun <T> safe(default: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        warn("Voice log failed", t)
        default
    }

    /** How the next turn starts; ignored while a capture runs (a tap on the mic ends it). */
    fun hint(start: GarVoiceStart) {
        safe(Unit) {
            if (!enabled) return
            synchronized(lock) {
                if (capturing == null || start == GarVoiceStart.TYPED) {
                    hint = Pair(start, monoClock())
                }
            }
        }
    }

    /** The user tapped their bubble with [text] to edit it. */
    fun editing(text: String) {
        safe(Unit) {
            if (!enabled) return
            synchronized(lock) { editing = text }
        }
    }

    /**
     * A speech capture starts with [engine]: opens its turn, or returns null while the log is off
     * and for a capture without a hint, which isn't GAR's (another app's dictation through
     * SttService or the speech popup). A capture whose turn the evaluator never got is written.
     */
    fun captureStarted(engine: String): VoiceTurn? = safe(null) {
        if (!enabled) {
            return@safe null
        }
        val turn = synchronized(lock) {
            capturing?.let { left ->
                left.update { it.answer = VoiceRecord.Answer(outcome = "not_evaluated") }
                finishLocked(left)
            }
            val start = takeHint() ?: return@safe null
            open(start).also { capturing = it }
        }
        val model = try { sttModel() } catch (t: Throwable) { null }
        turn.update {
            it.sttEngine = engine
            it.sttModel = model
        }
        turn
    }

    /**
     * The evaluator got [input]: the turn it belongs to (the capture's, or a new one for typed
     * input, buttons and other recognizers), null while the log is off or for an empty event.
     * A Final takes the held turn, to be judged when the new turn is answered; None and Error
     * end the turn.
     */
    fun input(input: VoiceInput): VoiceTurn? = safe(null) {
        if (!enabled) {
            return@safe null
        }
        when (input) {
            is VoiceInput.Final -> finalInput(input.utterances)
            VoiceInput.None -> {
                val turn = synchronized(lock) {
                    capturing?.also { capturing = null }
                        // another recognizer (Vosk) heard nothing
                        ?: takeHint()?.takeIf { it != GarVoiceStart.TYPED }?.let { start ->
                            open(start).also { opened ->
                                opened.update { it.sttEngine = ENGINE_OTHER }
                            }
                        }
                } ?: return@safe null
                turn.update { it.answer = VoiceRecord.Answer(outcome = "no_input") }
                finish(turn)
                null
            }
            is VoiceInput.Error -> {
                val turn = synchronized(lock) {
                    capturing.also { capturing = null } ?: open(takeHint() ?: GarVoiceStart.OTHER)
                }
                turn.update {
                    it.answer = VoiceRecord.Answer(
                        outcome = "stt_error",
                        errorClass = input.throwable.javaClass.name,
                        errorMessage = input.throwable.message,
                    )
                }
                finish(turn)
                null
            }
        }
    }

    private fun finalInput(utterances: List<String>): VoiceTurn {
        val now = monoClock()
        val (turn, previous) = synchronized(lock) {
            val start = takeHint()
            // the capture's text: a button tapped while listening is a turn of its own
            val transcribed = capturing?.takeIf { capture ->
                synchronized(capture.record) { capture.record.transcript != null }
            }
            val turn = if (start == GarVoiceStart.TYPED || transcribed == null) {
                open(start ?: GarVoiceStart.BUTTON).also { opened ->
                    if (start != null && start != GarVoiceStart.TYPED) {
                        opened.update { it.sttEngine = ENGINE_OTHER } // Vosk, another app
                    }
                }
            } else {
                capturing = null
                transcribed
            }
            val editedFrom = if (start == GarVoiceStart.TYPED) editing else null
            if (start == GarVoiceStart.TYPED) {
                editing = null
            }
            turn.update {
                it.input = utterances.firstOrNull() ?: ""
                it.alternatives = utterances.size
                it.editedFrom = editedFrom
                it.stage(STAGE_INPUT, now)
            }
            // judged when this turn is answered (finishLocked)
            val previous = held.also { held = null }
            turn.previous = previous
            Pair(turn, previous)
        }
        if (previous != null) {
            // in case this turn never ends
            schedule(holdMillis, Runnable { writePrevious(turn) })
        }
        return turn
    }

    private fun writePrevious(turn: VoiceTurn) = safe(Unit) {
        val previous = synchronized(lock) { turn.previous.also { turn.previous = null } }
        if (previous != null) {
            write(previous)
        }
    }

    /** Call with [lock] held: marks [previous] from [next], which is answered, and writes it. */
    private fun judge(previous: VoiceTurn, next: VoiceTurn) {
        val (before, end) = synchronized(previous.record) {
            Pair(signalTurn(previous.record), previous.record.endMono)
        }
        val (after, start, editedFrom) = synchronized(next.record) {
            Triple(signalTurn(next.record), next.record.startMono, next.record.editedFrom)
        }
        val gap = end?.let { start - it } ?: Long.MAX_VALUE
        val previousFollowUp = synchronized(previous.record) { previous.record.followUp }
        val nextFollowUp = synchronized(next.record) { next.record.followUp }
        val reasons = VoiceSignals.reasons(before, after, gap, editedFrom) +
            VoiceFollowUp.signals(previousFollowUp, nextFollowUp, after.input)
        if (reasons.isNotEmpty()) {
            markSuspect(previous, reasons, next.id)
        }
        write(previous)
    }

    /**
     * [turn]'s answer: the skill that ran ([skill]), what it said ([speech]), its card's class,
     * whether it was the missing permissions card, and whether GAR didn't understand
     * ([VoiceSignals.NOT_UNDERSTOOD] or [VoiceSignals.ASKED_TO_REPEAT], else null).
     */
    fun answered(
        turn: VoiceTurn,
        skill: String,
        speech: String,
        card: String,
        permissionCard: Boolean,
        notUnderstood: String?,
        spoken: Boolean? = null,
    ) = safe(Unit) {
        val now = monoClock()
        turn.update {
            it.answer = VoiceRecord.Answer(
                outcome = if (permissionCard) "missing_permissions" else "answered",
                skill = skill,
                speech = speech,
                card = card,
                permissionCard = permissionCard,
                spoken = spoken,
            )
            it.stage(STAGE_ANSWER, now)
        }
        if (notUnderstood != null) {
            markSuspect(turn, listOf(notUnderstood), null)
        }
        if (speech.isNotBlank() && spoken != false && !stale(turn)) {
            synchronized(lock) { speaking = turn }
        }
        finish(turn)
    }

    /**
     * A continued-listening window ended without a turn: nothing was said, or only noise or one
     * of Whisper's silence phrases ([hallucination]). Its capture's turn is dropped, and the next
     * turn counts it.
     */
    fun windowSilent(hallucination: Boolean) = safe(Unit) {
        if (!enabled) return
        synchronized(lock) {
            capturing?.let { turn ->
                synchronized(turn.record) {
                    turn.finished = true
                    turn.written = true
                    // the counts it took when it opened go on to the next turn
                    silentWindows += turn.record.continuedSilent
                    hallucinatedWindows += turn.record.continuedHallucination
                }
            }
            capturing = null
            if (hallucination) {
                hallucinatedWindows += 1
            } else {
                silentWindows += 1
            }
        }
    }

    /** [turn]'s follow-up was recorded: GAR asked what "it" was, or nothing took the rewrite. */
    internal fun followedUp(turn: VoiceTurn, followUp: VoiceFollowUp) = safe(Unit) {
        VoiceFollowUp.ownSignal(followUp)?.let { markSuspect(turn, listOf(it), null) }
    }

    /** Matching or the skill threw [throwable] for [turn]. */
    fun failed(turn: VoiceTurn, throwable: Throwable) = safe(Unit) {
        turn.update {
            it.answer = VoiceRecord.Answer(
                outcome = "error",
                errorClass = throwable.javaClass.name,
                errorMessage = throwable.message,
            )
            it.stage(STAGE_ANSWER, monoClock())
        }
        markSuspect(turn, listOf(VoiceSignals.ERROR), null)
        finish(turn)
    }

    /** The text to speech started speaking: the first word of the last answer. */
    fun firstWord() {
        safe(Unit) {
            if (!enabled) return
            val turn = synchronized(lock) { speaking.also { speaking = null } } ?: return
            turn.stage(STAGE_FIRST_WORD)
        }
    }

    private fun finish(turn: VoiceTurn) {
        synchronized(lock) { finishLocked(turn) }
    }

    private fun finishLocked(turn: VoiceTurn) {
        val now = monoClock()
        val stale = stale(turn)
        val frameSummary = try {
            frames(turn.record.startMono, now)
        } catch (t: Throwable) {
            warn("Could not read the frame times", t)
            null
        }
        val hasInput = synchronized(turn.record) {
            if (turn.finished) {
                return
            }
            turn.finished = true
            if (stale) {
                turn.written = true
            }
            turn.record.endMono = now
            turn.record.frames = frameSummary
            turn.record.stage(STAGE_END, now)
            turn.record.input != null
        }
        if (capturing === turn) {
            capturing = null
        }
        val previous = turn.previous
        turn.previous = null
        if (stale) {
            return // from before Clear or the log going off
        }
        if (previous != null) {
            judge(previous, turn)
        }
        if (!hasInput) {
            write(turn)
            return
        }
        held?.takeIf { it !== turn }?.let { write(it) }
        held = turn
        schedule(holdMillis, Runnable { flushIfHeld(turn) })
    }

    private fun flushIfHeld(turn: VoiceTurn) = safe(Unit) {
        val wasHeld = synchronized(lock) { (held === turn).also { if (it) held = null } }
        if (wasHeld) {
            write(turn)
        }
    }

    /** Writes the held turn now, e.g. before sharing the log. */
    fun flush() = safe(Unit) {
        val turn = synchronized(lock) { held.also { held = null } }
        if (turn != null) {
            write(turn)
        }
    }

    private fun markSuspect(turn: VoiceTurn, reasons: List<String>, by: String?) {
        var wav: String? = null
        turn.update {
            it.suspect.addAll(reasons)
            if (by != null) {
                it.suspectBy = by
            }
            wav = it.wav
        }
        wav?.let { name ->
            io.execute {
                safe(Unit) {
                    if (!stale(turn)) {
                        val newName = files.markSuspect(name)
                        turn.update { it.wav = newName }
                    }
                }
            }
        }
    }

    internal fun saveAudio(turn: VoiceTurn, name: String, pcm: FloatArray) = safe(Unit) {
        io.execute {
            safe(Unit) {
                val (peak, mean) = VoiceTurn.levels(pcm)
                turn.update {
                    val capture = it.capture ?: VoiceRecord.Capture().also { c -> it.capture = c }
                    capture.peakDbfs = peak
                    capture.meanDbfs = mean
                }
                if (enabled && !stale(turn)) {
                    files.saveWav(name, pcm)
                }
            }
        }
    }

    private fun write(turn: VoiceTurn) {
        io.execute {
            safe(Unit) {
                val (json, summary) = synchronized(turn.record) {
                    if (turn.written) {
                        return@execute
                    }
                    turn.written = true
                    if (stale(turn)) {
                        return@execute
                    }
                    Pair(turn.record.toJson().toString(), summary(turn.record))
                }
                if (enabled) {
                    files.append(json)
                    log(summary)
                    safe(Unit) { written(json) }
                }
            }
        }
    }

    /** Deletes the logs and recordings, and forgets the turns not yet written. */
    fun clear(done: (Boolean) -> Unit = {}) = safe(Unit) {
        synchronized(lock) { forget() }
        io.execute {
            val ok = safe(false) {
                files.clear()
                true
            }
            safe(Unit) { done(ok) }
        }
    }

    /** Writes the held turn, then zips the log into [target]; [done] gets it, null on failure. */
    fun zipTo(target: File, done: (File?) -> Unit) = safe(Unit) {
        flush()
        io.execute {
            val zipped = safe(null) {
                files.zipTo(target)
                target
            }
            safe(Unit) { done(zipped) }
        }
    }

    /** Whether [turn] is from before the last [clear] or the log going off. */
    private fun stale(turn: VoiceTurn): Boolean = turn.generation != generation

    /** What [VoiceSignals] reads of [r]; call with its lock held. */
    private fun signalTurn(r: VoiceRecord) = VoiceSignals.Turn(
        input = r.input,
        skill = r.answer?.skill,
        path = r.path,
        check = r.check,
        card = r.answer?.card,
        trouble = VoiceSignals.troubled(
            r.answer?.outcome, r.path, r.check, r.model?.outcome, r.answer?.card, r.suspect
        ),
        promptNames = r.prompt?.names ?: listOf(),
    )

    /** Call with [lock] held: a new generation, so turns in flight are dropped too. */
    private fun forget() {
        generation += 1
        listOfNotNull(capturing, speaking, held).forEach { turn ->
            synchronized(turn.record) { turn.written = true }
        }
        capturing = null
        speaking = null
        held = null
        hint = null
        editing = null
        silentWindows = 0
        hallucinatedWindows = 0
    }

    /** Call with [lock] held. */
    private fun takeHint(): GarVoiceStart? {
        val taken = hint
        hint = null
        return taken?.takeIf { monoClock() - it.second <= hintMillis }?.first
    }

    /** Call with [lock] held. */
    private fun open(start: GarVoiceStart): VoiceTurn {
        seq += 1
        val wall = wallClock()
        val record = VoiceRecord(
            id = VoiceRecord.idFor(wall, seq),
            seq = seq,
            wallStart = wall,
            startMono = monoClock(),
            start = start,
        )
        record.continuedSilent = silentWindows
        record.continuedHallucination = hallucinatedWindows
        silentWindows = 0
        hallucinatedWindows = 0
        val turn = VoiceTurn(this, record, generation)
        // a binder call: on the log's thread, so that the input isn't held up by it
        io.execute {
            val locked = try { isLocked() } catch (t: Throwable) { null }
            turn.update { it.locked = locked }
        }
        return turn
    }

    companion object {
        /** How long a finished turn waits for the next turn's failure signals. */
        const val HOLD_MILLIS = 60_000L

        /**
         * How long a start hint waits for its turn: a capture can take 28 s before Vosk or
         * another recognizer sends its text.
         */
        const val HINT_MILLIS = 30_000L

        const val ENGINE_WHISPER = "whisper"
        const val ENGINE_OTHER = "other"

        /**
         * One line for logcat: how each stage went and how long it took, never what was said or
         * any name.
         */
        fun summary(r: VoiceRecord): String {
            val parts = ArrayList<String>()
            parts.add("turn ${r.id}")
            parts.add("start=${r.start.key}")
            r.locked?.let { parts.add("locked=$it") }
            r.sttEngine?.let { parts.add("stt=$it") }
            r.capture?.let { c ->
                c.end?.let { parts.add("end=$it") }
                c.speechMs?.let { parts.add("speech=${it}ms") }
                c.meanDbfs?.takeIf { it.isFinite() }
                    ?.let { parts.add(String.format(Locale.ROOT, "level=%.0fdBFS", it)) }
            }
            r.prompt?.let { parts.add("prompt=${it.tokens}tok") }
            r.transcript?.let { t ->
                parts.add("filter=${t.filterReason ?: t.filter}")
                parts.add("decode=${t.decodeMs}ms")
            }
            r.path?.let { parts.add("route=$it") }
            r.check?.let { parts.add("check=$it") }
            r.rankMillis?.let { wall ->
                parts.add(
                    "rank=${wall}ms" + (r.rankCpuMillis?.let { "/cpu=${it}ms" } ?: "") +
                        (if (r.rankModelLoading) "/model_loading" else "")
                )
            }
            r.model?.let { parts.add("model=${it.outcome}" + (it.tool?.let { t -> ":$t" } ?: "")) }
            r.answer?.let { a ->
                parts.add("answer=${a.outcome}" + (a.skill?.let { s -> ":$s" } ?: ""))
            }
            listOf(
                "text" to VoiceRecord.STAGE_TEXT,
                "routed" to VoiceRecord.STAGE_ROUTE,
                "word" to STAGE_FIRST_WORD,
            ).forEach { (key, stage) -> r.latency(stage)?.let { parts.add("$key=${it}ms") } }
            if (r.suspect.isNotEmpty()) {
                parts.add("suspect=" + r.suspect.joinToString(","))
            }
            return parts.joinToString(" ")
        }
    }
}
