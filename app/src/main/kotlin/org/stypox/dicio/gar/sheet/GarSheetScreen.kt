package org.stypox.dicio.gar.sheet

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.Window
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.gar.GarKeepScreenOn
import org.stypox.dicio.gar.perf.GarJank
import org.stypox.dicio.gar.perf.GarStartupCache
import org.stypox.dicio.gar.ui.GarChip
import org.stypox.dicio.gar.ui.GarChipRow
import org.stypox.dicio.gar.ui.GarChips
import org.stypox.dicio.gar.ui.GarMicState
import org.stypox.dicio.gar.ui.GarThinking
import org.stypox.dicio.gar.ui.garElapsedNow
import org.stypox.dicio.gar.ui.garTapStopsSpeech
import org.stypox.dicio.gar.ui.garble.LocalGarbleColors
import org.stypox.dicio.gar.ui.garble.garbleSheet
import org.stypox.dicio.gar.ui.rememberGarMicState
import org.stypox.dicio.gar.ui.rememberGarReducedMotion
import org.stypox.dicio.gar.ui.rememberGarStarterChips
import org.stypox.dicio.gar.ui.tag
import org.stypox.dicio.io.input.VoiceActivity
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.SkillOutputPreviews
import java.util.Locale
import kotlin.math.roundToInt

/** Where the sheet rests: on screen, or slid below it. */
internal enum class GarSheetAnchor { Shown, Hidden }

/** What the sheet shows, top to bottom. */
@Immutable
internal data class GarSheetContent(
    val micState: GarMicState,
    /** The pending request, or else the last answered one; null before any. */
    val transcript: String?,
    val transcriptPending: Boolean,
    val answer: GarSheetTurn?,
    val chips: List<GarChip>,
    val chipsEnabled: Boolean,
    /** Where [answer] came from (the provenance chips); null hides them. */
    val provenance: GarSheetProvenance? = null,
)

/** What the sheet's parts do; the controller's in the app, nothing in previews. */
internal class GarSheetActions(
    val onDismiss: () -> Unit = {},
    val onClosed: () -> Unit = {},
    val onMic: (GarMicState) -> Unit = {},
    val onOpenApp: () -> Unit = {},
    val onChip: (GarChip) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onStartTyping: () -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onStopSpeaking: () -> Unit = {},
    val onTouch: () -> Unit = {},
    val onPointerDown: () -> Unit = {},
    val onPointerUp: () -> Unit = {},
)

/**
 * The sheet in the session window: the theme from the cached settings (an empty frame while the
 * cache is empty, never a blocking read), then a shell until the app's singletons are fetched
 * after the first frame, then the live sheet. Both are the same [GarSheetLayout], so the sheet
 * slides in once.
 */
@Composable
internal fun GarSheetRoot(controller: GarSheetController) {
    val settings by remember { controller.userSettings.data }
        .collectAsState(initial = GarStartupCache.userSettings)
    val userSettings = settings ?: return
    AppTheme(
        theme = userSettings.theme,
        dynamicColors = userSettings.dynamicColors,
        garColorSource = userSettings.garColorSource,
        garSeedColor = userSettings.garSeedColor,
    ) {
        NavigationBarAppearance(controller.host.window)
        val deps = controller.deps.value
        val locale = deps?.let { it.localeManager.locale.collectAsState().value }
        var typing by remember { mutableStateOf<TextFieldValue?>(null) }
        val noVoiceActivity = remember { mutableStateOf<VoiceActivity?>(null) }
        GarSheetProviders(controller, locale) {
            val live = deps?.let { garSheetLive(controller, it, typing != null) }
            GarSheetLayout(
                content = live?.content ?: SHELL,
                voiceActivity = live?.voiceActivity ?: noVoiceActivity,
                ctx = deps?.skillContext,
                closing = controller.closing.value,
                backProgress = controller.backProgress.floatValue,
                actions = controller.actions,
                typing = typing,
                onTypingChange = { typing = it },
            )
        }
    }
}

