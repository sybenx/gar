package org.stypox.dicio.gar.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.stypox.dicio.R
import org.stypox.dicio.gar.ui.garble.GarbleColorMath
import org.stypox.dicio.gar.ui.garble.GarbleColorSource
import org.stypox.dicio.gar.ui.garble.GarbleColorSources
import org.stypox.dicio.gar.ui.garble.GarbleMark
import org.stypox.dicio.gar.ui.garble.GarbleMarkState
import org.stypox.dicio.gar.ui.garble.GarblePresets
import org.stypox.dicio.gar.ui.garble.GarbleShapes
import org.stypox.dicio.gar.ui.garble.GarbleType
import org.stypox.dicio.gar.ui.garble.LocalGarbleColors
import org.stypox.dicio.gar.ui.garble.garbleCard
import org.stypox.dicio.gar.ui.garble.garbleRaised
import org.stypox.dicio.gar.ui.garble.garbleSwitchColors
import org.stypox.dicio.gar.ui.garble.setGarbleColors
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst
import javax.inject.Inject

/** Navigation route of [GarAppearanceScreen]. */
@Serializable
object GarAppearance

/** The entry in Dicio's main settings that opens [GarAppearanceScreen]. */
@Composable
fun GarAppearanceItem(onClick: () -> Unit) {
    SettingsItem(
        title = stringResource(R.string.gar_appearance_title),
        icon = Icons.Outlined.Palette,
        description = stringResource(R.string.gar_appearance_summary),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Reads and writes the theme and the color source in UserSettings. */
@HiltViewModel
class GarAppearanceViewModel @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = dataStore.data
        .toStateFlowDistinctBlockingFirst(viewModelScope)

    fun setColors(source: GarbleColorSource, seed: Int) {
        viewModelScope.launch {
            dataStore.updateData { it.toBuilder().setGarbleColors(source, seed).build() }
        }
    }

    fun setTheme(theme: Theme) {
        viewModelScope.launch {
            dataStore.updateData { it.toBuilder().setTheme(theme).build() }
        }
    }
}

/**
 * Appearance: a live preview card (the whole app re-themes as soon as a choice is written), the
 * color source (Garble, the wallpaper on Android 12+, or one picked color: amber, coral, mint or
 * any from the picker) and the theme (System, Light, Dark, Black).
 */
@Composable
fun GarAppearanceScreen(
    navigationIcon: @Composable () -> Unit,
    viewModel: GarAppearanceViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val g = LocalGarbleColors.current
    val source = GarbleColorSources.resolve(settings)
    val seed = GarbleColorSources.seed(settings)
    var picking by rememberSaveable { mutableStateOf(false) }

    Scaffold(containerColor = g.bg) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = 4.dp, end = 16.dp),
            ) {
                navigationIcon()
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.gar_appearance_title),
                    fontSize = 28.sp,
                    color = g.text,
                    modifier = Modifier.semantics { heading() },
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                PreviewCard()

                Spacer(modifier = Modifier.height(28.dp))
                Overline(stringResource(R.string.gar_appearance_colors))
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.selectableGroup(),
                ) {
                    ColorSourceRow(
                        title = stringResource(R.string.gar_appearance_garble),
                        summary = stringResource(R.string.gar_appearance_garble_summary),
                        selected = source == GarbleColorSource.GARBLE,
                        onClick = { viewModel.setColors(GarbleColorSource.GARBLE, settings.garSeedColor) },
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ColorSourceRow(
                            title = stringResource(R.string.gar_appearance_system),
                            summary = stringResource(R.string.gar_appearance_system_summary),
                            selected = source == GarbleColorSource.SYSTEM,
                            onClick = {
                                viewModel.setColors(GarbleColorSource.SYSTEM, settings.garSeedColor)
                            },
                        )
                    }
                    ColorSourceRow(
                        title = stringResource(R.string.gar_appearance_custom),
                        summary = stringResource(R.string.gar_appearance_custom_summary),
                        selected = source == GarbleColorSource.CUSTOM,
                        onClick = { viewModel.setColors(GarbleColorSource.CUSTOM, seed) },
                    ) {
                        SwatchRow(
                            selected = seed.takeIf { source == GarbleColorSource.CUSTOM },
                            stored = seed,
                            onSwatch = { viewModel.setColors(GarbleColorSource.CUSTOM, it) },
                            onMore = { picking = true },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                Overline(stringResource(R.string.gar_appearance_theme))
                ThemeSegments(
                    theme = settings.theme,
                    onTheme = viewModel::setTheme,
                )
            }
        }
    }

    if (picking) {
        GarColorPickerDialog(
            initial = seed,
            onPick = {
                picking = false
                viewModel.setColors(GarbleColorSource.CUSTOM, it)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Overline(text: String) {
    Text(
        text = text.uppercase(),
        style = GarbleType.Overline,
        color = LocalGarbleColors.current.muted,
        modifier = Modifier
            .padding(start = 4.dp, bottom = 10.dp)
            .semantics { heading() },
    )
}

/** The mark upright, the wordmark and a sample of the one accent action, in today's colors. */
@Composable
private fun PreviewCard() {
    val g = LocalGarbleColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .garbleCard()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GarbleMark(state = GarbleMarkState.Understood, modifier = Modifier.size(44.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = GarbleType.WORDMARK, style = GarbleType.wordmark(28.sp), color = g.text)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .background(g.accent, GarbleShapes.Pill)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.gar_appearance_main_action),
                color = g.onAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** One color source; the selected row gets the card fill and shadow. [extra] goes under it. */
@Composable
private fun ColorSourceRow(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val g = LocalGarbleColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.garbleCard(GarbleShapes.StarterCard)
                } else {
                    Modifier
                }
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = g.text)
                Text(text = summary, fontSize = 14.sp, color = g.muted)
            }
        }
        if (extra != null) {
            Box(modifier = Modifier.padding(start = 56.dp, end = 12.dp, bottom = 14.dp)) {
                extra()
            }
        }
    }
}

