package org.stypox.dicio.gar.voicelog

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import cc.aaro.gar.router.CallRewrite
import cc.aaro.gar.router.RouterOutput
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.BuildConfig
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.perf.GarJank
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.stt.GarSttPromptNames
import org.stypox.dicio.gar.stt.SttModelCatalog
import org.stypox.dicio.gar.ui.GarStarterChips
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.skills.fallback.text.TextFallbackOutput
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The voice log (gar/DESIGN.md, "Voice log"): one JSON line per turn in
 * `files/gar-voice-log/`, so that the developer can see from daily use at which stage a spoken
 * command worked or failed. "Voice log" in GAR's settings, on by default in debug builds only
 * ([settingOn]); nothing leaves the phone unless the user shares it, and turning it off deletes
 * the recorded clips.
 *
 * The stages call these hooks; each returns at once and never throws, and does nothing until
 * [install] ran or while the setting is off. The evaluator carries a turn through its coroutine
 * ([input], [turn]); the ranker, the model's call rewrite and the text to speech, which aren't
 * coroutines, record into the turn being matched ([scoring], [routing]) or answered, which is
 * fine since GAR matches one input at a time.
 */
object GarVoiceLog {
    private const val TAG = "GarVoice"
    private const val SHARE_DIR = "gar-voice-log-share"

    @Volatile
    private var turns: VoiceTurns? = null

    @Volatile
    private var scoringTurn: VoiceTurn? = null

    @Volatile
    private var routingTurn: VoiceTurn? = null

    /** Set while [listen] starts speech input for the evaluator, on that thread. */
    private val listening = ThreadLocal<Boolean>()

