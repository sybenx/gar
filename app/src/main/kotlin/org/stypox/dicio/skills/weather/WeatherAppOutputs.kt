package org.stypox.dicio.skills.weather

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.showsOutput
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton
import org.stypox.dicio.gar.ui.garble.garbleSwitchColors
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.skills.contact_info.ContactSay
import org.stypox.dicio.util.getString

/** Why GAR opens the user's weather app instead of answering. */
enum class WeatherAppReason {
    /** Open-Meteo failed or timed out (the network may well be fine). */
    UNREACHABLE,

    /** The weather skill is off, as the user chose before. */
    SKILL_OFF,

    /** The user just said to keep the weather skill off. */
    STAYS_OFF,
}

/**
 * GAR (gar:weather). The user's weather app in place of a forecast GAR can't give: Open-Meteo
 * isn't answering, or the weather skill is off. One app (or the one picked): opened, "Open-Meteo
 * isn't answering, so I opened Breezy Weather."; several: "Which weather app?" once, remembered
 * with "Always use this app" ([WeatherAppPickOutput]); none, or "Don't open an app": the plain
 * message, or null for the skill-off paths, whose callers keep their own answer.
 */
object WeatherAppFallback {
    /**
     * @param locked the phone is locked: the pick isn't saved (a lasting choice is the owner's),
     * and the app comes up after unlocking (`GarLeavingContext`)
     */
    suspend fun output(access: WeatherAppAccess, reason: WeatherAppReason, locked: Boolean): SkillOutput? =
        when (val choice = WeatherApps.choose(access.setting(), access.installed())) {
            is WeatherAppChoice.App -> if (access.open(choice.app)) {
                WeatherAppOpenedOutput(choice.app, reason)
            } else {
                noApp(reason)
            }
            is WeatherAppChoice.Ask -> WeatherAppPickOutput(reason, choice.apps, remember = !locked, access)
            WeatherAppChoice.NoApp -> noApp(reason)
        }

    /** Without an app: "Open-Meteo isn't answering right now. Try again in a bit.", or null. */
    fun noApp(reason: WeatherAppReason): SkillOutput? = when (reason) {
        WeatherAppReason.UNREACHABLE -> WeatherMessageOutput { it.getString(R.string.gar_weather_no_connection) }
        WeatherAppReason.SKILL_OFF, WeatherAppReason.STAYS_OFF -> null
    }

    /** "Open-Meteo isn't answering, so I opened %1$s." */
    @StringRes
    fun openedText(reason: WeatherAppReason?): Int = when (reason) {
        WeatherAppReason.UNREACHABLE -> R.string.gar_weather_app_opened_unreachable
        WeatherAppReason.SKILL_OFF -> R.string.gar_weather_app_opened_off
        WeatherAppReason.STAYS_OFF -> R.string.gar_weather_app_opened_stays_off
        null -> R.string.gar_weather_app_opening
    }

    /** What "Which weather app?" starts with: "Open-Meteo isn't answering." */
    @StringRes
    fun leadText(reason: WeatherAppReason): Int = when (reason) {
        WeatherAppReason.UNREACHABLE -> R.string.gar_weather_app_lead_unreachable
        WeatherAppReason.SKILL_OFF -> R.string.gar_weather_app_lead_off
        WeatherAppReason.STAYS_OFF -> R.string.gar_weather_app_lead_stays_off
    }
}

/**
 * "Open-Meteo isn't answering, so I opened Breezy Weather.", or "Opening Clima." after
 * "Which weather app?" ([reason] null). When the pick was saved, "Saved. Change it in Settings,
 * Weather app." follows the action in speech and sits under the headline in small text, as in
 * `MapAppSavedOutput`.
 */
