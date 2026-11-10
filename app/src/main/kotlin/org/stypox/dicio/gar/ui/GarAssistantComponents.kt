package org.stypox.dicio.gar.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.gar.conversation.GarContinuedListening
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.VoiceActivity
import org.stypox.dicio.ui.home.garSttFabLook
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.SkillInfoPreviews
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The pieces the home screen and the assistant sheet share (gar/DESIGN.md, "Home and chips"):
 * the microphone, its state's label, the chips, the answer and thinking cards, the user's bubble
 * and the tap that stops GAR's speech.
 */

/**
 * The microphone in [state]. [onClick] null: display only (the sheet's state row), hidden from
 * accessibility since the label next to it says the state. Otherwise a circle on
 * `primaryContainer` whose click means what the caller decides; its content description follows
 * the state. [voiceActivity] is read in the draw phase only.
 */
@Composable
fun GarMicIndicator(
    state: GarMicState,
    voiceActivity: State<VoiceActivity?>,
    onClick: (() -> Unit)?,       // null = display only (sheet state row)
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val reduced = rememberGarReducedMotion()
    val scale = size / BASE_SIZE
    val description = garMicDescription(state)
    val colors = MaterialTheme.colorScheme
    val outer = if (onClick == null) {
        modifier
            .size(size)
            .clearAndSetSemantics {}
    } else {
        modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .background(colors.primaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    }
    val contentColor = if (onClick == null) colors.primary else colors.onPrimaryContainer
    val iconSize = size * ICON_SHARE

    Box(modifier = outer, contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            when (state) {
                is GarMicState.Listening -> ListeningBars(
                    voiceActivity = voiceActivity,
                    scale = scale,
                    reduced = reduced,
                    atRest = false,
                    followUpEndsAt = state.followUpEndsAt,
                )
                GarMicState.Starting -> {
                    ListeningBars(
                        voiceActivity = voiceActivity,
                        scale = scale,
                        reduced = reduced,
                        atRest = true,
                        followUpEndsAt = null,
                    )
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp * scale),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                }
                GarMicState.Transcribing -> CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 3.dp * scale,
                    color = LocalContentColor.current,
                )
                is GarMicState.Thinking -> ThinkingDots(scale = scale, reduced = reduced)
                GarMicState.Speaking -> Icon(
                    Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(iconSize)
                )
                GarMicState.Idle -> Icon(
                    Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(iconSize)
                )
                is GarMicState.Unavailable -> {
                    val look = state.stt?.let { garSttFabLook(it) }
                    Icon(
                        look?.icon ?: Icons.Default.MicOff,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                    )
                    if (look?.showProgress == true) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp * scale),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    }
                }
            }
        }
    }
}

/** What a tap on the microphone does in [state], for screen readers. */
@Composable
private fun garMicDescription(state: GarMicState): String = when (state) {
    GarMicState.Idle, is GarMicState.Thinking -> stringResource(R.string.start_listening)
    is GarMicState.Listening, GarMicState.Starting -> stringResource(R.string.gar_mic_stop_listening)
    GarMicState.Transcribing -> stringResource(R.string.gar_mic_cancel_transcription)
    GarMicState.Speaking -> stringResource(R.string.gar_mic_stop_speaking)
    is GarMicState.Unavailable -> state.stt?.let { garSttFabLook(it).text }
        ?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.start_listening)
}

/**
 * Four bars that follow the level (or rest at their lowest, [atRest]), with a small wobble unless
 * [reduced]; in a continued-listening window ending at [followUpEndsAt] an arc around them
 * empties. Everything moving is read in the draw phase, so nothing recomposes while the user
 * talks.
 */
