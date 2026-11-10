package org.stypox.dicio.gar

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.format.ModelFamily
import cc.aaro.gar.router.tools.ToolSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dicio.skill.skill.AlwaysBestScore
import org.stypox.dicio.R
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarLockedSkill
import org.stypox.dicio.gar.call.GarRoutedSkill
import org.stypox.dicio.gar.conversation.GarSpeechMode
import org.stypox.dicio.gar.conversation.GarTurnElement
import org.stypox.dicio.gar.model.selectedModelFamily
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.timer.GarStartedTimers
import org.stypox.dicio.gar.tools.GarToolsProvider
import org.stypox.dicio.gar.ui.GarThinking
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Called by [org.stypox.dicio.eval.SkillEvaluator] (through [garMatchUtterances]): checks the
 * matches of Dicio's default skills, and routes the input with the model when no skill matched
 * (or a match was rejected), before Dicio's text fallback. See gar/DESIGN.md, "Data flow".
 */
interface GarFallback {
    /**
     * Also called while a follow-up question is pending (GAR's or a skill's) whose skills didn't
     * match the input. A call then ends that conversation, like a match in Dicio's default batch
     * does, so an unanswered question never keeps the model away.
     *
     * @return the input and a skill for the target of the model's tool call, or null to let
     * Dicio's fallback handle the input. Never throws.
     */
    suspend fun tryRoute(
        ctx: SkillContextInternal,
        ranker: SkillRanker,
        utterances: List<String>,
    ): Pair<String, SkillWithResult<*>>?

    /**
     * Called once per input, before Dicio's ranker: the check for the matches of Dicio's default
     * skills (never for answers to a pending question), or null when they aren't checked. The
     * check says whether to treat a match for an input as no match, so that the input goes to the
     * model instead; see [GarMatchCheck]. Null when the model can't take the input (off, missing
     * or failed to load) or the language isn't English: Dicio's match is better than its text
     * fallback. Neither this nor the check throws.
     */
    suspend fun matchCheck(ctx: SkillContextInternal): GarMatchRejecter? = null
}

/** Whether to treat [match], Dicio's match for [input], as no match. Never throws. */
fun interface GarMatchRejecter {
    fun rejects(match: SkillWithResult<*>, input: String): Boolean
}

/** Where [GarFallbackImpl] gets the model from; implemented by `GarModelManager`. */
interface GarRouterSource {
    /** Whether the model selected in [settings] is on disk. */
    fun isModelAvailable(settings: GarSettings): Boolean

    /**
     * Whether the model selected in [settings] can route: it is on disk, and it neither failed to
     * load nor stopped the app while loading (until the user retries).
     */
    fun canRoute(settings: GarSettings): Boolean = isModelAvailable(settings)

    /**
     * Whether the model selected in [settings] is loaded now, so [route] won't wait for a load
     * (gar/DESIGN.md, "Model lifecycle and settings").
     */
    fun isLoaded(settings: GarSettings): Boolean = true

    /** Routes [utterance] with the model selected in [settings], loading it first if needed. */
    suspend fun route(settings: GarSettings, utterance: String, tools: List<ToolSpec>): RouterOutput
}

fun interface GarLogger {
    fun log(message: String, throwable: Throwable?)

    /** Development details, e.g. each routed utterance with its result and timing. */
    fun debug(message: String) {}

    companion object {
        fun android(tag: String) = object : GarLogger {
            override fun log(message: String, throwable: Throwable?) {
                Log.w(tag, message, throwable)
            }

            override fun debug(message: String) {
                Log.d(tag, message)
            }
        }
    }
}

/**
 * @param isDeviceLocked whether the phone is locked (with a PIN, pattern, ...): only tools marked
 * [ToolSpec.onLockScreen] run then
 * @param timerNames the names of the timers GAR started in the clock app, null for an unnamed one
 */