class WeatherAppOpenedOutput(
    val app: WeatherApp,
    val reason: WeatherAppReason?,
    val saved: Boolean = false,
) : SkillOutput {
    private fun openedText(ctx: SkillContext): String =
        ctx.getString(WeatherAppFallback.openedText(reason), app.label)

    // the action first: the sheet goes away as the app comes up, which can cut the rest off
    override fun getSpeechOutput(ctx: SkillContext): String = listOfNotNull(
        openedText(ctx),
        ctx.getString(R.string.gar_weather_app_saved).takeIf { saved },
    ).joinToString(" ")

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = openedText(ctx))
            if (saved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gar_weather_app_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Open-Meteo isn't answering. Which weather app? Breezy Weather or Clima?": one row per installed
 * weather app and, when the user hasn't picked one yet and the phone isn't locked ([remember]),
 * "Always use this app" (on by default), as in "Which maps app?" (`MapAppPickOutput`). A tap
 * sends the app's label as input, so taps and spoken answers ("breezy", "the second one") both
 * go through [AnswerSkill]. Rows and the switch are neutral: nothing here is the one primary
 * action.
 */
class WeatherAppPickOutput(
    val reason: WeatherAppReason,
    val apps: List<WeatherApp>,
    val remember: Boolean,
    private val access: WeatherAppAccess,
) : SkillOutput {

    var always by mutableStateOf(true)

    /** Set once the user answered, to disable the rows. */
    var answered by mutableStateOf(false)

    /** The row tapped, so its label sent as input picks that app, also among same-named apps. */
    private var tapped: WeatherApp? = null

    /** The app [input] picks: the tapped row's, or the one it names. */
    fun pick(input: String): WeatherApp? =
        tapped?.takeIf { it.label == input } ?: WeatherApps.pick(input, apps)

    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(WeatherAppFallback.leadText(reason)) +
        " " + ctx.getString(R.string.gar_weather_which_app, ContactSay.joinedOr(ctx, apps.map { it.label }))

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(AnswerSkill(this), GarCancelSkill(WeatherInfo) { answered = true }),
        )

    /** Opens [app]; saves it as the "Weather app" when GAR may and Always is on. */
    suspend fun choose(app: WeatherApp): SkillOutput {
        answered = true
        val save = remember && always
        if (save) {
            access.setSetting(app.pkg)
        }
        return if (access.open(app)) {
            WeatherAppOpenedOutput(app, reason = null, saved = save)
        } else {
            WeatherMessageOutput { it.getString(R.string.gar_weather_app_open_failed, app.label) }
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the rows only make sense while this card is the latest answer
        val log by skillEvaluator.state.collectAsState()
        val enabled = !answered &&
            log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer.showsOutput(this)

        Column {
            // one centered headline, as in "Which maps app?"; the rows below stay a left list
            Headline(
                text = stringResource(WeatherAppFallback.leadText(reason)) + " " +
                    stringResource(R.string.gar_weather_which_app_title),
            )
            Spacer(modifier = Modifier.height(4.dp))
            // apps with the same name ("Weather" twice) also show their package to tell them apart
            val sameName = apps.groupBy { it.label }.filterValues { it.size > 1 }.keys
            for (app in apps) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled, role = Role.Button) {
                            tapped = app
                            answered = true
                            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(app.label, 1.0f))))
                        }
                        .padding(vertical = if (app.label in sameName) 6.dp else 12.dp),
                ) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    if (app.label in sameName) {
                        Text(
                            app.pkg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (remember) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = always, enabled = enabled, role = Role.Switch) { always = it },
                ) {
                    Text(
                        stringResource(R.string.skill_places_always_use),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = always, onCheckedChange = null, enabled = enabled, colors = garbleSwitchColors())
                }
            }
        }
    }

    /** "Breezy", "the first one", or a tapped row's label. */
    private class AnswerSkill(private val output: WeatherAppPickOutput) :
        Skill<WeatherApp?>(WeatherInfo, Specificity.HIGH) {

        override fun score(ctx: SkillContext, input: String): Pair<Score, WeatherApp?> {
            val picked = output.pick(input)
            return Pair(if (picked == null) AlwaysWorstScore else AlwaysBestScore, picked)
        }

        override suspend fun generateOutput(ctx: SkillContext, inputData: WeatherApp?): SkillOutput =
            // AlwaysWorstScore is never chosen, so inputData isn't null here
            GarFollowUpOutput(withContext(Dispatchers.IO) { output.choose(inputData!!) })
    }
}

/**
 * The forecast card's "Open Breezy Weather" (neutral, never the accent): the "Weather app"
 * setting's app, or the only one; with several and none picked, "Open weather app" lists them.
 * Nothing without a weather app, or with "Don't open an app".
 */
@Composable
fun WeatherAppButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val access = remember(context) { runCatching { WeatherAppAccess.of(context) }.getOrNull() } ?: return
    val choice by produceState<WeatherAppChoice?>(null, access) {
        value = withContext(Dispatchers.IO) {
            runCatching { WeatherApps.choose(access.setting(), access.installed()) }.getOrNull()
        }
    }
    when (val current = choice) {
        is WeatherAppChoice.App -> GarbleSecondaryButton(onClick = { access.open(current.app) }, modifier = modifier) {
            Text(stringResource(R.string.gar_weather_open_app, current.app.label))
        }
        is WeatherAppChoice.Ask -> {
            var menu by remember { mutableStateOf(false) }
            Box(modifier = modifier) {
                GarbleSecondaryButton(onClick = { menu = true }) {
                    Text(stringResource(R.string.gar_weather_open_any_app))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (app in current.apps) {
                        DropdownMenuItem(
                            text = { Text(app.label) },
                            onClick = {
                                menu = false
                                access.open(app)
                            },
                        )
                    }
                }
            }
        }
        else -> {}
    }
}
