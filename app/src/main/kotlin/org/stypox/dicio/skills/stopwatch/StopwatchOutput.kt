package org.stypox.dicio.skills.stopwatch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString
import java.text.DecimalFormatSymbols
import java.time.Duration
import org.dicio.numbers.unit.Duration as DicioDuration

/**
 * The answer to a stopwatch action, with a card that shows the stopwatch live: its time ticks
 * while it runs and the card is on screen, and its laps follow the stopwatch.
 */
class StopwatchOutput(
    val event: StopwatchEvent,
    private val state: StateFlow<StopwatchState>,
    private val clock: StopwatchClock,
) : SkillOutput {

    override fun getSpeechOutput(ctx: SkillContext): String = answer(ctx, speech = true)

    /** The answer to [event], with times spoken ("one minute") or written ("1:00.0"). */
    fun answer(ctx: SkillContext, speech: Boolean): String {
        fun time(millis: Long) = if (speech) spokenDuration(ctx, millis) else formatStopwatch(millis)
        return when (val e = event) {
            StopwatchEvent.Started -> ctx.getString(R.string.skill_stopwatch_started)
            is StopwatchEvent.Resumed -> ctx.getString(R.string.skill_stopwatch_resumed, time(e.millis))
            is StopwatchEvent.AlreadyRunning ->
                ctx.getString(R.string.skill_stopwatch_already_running, time(e.millis))
            is StopwatchEvent.Stopped -> ctx.getString(R.string.skill_stopwatch_stopped, time(e.millis))
            is StopwatchEvent.NotRunning -> if (e.millis == 0L) {
                ctx.getString(R.string.skill_stopwatch_not_running)
            } else {
                ctx.getString(R.string.skill_stopwatch_stopped_at, time(e.millis))
            }
            StopwatchEvent.Reset -> ctx.getString(R.string.skill_stopwatch_reset)
            StopwatchEvent.AlreadyReset -> ctx.getString(R.string.skill_stopwatch_already_reset)
            is StopwatchEvent.Lap ->
                ctx.getString(R.string.skill_stopwatch_lap, e.number, time(e.lapMillis))
            is StopwatchEvent.Elapsed -> if (e.running) {
                ctx.getString(R.string.skill_stopwatch_elapsed, time(e.millis))
            } else {
                ctx.getString(R.string.skill_stopwatch_stopped_at, time(e.millis))
            }
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val current by state.collectAsState()
        val millis = rememberElapsedMillis(current, clock)
        val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }

        Column {
            Text(
                text = formatStopwatch(millis, separator),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.displayMedium.merge(TABULAR_NUMBERS),
                modifier = Modifier.fillMaxWidth(),
            )
            Subtitle(text = answer(ctx, speech = false))

            val laps = current.lapDurations()
            if (laps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                // newest first, like stopwatch apps
                for (i in laps.indices.reversed().take(MAX_LAPS_SHOWN)) {
                    LapRow(
                        label = ctx.getString(R.string.skill_stopwatch_lap_label, i + 1),
                        lap = formatStopwatch(laps[i], separator),
                        total = formatStopwatch(current.lapTotals[i], separator),
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_LAPS_SHOWN = 10

        /** Whole seconds as dicio-numbers says them ("one minute ten seconds"). */
        fun spokenDuration(ctx: SkillContext, millis: Long): String {
            val seconds = millis / 1000
            if (seconds == 0L) {
                return ctx.getString(R.string.skill_stopwatch_under_a_second)
            }
            return ctx.parserFormatter
                ?.niceDuration(DicioDuration(Duration.ofSeconds(seconds)))
                ?.speech(true)
                ?.get()
                ?: formatStopwatch(millis)
        }
    }
}

private const val TICK_MILLIS = 50L

/** Digits of equal width, so the ticking time doesn't jitter. */
private val TABULAR_NUMBERS = TextStyle(fontFeatureSettings = "tnum")

/** The time of [state], updated every [TICK_MILLIS] while it runs and frames are drawn. */
@Composable
private fun rememberElapsedMillis(state: StopwatchState, clock: StopwatchClock): Long {
    var millis by remember(state) { mutableLongStateOf(state.elapsedMillis(clock.now())) }
    if (state.isRunning) {
        LaunchedEffect(state) {
            while (true) {
                // waits while the card isn't drawn (app in the background), so it doesn't tick then
                withFrameMillis { }
                millis = state.elapsedMillis(clock.now())
                delay(TICK_MILLIS)
            }
        }
    }
    return millis
}

@Composable
private fun LapRow(label: String, lap: String, total: String) {
    val style = MaterialTheme.typography.bodyMedium.merge(TABULAR_NUMBERS)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = style, modifier = Modifier.weight(1f))
        Text(text = lap, style = style, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(text = total, style = style, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
