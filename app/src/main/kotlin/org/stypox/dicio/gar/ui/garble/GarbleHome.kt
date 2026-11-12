package org.stypox.dicio.gar.ui.garble

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.stypox.dicio.R
import org.stypox.dicio.gar.ui.GarChip
import org.stypox.dicio.gar.ui.GarChips
import org.stypox.dicio.gar.ui.GarMicState
import org.stypox.dicio.gar.ui.GarStarterChips
import org.stypox.dicio.gar.ui.GarStateLabel
import org.stypox.dicio.io.input.VoiceActivity

/**
 * Home's top bar: [navigationIcon] (the drawer, with About and the speech popup), the logo mark
 * and the "garble" wordmark, and a settings button at the right. Neutral bg, no tint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarbleHomeTopBar(
    navigationIcon: @Composable () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val g = LocalGarbleColors.current
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GarbleMark(state = GarbleMarkState.Logo, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = GarbleType.WORDMARK,
                    style = GarbleType.wordmark(28.sp),
                    color = g.text,
                    maxLines = 1,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        navigationIcon = navigationIcon,
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = g.bg,
            scrolledContainerColor = g.bg,
            titleContentColor = g.text,
            navigationIconContentColor = g.text,
            actionIconContentColor = g.text,
        ),
    )
}

/** A starter card's neutral icon and title, by the skill it's for ([GarStarterChips.skillOf]). */
object GarbleStarters {
    fun icon(skill: String?): ImageVector = when (skill) {
        "weather" -> Icons.Outlined.WbSunny
        "current_time" -> Icons.Outlined.Schedule
        "world_time" -> Icons.Outlined.Public
        "date" -> Icons.Outlined.CalendarToday
        "battery" -> Icons.Outlined.BatteryFull
        "calculator" -> Icons.Outlined.Calculate
        "units" -> Icons.Outlined.Straighten
        "timer" -> Icons.Outlined.Timer
        else -> Icons.Outlined.ChatBubbleOutline
    }

    fun title(skill: String?): Int = when (skill) {
        "weather" -> R.string.gar_home_starter_weather
        "current_time" -> R.string.gar_home_starter_time
        "world_time" -> R.string.gar_home_starter_world_time
        "date" -> R.string.gar_home_starter_date
        "battery" -> R.string.gar_home_starter_battery
        "calculator" -> R.string.gar_home_starter_calculator
        "units" -> R.string.gar_home_starter_units
        "timer" -> R.string.gar_home_starter_timer
        else -> R.string.gar_home_starter_other
    }
}

/**
 * The empty home: "Try saying", the offline line and one card per starter (neutral icon in a
 * raised circle, title, the words it sends). A tap sends the starter like a chip; taps are
 * ignored for [GarChips.TAP_GUARD_MILLIS] after the starters appear or change.
 */
@Composable
fun GarbleTrySaying(
    starters: List<GarChip>,
    onStarter: (GarChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGarbleColors.current
    val context = LocalContext.current
    val skills = remember(starters, context) {
        starters.map { GarStarterChips.skillOf(context, it) }
    }
    val shownAt = remember { mutableLongStateOf(0L) }
    LaunchedEffect(starters) { shownAt.longValue = SystemClock.uptimeMillis() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.gar_home_try_saying),
            fontSize = 30.sp,
            lineHeight = 36.sp,
            color = g.text,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.gar_home_try_saying_offline),
            fontSize = 14.sp,
            color = g.muted,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            starters.forEachIndexed { i, starter ->
                GarbleStarterCard(
                    icon = GarbleStarters.icon(skills.getOrNull(i)),
                    title = stringResource(GarbleStarters.title(skills.getOrNull(i))),
                    example = stringResource(R.string.gar_home_try_example, starter.label),
                    onClick = {
                        if (SystemClock.uptimeMillis() - shownAt.longValue >=
                            GarChips.TAP_GUARD_MILLIS
                        ) {
                            onStarter(starter)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GarbleStarterCard(
    icon: ImageVector,
    title: String,
    example: String,
    onClick: () -> Unit,
) {
    val g = LocalGarbleColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .garbleCard(GarbleShapes.StarterCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .garbleRaised(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = g.text,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = g.text)
            Text(text = example, fontSize = 15.sp, color = g.muted)
        }
    }
}

/** What a tap on home's mic does in [state], for screen readers. */
@Composable
private fun garbleMicDescription(state: GarMicState): String = when (state) {
    is GarMicState.Listening, GarMicState.Starting -> stringResource(R.string.gar_mic_stop_listening)
    GarMicState.Transcribing -> stringResource(R.string.gar_mic_cancel_transcription)
    GarMicState.Speaking -> stringResource(R.string.gar_mic_stop_speaking)
    else -> stringResource(R.string.start_listening)
}

/**
 * Home's controls: the mic's state in muted text (nothing while idle), then the 84 dp mic button
 * centered with the 52 dp keyboard button beside it. While the mic is busy the button shows the
 * mark for its state (dots while listening, the wave while hearing or thinking, upright while
 * speaking). The voice flag is derived, so the level's frames never recompose this.
 */
@Composable
fun GarbleHomeControls(
    micState: GarMicState,
    voiceActivity: State<VoiceActivity?>,
    onMic: () -> Unit,
    onType: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGarbleColors.current
    val voice by remember(voiceActivity) {
        derivedStateOf { voiceActivity.value?.speaking == true }
    }
    val active = micState != GarMicState.Idle && micState !is GarMicState.Unavailable

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 20.dp)
                .padding(horizontal = 16.dp),
        ) {
            if (micState != GarMicState.Idle) {
                CompositionLocalProvider(LocalContentColor provides g.muted) {
                    GarStateLabel(
                        state = micState,
                        announce = micState !is GarMicState.Listening &&
                            micState != GarMicState.Starting,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(modifier = Modifier.size(KEYBOARD_SIZE)) // balances the keyboard button
            GarbleMicButton(
                onClick = onMic,
                size = GarbleMicButtonDefaults.HomeSize,
                active = active,
                // answered: the collapse after an instant answer ends upright, not on the logo
                markState = garbleMarkStateFor(micState, voice, answered = true),
                contentDescription = garbleMicDescription(micState),
            )
            GarbleKeyboardButton(onClick = onType)
        }
    }
}

/** The raised keyboard circle next to the mic (52 dp on home, 48 dp in the sheet). */
@Composable
fun GarbleKeyboardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = KEYBOARD_SIZE,
) {
    val g = LocalGarbleColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .garbleRaised()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Outlined.Keyboard,
            contentDescription = stringResource(R.string.gar_mic_type),
            tint = g.text,
            modifier = Modifier.size(24.dp),
        )
    }
}

private val KEYBOARD_SIZE = 52.dp
