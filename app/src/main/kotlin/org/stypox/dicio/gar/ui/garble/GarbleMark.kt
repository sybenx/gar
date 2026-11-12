package org.stypox.dicio.gar.ui.garble

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch
import org.stypox.dicio.gar.stt.GarMicLevel
import org.stypox.dicio.gar.ui.rememberGarReducedMotion

/** What drives Hearing and Speaking; read in the draw phase, once per frame. */
interface GarbleMarkLevels {
    fun mic(): GarMicLevel.Frame

    /** TTS amplitude 0..1, or null for the synthetic pulse. */
    fun speech(): Float?

    /** The live mic and speech ([GarMicLevel]). */
    object Live : GarbleMarkLevels {
        override fun mic() = GarMicLevel.frame.value
        override fun speech() = GarMicLevel.speech.value
    }

    /** Fixed values, for previews and screenshots. */
    class Fixed(
        private val frame: GarMicLevel.Frame,
        private val speech: Float? = null,
    ) : GarbleMarkLevels {
        override fun mic() = frame
        override fun speech() = speech
    }
}

/** The spring between states (~250 ms, a touch of overshoot). */
internal val GarbleMarkSpring = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

/**
 * Garble's 5 bar mark in [state], fitted into this composable's size (give it a size, e.g.
 * `Modifier.size(32.dp)`; it's square in spirit, a wide box centers it). Decorative: no
 * semantics, put the label on the button or text next to it.
 *
 * States change with a spring from wherever the bars are; everything that moves (the level, the
 * breathing, the wave) is read in the draw phase of one graphics layer, so nothing recomposes per
 * frame. The frame clock only runs while the state moves or a spring is running (under reduced
 * motion only while real data drives it), and advances at most about 60 times a second.
 */
@Composable
fun GarbleMark(
    state: GarbleMarkState,
    modifier: Modifier = Modifier,
    colors: List<Color> = LocalGarbleColors.current.mark,
    levels: GarbleMarkLevels = GarbleMarkLevels.Live,
    reducedMotion: Boolean = rememberGarReducedMotion(),
) {
    val holder = remember { GarbleMarkHolder() }
    // a new state springs from what is on screen now; both are set in the same composition, so
    // no frame draws the new state without its transition (the first composition has none)
    val from = remember(state, reducedMotion) { holder.drawnOrNull() }
    val progress = remember(state, reducedMotion) { Animatable(if (from == null) 1f else 0f) }
    // milliseconds since the first frame; never reset, the moving poses are periodic
    val clock = remember { mutableLongStateOf(0L) }
    val currentState by rememberUpdatedState(state)

    LaunchedEffect(state, reducedMotion) {
        val spring = launch { progress.animateTo(1f, GarbleMarkSpring) }
        while (
            GarbleMarkGeometry.ticks(state, reducedMotion, levels.speech() != null) ||
            spring.isActive
        ) {
            withFrameMillis { now ->
                if (holder.clockStart < 0) holder.clockStart = now
                val elapsed = now - holder.clockStart
                // capped near 60 fps: a 90/120 Hz display would redraw 3 to 4 times per mic frame
                if (elapsed - clock.longValue < GarbleMarkGeometry.MIN_TICK_MILLIS) {
                    return@withFrameMillis
                }
                // Hearing only moves with the mic: no redraw while its frame is the same
                val mic = levels.mic()
                if (state == GarbleMarkState.Hearing && !spring.isActive && mic === holder.lastMic) {
                    return@withFrameMillis
                }
                holder.lastMic = mic
                clock.longValue = elapsed
            }
        }
    }

    Spacer(
        modifier = modifier
            .clearAndSetSemantics {}
            .graphicsLayer()
            .drawBehind {
                val t = clock.longValue // the frame clock: draw-only invalidation
                val frame = levels.mic()
                GarbleMarkGeometry.pose(
                    state = currentState,
                    timeMillis = t,
                    level = frame.rms,
                    bands = frame.bands,
                    speech = levels.speech(),
                    reducedMotion = reducedMotion,
                    out = holder.target,
                )
                if (from == null) {
                    holder.target.copyInto(holder.drawn)
                } else {
                    GarbleMarkGeometry.lerp(from, holder.target, progress.value, holder.drawn)
                }
                holder.hasDrawn = true

                val unit = minOf(size.width, size.height) / GarbleMarkGeometry.BOX
                val w = GarbleMarkGeometry.BAR_WIDTH * unit
                val radius = CornerRadius(w / 2f)
                for (i in 0 until GarbleMarkGeometry.BARS) {
                    val h = holder.drawn[i * 3] * unit
                    val cx = center.x + GarbleMarkGeometry.barCenterX(i) * unit
                    val cy = center.y + holder.drawn[i * 3 + 2] * unit
                    rotate(holder.drawn[i * 3 + 1], pivot = Offset(cx, cy)) {
                        drawRoundRect(
                            color = colors[i % colors.size],
                            topLeft = Offset(cx - w / 2f, cy - h / 2f),
                            size = Size(w, h),
                            cornerRadius = radius,
                        )
                    }
                }
            },
    )
}

/** The poses between frames; plain arrays, written only in the draw phase and on a state change. */
private class GarbleMarkHolder {
    val target = FloatArray(GarbleMarkGeometry.BARS * 3)
    val drawn = FloatArray(GarbleMarkGeometry.BARS * 3)
    var hasDrawn = false
    var clockStart = -1L

    /** The mic frame the clock last advanced for (Hearing skips a frame it already drew). */
    var lastMic: GarMicLevel.Frame? = null

    fun drawnOrNull(): FloatArray? = if (hasDrawn) drawn.copyOf() else null
}