/** Before the singletons: the state row says Starting, nothing else yet. */
private val SHELL = GarSheetContent(
    micState = GarMicState.Starting,
    transcript = null,
    transcriptPending = false,
    answer = null,
    chips = listOf(),
    chipsEnabled = false,
)

/** LocalContext in GAR's language that tells the sheet before starting an app. */
@Composable
private fun GarSheetProviders(
    controller: GarSheetController,
    locale: Locale?,
    content: @Composable () -> Unit,
) {
    val base = controller.host.context
    val context = remember(base, locale) { GarLeavingContext(localized(base, locale)) }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalActivityResultRegistryOwner provides controller.activityResultOwner,
        content = content,
    )
}

private fun localized(base: Context, locale: Locale?): Context {
    if (locale == null) {
        return base
    }
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(locale)
    return base.createConfigurationContext(configuration)
}

// AppTheme sets no bar appearance outside an Activity: the sheet's window gets it here
@Composable
private fun NavigationBarAppearance(window: Window) {
    val light = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > 0.5f
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = light
    }
}

private class GarSheetLive(
    val content: GarSheetContent,
    val voiceActivity: State<VoiceActivity?>,
)

/**
 * The live sheet's content from the singletons, and what follows from it: the jank tags, the
 * "leaving GAR" check, the idle timer and the screen.
 */
@Composable
private fun garSheetLive(
    controller: GarSheetController,
    deps: GarSheetDeps,
    typing: Boolean,
): GarSheetLive {
    val log by deps.skillEvaluator.state.collectAsState()
    val turns = remember(log) { controller.select(log) }
    val micState by rememberGarMicState()
    val voiceActivity = deps.sttInputDevice.voiceActivity.collectAsState()
    val starters by rememberGarStarterChips()
    val garSettings by remember { deps.garSettings.data }
        .collectAsState(initial = GarStartupCache.garSettings)
    val latest = turns.latest

    val answerChips = remember(latest?.answer) {
        latest?.let { GarChips.forOutput(it.answer, deps.skillContext) } ?: listOf()
    }
    val chips = when {
        turns.pending != null -> listOf()
        latest != null -> answerChips
        controller.host.locked -> listOf()
        else -> starters
    }

    // provenance: an answer is the model's if its routing ran since the question began
    val routing by GarThinking.routing.collectAsState()
    val provenanceTracker = remember { GarSheetProvenanceTracker() }
    val pendingNow = turns.pending != null
    SideEffect {
        provenanceTracker.onRouting(routing?.startedAt, garElapsedNow())
        provenanceTracker.onPending(pendingNow, routing != null)
    }
    val provenance = remember(latest?.answer) {
        latest?.let {
            GarSheetProvenance(
                route = provenanceTracker.routeFor(it.answer, garElapsedNow()),
                skillId = it.skill?.id,
            )
        }
    }

    val content = GarSheetContent(
        micState = micState,
        transcript = turns.pending?.userInput ?: latest?.question,
        transcriptPending = turns.pending != null,
        answer = latest,
        chips = chips,
        chipsEnabled = latest != null || controller.startersEnabled.value,
        provenance = provenance,
    )

    LaunchedEffect(latest) {
        controller.onLatestAnswer(latest?.skill?.id)
    }
    val window = controller.host.window
    LaunchedEffect(micState.tag) {
        GarJank.state(window, "mic", micState.tag)
    }

    // the idle timer and the screen: GarSheetState.idleDecision
    val busy = GarSheetState.busy(micState)
    LaunchedEffect(busy) {
        if (!busy) {
            controller.touched() // idle from now on
        }
    }
    // the moment busy last changed, taken in the same composition: the effect above updates
    // lastActiveAt only after this frame, and a long turn would otherwise be dismissed at once
    val busyChangedAt = remember(busy) { SystemClock.elapsedRealtime() }
    val timeouts = remember { controller.timeouts() }
    var tick by remember { mutableIntStateOf(0) }
    val lastActiveAt = maxOf(controller.lastActiveAt.longValue, busyChangedAt)
    val touchExploration = controller.touchExploration.value
    val idle = remember(
        micState, latest, typing, lastActiveAt, garSettings, tick, touchExploration
    ) {
        GarSheetState.idleDecision(
            micState = micState,
            hasAnswer = latest != null,
            continuedOpen = (micState as? GarMicState.Listening)?.followUpEndsAt != null,
            locked = controller.host.locked,
            lastBusyAt = lastActiveAt,
            now = SystemClock.elapsedRealtime(),
            timeouts = timeouts,
            typing = typing,
            settingOn = garSettings?.let(GarKeepScreenOn::settingOn) ?: true,
            touchExploration = touchExploration,
        )
    }
    SideEffect {
        controller.keepScreenOn(idle.keepScreenOn)
    }
    LaunchedEffect(idle) {
        val now = SystemClock.elapsedRealtime()
        val dismissAt = idle.dismissAt
        if (dismissAt != null) {
            delay((dismissAt - now).coerceAtLeast(0))
            controller.dismiss()
        } else if (idle.keepScreenOn && !idle.busy) {
            // over the lock screen the screen stays on for a while after the last busy moment
            delay((lastActiveAt + GarKeepScreenOn.LOCKED_WINDOW_MILLIS - now).coerceAtLeast(0))
            tick += 1
        }
    }

    // starters: enabled at the first pointer-up after show, or soon if no finger was down then
    LaunchedEffect(Unit) {
        delay(GarChips.TAP_GUARD_MILLIS)
        controller.enableStartersIfNoPointer()
    }

    return GarSheetLive(content, voiceActivity)
}