/**
 * Amber, coral and mint, the picked color when it's none of them, and a raised "+" for the
 * picker. [selected] is the seed in use (null when the source isn't CUSTOM); a selected swatch
 * gets a 2 dp ring in the text color.
 */
@Composable
private fun SwatchRow(
    selected: Int?,
    stored: Int,
    onSwatch: (Int) -> Unit,
    onMore: () -> Unit,
) {
    val g = LocalGarbleColors.current
    val names = mapOf(
        GarblePresets.AMBER to stringResource(R.string.gar_appearance_amber),
        GarblePresets.CORAL to stringResource(R.string.gar_appearance_coral),
        GarblePresets.MINT to stringResource(R.string.gar_appearance_mint),
    )
    val swatches = GarblePresets.SEEDS + listOfNotNull(stored.takeIf { it !in GarblePresets.SEEDS })
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.selectableGroup(),
    ) {
        for (swatch in swatches) {
            val isSelected = swatch == selected
            val label = names[swatch]
                ?: stringResource(R.string.gar_appearance_your_color, hexOf(swatch))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .then(
                        if (isSelected) Modifier.border(2.dp, g.text, CircleShape) else Modifier
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSwatch(swatch) }
                    .semantics { contentDescription = label },
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(swatch), CircleShape),
                )
            }
        }
        // the whole 52 dp box takes the tap (a 48 dp+ target); the 44 dp raised circle is visual
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onMore),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .garbleRaised(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.gar_appearance_more_colors),
                    tint = g.text,
                )
            }
        }
    }
}

