package org.stypox.dicio.gar.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarConfirmOutput
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.sms.LocalGarCardHasTitle
import org.stypox.dicio.gar.ui.GarMicState
import org.stypox.dicio.gar.ui.GarStateLabel
import org.stypox.dicio.gar.ui.garble.GarbleMark
import org.stypox.dicio.gar.ui.garble.GarbleMarkSpring
import org.stypox.dicio.gar.ui.garble.GarbleMarkState
import org.stypox.dicio.gar.ui.garble.GarbleMicButton
import org.stypox.dicio.gar.ui.garble.GarbleMicButtonDefaults
import org.stypox.dicio.gar.ui.garble.GarbleShapes
import org.stypox.dicio.gar.ui.garble.GarbleType
import org.stypox.dicio.gar.ui.garble.LocalGarbleColors
import org.stypox.dicio.gar.ui.garble.garbleCard
import org.stypox.dicio.gar.ui.garble.garbleMarkStateFor
import org.stypox.dicio.gar.ui.garble.garbleRaised
import org.stypox.dicio.gar.ui.rememberGarReducedMotion
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.ui.home.garSttFabLook
import java.util.Locale

/*
 * The Garble sheet's parts (the approved canvas: Listening and Answer). GarSheetColumn puts them
 * in order; the layout and its behaviour stay in GarSheetScreen.kt.
 */

/**
 * The small mark in the header: upright, waving while the model thinks, pulsing while speaking.
 * While the Listening layout is up ([listeningLayout]) the big mark shows the work, so this one
 * stays upright.
 */
internal fun garSheetHeaderMark(state: GarMicState, listeningLayout: Boolean = false): GarbleMarkState =
    when {
        listeningLayout -> GarbleMarkState.Understood
        // the big mark (or the mic button's dots) shows the listening
        state is GarMicState.Listening || state == GarMicState.Starting -> GarbleMarkState.Understood
        else -> garbleMarkStateFor(state, voice = false, answered = true)
    }

/**
 * The Listening layout (big mark, "Type instead", stop) instead of the answer: while listening for
 * a new request, i.e. with no answer on the sheet yet, or after the user asked to listen again
 * ([userAsked]). A mic reopened by an answer (a confirmation, a follow-up window) keeps the answer
 * on screen, with the mic button's dots. Once the words are in it stays up through Transcribing and
 * Thinking ([wasListening]: it was up a moment ago), so the big mark goes Hearing, Thinking, and
 * the answer layout only takes over when the answer is there.
 */
internal fun garSheetListeningLayout(
    state: GarMicState,
    typing: Boolean,
    hasAnswer: Boolean = false,
    userAsked: Boolean = false,
    wasListening: Boolean = false,
): Boolean {
    if (typing) {
        return false
    }
    val listening = when (state) {
        is GarMicState.Listening -> state.followUpEndsAt == null
        GarMicState.Starting -> true
        GarMicState.Transcribing, is GarMicState.Thinking -> wasListening
        else -> false
    }
    return listening && (!hasAnswer || userAsked)
}

/** Seconds with one decimal ("1.4"). */
internal fun garSheetSeconds(millis: Long, locale: Locale = Locale.ROOT): String =
    String.format(locale, "%.1f", millis / 1000.0)

/** An answer that waits for a spoken reply. */
internal sealed interface GarSheetConfirm {
    class Sms(val output: SmsCardOutput) : GarSheetConfirm
    data object YesNo : GarSheetConfirm
}

internal fun garSheetConfirm(answer: SkillOutput): GarSheetConfirm? =
    when (val output = (answer as? GarFollowUpOutput)?.output ?: answer) {
        is SmsCardOutput -> GarSheetConfirm.Sms(output)
        is GarConfirmOutput -> GarSheetConfirm.YesNo
        else -> null
    }

/** The drag handle's bar: 36 x 4 in `line`. */
@Composable
internal fun GarSheetHandleBar() {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(LocalGarbleColors.current.line)
            .clearAndSetSemantics {},
    )
}

