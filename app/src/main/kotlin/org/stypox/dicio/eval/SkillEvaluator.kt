package org.stypox.dicio.eval

import android.util.Log
import androidx.datastore.core.DataStore // GAR
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex // GAR
import kotlinx.coroutines.sync.withLock // GAR
import kotlinx.coroutines.withContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.gar.GarFallback // GAR
import org.stypox.dicio.gar.conversation.GarContinuedListening // GAR
import org.stypox.dicio.gar.conversation.GarInputMode // GAR
import org.stypox.dicio.gar.conversation.GarRinging // GAR
import org.stypox.dicio.gar.conversation.GarSpeechMode // GAR
import org.stypox.dicio.gar.conversation.GarTurnElement // GAR
import org.stypox.dicio.gar.conversation.GarWindowFacts // GAR
import org.stypox.dicio.gar.followup.GarAnswered // GAR
import org.stypox.dicio.gar.followup.GarConversationMemory // GAR
import org.stypox.dicio.gar.followup.GarTurnToken // GAR
import org.stypox.dicio.gar.garMatchUtterances // GAR
import org.stypox.dicio.gar.settings.GarSettings // GAR
import org.stypox.dicio.gar.stt.GarWhisperHallucinations // GAR
import org.stypox.dicio.gar.voicelog.GarVoiceLog // GAR
import org.stypox.dicio.gar.voicelog.GarVoiceStart // GAR
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState // GAR
import org.stypox.dicio.skills.conversation.ConversationInfo // GAR
import org.stypox.dicio.skills.conversation.ConversationStopOutput // GAR
import org.stypox.dicio.ui.home.Interaction
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.home.QuestionAnswer
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext // GAR

interface SkillEvaluator {
    val state: StateFlow<InteractionLog>

    var permissionRequester: suspend (List<Permission>) -> Boolean

    fun processInputEvent(event: InputEvent)

    // GAR: forgets the interactions and any pending question, so that the answers of an unlocked
    // phone (messages, calls, emails) don't show when GAR opens over the lock screen
    fun forgetInteractions() {}

    // GAR: forgets what the next turn could build on (follow-ups, the previous output, the
    // continued window, the ranker's batches) but keeps the log: the assistant sheet over the
    // lock screen shows only its own turns, and "reply" mustn't act on a message read unlocked
    fun forgetConversation() {}
}