/**
 * The sheet itself, stateless: a scrim, and a bottom-anchored surface that grows upward with its
 * content (the action row stays put), slides in, and goes away when swiped down.
 */
@Composable
internal fun GarSheetLayout(
    content: GarSheetContent,
    voiceActivity: State<VoiceActivity?>,
    ctx: SkillContext?,
    closing: Boolean,
    backProgress: Float,
    actions: GarSheetActions,
    modifier: Modifier = Modifier,
    typing: TextFieldValue? = null,
    onTypingChange: (TextFieldValue?) -> Unit = {},
) {
    val inspection = LocalInspectionMode.current
    val reduced = rememberGarReducedMotion()
    val density = LocalDensity.current
    val currentActions by rememberUpdatedState(actions)
    val dragState = remember {
        AnchoredDraggableState(if (inspection) GarSheetAnchor.Shown else GarSheetAnchor.Hidden)
    }
    var sheetHeight by remember { mutableIntStateOf(0) }
    var everShown by remember { mutableStateOf(false) }

    // slide in once measured (fade only under reduced motion)
    LaunchedEffect(Unit) {
        snapshotFlow { sheetHeight }.first { it > 0 }
        if (reduced) {
            dragState.snapTo(GarSheetAnchor.Shown)
        } else {
            dragState.animateTo(GarSheetAnchor.Shown)
        }
        everShown = true
    }
    // swiped down: dismiss
    LaunchedEffect(Unit) {
        snapshotFlow { dragState.settledValue }.collect {
            if (it == GarSheetAnchor.Hidden && everShown) {
                currentActions.onDismiss()
            }
        }
    }
    // slide out, then the session hides
    LaunchedEffect(closing) {
        if (closing) {
            if (!reduced && sheetHeight > 0 && dragState.settledValue != GarSheetAnchor.Hidden) {
                dragState.animateTo(GarSheetAnchor.Hidden)
            }
            currentActions.onClosed()
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (closing || (reduced && !everShown && !inspection)) 0f else 1f,
        animationSpec = tween(FADE_MILLIS),
        label = "sheet alpha",
    )
    val velocityThreshold = with(density) { VELOCITY_THRESHOLD.toPx() }
    val nestedScroll = remember(dragState) { garSheetNestedScroll(dragState, velocityThreshold) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // any touch resets the idle timer; the first pointer-up enables the starters
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        currentActions.onTouch()
                        when (event.type) {
                            PointerEventType.Press -> currentActions.onPointerDown()
                            PointerEventType.Release -> if (event.changes.none { it.pressed }) {
                                currentActions.onPointerUp()
                            }
                        }
                    }
                }
            },
    ) {
        val statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val maxSheetHeight = (maxHeight - statusBar) * MAX_HEIGHT_SHARE
        val answerMaxHeight = maxHeight * ANSWER_HEIGHT_SHARE
        val scrim = MaterialTheme.colorScheme.scrim

        // the scrim: a tap outside the sheet dismisses it (screen readers use the handle). Its
        // alpha follows the sheet's offset in the draw phase, so a drag or a resize doesn't
        // recompose the host every frame.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val hiddenAt = dragState.anchors.positionOf(GarSheetAnchor.Hidden)
                    val offset = dragState.offset
                    val shownFraction = when {
                        inspection -> 1f
                        sheetHeight == 0 || offset.isNaN() || hiddenAt.isNaN() || hiddenAt <= 0f -> 0f
                        else -> (1f - offset / hiddenAt).coerceIn(0f, 1f)
                    }
                    drawRect(scrim, alpha = SCRIM_ALPHA * shownFraction * alpha)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { currentActions.onDismiss() },
                )
                .clearAndSetSemantics {},
        )

        val title = stringResource(R.string.gar_sheet_title)
        // Garble: a neutral surface, 28dp top corners, a 1dp hairline and a soft upward shadow;
        // no tonal elevation
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = MAX_WIDTH)
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .onSizeChanged { size ->
                    sheetHeight = size.height
                    val hidden = size.height.toFloat()
                    // no new anchors (an allocation) when the height is the same
                    if (dragState.anchors.positionOf(GarSheetAnchor.Hidden) != hidden) {
                        dragState.updateAnchors(
                            DraggableAnchors {
                                GarSheetAnchor.Shown at 0f
                                GarSheetAnchor.Hidden at size.height.toFloat()
                            }
                        )
                    }
                }
                .then(
                    if (inspection) {
                        Modifier
                    } else {
                        Modifier.offset {
                            // below the screen until measured
                            val offset = dragState.offset
                            IntOffset(
                                0,
                                if (offset.isNaN()) constraints.maxHeight else offset.roundToInt(),
                            )
                        }
                    }
                )
                .graphicsLayer {
                    // predictive back (API 34+): the sheet shrinks a little and follows the finger
                    val scale = 1f - BACK_SCALE * backProgress
                    scaleX = scale
                    scaleY = scale
                    translationY = BACK_SLIDE.toPx() * backProgress
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    this.alpha = alpha
                }
                .garbleSheet()
                .nestedScroll(nestedScroll)
                .anchoredDraggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                        state = dragState,
                        positionalThreshold = { distance -> distance * POSITIONAL_THRESHOLD },
                    ),
                )
                .garTapStopsSpeech(
                    speaking = { content.micState == GarMicState.Speaking },
                    stopSpeaking = { currentActions.onStopSpeaking() },
                )
                .semantics {
                    paneTitle = title
                    isTraversalGroup = true
                }
                // like a Surface: touches on the sheet never reach the scrim below
                .pointerInput(Unit) {},
        ) {
            CompositionLocalProvider(LocalContentColor provides LocalGarbleColors.current.text) {
                GarSheetColumn(
                    content = content,
                    voiceActivity = voiceActivity,
                    ctx = ctx,
                    answerMaxHeight = answerMaxHeight,
                    actions = actions,
                    typing = typing,
                    onTypingChange = onTypingChange,
                )
            }
        }
    }
}