/** Small mark + "garble", and the "on this phone" pill at the end. */
@Composable
internal fun GarSheetHeader(
    state: GarMicState,
    modifier: Modifier = Modifier,
    listeningLayout: Boolean = false,
) {
    val g = LocalGarbleColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GarbleMark(
                state = garSheetHeaderMark(state, listeningLayout),
                modifier = Modifier.size(HEADER_MARK),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = GarbleType.WORDMARK,
                style = GarbleType.wordmark(22.sp),
                color = g.text,
                maxLines = 1,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(GarbleShapes.Pill)
                .background(g.card)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Smartphone,
                contentDescription = null,
                tint = g.muted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.gar_sheet_on_this_phone),
                fontSize = 13.sp,
                color = g.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The state as a muted line under the header, only for what the user must know (thinking with its
 * timer, transcribing, speech input unavailable); it keeps GarStateLabel's announcements.
 */
@Composable
internal fun GarSheetStateLine(state: GarMicState, modifier: Modifier = Modifier) {
    val shown = state is GarMicState.Thinking || state == GarMicState.Transcribing ||
        state is GarMicState.Unavailable
    if (!shown) {
        return
    }
    CompositionLocalProvider(LocalContentColor provides LocalGarbleColors.current.muted) {
        GarStateLabel(state = state, announce = true, modifier = modifier)
    }
}

/**
 * Listening for a new request: "LISTENING", the live transcript (30sp, the unfinished word muted;
 * only Vosk sends partial text), the big mark (dots, or hearing while the voice comes through).
 */
@Composable
internal fun GarSheetListeningBody(
    state: GarMicState,
    transcript: String?,
    voice: State<Boolean>,
    modifier: Modifier = Modifier,
) {
    val g = LocalGarbleColors.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Column(modifier = modifier.fillMaxWidth()) {
        val overline = stringResource(
            if (state == GarMicState.Starting) {
                R.string.gar_sheet_overline_starting
            } else {
                R.string.gar_sheet_overline_listening
            }
        )
        // not a live region: TalkBack would be recorded; the earcon says it
        Text(
            text = overline.uppercase(locale),
            style = GarbleType.Overline,
            color = g.muted,
        )
        if (!transcript.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            val text = transcript.trimEnd()
            val cut = text.lastIndexOf(' ')
            Text(
                text = buildAnnotatedString {
                    append(text.substring(0, cut + 1))
                    withStyle(SpanStyle(color = g.muted)) {
                        append(text.substring(cut + 1))
                    }
                },
                fontSize = 30.sp,
                lineHeight = 36.sp,
                color = g.text,
            )
        }
        Spacer(modifier = Modifier.height(22.dp))
        GarSheetBigMark(
            state = garbleMarkStateFor(state, voice.value, answered = true),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** The big listening mark; it grows from the center when the layout appears. */
@Composable
private fun GarSheetBigMark(state: GarbleMarkState, modifier: Modifier = Modifier) {
    val reduced = rememberGarReducedMotion()
    val grow = remember { Animatable(if (reduced) 1f else BIG_MARK_START) }
    LaunchedEffect(Unit) {
        grow.animateTo(1f, GarbleMarkSpring)
    }
    GarbleMark(
        state = state,
        reducedMotion = reduced,
        modifier = modifier
            .size(BIG_MARK)
            .graphicsLayer {
                scaleX = grow.value
                scaleY = grow.value
            },
    )
}

/** The Listening layout's buttons: "Type instead" (a raised pill) and stop (a raised circle). */
@Composable
internal fun GarSheetListeningButtons(
    onTypeInstead: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        GarSheetRaisedPill(
            text = stringResource(R.string.gar_sheet_type_instead),
            icon = Icons.Rounded.Keyboard,
            onClick = onTypeInstead,
            modifier = Modifier.weight(1f),
        )
        GarSheetRaisedCircle(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.gar_mic_stop_listening),
            onClick = onStop,
            size = LISTENING_BUTTON,
        )
    }
}

/** A secondary button: a borderless raised pill in the text color. */
@Composable
internal fun GarSheetRaisedPill(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGarbleColors.current
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(LISTENING_BUTTON)
            .garbleRaised(GarbleShapes.Pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = g.text,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = g.text,
            maxLines = 1,
        )
    }
}

/** An icon button: a raised circle, [size] at least 48dp. */
@Composable
internal fun GarSheetRaisedCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = SMALL_BUTTON,
) {
    val g = LocalGarbleColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.coerceAtLeast(48.dp))
            .garbleRaised(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = g.text,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** "You said" and the request (18sp); tapping it edits it when [onEdit] is set. */
@Composable
internal fun GarSheetYouSaid(
    text: String,
    pending: Boolean,
    onEdit: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val g = LocalGarbleColors.current
    val edit = stringResource(R.string.gar_sheet_edit)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onEdit == null) {
                    Modifier
                } else {
                    Modifier.clickable(onClickLabel = edit, role = Role.Button, onClick = onEdit)
                }
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = stringResource(R.string.gar_sheet_you_said),
            fontSize = 13.sp,
            color = g.muted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontStyle = if (pending) FontStyle.Italic else FontStyle.Normal,
            color = if (pending) g.text.copy(alpha = PENDING_ALPHA) else g.text,
        )
    }
}