@Composable
private fun ListeningBars(
    voiceActivity: State<VoiceActivity?>,
    scale: Float,
    reduced: Boolean,
    atRest: Boolean,
    followUpEndsAt: Long?,
) {
    val color = MaterialTheme.colorScheme.primary
    val phase = remember { mutableFloatStateOf(0f) }
    if (!reduced && !atRest) {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis { phase.floatValue = it / 1000f }
            }
        }
    }
    val windowLeft = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(followUpEndsAt) {
        windowLeft.floatValue = 0f
        while (followUpEndsAt != null) {
            val remaining = followUpEndsAt - garElapsedNow()
            windowLeft.floatValue =
                (remaining / GarContinuedListening.WINDOW_MILLIS.toFloat()).coerceIn(0f, 1f)
            if (remaining <= 0) {
                break
            }
            withFrameMillis {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val level = if (atRest) 0f else voiceActivity.value?.level?.coerceIn(0f, 1f) ?: 0f
                val barWidth = BAR_WIDTH.toPx() * scale
                val gap = BAR_GAP.toPx() * scale
                val minHeight = BAR_MIN.toPx() * scale
                val maxHeight = BAR_MAX.toPx() * scale
                val total = barWidth * BAR_WEIGHTS.size + gap * (BAR_WEIGHTS.size - 1)
                var x = center.x - total / 2
                BAR_WEIGHTS.forEachIndexed { i, weight ->
                    val wobble = if (reduced || atRest) {
                        0f
                    } else {
                        (WOBBLE_BASE + WOBBLE_LEVEL * level) *
                            sin(phase.floatValue * 2 * PI.toFloat() * WOBBLE_HZ + i * WOBBLE_SHIFT)
                    }
                    val share = (level * weight + wobble).coerceIn(0f, 1f)
                    val height = minHeight + (maxHeight - minHeight) * share
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, center.y - height / 2),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(barWidth / 2),
                    )
                    x += barWidth + gap
                }

                val left = windowLeft.floatValue
                if (followUpEndsAt != null && left > 0f) {
                    val stroke = ARC_WIDTH.toPx()
                    val radius = size.minDimension / 2 - stroke
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * left,
                        useCenter = false,
                        topLeft = center - Offset(radius, radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = stroke),
                    )
                }
            }
    )
}

/** Three dots that pulse in turn (1.2 s); static under reduced motion. */
@Composable
private fun ThinkingDots(scale: Float, reduced: Boolean) {
    val color = LocalContentColor.current
    val time = if (reduced) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "thinking").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = DOTS_PERIOD_MILLIS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "thinkingDots",
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val radius = DOT_RADIUS.toPx() * scale
                val gap = DOT_GAP.toPx() * scale
                for (i in 0 until 3) {
                    val alpha = if (reduced) {
                        1f
                    } else {
                        val wave = cos(2 * PI.toFloat() * (time.value - i / 3f))
                        DOT_MIN_ALPHA + (1f - DOT_MIN_ALPHA) * (0.5f + 0.5f * wave)
                    }
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = radius,
                        center = Offset(center.x + (i - 1) * (radius * 2 + gap), center.y),
                    )
                }
            }
    )
}

/**
 * The state as text: "Listening…", "Thinking… 3 s" and so on (the Unavailable text is the home
 * button's). With [announce], Thinking past [GarThinking.ELAPSED_AFTER_MILLIS] and Unavailable
 * are a polite live region; the spoken text counts the seconds in steps of 5 so that TalkBack
 * doesn't speak every second. Never announce while listening (the recording would hear it).
 */
@Composable
fun GarStateLabel(state: GarMicState, announce: Boolean, modifier: Modifier = Modifier) {
    val text: String
    val description: String
    var live = false
    when (state) {
        is GarMicState.Thinking -> {
            val elapsed = rememberThinkingElapsed(state.since)
            val seconds = (elapsed / 1000).toInt()
            val counting = elapsed >= GarThinking.ELAPSED_AFTER_MILLIS
            val name = state.skill?.name(LocalContext.current)
            text = when {
                state.loadingModel && counting ->
                    stringResource(R.string.gar_mic_loading_model_seconds, seconds)
                state.loadingModel -> stringResource(R.string.gar_mic_loading_model)
                name != null && counting -> stringResource(R.string.gar_mic_skill_seconds, name, seconds)
                name != null -> name
                counting -> stringResource(R.string.gar_mic_thinking_seconds, seconds)
                else -> stringResource(R.string.gar_mic_thinking)
            }
            description = when {
                !counting -> text
                seconds < ANNOUNCE_STEP_SECONDS -> stringResource(R.string.gar_mic_still_thinking)
                else -> stringResource(
                    R.string.gar_mic_thinking_for, seconds / ANNOUNCE_STEP_SECONDS * ANNOUNCE_STEP_SECONDS
                )
            }
            live = counting
        }
        is GarMicState.Unavailable -> {
            text = state.stt?.let { garSttFabLook(it).text } ?: ""
            description = text
            live = true
        }
        else -> {
            text = when (state) {
                GarMicState.Idle -> stringResource(R.string.gar_mic_idle)
                is GarMicState.Listening -> if (state.followUpEndsAt != null) {
                    stringResource(R.string.gar_stt_listening_follow_up)
                } else {
                    stringResource(R.string.listening)
                }
                GarMicState.Starting -> stringResource(R.string.gar_mic_starting)
                GarMicState.Transcribing -> stringResource(R.string.gar_stt_transcribing)
                GarMicState.Speaking -> stringResource(R.string.gar_mic_speaking)
                else -> ""
            }
            description = text
        }
    }
    val polite = announce && live &&
        state !is GarMicState.Listening && state !is GarMicState.Starting
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = modifier.semantics {
            contentDescription = description
            if (polite) {
                liveRegion = LiveRegionMode.Polite
            }
        },
    )
}