/**
 * The sheet's parts in their TalkBack order (the Garble canvas): the handle, the header, then
 * either the Listening layout (a new request: "LISTENING", the live transcript, the big mark,
 * "Type instead" and stop) or the answer layout ("You said", the answer card, the provenance and
 * suggestion chips, a confirmation's hint, the bottom row). [answerMaxHeight] caps the answer
 * card; the middle scrolls when the sheet's own cap leaves it less.
 */
@Composable
private fun GarSheetColumn(
    content: GarSheetContent,
    voiceActivity: State<VoiceActivity?>,
    ctx: SkillContext?,
    answerMaxHeight: Dp,
    actions: GarSheetActions,
    typing: TextFieldValue?,
    onTypingChange: (TextFieldValue?) -> Unit,
) {
    val state = content.micState
    val stateFocus = remember { FocusRequester() }
    val inspection = LocalInspectionMode.current
    val reduced = rememberGarReducedMotion()
    LaunchedEffect(Unit) {
        if (!inspection) {
            // after the first frame, so that TalkBack lands in the sheet
            delay(FIRST_FOCUS_DELAY_MILLIS)
            stateFocus.requestFocus()
        }
    }
    // the endpointer hears a voice: changes rarely, unlike the level (read in the draw phase)
    val voice = remember(voiceActivity) {
        derivedStateOf { voiceActivity.value?.speaking == true }
    }
    // the user asked to listen again (a new request, from Idle or over a pending answer): the
    // Listening layout stays up until that request is answered, even if an older answer lands
    // meanwhile. A mic reopened by the answer itself keeps the answer on screen.
    var userListening by remember { mutableStateOf(false) }
    var userHeard by remember { mutableStateOf(false) }
    val answer = content.answer
    val onMic = { micState: GarMicState ->
        if (micState == GarMicState.Idle || micState is GarMicState.Thinking) {
            userListening = true
            userHeard = false
        }
        actions.onMic(micState)
    }
    LaunchedEffect(state, userListening) {
        if (!userListening) {
            return@LaunchedEffect
        }
        when (state) {
            is GarMicState.Listening, GarMicState.Starting -> userHeard = true
            GarMicState.Transcribing, is GarMicState.Thinking -> Unit
            // done (answered, cancelled or unavailable) once listening has begun
            else -> if (userHeard) {
                userListening = false
                userHeard = false
            }
        }
    }
    // whether the Listening layout was up in the last composition (read, not observed)
    val lastLayout = remember { GarSheetLayoutMemory() }
    val listeningLayout = garSheetListeningLayout(
        state = state,
        typing = typing != null,
        hasAnswer = answer != null,
        userAsked = userListening,
        wasListening = lastLayout.listening,
    )
    SideEffect { lastLayout.listening = listeningLayout }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .then(if (reduced || inspection) Modifier else Modifier.animateContentSize()),
    ) {
        // 1. the handle: tap (or TalkBack's "Close") dismisses
        val close = stringResource(R.string.gar_sheet_close)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_HEIGHT)
                .clickable(role = Role.Button, onClick = actions.onDismiss)
                .semantics { contentDescription = close },
        ) {
            GarSheetHandleBar()
        }

        // 2. the header, where TalkBack starts; the state line under it says what is going on
        // (a live region only past listening: TalkBack would be transcribed)
        Column(
            modifier = Modifier
                .focusRequester(stateFocus)
                .focusable()
                .padding(horizontal = SIDE),
        ) {
            GarSheetHeader(state = state, listeningLayout = listeningLayout)
            if (!listeningLayout) {
                GarSheetStateLine(state = state, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (listeningLayout) {
            // 3L. listening for a new request
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SIDE + 4.dp, vertical = 18.dp),
            ) {
                GarSheetListeningBody(
                    state = state,
                    transcript = content.transcript.takeIf { content.transcriptPending },
                    voice = voice,
                )
            }
            GarSheetListeningButtons(
                onTypeInstead = { onTypingChange(TextFieldValue()) },
                onStop = { onMic(state) },
                modifier = Modifier.padding(start = SIDE + 4.dp, end = SIDE + 4.dp, bottom = 24.dp),
            )
        } else {
            GarSheetAnswerLayout(
                content = content,
                voice = voice,
                ctx = ctx,
                answerMaxHeight = answerMaxHeight,
                actions = actions,
                typing = typing,
                onTypingChange = onTypingChange,
                onMic = onMic,
            )
        }
    }
}