/** System, Light, Dark, Black on the card fill; the selected segment is raised and neutral. */
@Composable
private fun ThemeSegments(theme: Theme, onTheme: (Theme) -> Unit) {
    val g = LocalGarbleColors.current
    val options = listOf(
        Theme.THEME_SYSTEM to R.string.gar_appearance_theme_system,
        Theme.THEME_LIGHT to R.string.gar_appearance_theme_light,
        Theme.THEME_DARK to R.string.gar_appearance_theme_dark,
        Theme.THEME_BLACK to R.string.gar_appearance_theme_black,
    )
    // System with a black dark mode (Dicio's own choice) shows as System plus the switch below
    val blackInDark = theme == Theme.THEME_SYSTEM_DARK_BLACK
    val shown = when (theme) {
        Theme.THEME_SYSTEM_DARK_BLACK, Theme.UNRECOGNIZED -> Theme.THEME_SYSTEM
        else -> theme
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .garbleCard(GarbleShapes.Pill)
                .padding(4.dp)
                .selectableGroup(),
        ) {
            for ((option, label) in options) {
                val selected = option == shown
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .then(if (selected) Modifier.garbleRaised() else Modifier.clip(GarbleShapes.Pill))
                        .selectable(selected = selected, role = Role.RadioButton) {
                            // tapping System again keeps the black variant instead of dropping it
                            onTheme(
                                if (option == Theme.THEME_SYSTEM && blackInDark) {
                                    Theme.THEME_SYSTEM_DARK_BLACK
                                } else {
                                    option
                                }
                            )
                        },
                ) {
                    Text(
                        text = stringResource(label),
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) g.text else g.muted,
                    )
                }
            }
        }
        if (shown == Theme.THEME_SYSTEM) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(GarbleShapes.Pill)
                    .toggleable(value = blackInDark, role = Role.Switch) { black ->
                        onTheme(if (black) Theme.THEME_SYSTEM_DARK_BLACK else Theme.THEME_SYSTEM)
                    }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.gar_appearance_theme_black_in_dark),
                    fontSize = 15.sp,
                    color = g.text,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = blackInDark,
                    onCheckedChange = null,
                    colors = garbleSwitchColors(),
                )
            }
        }
    }
}

/**
 * Any color: hue, saturation and lightness sliders, or a hex code; the swatch on top shows it.
 * The theme fits the accent's lightness for contrast, so any pick stays readable.
 */
@Composable
private fun GarColorPickerDialog(
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = LocalGarbleColors.current
    val start = remember(initial) { GarbleColorMath.toHsl(Color(initial)) }
    var hue by rememberSaveable { mutableFloatStateOf(start.h) }
    var saturation by rememberSaveable { mutableFloatStateOf(start.s) }
    var lightness by rememberSaveable { mutableFloatStateOf(start.l) }
    val color = GarbleColorMath.fromHsl(GarbleColorMath.Hsl(hue, saturation, lightness))
    var hex by rememberSaveable { mutableStateOf(hexOf(color.toArgb())) }

    fun fromSliders(h: Float = hue, s: Float = saturation, l: Float = lightness) {
        hue = h
        saturation = s
        lightness = l
        hex = hexOf(GarbleColorMath.fromHsl(GarbleColorMath.Hsl(h, s, l)).toArgb())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surface,
        titleContentColor = g.text,
        textContentColor = g.text,
        title = { Text(stringResource(R.string.gar_appearance_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                        .size(64.dp)
                        .background(color, CircleShape),
                )
                PickerSlider(
                    label = stringResource(R.string.gar_appearance_picker_hue),
                    value = hue,
                    range = 0f..359f,
                    onChange = { fromSliders(h = it) },
                )
                PickerSlider(
                    label = stringResource(R.string.gar_appearance_picker_saturation),
                    value = saturation,
                    range = 0f..1f,
                    onChange = { fromSliders(s = it) },
                )
                PickerSlider(
                    label = stringResource(R.string.gar_appearance_picker_lightness),
                    value = lightness,
                    range = 0.15f..0.85f,
                    onChange = { fromSliders(l = it) },
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text.take(7)
                        parseHex(hex)?.let { argb ->
                            val hsl = GarbleColorMath.toHsl(Color(argb))
                            hue = hsl.h
                            saturation = hsl.s
                            lightness = hsl.l.coerceIn(0.15f, 0.85f)
                        }
                    },
                    label = { Text(stringResource(R.string.gar_appearance_picker_hex)) },
                    singleLine = true,
                    isError = parseHex(hex) == null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(color.toArgb()) }) {
                Text(stringResource(R.string.gar_appearance_picker_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.gar_appearance_picker_cancel), color = g.text)
            }
        },
    )
}

@Composable
private fun PickerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(text = label, fontSize = 13.sp, color = LocalGarbleColors.current.muted)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** "#RRGGBB" for an ARGB int. */
internal fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** An opaque ARGB int from "#RRGGBB" or "RRGGBB", else null. */
internal fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) {
        return null
    }
    return digits.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}