@Singleton
class GarFallbackImpl(
    private val settings: DataStore<GarSettings>,
    private val toolsProvider: GarToolsProvider,
    private val routerSource: GarRouterSource,
    private val logger: GarLogger,
    private val isDeviceLocked: () -> Boolean = { false },
    private val timerNames: () -> List<String?> = { listOf() },
    /**
     * Says that the answer takes a moment ("One moment."), once, when the model is still loading
     * [slowLoadMillis] after routing started; nothing is waited for silently.
     */
    private val sayLoading: suspend (SkillContextInternal) -> Unit = {},
    private val slowLoadMillis: Long = SLOW_LOAD_MILLIS,
) : GarFallback {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        settings: DataStore<GarSettings>,
        toolsProvider: GarToolsProvider,
        routerSource: GarRouterSource,
    ) : this(
        settings,
        toolsProvider,
        routerSource,
        GarLogger.android(TAG),
        isDeviceLocked = {
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        },
        // read once per input: the clock app's timers that GAR started and still remembers (a
        // failed read only skips the check of this input)
        timerNames = { GarStartedTimers.get(context).current().map { it.name } },
        sayLoading = { ctx ->
            // the turn's own mode: a typed request gets nothing said, as its answer
            val mode = GarTurnElement.current()?.mode
            val text = context.getString(R.string.gar_model_loading_wait)
            withContext(Dispatchers.Main) {
                GarSpeechMode.speak(ctx.speechOutputDevice, mode, text)
            }
        },
    )

    // one model, one request at a time (the evaluator itself doesn't serialize inputs)
    private val mutex = Mutex()

    override suspend fun matchCheck(ctx: SkillContextInternal): GarMatchRejecter? = try {
        val currentSettings = settings.data.first()
        if (ctx.sentencesLanguage != ENGLISH ||
            !currentSettings.llmEnabled ||
            !routerSource.canRoute(currentSettings)
        ) {
            null
        } else {
            val state = GarMatchCheck.State(
                tools = toolsProvider.allTools(),
                timerNames = timerNames(),
                parserFormatter = ctx.parserFormatter,
            )
            GarMatchRejecter { match, input ->
                try {
                    GarMatchCheck.rejects(state, match, input).also { rejects ->
                        if (rejects) {
                            logger.debug("'$input' matched " +
                                "${match.skill.correspondingSkillInfo.id} as ${match.inputData} " +
                                "without naming what it acts on: asking the model")
                        }
                    }
                } catch (t: Throwable) {
                    logger.log("Error while checking a match", t)
                    false
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.log("Error while preparing the match check", t)
        null
    }

    override suspend fun tryRoute(
        ctx: SkillContextInternal,
        ranker: SkillRanker,
        utterances: List<String>,
    ): Pair<String, SkillWithResult<*>>? {
        val turn = GarVoiceLog.turn() // the voice log's, null while it is off
        try {
            val utterance = utterances.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            if (ctx.sentencesLanguage != ENGLISH) {
                turn?.modelSkipped("not_english")
                return null // slot resolution and follow-up answers are English only for now
            }
            val currentSettings = settings.data.first()
            if (!currentSettings.llmEnabled || !routerSource.isModelAvailable(currentSettings)) {
                turn?.modelSkipped(if (currentSettings.llmEnabled) "no_model" else "off")
                return null
            }
            val offered = toolsProvider.offeredTools(ctx)
            if (offered.isEmpty()) {
                turn?.modelSkipped("no_tools")
                return null
            }

            val start = System.nanoTime()
            val output = mutex.withLock {
                GarVoiceLog.routing(turn) // for the call rewrite's record
                GarThinking.routingStarted() // the mic state's "Thinking" with the model
                try {
                    coroutineScope {
                        // a model that still loads (a cold show, memory pressure) takes seconds:
                        // say so once instead of waiting silently
                        val loading = if (routerSource.isLoaded(currentSettings)) null else launch {
                            delay(slowLoadMillis)
                            turn?.stage(STAGE_LOADING_SAID)
                            sayLoading(ctx)
                        }
                        try {
                            routerSource.route(currentSettings, utterance, offered.map { it.tool })
                        } finally {
                            loading?.cancel()
                        }
                    }
                } finally {
                    GarThinking.routingEnded()
                    GarVoiceLog.routing(null)
                }
            }
            val millis = (System.nanoTime() - start) / 1_000_000
            logger.debug("'$utterance' -> $output in $millis ms")
            turn?.modelOutput(output, millis)
            return when (output) {
                is RouterOutput.Call -> {
                    val target = offered.firstOrNull { it.tool.name == output.tool.name }
                        ?: run {
                            logger.log("Model called a tool that wasn't offered: ${output.tool.name}", null)
                            return null
                        }
                    // a new command: drop pending follow-up questions, as a default batch match
                    // does (with no match, they stay and the text fallback asks to repeat)
                    ranker.removeAllBatches()

                    if (!target.tool.onLockScreen && isDeviceLocked()) {
                        turn?.modelLocked()
                        return Pair(
                            utterance,
                            SkillWithResult(GarLockedSkill(target.skillInfo), AlwaysBestScore, Unit),
                        )
                    }

                    // gar:messages: an sms_send for an email or a reply, a read_notifications
                    // about messages, under the skill that answers it (its permissions and icon)
                    val targetInfo = org.stypox.dicio.skills.messages.MessagesCalls.rerouteInfo(
                        output.tool, output.rawSlots, output.utterance, toolsProvider::isSkillEnabled,
                    ) ?: target.skillInfo
                    val skill = GarRoutedSkill(
                        targetInfo = targetInfo,
                        target = target.skill,
                        confirm = currentSettings.confirmLlmActions,
                        // FunctionGemma calls a tool for almost anything (21 of 22 out-of-scope
                        // requests in the eval), so with it even harmless tools ask first
                        confirmAllTools =
                            currentSettings.selectedModelFamily() == ModelFamily.FUNCTION_GEMMA,
                    )
                    val call = GarCall(
                        tool = target.tool,
                        rawSlots = output.rawSlots,
                        utterance = output.utterance,
                    )
                    Pair(utterance, SkillWithResult(skill, AlwaysBestScore, call))
                }
                // a misroute the router's filter caught: Dicio's fallback, like no match
                is RouterOutput.Dropped, RouterOutput.NoMatch -> null
                is RouterOutput.Failed -> {
                    logger.log("Router failed: ${output.reason}", output.cause)
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // also NotImplementedError and other Errors: the fallback must never break Dicio
            logger.log("Error while routing with the model", t)
            turn?.modelSkipped("error: ${t.javaClass.simpleName}")
            return null
        }
    }

    companion object {
        private const val TAG = "GarFallback"
        const val ENGLISH = "en"

        /** How long a route waits for a loading model before [sayLoading]. */
        const val SLOW_LOAD_MILLIS = 1_500L

        /** The voice log's stage for [sayLoading]. */
        const val STAGE_LOADING_SAID = "model_loading_said"
    }
}
