package org.stypox.dicio.gar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Where the keyboard's insets are applied (gar/DESIGN.md, "Frame rate"). Dicio padded the whole
 * app for `safeDrawing` at the root, which includes the keyboard, so every frame of the keyboard's
 * animation re-measured everything: the drawer, the NavHost, the top bar and its text field. The
 * root now pads for the bars and the cutout only (they don't animate), and each screen pads for
 * the keyboard where it matters: the home screen's bottom bar (the list above it shrinks, the top
 * bar stays put), and the other screens as a whole, as before.
 */
object GarInsets {
    /** `safeDrawing` without the keyboard. */
    @Composable
    fun rootInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
}

/**
 * A screen padded for the keyboard (what the root's `safeDrawing` did for it before). The root
 * has consumed the bars, so this only adds what the keyboard covers beyond them.
 */
@Composable
fun GarImePadded(content: @Composable () -> Unit) {
    Box(modifier = Modifier.imePadding()) {
        content()
    }
}
