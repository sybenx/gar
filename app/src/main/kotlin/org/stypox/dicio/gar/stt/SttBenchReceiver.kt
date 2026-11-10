package org.stypox.dicio.gar.stt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cc.aaro.gar.router.SpokenForm
import cc.aaro.gar.stt.AudioCtx
import cc.aaro.gar.stt.WavReader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.stypox.dicio.BuildConfig
import java.io.File
import java.util.Locale
import javax.inject.Inject

/**
 * Debug builds only (registered in the debug manifest): runs every WAV in
 * `filesDir/gar-stt-test/bench/` through the selected Whisper model and logs one `GarStt` line
 * each, then a summary. For choosing the model, MIN_AUDIO_CTX and the hang on the phone (design
 * section 12):
 *
 * ```
 * adb shell am broadcast -a cc.aaro.gar.STT_BENCH -p cc.aaro.gar.gar \
 *     [--ei min_audio_ctx 96] [--ei audio_ctx 1500] [--ei repeat 3] \
 *     [--es names "Aaron, Erin, Mom"] [--ez bias false]
 * adb logcat -s GarStt
 * ```
 *
 * `min_audio_ctx` replaces [AudioCtx.MIN_AUDIO_CTX]; `audio_ctx` fixes the context for every clip
 * (1500 is Whisper's full window); `repeat` runs each clip that many times. The prompt gets the
 * phone's contact names as the input device would ([SttModels.promptNames], empty while the
 * setting is off), or `names` (comma-separated, most important first) instead; `bias false`
 * runs without a prompt. Each line says whether the owner's name went into the prompt and where
 * it came from ([OwnerSource]: setting, asked, profile), and the prompt's tokens and names; the
 * names themselves are logged only when given with `names` (logcat is readable by adb). Keep GAR
 * in the foreground while it runs. A second broadcast while one runs is ignored.
 */
@AndroidEntryPoint
class SttBenchReceiver : BroadcastReceiver() {
    @Inject
    lateinit var models: SttModels

    @Inject
    lateinit var contactNames: GarSttPromptNames

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) {
            return
        }
        val minAudioCtx = intent.getIntExtra("min_audio_ctx", AudioCtx.MIN_AUDIO_CTX)
        val fixedAudioCtx = intent.getIntExtra("audio_ctx", 0)
        val repeat = intent.getIntExtra("repeat", 1).coerceAtLeast(1)
        val bias = intent.getBooleanExtra("bias", true)
        val names = intent.getStringExtra("names")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
        val dir = File(context.filesDir, "gar-stt-test/bench")

        synchronized(SttBenchReceiver) {
            if (running?.isActive == true) {
                Log.w(TAG, "bench: already running")
                return
            }
            // the work outlives onReceive: a bench of 40 clips takes longer than a broadcast may
            running = scope.launch {
                bench(dir, minAudioCtx, fixedAudioCtx, repeat, bias, names)
            }
        }
    }

    /** @param names the prompt's names, or null for the contacts' */
    private suspend fun bench(
        dir: File,
        minAudioCtx: Int,
        fixedAudioCtx: Int,
        repeat: Int,
        bias: Boolean,
        names: List<String>?,
    ) {
        val files = dir.listFiles { file -> file.name.endsWith(".wav") }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            Log.w(TAG, "bench: no .wav files in $dir")
            return
        }
        Log.i(TAG, "bench: ${files.size} clips, min_audio_ctx $minAudioCtx, " +
            "audio_ctx ${if (fixedAudioCtx > 0) fixedAudioCtx else "from length"}, repeat $repeat")

        models.acquire()
        try {
            val loadStart = System.nanoTime()
            models.use { } // wait for the load, so the first clip's time is only its own
            Log.i(TAG, "bench: model ready after ${(System.nanoTime() - loadStart) / 1_000_000} ms")
            // acquire started reading them the first time; empty if off or not allowed
            val built = if (bias && names == null) {
                withTimeoutOrNull(NAMES_WAIT_MILLIS) { contactNames.state.first { it != null } }
            } else {
                null
            }
            val promptNames = when {
                !bias -> emptyList()
                names != null -> names
                else -> models.promptNames.value
            }
            // the contacts' names stay out of logcat, only how many and the owner's source
            val owner = built?.owner
            Log.i(TAG, "bench: prompt names " + when {
                promptNames.isEmpty() -> "none"
                names != null -> "\"${promptNames.joinToString(", ")}\""
                else -> "from contacts, ${promptNames.size} candidates, " +
                    "owner from ${owner?.source?.label ?: "nowhere"}"
            })

            val totals = mutableListOf<Long>()
            for (file in files) {
                val pcm = try {
                    file.inputStream().buffered().use { WavReader.read(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "bench ${file.name}: unreadable", e)
                    continue
                }
                val audioCtx = if (fixedAudioCtx > 0) {
                    fixedAudioCtx
                } else {
                    AudioCtx.forSamples(pcm.size, minAudioCtx)
                }
                repeat(repeat) {
                    val transcript = models.use {
                        it.transcribe(pcm, pcm.size, audioCtx, names = promptNames)
                    }
                    totals.add(transcript.totalMicros / 1000)
                    val spoken = SpokenForm.of(transcript.text).spoken
                    Log.i(TAG, String.format(
                        Locale.ROOT,
                        "bench %s: clip %.2f s, audio_ctx %d, encode %d ms, total %d ms, " +
                            "tokens %d, no_speech %.2f, confidence %.2f, prompt %d tokens %d names, " +
                            "owner %s, raw \"%s\", spoken \"%s\"",
                        file.name,
                        pcm.size / AudioCtx.SAMPLE_RATE.toFloat(),
                        transcript.audioCtx,
                        transcript.encodeMicros / 1000,
                        transcript.totalMicros / 1000,
                        transcript.tokens,
                        transcript.noSpeechProb,
                        transcript.confidence,
                        transcript.promptTokens,
                        transcript.promptNames.size,
                        SttBenchLog.ownerInPrompt(transcript.promptNames, built != null, owner),
                        transcript.text,
                        spoken,
                    ))
                }
            }
            totals.sort()
            if (totals.isNotEmpty()) {
                Log.i(TAG, "bench: ${totals.size} runs, total ms median ${totals[totals.size / 2]}, " +
                    "max ${totals.last()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "bench: failed", e)
        } finally {
            models.release()
        }
    }

    companion object {
        const val ACTION = "cc.aaro.gar.STT_BENCH"
        private const val TAG = "GarStt"
        private const val NAMES_WAIT_MILLIS = 3_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var running: Job? = null
    }
}

/** The bench's log fields, apart from the receiver so that tests can run them. */
object SttBenchLog {
    /**
     * Whether the owner's name went into the prompt ([promptNames], what the engine fitted)
     * and its source: "in (profile)", "out (setting)", "unknown" (no source gave one), or
     * "n/a" when the prompt isn't the contacts' ([fromContacts] false: names given with the
     * extra, no prompt asked for, or the names not built in time). No name, for logcat.
     */
    fun ownerInPrompt(promptNames: List<String>, fromContacts: Boolean, owner: PromptOwner?): String =
        when {
            !fromContacts -> "n/a"
            owner == null -> "unknown"
            ContactPromptNames.hasOwner(promptNames, owner.name) -> "in (${owner.source.label})"
            else -> "out (${owner.source.label})"
        }
}