class SkillEvaluatorImpl(
    private val skillContext: SkillContextInternal,
    private val skillHandler: SkillHandler,
    private val sttInputDevice: SttInputDeviceWrapper,
    private val garFallback: GarFallback, // GAR: on-device model, tried before the fallback skill
    garSettings: DataStore<GarSettings>, // GAR: continued listening and speak vs show
) : SkillEvaluator {

    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        // GAR: the settings that GarContinuedListening and GarSpeechMode read at once
        scope.launch {
            try {
                garSettings.data.collect {
                    GarContinuedListening.enabled = GarContinuedListening.settingOn(it)
                    GarSpeechMode.speakTypedAnswers = it.speakTypedAnswers
                }
            } catch (throwable: Throwable) {
                Log.w(TAG, "Could not follow GAR's settings", throwable)
            }
        }
    }

    // GAR: match one input at a time. The model fallback can take seconds, and inputs matched
    // concurrently share skillContext.standardMatchHelper (and the stack of batches)
    private val matchMutex = Mutex()

    private val skillRanker: SkillRanker
        get() = skillHandler.skillRanker.value

    private val _state = MutableStateFlow(
        InteractionLog(
            interactions = listOf(),
            pendingQuestion = null,
        )
    )
    override val state: StateFlow<InteractionLog> = _state

    // must be kept up to date even when the activity is recreated, for this reason it is `var`
    override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }

    override fun processInputEvent(event: InputEvent) = processInput(event, false) // GAR

    // GAR: [continued] for an input heard by a continued-listening window
    private fun processInput(event: InputEvent, continued: Boolean) {
        // GAR: the turn's element (how it came in) and the voice log's turn go with it
        val turn = (event as? InputEvent.Final)?.let {
            GarContinuedListening.onInput() // a newer input: no window for an older answer
            GarTurnElement.of(it, continued, isLocked())
        }
        scope.launch(GarVoiceLog.input(event) + (turn ?: EmptyCoroutineContext)) {
            suspendProcessInputEvent(event)
        }
    }

    // GAR: see SkillEvaluator.forgetInteractions; a pending "Want to reply?" goes too, so that it
    // can't be answered from the lock screen
    override fun forgetInteractions() {
        _state.value = InteractionLog(interactions = listOf(), pendingQuestion = null)
        forgetConversation()
    }

    // GAR: see SkillEvaluator.forgetConversation
    override fun forgetConversation() {
        skillContext.previousOutput = null
        GarConversationMemory.forget() // GAR: the follow-ups' memory too
        GarContinuedListening.onInput() // GAR: and no window for an answer given before
        scope.launch {
            matchMutex.withLock { skillRanker.removeAllBatches() }
        }
    }

    private suspend fun suspendProcessInputEvent(event: InputEvent) {
        when (event) {
            is InputEvent.Error -> {
                addErrorInteractionFromPending(event.throwable)
            }
            is InputEvent.Final -> {
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterances[0].first,
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
                evaluateMatchingSkill(event.utterances.map { it.first })
            }
            InputEvent.None -> {
                _state.value = _state.value.copy(pendingQuestion = null)
            }
            is InputEvent.Partial -> {
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterance,
                        // the next input can be a continuation of the last interaction only if the
                        // last skill invocation provided some skill batches (which are the only way
                        // to continue an interaction/conversation)
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
            }
        }
    }

    private suspend fun evaluateMatchingSkill(utterances: List<String>) {
        val turn = GarTurnElement.current() // GAR
        if (turn?.continued == true && endsContinuedConversation(utterances)) {
            return // GAR
        }
        // GAR: a follow-up ("turn it off") waits for the answer of the turn before it
        GarConversationMemory.awaitSettled(utterances)
        var token: GarTurnToken? = null // GAR: the follow-ups' memory of this turn
        val (chosenInput, chosenSkill) = matchMutex.withLock { try { // GAR: withLock
            GarContinuedListening.matchingContinued = turn?.continued == true // GAR
            // GAR: Dicio's ranker on each alternative (its matches checked by GarMatchCheck),
            // then the model, then the fallback skill: see garMatchUtterances
            garMatchUtterances(skillContext, skillRanker, garFallback, utterances)
                // GAR: the ranker keeps its batches only when a pending question's batch matched
                .also { token = turn?.let { GarConversationMemory.begin(it, skillRanker.hasAnyBatches()) } }
        } catch (throwable: Throwable) {
            GarVoiceLog.failed(throwable) // GAR
            addErrorInteractionFromPending(throwable)
            return
        } finally {
            GarContinuedListening.matchingContinued = false // GAR
            // standardMatchHelper only needs to be set while calling score() on skills, so once
            // all matching and scoring is done, free up the memory it uses (which may be
            // significant since the purpose of MatchHelper is to cache information about the input)
            skillContext.standardMatchHelper = null
        } } // GAR: withLock
        val skillInfo = chosenSkill.skill.correspondingSkillInfo
        var answered: GarAnswered? = null // GAR: what the follow-ups' memory gets

        _state.value = _state.value.copy(
            pendingQuestion = PendingQuestion(
                userInput = chosenInput,
                // the skill ranker would have discarded all batches, if the chosen skill was not
                // the continuation of the last interaction (since continuing an
                // interaction/conversation is done through the stack of batches)
                continuesLastInteraction = skillRanker.hasAnyBatches(),
                skillBeingEvaluated = skillInfo,
            )
        )

        try {
            val permissions = skillInfo.neededPermissions
            if (permissions.isNotEmpty() && !permissionRequester(permissions)) {
                // permissions were not granted, show message
                // GAR: the voice log's turn ends with the permissions card
                MissingPermissionsSkillOutput(skillInfo).let {
                    GarVoiceLog.answered(skillInfo, it, "")
                    addInteractionFromPending(it)
                }
                return
            }

            skillContext.previousOutput =
                _state.value.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
            val output = chosenSkill.generateOutput(skillContext)

            val interactionPlan = output.getInteractionPlan(skillContext)
            // GAR: said for spoken input, shown only for typed input and buttons; in a continued
            // turn the text fallback is shown only (the window may have heard the TV). Decided
            // before the answer lands, so that screen readers announce only what isn't said
            val mode = turn?.mode
            val shownOnly = turn?.continued == true &&
                GarContinuedListening.silentInContinued(output)
            val speech = output.getSpeechOutput(skillContext)
            val spoke = !shownOnly && GarSpeechMode.speaks(mode, speech)
            addInteractionFromPending(output, spoken = spoke) // GAR: spoken
            speech.let {
                GarVoiceLog.answered(skillInfo, output, it, spoke) // GAR
                if (spoke) {
                    withContext (Dispatchers.Main) {
                        GarSpeechMode.speak(skillContext.speechOutputDevice, mode, it) // GAR
                    }
                }
            }
            answered = GarAnswered( // GAR
                skillInfo.id, chosenInput, chosenSkill.inputData, turn?.call, output,
                interactionPlan,
            )

            when (interactionPlan) {
                InteractionPlan.FinishInteraction -> {
                    // current conversation has ended, reset to the default batch of skills
                    skillRanker.removeAllBatches()
                }
                is InteractionPlan.FinishSubInteraction -> {
                    skillRanker.removeTopBatch()
                }
                is InteractionPlan.Continue -> {
                    // nothing to do, just continue with current batches
                }
                is InteractionPlan.StartSubInteraction -> {
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
                is InteractionPlan.ReplaceSubInteraction -> {
                    skillRanker.removeTopBatch()
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
            }

            // GAR: a question after typed input shows only, and so does a continued turn's
            // fallback; any other spoken answer may keep listening (continued listening)
            if (interactionPlan.reopenMicrophone) {
                if (mode != GarInputMode.TYPED && !shownOnly) {
                    skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                        GarVoiceLog.listen(GarVoiceStart.FOLLOW_UP) { // GAR: the voice log's start
                            sttInputDevice.tryLoad(this::processInputEvent)
                        }
                    }
                }
            } else if (turn != null && !shownOnly) {
                scheduleContinuedWindow(turn, skillInfo.id, output, interactionPlan, spoke)
            }

        } catch (throwable: Throwable) {
            GarVoiceLog.failed(throwable) // GAR
            addErrorInteractionFromPending(throwable)
            return
        } finally {
            // GAR: the follow-ups' memory, after the output, its plan and its speech
            token?.let { GarConversationMemory.finish(it, answered, isLocked()) }
        }
    }

    // GAR: in a continued turn in another language, "danke", "c'est tout" and the like end the
    // conversation with the silent "OK" (English has the conversation skill)
    private suspend fun endsContinuedConversation(utterances: List<String>): Boolean {
        val locale = skillContext.locale
        if (locale.language == "en" ||
            !GarContinuedListening.endsConversation(locale, utterances.firstOrNull() ?: "")
        ) {
            return false
        }
        val pending = _state.value.pendingQuestion
        _state.value = _state.value.copy(
            pendingQuestion = pending?.copy(skillBeingEvaluated = ConversationInfo)
        )
        val output = ConversationStopOutput(
            before = _state.value.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
        )
        GarVoiceLog.answered(ConversationInfo, output, "", false)
        addInteractionFromPending(output)
        skillRanker.removeAllBatches()
        return true
    }

    // GAR: once the answer of [turn] has been said, the microphone may stay open for a follow-up
    // (gar/DESIGN.md, "Continued conversation"); GarContinuedListening decides
    private fun scheduleContinuedWindow(
        turn: GarTurnElement,
        skillId: String,
        output: SkillOutput,
        plan: InteractionPlan,
        spoke: Boolean,
    ) {
        val context = skillContext.android
        GarContinuedListening.afterAnswer(
            skillContext.speechOutputDevice,
            facts = {
                GarWindowFacts(
                    settingOn = GarContinuedListening.enabled,
                    spokenInput = turn.mode == GarInputMode.SPOKEN,
                    planReopens = plan.reopenMicrophone,
                    spoke = spoke,
                    conversationEnds = GarContinuedListening.endsWindow(output),
                    skillId = skillId,
                    onScreen = GarContinuedListening.onScreen(),
                    lockedAtStart = turn.lockedAtStart,
                    lockedNow = isLocked(),
                    ringing = GarRinging.isRinging(),
                    call = GarContinuedListening.inCall(context),
                    speakerMusic = GarContinuedListening.speakerMusic(context),
                    whisper = sttInputDevice.hasBriefListening,
                    sttListening = when (sttInputDevice.uiState.value) {
                        SttState.Listening, SttState.Transcribing, SttState.WaitingForResult -> true
                        else -> false
                    },
                )
            },
            open = ::openContinuedWindow,
        )
    }

    // GAR: opens the window scheduled at [scheduled]; the screen turning off closes it
    private fun openContinuedWindow(scheduled: Long) {
        val unwatch = GarContinuedListening.watchScreenOff(skillContext.android) {
            sttInputDevice.stopBriefListening()
        }
        val started = GarVoiceLog.listen(GarVoiceStart.CONTINUED) {
            sttInputDevice.tryListenBriefly(
                { event ->
                    GarContinuedListening.closed(scheduled)
                    unwatch()
                    onContinuedInput(event)
                },
                GarContinuedListening.WINDOW_MILLIS,
                GarContinuedListening.MAX_SPEECH_MILLIS,
            )
        }
        if (!started) {
            unwatch()
            return
        }
        GarContinuedListening.opened(scheduled) {
            unwatch()
            sttInputDevice.stopBriefListening()
        }
    }

    // GAR: what a window heard; silence and Whisper's silence phrases are no turn
    private fun onContinuedInput(event: InputEvent) {
        when (event) {
            InputEvent.None -> GarVoiceLog.windowSilent(hallucination = false)
            is InputEvent.Final -> {
                val locale = skillContext.locale
                val text = event.utterances.firstOrNull()?.first ?: ""
                if (GarWhisperHallucinations.isSilence(locale, text) ||
                    GarWhisperHallucinations.isSilence(locale, event.written ?: text)
                ) {
                    GarVoiceLog.windowSilent(hallucination = true)
                } else {
                    processInput(event, true)
                }
            }
            else -> processInput(event, true)
        }
    }

    // GAR: whether the phone is locked now; false when it can't tell
    private fun isLocked(): Boolean = try {
        GarContinuedListening.isLocked(skillContext.android)
    } catch (e: RuntimeException) {
        false
    }

    private fun addErrorInteractionFromPending(throwable: Throwable) {
        Log.e(TAG, "Error while evaluating skills", throwable)
        addInteractionFromPending(ErrorSkillOutput(throwable, true))
    }

    // GAR: spoken, whether the answer is said by TTS (QuestionAnswer.spoken)
    private fun addInteractionFromPending(skillOutput: SkillOutput, spoken: Boolean = false) {
        val log = _state.value
        val pendingUserInput = log.pendingQuestion?.userInput
        val pendingContinuesLastInteraction = log.pendingQuestion?.continuesLastInteraction
            ?: skillRanker.hasAnyBatches()
        val pendingSkill = log.pendingQuestion?.skillBeingEvaluated
        val questionAnswer = QuestionAnswer(pendingUserInput, skillOutput, spoken) // GAR: spoken

        _state.value = log.copy(
            interactions = log.interactions.toMutableList().also { inters ->
                if (pendingContinuesLastInteraction && inters.isNotEmpty()) {
                    inters[inters.size - 1] = inters[inters.size - 1].let { i -> i.copy(
                        questionsAnswers = i.questionsAnswers.toMutableList()
                            .apply { add(questionAnswer) }
                    ) }
                } else {
                    inters.add(
                        Interaction(
                            skill = pendingSkill,
                            questionsAnswers = listOf(questionAnswer)
                        )
                    )
                }
            },
            pendingQuestion = null,
        )
    }

    companion object {
        val TAG = SkillEvaluator::class.simpleName
    }
}

@Module
@InstallIn(SingletonComponent::class)
class SkillEvaluatorModule {
    @Provides
    @Singleton
    fun provideSkillEvaluator(
        skillContext: SkillContextInternal,
        skillHandler: SkillHandler,
        sttInputDevice: SttInputDeviceWrapper,
        garFallback: GarFallback, // GAR
        garSettings: DataStore<GarSettings>, // GAR
    ): SkillEvaluator {
        return SkillEvaluatorImpl( // GAR
            skillContext, skillHandler, sttInputDevice, garFallback, garSettings,
        )
    }
}
