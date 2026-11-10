package org.stypox.dicio.gar.stt

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.map
import org.stypox.dicio.R
import org.stypox.dicio.gar.conversation.GarContinuedListening
import org.stypox.dicio.io.input.VoiceActivity

/**
 * The input device's [VoiceActivity] (the wrapper's, so it follows device changes), null while
 * the device reports none (Vosk, nothing) and in previews. Read its value only in the draw phase
 * (see [garLevelRing], [GarSttLevelBar]), or through `derivedStateOf`: it changes about 31 times
 * a second while listening, and reading it in composition would recompose that often.
 */
@Composable
fun rememberGarVoiceActivity(): State<VoiceActivity?> {
    if (LocalInspectionMode.current) {
        return remember { mutableStateOf(null) } // previews have no Hilt
    }
    val context = LocalContext.current
    val flow = remember(context) {
        EntryPointAccessors.fromApplication(context, GarSttEntryPoint::class.java)
            .sttInputDevice().voiceActivity
    }
    return flow.collectAsState()
}

/**
 * The home button's text for NotDownloaded when Whisper is the input device ("Download speech
 * model (83 MB)"), or null for Dicio's text (Vosk). Only Whisper reports voice activity, which
 * tells the two apart without asking the device resolver.
 */
@Composable
fun garSttDownloadLabel(voiceActivity: State<VoiceActivity?>): String? {
    if (LocalInspectionMode.current) {
        return null
    }
    val isWhisper by remember(voiceActivity) { derivedStateOf { voiceActivity.value != null } }
    if (!isWhisper) {
        return null
    }
    val context = LocalContext.current
    val size by remember(context) {
        EntryPointAccessors.fromApplication(context, GarSttEntryPoint::class.java)
            .garSettings().data.map { SttModelCatalog.downloadSize(it.sttModel) }
    }.collectAsState(initial = null)
    return size?.let {
        stringResource(R.string.gar_stt_download_model, Formatter.formatShortFileSize(context, it))
    }
}

/**
 * The end of the continued-listening window that is open (`System.nanoTime` in milliseconds),
 * null when none is and in previews.
 */
@Composable
fun rememberGarBriefWindow(): State<Long?> {
    if (LocalInspectionMode.current) {
        return remember { mutableStateOf(null) } // previews have no Hilt
    }
    val context = LocalContext.current
    val flow = remember(context) {
        EntryPointAccessors.fromApplication(context, GarSttEntryPoint::class.java)
            .sttInputDevice().briefWindowEndsAt
    }
    return flow.collectAsState()
}

/** "Listening for a follow-up" while a continued-listening window is open, else null. */
@Composable
fun garBriefListeningLabel(): String? {
    val endsAt by rememberGarBriefWindow()
    return if (endsAt != null) stringResource(R.string.gar_stt_listening_follow_up) else null
}

/**
 * The share of the window ending at [endsAt] that is left, from 1 to 0, updated every frame
 * while it runs; read it in the draw phase.
 */
@Composable
private fun rememberBriefWindowLeft(endsAt: Long?): State<Float> {
    val left = remember { mutableStateOf(0f) }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            val remaining = endsAt - System.nanoTime() / 1_000_000
            val window = GarContinuedListening.WINDOW_MILLIS.toFloat()
            left.value = (remaining / window).coerceIn(0f, 1f)
            if (remaining <= 0) {
                break
            }
            withFrameMillis {}
        }
        if (endsAt == null) {
            left.value = 0f
        }
    }
    return left
}

/**
 * Draws a ring behind the microphone that grows with the level, read in the draw phase so that
 * nothing recomposes while the user talks. In a continued-listening window, a thin arc around it
 * empties until the window ends.
 */
fun Modifier.garLevelRing(voiceActivity: State<VoiceActivity?>, color: Color): Modifier = composed {
    val endsAt by rememberGarBriefWindow()
    val windowLeft = rememberBriefWindowLeft(endsAt)
    drawBehind {
        val left = windowLeft.value
        if (endsAt != null && left > 0f) {
            val stroke = WINDOW_ARC_WIDTH.toPx()
            val radius = size.minDimension / 2 * (1f + RING_GROWTH) + stroke
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
        val level = voiceActivity.value?.level?.coerceIn(0f, 1f) ?: return@drawBehind
        if (level <= 0f) {
            return@drawBehind
        }
        val radius = size.minDimension / 2 * (1f + RING_GROWTH * level)
        drawCircle(color = color.copy(alpha = RING_ALPHA), radius = radius)
    }
}

/**
 * The STT popup's level bar, shown while Whisper listens (it has no partial text to show). Draws
 * nothing otherwise, and keeps its height so the popup doesn't jump.
 */
@Composable
fun GarSttLevelBar(
    voiceActivity: State<VoiceActivity?>,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .width(LEVEL_BAR_WIDTH)
            .height(LEVEL_BAR_HEIGHT)
            .drawBehind {
                if (!listening) {
                    return@drawBehind
                }
                val level = voiceActivity.value?.level?.coerceIn(0f, 1f) ?: return@drawBehind
                val corner = CornerRadius(size.height / 2)
                drawRoundRect(color = track, cornerRadius = corner)
                drawRoundRect(
                    color = fill,
                    topLeft = Offset.Zero,
                    size = Size(size.width * level, size.height),
                    cornerRadius = corner,
                )
            }
    )
}

private const val RING_GROWTH = 0.75f
private const val RING_ALPHA = 0.3f
private val LEVEL_BAR_WIDTH = 120.dp
private val LEVEL_BAR_HEIGHT = 4.dp
private val WINDOW_ARC_WIDTH = 2.dp