/**
 * The answer card: card bg, 24dp corners, 20dp padding, a soft shadow; the title row (the skill's
 * icon and name in the text color, or [title] instead of the name, a muted [status] at the end),
 * then the output. With [maxHeight] the card is at most that tall and scrolls inside.
 */
@Composable
internal fun GarSheetAnswerCard(
    skill: SkillInfo?,
    output: SkillOutput,
    ctx: SkillContext,
    status: String?,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
    title: String? = null,
) {
    val g = LocalGarbleColors.current
    CompositionLocalProvider(LocalContentColor provides g.text) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .garbleCard()
                .then(
                    if (maxHeight.isSpecified) {
                        Modifier
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    }
                )
                .padding(20.dp),
        ) {
            if (skill != null || status != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {},
                ) {
                    if (skill != null) {
                        Icon(
                            painter = skill.icon(),
                            contentDescription = null,
                            tint = g.text,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = title ?: skill.name(LocalContext.current),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = g.text,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (status != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = status,
                            fontSize = 13.sp,
                            color = g.muted,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // the title row above says what the output is
                CompositionLocalProvider(LocalGarCardHasTitle provides (skill != null)) {
                    output.GraphicalOutput(ctx = ctx)
                }
            }
        }
    }
}

/** Where the answer came from: "instant" or "on-device model, 1.4 s", and "skill <id>". */
@Composable
internal fun GarSheetProvenanceRow(provenance: GarSheetProvenance, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        when (val route = provenance.route) {
            GarSheetRoute.Instant -> ProvenanceChip(
                icon = Icons.Rounded.Bolt,
                label = null,
                value = stringResource(R.string.gar_sheet_provenance_instant),
            )
            is GarSheetRoute.Model -> ProvenanceChip(
                icon = Icons.Rounded.Memory,
                label = null,
                value = stringResource(
                    R.string.gar_sheet_provenance_model,
                    garSheetSeconds(route.millis, locale),
                ),
            )
        }
        provenance.skillId?.let {
            ProvenanceChip(
                icon = null,
                label = stringResource(R.string.gar_sheet_provenance_skill),
                value = it,
            )
        }
    }
}

/** Monospace 12sp on card bg, 8dp corners: a muted [label] and the [value] in the text color. */
@Composable
private fun ProvenanceChip(icon: ImageVector?, label: String?, value: String) {
    val g = LocalGarbleColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(GarbleShapes.Chip)
            .background(g.card)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = g.muted,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = buildAnnotatedString {
                if (label != null) {
                    withStyle(SpanStyle(color = g.muted)) {
                        append(label)
                        append(' ')
                    }
                }
                append(value)
            },
            style = GarbleType.Provenance,
            color = g.text,
            maxLines = 1,
        )
    }
}

/** Under a card that waits for a spoken reply: a small listening mark and what to say. */
@Composable
internal fun GarSheetConfirmHint(
    text: String,
    markState: GarbleMarkState,
    modifier: Modifier = Modifier,
) {
    val g = LocalGarbleColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        GarbleMark(state = markState, modifier = Modifier.size(HINT_MARK))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, fontSize = 14.sp, color = g.muted)
    }
}

/** The hint for [confirm], or null when it no longer waits (an SMS sent or cancelled). */
@Composable
internal fun garSheetConfirmHint(confirm: GarSheetConfirm): String? = when (confirm) {
    is GarSheetConfirm.Sms -> if (confirm.output.draft.isPending) {
        stringResource(R.string.gar_sheet_sms_hint)
    } else {
        null
    }
    GarSheetConfirm.YesNo -> {
        val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
        stringResource(
            R.string.gar_sheet_yes_no_hint,
            stringResource(R.string.gar_yes).lowercase(locale),
            stringResource(R.string.gar_no).lowercase(locale),
        )
    }
}

