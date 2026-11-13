package org.stypox.dicio.gar.ui.garble

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Garble's buttons. The theme's primary color is the accent, so Material's OutlinedButton,
 * ElevatedButton and TextButton would all draw accent labels; only the one primary action per
 * card (a plain [androidx.compose.material3.Button]) may use the accent. These keep the same
 * shape of API so a call site only changes its name.
 */

/** A secondary action: a borderless raised pill in the text color, at least 48 dp tall. */
@Composable
fun GarbleSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val g = LocalGarbleColors.current
    CompositionLocalProvider(
        LocalContentColor provides g.text,
        LocalTextStyle provides MaterialTheme.typography.labelLarge,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
            modifier = modifier
                .then(if (enabled) Modifier else Modifier.alpha(0.38f))
                .defaultMinSize(minWidth = 58.dp, minHeight = 48.dp)
                .garbleRaised()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(contentPadding),
        )
    }
}

/** A quiet text action (Cancel, No) in the text color instead of the accent. */
@Composable
fun GarbleTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = LocalGarbleColors.current.text),
        content = content,
    )
}

/** Neutral switch colors: a checked track in the text color, not the accent. */
@Composable
fun garbleSwitchColors(): SwitchColors {
    val g = LocalGarbleColors.current
    return SwitchDefaults.colors(
        checkedTrackColor = g.text,
        checkedThumbColor = g.bg,
        checkedIconColor = g.text,
        checkedBorderColor = g.text,
        uncheckedTrackColor = g.raised,
        uncheckedThumbColor = g.muted,
        uncheckedBorderColor = g.line,
    )
}

/** Neutral checkbox colors: a checked box in the text color, not the accent. */
@Composable
fun garbleCheckboxColors(): CheckboxColors {
    val g = LocalGarbleColors.current
    return CheckboxDefaults.colors(
        checkedColor = g.text,
        checkmarkColor = g.bg,
        uncheckedColor = g.muted,
    )
}

/** Neutral slider colors: a setting's slider is not progress, so no accent on it. */
@Composable
fun garbleSliderColors(): SliderColors {
    val g = LocalGarbleColors.current
    return SliderDefaults.colors(
        thumbColor = g.text,
        activeTrackColor = g.text,
        // not g.raised: dialogs are drawn in about that gray, which hid the track in the color picker
        inactiveTrackColor = g.muted.copy(alpha = 0.3f),
        activeTickColor = g.bg,
        inactiveTickColor = g.muted,
    )
}

/**
 * The one primary action per card (Send, Yes): Material's filled button in the accent, at least
 * 48 dp tall so it lines up with [GarbleSecondaryButton] beside it.
 */
@Composable
fun GarblePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * A secondary icon action (discard, stop): a borderless raised circle with the icon in the text
 * color, [size] at least 48 dp. [contentDescription] is what TalkBack reads.
 */
@Composable
fun GarbleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
            .size(size.coerceAtLeast(48.dp))
            .garbleRaised(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LocalGarbleColors.current.text,
            modifier = Modifier.size(20.dp),
        )
    }
}