    /** Starts the log; called once from `App.onCreate`. */
    fun install(context: Context) {
        try {
            if (turns != null) {
                return
            }
            val appContext = context.applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext, GarVoiceLogEntryPoint::class.java
            )
            val settings = entryPoint.garSettings()
            val promptNames = entryPoint.sttPromptNames()
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "GarVoiceLog").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
            val current = AtomicReference<GarSettings?>(null)
            val files = VoiceLogFiles(File(appContext.filesDir, VoiceLogFiles.DIR_NAME))
            val starters = VoiceStarterIndex(files, GarStarterChips::indexLine)
            val created = VoiceTurns(
                files = files,
                io = executor,
                schedule = { delay, task -> executor.schedule(task, delay, TimeUnit.MILLISECONDS) },
                log = { Log.i(TAG, it) },
                warnLog = { message, t -> Log.w(TAG, message, t) },
                isLocked = {
                    appContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
                },
                sttModel = {
                    current.get()?.let { SttModelCatalog.forSetting(it.sttModel).fileName }
                },
                promptInfoSource = {
                    val owner = promptNames.state.value?.owner
                    VoicePromptInfo(
                        on = current.get()
                            ?.let { GarSttPromptNames.contactNamesOn(it.sttContactNames) } ?: false,
                        owner = owner?.name,
                        ownerSource = owner?.source?.label,
                    )
                },
                // the voice log's clock is System.nanoTime in milliseconds, GarJank's in nanos
                frames = { from, to -> GarJank.summary(from * 1_000_000, to * 1_000_000) },
                written = starters::add,
            )
            turns = created
            // an update from before the index: build it once, on the log's thread, well after
            // the start and its first commands
            executor.schedule({
                try {
                    if (created.enabled) {
                        starters.buildIfMissing()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not index the starters", t)
                }
            }, STARTER_INDEX_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    settings.data
                        .map { it.also(current::set) }
                        .map { settingOn(it) }
                        .distinctUntilChanged()
                        .collect { created.enabled = it }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not follow the voice log setting", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not start the voice log", t)
        }
    }

    /**
     * Whether "Voice log" is on: as the user set it, else [debugBuild] (on in debug builds, off
     * in releases, since it keeps what the user says and short clips of it).
     */
    fun settingOn(settings: GarSettings, debugBuild: Boolean = BuildConfig.DEBUG): Boolean = when {
        settings.voiceLogOff -> false
        settings.voiceLogOn -> true
        else -> debugBuild
    }

    /** [settings] with "Voice log" set to [on] by the user. */
    fun withSetting(settings: GarSettings.Builder, on: Boolean): GarSettings.Builder =
        settings.setVoiceLogOff(!on).setVoiceLogOn(on)

    /** How the next turn starts (the mic button, the assist gesture, ...). */
    fun hint(start: GarVoiceStart) {
        turns?.hint(start)
    }

    /** The user tapped their bubble with [text] to edit it. */
    fun editing(text: String) {
        turns?.editing(text)
    }

    /**
     * Starts speech input for the evaluator with [startInput] (`sttInputDevice.tryLoad` or
     * `onClick` with `processInputEvent`), as [start]. Only a Whisper capture started from in
     * here opens a turn: another app's dictation through SttService or the speech popup is never
     * logged. The Whisper device starts its capture on this thread, inside [startInput].
     */
    fun <T> listen(start: GarVoiceStart, startInput: () -> T): T {
        hint(start)
        listening.set(true)
        try {
            return startInput()
        } finally {
            listening.remove()
        }
    }

    /** A Whisper capture starts; its turn, or null while the log is off or outside [listen]. */
    fun captureStarted(): VoiceTurn? = if (listening.get() == true) {
        turns?.captureStarted(VoiceTurns.ENGINE_WHISPER)
    } else {
        null
    }

    /**
     * The evaluator got [event]: a coroutine context that carries its turn to the hooks below,
     * empty when there is none (log off, partial text, no input).
     */
    fun input(event: InputEvent): CoroutineContext {
        val log = turns ?: return EmptyCoroutineContext
        val input = when (event) {
            is InputEvent.Final -> VoiceInput.Final(event.utterances.map { it.first })
            InputEvent.None -> VoiceInput.None
            is InputEvent.Error -> VoiceInput.Error(event.throwable)
            is InputEvent.Partial -> return EmptyCoroutineContext
        }
        return log.input(input)?.let { VoiceTurnElement(it) } ?: EmptyCoroutineContext
    }

    /** The turn the calling coroutine evaluates, if any. */
    suspend fun turn(): VoiceTurn? = currentCoroutineContext()[VoiceTurnElement]?.turn

    /** The turn whose input Dicio's ranker scores now ([scored]); null when done. */
    fun scoring(turn: VoiceTurn?) {
        scoringTurn = turn
    }

    /** Dicio's ranker scored a skill (one GAR line in `SkillRanker`). */
    fun scored(result: SkillWithResult<*>) {
        val turn = scoringTurn ?: return
        try {
            turn.scored(result.skill.correspondingSkillInfo.id, result.score.scoreIn01Range())
        } catch (t: Throwable) {
            turns?.warn("Could not record a score", t)
        }
    }

    /** The turn the model routes now (for [watchRewrite]); null when done. */
    fun routing(turn: VoiceTurn?) {
        routingTurn = turn
    }

    /** [rewrite], recording into the routed turn which calls it changed. */
    fun watchRewrite(rewrite: CallRewrite): CallRewrite = CallRewrite { call, tools ->
        val result = rewrite.rewrite(call, tools)
        if (result != call) {
            routingTurn?.rewritten(describe(call), describe(result))
        }
        result
    }

    private fun describe(call: RouterOutput.Call): String =
        call.tool.name +
            call.rawSlots.entries.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }

    /**
     * The evaluator's answer for its turn: [skillInfo]'s [output], saying [speech]; [spoken] is
     * false when the speech is shown only (a typed turn, see `GarSpeechMode`).
     */
    suspend fun answered(
        skillInfo: SkillInfo,
        output: SkillOutput,
        speech: String,
        spoken: Boolean = speech.isNotBlank(),
    ) {
        val turn = turn() ?: return
        val log = turns ?: return
        try {
            log.answered(
                turn,
                skill = skillInfo.id,
                speech = speech,
                card = output.javaClass.simpleName,
                permissionCard = output is MissingPermissionsSkillOutput,
                notUnderstood = (output as? TextFallbackOutput)?.let {
                    if (it.askToRepeat) {
                        VoiceSignals.ASKED_TO_REPEAT
                    } else {
                        VoiceSignals.NOT_UNDERSTOOD
                    }
                },
                spoken = spoken,
            )
        } catch (t: Throwable) {
            log.warn("Could not record an answer", t)
        }
    }

    /**
     * A continued-listening window heard nothing, or only noise or one of Whisper's silence
     * phrases ([hallucination]): no turn, counted in the next one.
     */
    fun windowSilent(hallucination: Boolean) {
        turns?.windowSilent(hallucination)
    }

    /** Matching or the skill threw [throwable] for the evaluator's turn. */
    suspend fun failed(throwable: Throwable) {
        val turn = turn() ?: return
        turns?.failed(turn, throwable)
    }

    /** The text to speech started speaking (one GAR line in `AndroidTtsSpeechDevice`). */
    fun firstWord() {
        turns?.firstWord()
    }

    /** Zips the log and opens Android's share sheet; nothing is sent until the user picks. */
    fun share(context: Context) {
        val log = turns ?: return
        val appContext = context.applicationContext
        val name = "gar-voice-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip"
        val dir = File(appContext.cacheDir, SHARE_DIR)
        log.zipTo(File(dir, name)) { zip ->
            // only the newest copy stays in the cache
            dir.listFiles()?.filter { it != zip }?.forEach { it.delete() }
            Handler(Looper.getMainLooper()).post {
                try {
                    if (zip == null) {
                        Toast.makeText(appContext, R.string.gar_voice_log_share_failed,
                            Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    val uri = FileProvider.getUriForFile(
                        appContext, appContext.packageName + FILE_PROVIDER_SUFFIX, zip
                    )
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val title = appContext.getString(R.string.gar_voice_log_share)
                    appContext.startActivity(
                        Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not share the voice log", t)
                }
            }
        }
    }

    /** Deletes the logs, the recordings and a shared copy, off the main thread. */
    fun clear(context: Context) {
        val appContext = context.applicationContext
        val shared = File(appContext.cacheDir, SHARE_DIR)
        val log = turns
        if (log == null) {
            // install failed: no log thread, so a thread of its own
            Thread({
                val ok = try {
                    File(appContext.filesDir, VoiceLogFiles.DIR_NAME).deleteRecursively() and
                        shared.deleteRecursively()
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not clear the voice log", t)
                    false
                }
                clearedToast(appContext, ok)
            }, "GarVoiceLogClear").start()
            return
        }
        log.clear { ok ->
            clearedToast(appContext, shared.deleteRecursively() && ok)
        }
    }

    private fun clearedToast(appContext: Context, ok: Boolean) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                appContext,
                if (ok) R.string.gar_voice_log_cleared else R.string.gar_voice_log_clear_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** How long after the start the starter index is built, if there is none. */
    private const val STARTER_INDEX_DELAY_MILLIS = 60_000L

    /** Matches the `<provider>` in the manifest. */
    const val FILE_PROVIDER_SUFFIX = ".garvoicelog"
}

/** Carries the evaluator's turn through its coroutine. */
class VoiceTurnElement(val turn: VoiceTurn) : AbstractCoroutineContextElement(VoiceTurnElement) {
    companion object Key : CoroutineContext.Key<VoiceTurnElement>
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GarVoiceLogEntryPoint {
    fun garSettings(): DataStore<GarSettings>
    fun sttPromptNames(): GarSttPromptNames
}