/** Milliseconds since [since], ticking at 1.5 s and then every second. */
@Composable
private fun rememberThinkingElapsed(since: Long): Long {
    var now by remember(since) { mutableLongStateOf(garElapsedNow()) }
    LaunchedEffect(since) {
        while (true) {
            val elapsed = now - since
            val next = if (elapsed < GarThinking.ELAPSED_AFTER_MILLIS) {
                GarThinking.ELAPSED_AFTER_MILLIS - elapsed
            } else {
                1000 - (elapsed % 1000)
            }
            delay(next.coerceAtLeast(1))
            now = garElapsedNow()
        }
    }
    return (now - since).coerceAtLeast(0)
}

/**
 * The chips under an answer or the starters: a row of M3 suggestion chips that scrolls sideways.
 * Taps are ignored for [GarChips.TAP_GUARD_MILLIS] after the chips first appear or change (a
 * finger already on the way lands on what just appeared), and while [enabled] is false.
 */
@Composable
fun GarChipRow(
    chips: List<GarChip>,
    enabled: Boolean,
    onChip: (GarChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) {
        return
    }
    val shownAt = remember(chips) { garElapsedNow() }
    val currentEnabled by rememberUpdatedState(enabled)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        for (chip in chips) {
            SuggestionChip(
                onClick = {
                    if (currentEnabled && garElapsedNow() - shownAt >= GarChips.TAP_GUARD_MILLIS) {
                        onChip(chip)
                    }
                },
                label = {
                    Text(text = chip.label, style = MaterialTheme.typography.labelLarge)
                },
                enabled = enabled,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { role = Role.Button },
            )
        }
    }
}

/**
 * An answer: [output]'s card on `surfaceContainerLow`, with [skill]'s icon and name on top (no
 * header when [skill] is null, e.g. for an error that no input caused). With [maxHeight] the card
 * is at most that tall and scrolls inside.
 */
@Composable
fun GarAnswerCard(
    skill: SkillInfo?,
    output: SkillOutput,
    ctx: SkillContext,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,   // set: heightIn(max) + verticalScroll inside the card
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = (if (maxHeight.isSpecified) {
                Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            } else {
                Modifier
            }).padding(16.dp),
        ) {
            if (skill != null) {
                SkillHeader(skill)
                Spacer(modifier = Modifier.height(8.dp))
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                output.GraphicalOutput(ctx = ctx)
            }
        }
    }
}

@Composable
private fun SkillHeader(skill: SkillInfo) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        SkillHeaderIcon(skill)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = skill.name(LocalContext.current),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Where an answer will be while GAR works on it: the skill's icon (GAR's mic before one is
 * chosen), the state's label with its timer, and a thin progress bar (a still one under reduced
 * motion). The label doesn't announce: the caller's state label does.
 */
@Composable
fun GarThinkingCard(state: GarMicState.Thinking, modifier: Modifier = Modifier) {
    val reduced = rememberGarReducedMotion()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkillHeaderIcon(state.skill)
                Spacer(modifier = Modifier.width(8.dp))
                GarStateLabel(state = state, announce = false)
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (reduced) {
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
        }
    }
}

