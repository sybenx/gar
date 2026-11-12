package org.stypox.dicio.gar.ui.garble

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure color math for Garble's derived schemes (no Android classes, unit tested): WCAG contrast,
 * HSL lightness and hue shifts, the 5 step mark ramp, and fitting an accent to the neutrals.
 */
object GarbleColorMath {
    /** onAccent text on the accent. */
    const val TEXT_CONTRAST = 4.5f

    /** The accent (a filled control) against every neutral it sits on. */
    const val UI_CONTRAST = 3f

    /** WCAG 2 contrast ratio, 1 to 21. */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** Hue in degrees [0, 360), saturation and lightness in [0, 1]. */
    data class Hsl(val h: Float, val s: Float, val l: Float)

    fun toHsl(c: Color): Hsl {
        val r = c.red
        val g = c.green
        val b = c.blue
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) {
            return Hsl(0f, 0f, l)
        }
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Hsl((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(hsl: Hsl, alpha: Float = 1f): Color {
        val h = ((hsl.h % 360f) + 360f) % 360f
        val s = hsl.s.coerceIn(0f, 1f)
        val l = hsl.l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r + m).coerceIn(0f, 1f),
            green = (g + m).coerceIn(0f, 1f),
            blue = (b + m).coerceIn(0f, 1f),
            alpha = alpha,
        )
    }

    fun rotateHue(c: Color, degrees: Float): Color =
        toHsl(c).let { fromHsl(it.copy(h = it.h + degrees), c.alpha) }

    /** [count] colors from [from] to [to] (both included), blended in Oklab. */
    fun ramp(from: Color, to: Color, count: Int = GarbleColors.MARK_BARS): List<Color> =
        List(count) { i -> lerp(from, to, if (count == 1) 0f else i / (count - 1f)) }

    /**
     * [seed]'s hue and saturation with the lightness moved, in 1% steps, until [ok] holds: up on
     * dark neutrals, down on light ones. Gives the last tried color if nothing passes (pure white
     * or black always do for the thresholds used here).
     */
    private fun shiftUntil(seed: Color, lighter: Boolean, ok: (Color) -> Boolean): Color {
        if (ok(seed)) {
            return seed
        }
        val hsl = toHsl(seed)
        var l = hsl.l
        var candidate = seed
        repeat(100) {
            l = (l + if (lighter) 0.01f else -0.01f).coerceIn(0f, 1f)
            candidate = fromHsl(hsl.copy(l = l))
            if (ok(candidate)) {
                return candidate
            }
        }
        return candidate
    }

    /** Text on the accent: a very dark tint of its hue on dark neutrals, white on light ones. */
    fun onAccentFor(accent: Color, light: Boolean): Color =
        if (light) Color.White else toHsl(accent).let { fromHsl(Hsl(it.h, min(it.s, 0.7f), 0.07f)) }

    /** True if [accent] works as Garble's accent on [neutrals] with [onAccent] text on it. */
    fun accentPasses(accent: Color, onAccent: Color, neutrals: GarbleNeutrals): Boolean =
        contrast(onAccent, accent) >= TEXT_CONTRAST &&
            backgrounds(neutrals).all { contrast(accent, it) >= UI_CONTRAST }

    /** [seed] as an accent for [neutrals], with its onAccent; lightness only is changed. */
    fun fitAccent(seed: Color, neutrals: GarbleNeutrals, light: Boolean): Pair<Color, Color> {
        val opaque = seed.copy(alpha = 1f)
        val accent = shiftUntil(opaque, lighter = !light) {
            accentPasses(it, onAccentFor(it, light), neutrals)
        }
        return accent to onAccentFor(accent, light)
    }

    /** [seed] visible as a mark bar on [neutrals]' bg (>= 3:1). */
    fun fitMark(seed: Color, neutrals: GarbleNeutrals, light: Boolean): Color =
        shiftUntil(seed.copy(alpha = 1f), lighter = !light) {
            contrast(it, neutrals.bg) >= UI_CONTRAST && contrast(it, neutrals.surface) >= UI_CONTRAST
        }

    private fun backgrounds(n: GarbleNeutrals) = listOf(n.bg, n.surface, n.card, n.raised)
}

/**
 * The full palette for [mode] and [source]. [seed] (ARGB, 0 = [GarblePresets.DEFAULT_SEED]) is
 * used by CUSTOM; [dynamic] (the wallpaper scheme's primary and tertiary for this mode) by SYSTEM,
 * which falls back to GARBLE without it.
 */
fun garbleColors(
    mode: GarbleMode,
    source: GarbleColorSource,
    seed: Int = 0,
    dynamic: Pair<Color, Color>? = null,
): GarbleColors {
    val neutrals = GarbleNeutrals.of(mode)
    val light = mode == GarbleMode.LIGHT
    fun derived(primary: Color, tertiary: Color, tag: GarbleColorSource): GarbleColors {
        val (accent, onAccent) = GarbleColorMath.fitAccent(primary, neutrals, light)
        val start = GarbleColorMath.fitMark(primary, neutrals, light)
        val end = GarbleColorMath.fitMark(tertiary, neutrals, light)
        return GarbleColors(
            neutrals = neutrals,
            accent = accent,
            onAccent = onAccent,
            mark = GarbleColorMath.ramp(start, end).map {
                GarbleColorMath.fitMark(it, neutrals, light)
            },
            mode = mode,
            source = tag,
        )
    }
    return when {
        source == GarbleColorSource.SYSTEM && dynamic != null ->
            derived(dynamic.first, dynamic.second, GarbleColorSource.SYSTEM)
        source == GarbleColorSource.CUSTOM -> {
            val primary = Color(if (seed == 0) GarblePresets.DEFAULT_SEED else seed)
            derived(
                primary,
                GarbleColorMath.rotateHue(primary, GarblePresets.CUSTOM_TERTIARY_HUE_SHIFT),
                GarbleColorSource.CUSTOM,
            )
        }
        else -> GarbleColors(
            neutrals = neutrals,
            accent = if (light) GarblePresets.LightAccent else GarblePresets.DarkAccent,
            onAccent = if (light) GarblePresets.LightOnAccent else GarblePresets.DarkOnAccent,
            mark = if (light) GarblePresets.LightMark else GarblePresets.DarkMark,
            mode = mode,
            source = GarbleColorSource.GARBLE,
        )
    }
}
