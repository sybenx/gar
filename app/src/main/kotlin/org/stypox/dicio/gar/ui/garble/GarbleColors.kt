package org.stypox.dicio.gar.ui.garble

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Light, dark or black (OLED) Garble; AppTheme maps Dicio's Theme setting onto it. */
enum class GarbleMode { LIGHT, DARK, BLACK }

/** Where the accent and the mark come from. The neutrals are the same for every source. */
enum class GarbleColorSource { GARBLE, SYSTEM, CUSTOM }

/**
 * The zero-hue grays, in depth order dim < bg < surface < card < raised (light: the sheet surface
 * is white, cards step down from it). Identical for every [GarbleColorSource].
 */
@Immutable
data class GarbleNeutrals(
    /** The app behind the sheet, under the scrim. */
    val dim: Color,
    /** Screen background. */
    val bg: Color,
    /** The sheet. */
    val surface: Color,
    /** Cards, the chip and pill fills. */
    val card: Color,
    /** Secondary buttons, the mic button, icon circles. */
    val raised: Color,
    /** Hairlines, progress tracks. */
    val line: Color,
    val text: Color,
    val muted: Color,
) {
    companion object {
        val Dark = GarbleNeutrals(
            dim = Color(0xFF0B0B0B),
            bg = Color(0xFF121212),
            surface = Color(0xFF1A1A1A),
            card = Color(0xFF232323),
            raised = Color(0xFF2D2D2D),
            line = Color(0xFF363636),
            text = Color(0xFFEDEDED),
            muted = Color(0xFFA6A6A6),
        )
        val Light = GarbleNeutrals(
            dim = Color(0xFFC6C6C6),
            bg = Color(0xFFF7F7F7),
            surface = Color(0xFFFFFFFF),
            card = Color(0xFFF4F4F4),
            raised = Color(0xFFEAEAEA),
            line = Color(0xFFDDDDDD),
            text = Color(0xFF171717),
            muted = Color(0xFF575757),
        )

        /** Spec: "rest as dark", except dim, which can't be lighter than a black bg. */
        val Black = Dark.copy(
            dim = Color(0xFF000000),
            bg = Color(0xFF000000),
            surface = Color(0xFF121212),
            card = Color(0xFF1A1A1A),
            raised = Color(0xFF242424),
        )

        fun of(mode: GarbleMode): GarbleNeutrals = when (mode) {
            GarbleMode.LIGHT -> Light
            GarbleMode.DARK -> Dark
            GarbleMode.BLACK -> Black
        }
    }
}

/**
 * Garble's palette: [GarbleNeutrals] plus the accent (ONLY the mark, one primary action per
 * screen, progress, the selected radio dot, swatches, focus) and the mark's 5 bar colors.
 * Read it with `LocalGarbleColors.current`; AppTheme provides it.
 */
@Immutable
data class GarbleColors(
    val neutrals: GarbleNeutrals,
    val accent: Color,
    /** Text and icons on [accent], >= 4.5:1. */
    val onAccent: Color,
    /** 5 colors, left bar to right bar. */
    val mark: List<Color>,
    val mode: GarbleMode,
    val source: GarbleColorSource,
) {
    val dim: Color get() = neutrals.dim
    val bg: Color get() = neutrals.bg
    val surface: Color get() = neutrals.surface
    val card: Color get() = neutrals.card
    val raised: Color get() = neutrals.raised
    val line: Color get() = neutrals.line
    val text: Color get() = neutrals.text
    val muted: Color get() = neutrals.muted
    val isLight: Boolean get() = mode == GarbleMode.LIGHT

    init {
        require(mark.size == MARK_BARS) { "the mark has $MARK_BARS bars, got ${mark.size}" }
    }

    companion object {
        const val MARK_BARS = 5
    }
}

/** The Garble defaults (indigo to cyan) and the "Pick a color" presets. */
object GarblePresets {
    val DarkAccent = Color(0xFF63C8FF)
    val DarkOnAccent = Color(0xFF06121F)
    val LightAccent = Color(0xFF3347D1)
    val LightOnAccent = Color(0xFFFFFFFF)
    val DarkMark = listOf(
        Color(0xFF8B7CFF), Color(0xFF7E93FF), Color(0xFF70ABFF), Color(0xFF63C8FF), Color(0xFF5EE6FF),
    )
    val LightMark = listOf(
        Color(0xFF6A57F0), Color(0xFF5468E6), Color(0xFF3F7FDB), Color(0xFF1F8FCB), Color(0xFF0A9DB5),
    )

    const val AMBER: Int = 0xFFFFB224.toInt()
    const val CORAL: Int = 0xFFFF8A7A.toInt()
    const val MINT: Int = 0xFF5FD7A8.toInt()

    /** The swatch row, in order. */
    val SEEDS: List<Int> = listOf(AMBER, CORAL, MINT)

    /** What a stored seed of 0 (never picked) means. */
    const val DEFAULT_SEED: Int = AMBER

    /** Custom schemes: the mark's far end is the seed's hue turned this far. */
    const val CUSTOM_TERTIARY_HUE_SHIFT = 40f
}

val LocalGarbleColors = staticCompositionLocalOf {
    garbleColors(GarbleMode.DARK, GarbleColorSource.GARBLE)
}
