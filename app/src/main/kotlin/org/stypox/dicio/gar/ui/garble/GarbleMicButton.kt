package org.stypox.dicio.gar.ui.garble

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.stypox.dicio.R
import org.stypox.dicio.gar.ui.rememberGarReducedMotion

object GarbleMicButtonDefaults {
    val HomeSize = 84.dp
    val SheetSize = 72.dp

    /** The plain mic icon's share of the button. */
    const val ICON_SHARE = 0.36f

    /** The dots' (and then the mark's) share of the button while [GarbleMicButton]'s active. */
    const val MARK_SHARE = 0.56f
}

/** A key for `Modifier.sharedElement` / `sharedBounds` between the button's mark and a big one. */
data object GarbleMarkSharedKey

/**
 * The mic button: a neutral raised circle with a plain mic icon in the text color (never the mark,
 * never tinted). While [active] (listening, or any state the screen wants the mark for) the icon
 * shrinks and fades and the mark grows from the center in [markState] (5 dots when listening),
 * with the mark's spring; when [active] ends it collapses back into the icon.
 *
 * The hand-off to a bigger mark (the sheet's Listening layout): either keep this button and let
 * its dots grow while the sheet rises (nothing to do), or put both marks in a
 * SharedTransitionLayout and pass [markModifier] = `Modifier.sharedElement(
 * rememberSharedContentState(GarbleMarkSharedKey), animatedVisibilityScope)` here and on the big
 * [GarbleMark]; the morph to dots here runs first, so the shared element starts from dots.
 *
 * [contentDescription] defaults to "Start listening" / "Stop listening".
 */
@Composable
fun GarbleMicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GarbleMicButtonDefaults.HomeSize,
    active: Boolean = false,
    markState: GarbleMarkState = GarbleMarkState.Listening,
    enabled: Boolean = true,
    contentDescription: String = stringResource(
        if (active) R.string.gar_mic_stop_listening else R.string.start_listening
    ),
    markModifier: Modifier = Modifier,
    levels: GarbleMarkLevels = GarbleMarkLevels.Live,
) {
    val colors = LocalGarbleColors.current
    val reduced = rememberGarReducedMotion()
    val morph = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active, reduced) {
        if (reduced) {
            morph.snapTo(if (active) 1f else 0f)
        } else {
            morph.animateTo(if (active) 1f else 0f, GarbleMarkSpring)
        }
    }
    // composition only changes when the mark appears or goes, not per frame of the morph
    val showMark by remember { derivedStateOf { morph.value > 0.01f } }

    Box(
        modifier = modifier
            .size(size.coerceAtLeast(48.dp))
            .garbleRaised(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint = colors.text,
            modifier = Modifier
                .size(size * GarbleMicButtonDefaults.ICON_SHARE)
                .graphicsLayer {
                    val m = morph.value.coerceIn(0f, 1f)
                    alpha = 1f - m
                    scaleX = 1f - 0.6f * m
                    scaleY = 1f - 0.6f * m
                },
        )
        if (showMark) {
            Box(
                modifier = Modifier
                    .size(size * GarbleMicButtonDefaults.MARK_SHARE)
                    .graphicsLayer {
                        val m = morph.value
                        alpha = m.coerceIn(0f, 1f)
                        scaleX = m
                        scaleY = m
                    },
            ) {
                GarbleMark(
                    state = markState,
                    modifier = markModifier.fillMaxSize(),
                    colors = colors.mark,
                    levels = levels,
                    reducedMotion = reduced,
                )
            }
        }
    }
}