/** The answer layout: what GarSheetColumn shows below the header unless it listens anew. */
@Composable
private fun ColumnScope.GarSheetAnswerLayout(
    content: GarSheetContent,
    voice: State<Boolean>,
    ctx: SkillContext?,
    answerMaxHeight: Dp,
    actions: GarSheetActions,
    typing: TextFieldValue?,
    onTypingChange: (TextFieldValue?) -> Unit,
    onMic: (GarMicState) -> Unit,
) {
    val state = content.micState
    val answer = content.answer
    // 3 to 6 scroll between the fixed rows: with the keyboard up or in landscape the sheet's
    // height cap would otherwise squeeze the bottom row out (a Column measures its weighted
    // child last, so the handle, the header and the bottom row always get their height)
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // 3. the request, or the text field
        if (typing != null) {
            TypingField(
                value = typing,
                onValueChange = { onTypingChange(it) },
                onSend = { text ->
                    onTypingChange(null)
                    actions.onSend(text)
                },
                onFocused = actions.onStartTyping,
            )
        } else if (content.transcript != null) {
            val text = content.transcript
            GarSheetYouSaid(
                text = text,
                pending = content.transcriptPending,
                onEdit = if (content.transcriptPending) {
                    null
                } else {
                    {
                        actions.onEdit(text)
                        onTypingChange(TextFieldValue(text))
                    }
                },
            )
        }

        // 4. the answer, read out politely by screen readers when it arrives, unless TTS
        // says it (two voices at once, and the barge-in recording would hear the reader)
        if (answer != null && ctx != null && !content.transcriptPending) {
            val confirm = remember(answer.answer) { garSheetConfirm(answer.answer) }
            GarSheetAnswerCard(
                skill = answer.skill,
                output = answer.answer,
                ctx = ctx,
                status = garSheetAnswerStatus(confirm),
                title = garSheetAnswerTitle(confirm),
                maxHeight = answerMaxHeight,
                // room for the card's shadow inside the scrolling column
                modifier = Modifier
                    .padding(horizontal = SIDE, vertical = 8.dp)
                    .then(
                        if (answer.spoken) {
                            Modifier
                        } else {
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        }
                    ),
            )

            // 4b. a confirmation listening for its answer: what to say
            val hint = confirm?.let { garSheetConfirmHint(it) }
            if (confirm != null && hint != null && garSheetShowsConfirmHint(confirm, state)) {
                GarSheetConfirmHint(
                    text = hint,
                    markState = garSheetConfirmHintMark(state, voice.value),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // 4c. where it came from
            content.provenance?.let {
                GarSheetProvenanceRow(
                    provenance = it,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        // 5. chips under the answer, or the starters before the first turn
        if (content.chips.isNotEmpty()) {
            GarChipRow(
                chips = content.chips,
                enabled = content.chipsEnabled,
                onChip = actions.onChip,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }

    // 6. the bottom row: keyboard, the mic, "Open GAR"
    GarSheetBottomRow(
        state = state,
        voice = voice,
        onKeyboard = { onTypingChange(if (typing == null) TextFieldValue() else null) },
        onMic = { onMic(state) },
        onOpenApp = actions.onOpenApp,
        modifier = Modifier.padding(start = SIDE, end = SIDE - 4.dp, top = 4.dp, bottom = 20.dp),
    )
}

/** No mic tap yet in this show of the sheet (an answer may be null). */
/** Plain memory of the last layout; not snapshot state, so writing it doesn't recompose. */
private class GarSheetLayoutMemory {
    var listening = false
}

@Composable
private fun TypingField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: (String) -> Unit,
    onFocused: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val inspection = LocalInspectionMode.current
    LaunchedEffect(Unit) {
        if (!inspection) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val send = {
        val text = value.text.trim()
        if (text.isNotEmpty()) {
            keyboard?.hide()
            onSend(text)
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.gar_sheet_type_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            IconButton(onClick = send) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.gar_sheet_send),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) onFocused() },
    )
}

/**
 * ModalBottomSheet's pattern: a tall answer scrolls first; a downward drag it doesn't use moves
 * the sheet, and a fling then settles it on the nearest anchor (or the one it was flung to).
 */
private fun garSheetNestedScroll(
    state: AnchoredDraggableState<GarSheetAnchor>,
    velocityThreshold: Float,
): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (delta < 0 && source == NestedScrollSource.UserInput &&
            !state.offset.isNaN() && state.offset > 0f
        ) {
            // pulled back up while partly swiped down: the sheet takes it first
            Offset(0f, state.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
        if (source == NestedScrollSource.UserInput) {
            Offset(0f, state.dispatchRawDelta(available.y))
        } else {
            Offset.Zero
        }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (available.y < 0 && !state.offset.isNaN() && state.offset > 0f) {
            settle(available.y)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!state.offset.isNaN() && state.offset > 0f) {
            settle(available.y)
        }
        return available
    }

    private suspend fun settle(velocity: Float) {
        val hidden = state.anchors.positionOf(GarSheetAnchor.Hidden)
        val target = when {
            velocity > velocityThreshold -> GarSheetAnchor.Hidden
            velocity < -velocityThreshold -> GarSheetAnchor.Shown
            state.offset > hidden * POSITIONAL_THRESHOLD -> GarSheetAnchor.Hidden
            else -> GarSheetAnchor.Shown
        }
        state.animateTo(target)
    }
}

private val MAX_WIDTH = 640.dp
private val HANDLE_HEIGHT = 48.dp
private val SIDE = 20.dp
private val VELOCITY_THRESHOLD = 125.dp // per second
private val BACK_SLIDE = 24.dp
private const val BACK_SCALE = 0.05f
private const val POSITIONAL_THRESHOLD = 0.4f
private const val SCRIM_ALPHA = 0.32f
private const val MAX_HEIGHT_SHARE = 0.85f
private const val ANSWER_HEIGHT_SHARE = 0.5f
private const val FADE_MILLIS = 150
private const val FIRST_FOCUS_DELAY_MILLIS = 100L

@Preview
@Composable
private fun GarSheetIdlePreview() {
    AppTheme {
        GarSheetLayout(
            content = GarSheetContent(
                micState = GarMicState.Idle,
                transcript = null,
                transcriptPending = false,
                answer = null,
                chips = listOf(GarChip("What's the weather"), GarChip("What time is it")),
                chipsEnabled = true,
            ),
            voiceActivity = remember { mutableStateOf<VoiceActivity?>(null) },
            ctx = null,
            closing = false,
            backProgress = 0f,
            actions = GarSheetActions(),
        )
    }
}

@Preview
@Composable
private fun GarSheetListeningPreview() {
    AppTheme {
        GarSheetLayout(
            content = GarSheetContent(
                micState = GarMicState.Listening(followUpEndsAt = null),
                transcript = "Set a timer for",
                transcriptPending = true,
                answer = null,
                chips = listOf(),
                chipsEnabled = false,
            ),
            voiceActivity = remember { mutableStateOf<VoiceActivity?>(null) },
            ctx = null,
            closing = false,
            backProgress = 0f,
            actions = GarSheetActions(),
        )
    }
}

@Preview
@Composable
private fun GarSheetAnswerPreview() {
    AppTheme(theme = org.stypox.dicio.settings.datastore.Theme.THEME_DARK) {
        GarSheetLayout(
            content = GarSheetContent(
                micState = GarMicState.Speaking,
                transcript = "What's the weather",
                transcriptPending = false,
                answer = GarSheetTurn(
                    skill = null,
                    question = "What's the weather",
                    answer = SkillOutputPreviews().values.first(),
                ),
                chips = listOf(GarChip("Tomorrow"), GarChip("This weekend")),
                chipsEnabled = true,
                provenance = GarSheetProvenance(GarSheetRoute.Model(1_400), "weather"),
            ),
            voiceActivity = remember { mutableStateOf<VoiceActivity?>(null) },
            ctx = SkillContextImpl.newForPreviews(LocalContext.current),
            closing = false,
            backProgress = 0f,
            actions = GarSheetActions(),
        )
    }
}

@Preview
@Composable
private fun GarSheetTypingPreview() {
    AppTheme {
        GarSheetLayout(
            content = GarSheetContent(
                micState = GarMicState.Idle,
                transcript = null,
                transcriptPending = false,
                answer = null,
                chips = listOf(),
                chipsEnabled = false,
            ),
            voiceActivity = remember { mutableStateOf<VoiceActivity?>(null) },
            ctx = null,
            closing = false,
            backProgress = 0f,
            actions = GarSheetActions(),
            typing = TextFieldValue("Remind me to"),
        )
    }
}
