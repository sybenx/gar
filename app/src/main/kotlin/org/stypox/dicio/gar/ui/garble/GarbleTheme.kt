package org.stypox.dicio.gar.ui.garble

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.stypox.dicio.settings.datastore.GarColorSource
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.UserSettings

/** Reading and writing the color source in UserSettings (gar_color_source, gar_seed_color). */
object GarbleColorSources {
    /**
     * The source the user gets. DEFAULT (never picked) keeps an old Material You choice: SYSTEM if
     * dynamic_colors is on, else GARBLE. SYSTEM needs Android 12 ([sdk] >= 31).
     */
    fun resolve(
        source: GarColorSource,
        dynamicColors: Boolean,
        sdk: Int = Build.VERSION.SDK_INT,
    ): GarbleColorSource {
        val resolved = when (source) {
            GarColorSource.GAR_COLOR_SOURCE_GARBLE -> GarbleColorSource.GARBLE
            GarColorSource.GAR_COLOR_SOURCE_SYSTEM -> GarbleColorSource.SYSTEM
            GarColorSource.GAR_COLOR_SOURCE_CUSTOM -> GarbleColorSource.CUSTOM
            GarColorSource.GAR_COLOR_SOURCE_DEFAULT,
            GarColorSource.UNRECOGNIZED ->
                if (dynamicColors) GarbleColorSource.SYSTEM else GarbleColorSource.GARBLE
        }
        return if (resolved == GarbleColorSource.SYSTEM && sdk < Build.VERSION_CODES.S) {
            GarbleColorSource.GARBLE
        } else {
            resolved
        }
    }

    fun resolve(settings: UserSettings): GarbleColorSource =
        resolve(settings.garColorSource, settings.dynamicColors)

    /** The seed CUSTOM uses (the stored one, or amber). */
    fun seed(settings: UserSettings): Int =
        settings.garSeedColor.takeIf { it != 0 } ?: GarblePresets.DEFAULT_SEED

    fun toProto(source: GarbleColorSource): GarColorSource = when (source) {
        GarbleColorSource.GARBLE -> GarColorSource.GAR_COLOR_SOURCE_GARBLE
        GarbleColorSource.SYSTEM -> GarColorSource.GAR_COLOR_SOURCE_SYSTEM
        GarbleColorSource.CUSTOM -> GarColorSource.GAR_COLOR_SOURCE_CUSTOM
    }
}

/**
 * Writes an explicit pick; dynamic_colors follows (SYSTEM) so Dicio's own row stays coherent.
 * Use it inside `dataStore.updateData { it.toBuilder().setGarbleColors(...).build() }`.
 */
fun UserSettings.Builder.setGarbleColors(
    source: GarbleColorSource,
    seed: Int = garSeedColor,
): UserSettings.Builder = setGarColorSource(GarbleColorSources.toProto(source))
    .setGarSeedColor(seed)
    .setDynamicColors(source == GarbleColorSource.SYSTEM)

/** What AppTheme needs from UserSettings; BaseActivity collects this (equality = no re-theme). */
data class GarThemeSettings(
    val theme: Theme,
    val dynamicColors: Boolean,
    val colorSource: GarColorSource,
    val seedColor: Int,
) {
    companion object {
        fun of(settings: UserSettings) = GarThemeSettings(
            theme = settings.theme,
            dynamicColors = settings.dynamicColors,
            colorSource = settings.garColorSource,
            seedColor = settings.garSeedColor,
        )
    }
}

/** AppTheme's palette; SYSTEM reads the wallpaper scheme, taking only primary and tertiary. */
@Composable
fun rememberGarbleColors(
    mode: GarbleMode,
    colorSource: GarColorSource,
    seedColor: Int,
    dynamicColors: Boolean,
): GarbleColors {
    val source = GarbleColorSources.resolve(colorSource, dynamicColors)
    val context = LocalContext.current
    return remember(mode, source, seedColor, context) {
        val dynamic = if (source == GarbleColorSource.SYSTEM &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            val scheme = if (mode == GarbleMode.LIGHT) {
                dynamicLightColorScheme(context)
            } else {
                dynamicDarkColorScheme(context)
            }
            scheme.primary to scheme.tertiary
        } else {
            null
        }
        garbleColors(mode, source, seedColor, dynamic)
    }
}

/**
 * Every Material role made neutral, so Dicio's own screens (MessageCards on secondaryContainer,
 * FABs on primaryContainer, tonal elevation through surfaceTint) show Garble grays; only primary
 * is the accent. The error roles come from [base].
 */
fun GarbleColors.toColorScheme(base: ColorScheme): ColorScheme = base.copy(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = raised,
    onPrimaryContainer = text,
    inversePrimary = accent,
    secondary = text,
    onSecondary = bg,
    secondaryContainer = card,
    onSecondaryContainer = text,
    tertiary = accent,
    onTertiary = onAccent,
    tertiaryContainer = card,
    onTertiaryContainer = text,
    background = bg,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = card,
    onSurfaceVariant = muted,
    surfaceTint = surface, // no tonal tint at any elevation
    inverseSurface = text,
    inverseOnSurface = bg,
    outline = line,
    outlineVariant = line,
    scrim = Color.Black,
    surfaceBright = raised,
    surfaceDim = dim,
    surfaceContainerLowest = bg,
    surfaceContainerLow = surface,
    surfaceContainer = card,
    surfaceContainerHigh = raised,
    surfaceContainerHighest = raised,
)
