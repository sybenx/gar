package org.stypox.dicio.gar.ui.garble

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.stypox.dicio.settings.datastore.GarColorSource
import org.stypox.dicio.settings.datastore.UserSettings

class GarbleColorsTest : StringSpec({
    val modes = GarbleMode.entries
    val seeds = GarblePresets.SEEDS + listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF0000FF.toInt(),
        0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF7F00FF.toInt(),
    )

    "contrast ratio" {
        GarbleColorMath.contrast(Color.Black, Color.White) shouldBe (21f plusOrMinus 0.01f)
        GarbleColorMath.contrast(Color.White, Color.White) shouldBe (1f plusOrMinus 0.001f)
    }

    "hsl round trip and hue rotation" {
        for (argb in seeds) {
            val c = Color(argb)
            val back = GarbleColorMath.fromHsl(GarbleColorMath.toHsl(c))
            back.red shouldBe (c.red plusOrMinus 0.005f)
            back.green shouldBe (c.green plusOrMinus 0.005f)
            back.blue shouldBe (c.blue plusOrMinus 0.005f)
        }
        val red = GarbleColorMath.toHsl(Color(0xFFFF0000))
        red.h shouldBe (0f plusOrMinus 0.01f)
        GarbleColorMath.toHsl(GarbleColorMath.rotateHue(Color(0xFFFF0000), 120f)).h shouldBe
            (120f plusOrMinus 0.5f)
        GarbleColorMath.toHsl(GarbleColorMath.rotateHue(Color(0xFFFF0000), -40f)).h shouldBe
            (320f plusOrMinus 0.5f)
    }

    "neutrals are zero hue and the same for every source" {
        for (mode in modes) {
            val n = GarbleNeutrals.of(mode)
            for (c in listOf(n.dim, n.bg, n.surface, n.card, n.raised, n.line, n.text, n.muted)) {
                c.red shouldBe c.green
                c.green shouldBe c.blue
            }
            garbleColors(mode, GarbleColorSource.GARBLE).neutrals shouldBe n
            garbleColors(mode, GarbleColorSource.CUSTOM, GarblePresets.CORAL).neutrals shouldBe n
            garbleColors(mode, GarbleColorSource.SYSTEM, dynamic = Color.Red to Color.Blue)
                .neutrals shouldBe n
        }
    }

    "spec neutral values" {
        GarbleNeutrals.Dark.bg.toArgb() shouldBe 0xFF121212.toInt()
        GarbleNeutrals.Light.surface.toArgb() shouldBe 0xFFFFFFFF.toInt()
        GarbleNeutrals.Black.bg.toArgb() shouldBe 0xFF000000.toInt()
        GarbleNeutrals.Black.raised.toArgb() shouldBe 0xFF242424.toInt()
        GarbleNeutrals.Black.text shouldBe GarbleNeutrals.Dark.text
    }

    "depth order: tonal steps away from the background" {
        val d = GarbleNeutrals.Dark
        listOf(d.dim, d.bg, d.surface, d.card, d.raised).zipWithNext().forEach { (a, b) ->
            (b.red > a.red) shouldBe true
        }
        val l = GarbleNeutrals.Light
        listOf(l.surface, l.card, l.raised).zipWithNext().forEach { (a, b) ->
            (b.red < a.red) shouldBe true
        }
    }

    "the Garble default accent passes" {
        for (mode in modes) {
            val c = garbleColors(mode, GarbleColorSource.GARBLE)
            GarbleColorMath.accentPasses(c.accent, c.onAccent, c.neutrals) shouldBe true
            c.mark.size shouldBe 5
        }
        garbleColors(GarbleMode.DARK, GarbleColorSource.GARBLE).accent.toArgb() shouldBe
            0xFF63C8FF.toInt()
        garbleColors(GarbleMode.LIGHT, GarbleColorSource.GARBLE).onAccent shouldBe Color.White
    }

    "custom and system accents are fitted to the neutrals" {
        for (mode in modes) {
            for (seed in seeds) {
                for (c in listOf(
                    garbleColors(mode, GarbleColorSource.CUSTOM, seed),
                    garbleColors(mode, GarbleColorSource.SYSTEM, dynamic = Color(seed) to Color(seed)),
                )) {
                    GarbleColorMath.contrast(c.onAccent, c.accent) shouldBeGreaterThanOrEqual 4.5f
                    for (bg in listOf(c.bg, c.surface, c.card, c.raised)) {
                        GarbleColorMath.contrast(c.accent, bg) shouldBeGreaterThanOrEqual 3f
                    }
                    c.mark.size shouldBe 5
                    for (m in c.mark) {
                        GarbleColorMath.contrast(m, c.bg) shouldBeGreaterThanOrEqual 3f
                    }
                }
            }
        }
    }

    "a seed that already passes is kept" {
        // amber is light enough on dark neutrals, so the accent is the seed itself
        garbleColors(GarbleMode.DARK, GarbleColorSource.CUSTOM, GarblePresets.AMBER).accent
            .toArgb() shouldBe GarblePresets.AMBER
        // on light neutrals it has to darken, but keeps its hue
        val light = garbleColors(GarbleMode.LIGHT, GarbleColorSource.CUSTOM, GarblePresets.AMBER)
        light.accent.toArgb() shouldNotBe GarblePresets.AMBER
        GarbleColorMath.toHsl(light.accent).h shouldBe
            (GarbleColorMath.toHsl(Color(GarblePresets.AMBER)).h plusOrMinus 2f)
    }

    "custom mark goes from the seed to its hue + 40" {
        val c = garbleColors(GarbleMode.DARK, GarbleColorSource.CUSTOM, GarblePresets.MINT)
        val seedHue = GarbleColorMath.toHsl(Color(GarblePresets.MINT)).h
        GarbleColorMath.toHsl(c.mark.first()).h shouldBe (seedHue plusOrMinus 2f)
        GarbleColorMath.toHsl(c.mark.last()).h shouldBe ((seedHue + 40f) % 360f plusOrMinus 2f)
        // seed 0 means amber
        garbleColors(GarbleMode.DARK, GarbleColorSource.CUSTOM, 0) shouldBe
            garbleColors(GarbleMode.DARK, GarbleColorSource.CUSTOM, GarblePresets.AMBER)
    }

    "system mark blends primary to tertiary in 5 steps" {
        val primary = Color(0xFFB0C6FF)
        val tertiary = Color(0xFFFFB0C8)
        val c = garbleColors(GarbleMode.DARK, GarbleColorSource.SYSTEM, dynamic = primary to tertiary)
        c.source shouldBe GarbleColorSource.SYSTEM
        c.accent shouldBe primary // already passes on dark
        c.mark.first() shouldBe primary
        c.mark.last() shouldBe tertiary
        GarbleColorMath.ramp(Color.Black, Color.White).size shouldBe 5
        // without a wallpaper scheme (Android < 12) SYSTEM is Garble
        garbleColors(GarbleMode.DARK, GarbleColorSource.SYSTEM).source shouldBe GarbleColorSource.GARBLE
    }

    "resolving the stored source" {
        val d = GarColorSource.GAR_COLOR_SOURCE_DEFAULT
        GarbleColorSources.resolve(d, dynamicColors = false, sdk = 34) shouldBe GarbleColorSource.GARBLE
        GarbleColorSources.resolve(d, dynamicColors = true, sdk = 34) shouldBe GarbleColorSource.SYSTEM
        GarbleColorSources.resolve(d, dynamicColors = true, sdk = 30) shouldBe GarbleColorSource.GARBLE
        GarbleColorSources.resolve(GarColorSource.GAR_COLOR_SOURCE_SYSTEM, false, sdk = 28) shouldBe
            GarbleColorSource.GARBLE
        GarbleColorSources.resolve(GarColorSource.GAR_COLOR_SOURCE_CUSTOM, true, sdk = 34) shouldBe
            GarbleColorSource.CUSTOM
        GarbleColorSources.resolve(GarColorSource.GAR_COLOR_SOURCE_GARBLE, true, sdk = 34) shouldBe
            GarbleColorSource.GARBLE
    }

    "writing a pick keeps dynamic_colors coherent" {
        val system = UserSettings.newBuilder().setGarbleColors(GarbleColorSource.SYSTEM).build()
        system.garColorSource shouldBe GarColorSource.GAR_COLOR_SOURCE_SYSTEM
        system.dynamicColors shouldBe true
        val custom = system.toBuilder()
            .setGarbleColors(GarbleColorSource.CUSTOM, GarblePresets.CORAL).build()
        custom.dynamicColors shouldBe false
        GarbleColorSources.seed(custom) shouldBe GarblePresets.CORAL
        GarbleColorSources.seed(UserSettings.getDefaultInstance()) shouldBe GarblePresets.AMBER
        // an old settings file has neither field: Garble, or Material You if it was on
        UserSettings.getDefaultInstance().garColorSource shouldBe GarColorSource.GAR_COLOR_SOURCE_DEFAULT
    }

    "Material roles are neutral" {
        val c = garbleColors(GarbleMode.DARK, GarbleColorSource.GARBLE)
        val base = darkColorScheme()
        val s = c.toColorScheme(base)
        s.primary shouldBe c.accent
        s.onPrimary shouldBe c.onAccent
        s.primaryContainer shouldBe c.raised
        s.secondaryContainer shouldBe c.card
        s.tertiaryContainer shouldBe c.card
        s.surfaceTint shouldBe c.surface
        s.background shouldBe c.bg
        s.surfaceContainerLow shouldBe c.surface
        s.onSurfaceVariant shouldBe c.muted
        s.error shouldBe base.error
    }
})