@Composable
private fun SkillHeaderIcon(skill: SkillInfo?) {
    val tint = MaterialTheme.colorScheme.primary
    if (skill != null) {
        Icon(skill.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    } else {
        Icon(
            Icons.Default.Mic, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * What the user said or typed, right-aligned at most 80% wide on `primaryContainer`; while
 * [pending] (still being heard or matched) in italics at 0.7 alpha. [onClick] (edit it) makes it
 * a 48 dp tall button.
 */
@Composable
fun GarUserBubble(
    text: String,
    pending: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH_SHARE),
        ) {
            val shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
            val color = MaterialTheme.colorScheme.primaryContainer
            val content = @Composable {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = if (pending) FontStyle.Italic else FontStyle.Normal,
                        modifier = if (pending) Modifier.alpha(PENDING_ALPHA) else Modifier,
                    )
                }
            }
            if (onClick == null) {
                Surface(shape = shape, color = color, content = content)
            } else {
                Surface(onClick = onClick, shape = shape, color = color, content = content)
            }
        }
    }
}

/**
 * A tap anywhere on this (the chat list, the sheet) while GAR speaks only stops the speech: the
 * down is looked at on the Initial pass, and if [speaking] then, the speech stops and that down
 * and its up are consumed, so the tap reaches no chip, bubble or button. Drags are left alone
 * (the list still scrolls, the sheet still swipes), and nothing is consumed while GAR is silent.
 */
fun Modifier.garTapStopsSpeech(speaking: () -> Boolean, stopSpeaking: () -> Unit): Modifier =
    composed {
        val isSpeaking by rememberUpdatedState(speaking)
        val stop by rememberUpdatedState(stopSpeaking)
        pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!isSpeaking()) {
                    return@awaitEachGesture
                }
                stop()
                down.consume()
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume() // the up of the stopping tap
                        break
                    }
                    if ((change.position - down.position).getDistance() > slop) {
                        break // a drag: scrolling and swiping go on as usual
                    }
                }
            }
        }
    }

private val BASE_SIZE = 72.dp
private const val ICON_SHARE = 0.45f
private val BAR_WIDTH = 8.dp
private val BAR_GAP = 4.dp
private val BAR_MIN = 4.dp
private val BAR_MAX = 28.dp
private val BAR_WEIGHTS = floatArrayOf(0.6f, 1f, 0.8f, 0.5f)
private const val WOBBLE_BASE = 0.04f
private const val WOBBLE_LEVEL = 0.12f
private const val WOBBLE_HZ = 1.6f
private const val WOBBLE_SHIFT = 1.1f
private val ARC_WIDTH = 2.dp
private const val DOTS_PERIOD_MILLIS = 1200
private val DOT_RADIUS = 4.dp
private val DOT_GAP = 6.dp
private const val DOT_MIN_ALPHA = 0.3f
private const val ANNOUNCE_STEP_SECONDS = 5
private const val BUBBLE_WIDTH_SHARE = 0.8f
private const val PENDING_ALPHA = 0.7f

private class PreviewOutput(private val text: String) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = text
}

@Preview
@Composable
private fun GarMicIndicatorPreview() {
    val level = remember { mutableStateOf<VoiceActivity?>(VoiceActivity(0.6f, true)) }
    AppTheme {
        Surface {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                listOf(
                    GarMicState.Idle,
                    GarMicState.Listening(null),
                    GarMicState.Listening(garElapsedNow() + 4000),
                    GarMicState.Starting,
                    GarMicState.Transcribing,
                    GarMicState.Thinking(garElapsedNow(), null, model = false, loadingModel = false),
                    GarMicState.Speaking,
                    GarMicState.Unavailable(SttState.NotDownloaded),
                ).forEach { state ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GarMicIndicator(state = state, voiceActivity = level, onClick = {})
                        GarMicIndicator(state = state, voiceActivity = level, onClick = null, size = 32.dp)
                        GarStateLabel(state = state, announce = false)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun GarChipRowPreview() {
    AppTheme {
        Surface {
            GarChipRow(
                chips = listOf(GarChip("Cancel timer"), GarChip("Show timers"), GarChip("Tomorrow")),
                enabled = true,
                onChip = {},
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Preview
@Composable
private fun GarCardsPreview() {
    val skill = SkillInfoPreviews().values.first()
    AppTheme {
        Surface {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                GarUserBubble(text = "set a timer for five minutes", pending = false, onClick = {})
                GarAnswerCard(
                    skill = skill,
                    output = PreviewOutput("Timer set for 5 minutes."),
                    ctx = SkillContextImpl.newForPreviews(LocalContext.current),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                GarUserBubble(text = "what's the weather tomorrow", pending = true, onClick = null)
                GarThinkingCard(
                    state = GarMicState.Thinking(
                        garElapsedNow() - 3000, skill, model = false, loadingModel = false
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