/** The answer card's title instead of the skill's name: "Text message" on the SMS card. */
@Composable
internal fun garSheetAnswerTitle(confirm: GarSheetConfirm?): String? =
    if (confirm is GarSheetConfirm.Sms) stringResource(R.string.gar_sheet_sms_title) else null

/**
 * Whether [GarSheetConfirmHint] shows under a card that waits ([garSheetConfirmHint] is not null).
 * The SMS card's answer skill stays on top while the text waits, so "send" works said after a
 * mic tap or typed, also once the reopened mic closed: the hint stays. A yes/no confirmation only
 * listens once, so its hint shows while the mic is open.
 */
internal fun garSheetShowsConfirmHint(confirm: GarSheetConfirm, state: GarMicState): Boolean =
    when (confirm) {
        is GarSheetConfirm.Sms -> true
        GarSheetConfirm.YesNo -> state is GarMicState.Listening || state == GarMicState.Starting
    }

/** The hint's mark: listening (or hearing a voice) while the mic is open, else the logo. */
internal fun garSheetConfirmHintMark(state: GarMicState, voice: Boolean): GarbleMarkState =
    if (state is GarMicState.Listening || state == GarMicState.Starting) {
        garbleMarkStateFor(state, voice)
    } else {
        GarbleMarkState.Logo
    }

/** The muted status in the answer card's title row ("not sent yet"), or null. */
@Composable
internal fun garSheetAnswerStatus(confirm: GarSheetConfirm?): String? =
    if (confirm is GarSheetConfirm.Sms && confirm.output.draft.isPending) {
        stringResource(R.string.gar_sheet_sms_not_sent)
    } else {
        null
    }

/**
 * The bottom row: keyboard (raised, 48dp) at the start, the mic (72dp) centered, "Open GAR" at
 * the end. The mic shows the mark while listening, loading or transcribing (dots in a follow-up).
 */
@Composable
internal fun GarSheetBottomRow(
    state: GarMicState,
    voice: State<Boolean>,
    onKeyboard: () -> Unit,
    onMic: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGarbleColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
            GarSheetRaisedCircle(
                icon = Icons.Rounded.Keyboard,
                contentDescription = stringResource(R.string.gar_sheet_type),
                onClick = onKeyboard,
            )
        }
        val active = state is GarMicState.Listening || state == GarMicState.Starting ||
            state == GarMicState.Transcribing
        GarbleMicButton(
            onClick = onMic,
            size = GarbleMicButtonDefaults.SheetSize,
            active = active,
            // answered: an instant answer goes Idle, and the collapse should end upright, not on
            // the tilted logo (the mark is hidden while Idle)
            markState = garbleMarkStateFor(state, voice.value, answered = true),
            contentDescription = garSheetMicDescription(state),
        )
        Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.weight(1f)) {
            IconButton(onClick = onOpenApp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = stringResource(R.string.gar_sheet_open_app),
                    tint = g.muted,
                )
            }
        }
    }
}

/** What a tap on the mic does in [state], for screen readers (GarMicIndicator's texts). */
@Composable
private fun garSheetMicDescription(state: GarMicState): String = when (state) {
    GarMicState.Idle, is GarMicState.Thinking -> stringResource(R.string.start_listening)
    is GarMicState.Listening, GarMicState.Starting -> stringResource(R.string.gar_mic_stop_listening)
    GarMicState.Transcribing -> stringResource(R.string.gar_mic_cancel_transcription)
    GarMicState.Speaking -> stringResource(R.string.gar_mic_stop_speaking)
    is GarMicState.Unavailable -> state.stt?.let { garSttFabLook(it).text }
        ?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.start_listening)
}

private val HEADER_MARK = 32.dp
private val HINT_MARK = 20.dp
private val BIG_MARK = 170.dp
private val LISTENING_BUTTON = 52.dp
private val SMALL_BUTTON = 48.dp
private const val BIG_MARK_START = 0.4f
private const val PENDING_ALPHA = 0.7f
