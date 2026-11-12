package org.stypox.dicio.gar.ui.garble

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Type that isn't Material's (body text stays Roboto / AppTypography). */
object GarbleType {
    /**
     * The lowercase "garble" wordmark: system sans medium, -0.5sp tracking (no bundled font).
     * Sizes: 22sp sheet header, 28sp home top bar.
     */
    fun wordmark(size: TextUnit): TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = size,
        letterSpacing = (-0.5).sp,
    )

    /** "LISTENING": muted caps 13sp with tracking. */
    val Overline = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.5.sp,
    )

    /** Provenance chips ("instant", "on-device model, 1.4 s"): monospace 12sp. */
    val Provenance = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )

    const val WORDMARK = "garble"
}
