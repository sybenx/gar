package org.stypox.dicio.skills.places

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
import dagger.hilt.android.EntryPointAccessors
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.ask.isLatest
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.parseOptionIndex
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.skills.contact_info.ContactSay
import org.stypox.dicio.util.getString

/** What an app in a list does with a place, one short line ("Starts the route"). */
@StringRes
fun MapApp.doesText(): Int = when {
    kind == MapAppKind.GMAPS_WEB -> R.string.gar_navigate_with_web
    point == RouteSupport.ROUTE -> R.string.gar_navigate_with_route
    point == RouteSupport.ROUTE_FROM_FIX -> R.string.gar_navigate_with_drawn
    else -> R.string.gar_navigate_with_pin
}

/**
 * GAR (gar:places). "Which maps app?", GAR's own picker, like Android's "Open with": one row per
 * installed maps app with what it does from GAR, and "Always use this app" (on by default) when
 * the user hasn't chosen one yet ([remember]). A tap does the action at once and, with Always on,
 * saves the app as "Navigate with". A spoken answer ("OsmAnd", "the second one") reads the switch
 * too, so it saves unless the user turned Always off. A save adds "Saved. Change it in Settings,
 * Navigate with." to what follows ([MapAppSavedOutput]). With "Ask every time" ([remember] false)
 * nothing is saved and the switch isn't shown.
 * The card names no place, so it is the same over the lock screen.
 */
class MapAppPickOutput(
    private val info: SkillInfo,
    val apps: List<MapApp>,
    val remember: Boolean,
    private val save: suspend (SkillContext, MapApp) -> Unit,
    private val onPick: suspend (SkillContext, MapApp) -> SkillOutput,
) : SkillOutput {

    /** The card's "Always use this app" switch. */
    var always by mutableStateOf(true)

    /** Set once the user answered, to disable the rows. */
    var answered by mutableStateOf(false)

    /** The row tapped, so its label sent as input picks that app. */
    private var tapped: MapApp? = null

    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_places_which_app, ContactSay.joinedOr(ctx, apps.map { it.label }))

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(AnswerSkill(this), GarCancelSkill(info) { answered = true }),
        )

    /** The app [input] names ("OsmAnd", "osm and", "use vela", "the first one"), or null. */
    fun pick(input: String): MapApp? {
        tapped?.takeIf { it.label == input }?.let { return it }
        if (isCancelPhrase(input) || normalizeAnswer(input).isEmpty()) {
            return null
        }
        parseOptionIndex(input, apps.size)?.let { return apps[it] }
        val words = normalizeAnswer(input).split(" ").filter { it !in LEADS }.ifEmpty { return null }
        val names = apps.flatMap { app -> MapAppChoice.spokenNames(app).map { Pair(app, it) } }
        return when (val match = NameMatcher.match(words.joinToString(" "), names, { it.second })) {
            is Resolution.Resolved -> match.value.first
            // several spellings of one app are still that app
            is Resolution.Ambiguous -> match.options.map { it.first }.distinct().singleOrNull()
            else -> null
        }
    }

    /**
     * Runs the pick of [app], tapped or spoken: saved when GAR asked for the first time and Always
     * is on, then the action, which then says where to change it.
     */
    suspend fun choose(ctx: SkillContext, app: MapApp): SkillOutput {
        answered = true
        if (!remember || !always) {
            return onPick(ctx, app)
        }
        save(ctx, app)
        return MapAppSavedOutput(onPick(ctx, app))
    }

    /**
     * Whether the rows and the switch take taps: while [latest], the log's last answer, is this
     * card, also wrapped after "Show it on the map?" or "Which address?" ([isLatest]).
     */
    fun enabled(latest: SkillOutput?): Boolean = isLatest(latest, this) && !answered

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the rows only make sense while this card is the latest answer
        val log by skillEvaluator.state.collectAsState()
        val enabled = enabled(log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer)

        Column {
            Headline(text = stringResource(R.string.skill_places_which_maps_app))
            Spacer(modifier = Modifier.height(4.dp))
            for (app in apps) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled, role = Role.Button) {
                            tapped = app
                            answered = true
                            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(app.label, 1.0f))))
                        }
                        .padding(vertical = 6.dp),
                ) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(app.doesText()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    Switch(checked = always, onCheckedChange = null, enabled = enabled)
                }
            }
        }
    }

    /** "OsmAnd", "the first one", or a tapped row's label. */
    private class AnswerSkill(private val output: MapAppPickOutput) :
        Skill<MapApp?>(output.info, Specificity.HIGH) {

        override fun score(ctx: SkillContext, input: String): Pair<Score, MapApp?> {
            val picked = output.pick(input)
            return Pair(if (picked == null) AlwaysWorstScore else AlwaysBestScore, picked)
        }

        override suspend fun generateOutput(ctx: SkillContext, inputData: MapApp?): SkillOutput =
            // AlwaysWorstScore is never chosen, so inputData isn't null here
            GarFollowUpOutput(output.choose(ctx, inputData!!))
    }

    private companion object {
        /** Words around a name: "use vela", "with osmand please", "open it in comaps". */
        val LEADS = setOf("use", "with", "in", "on", "open", "it", "please", "just", "go", "let's", "lets")
    }
}

/**
 * GAR (gar:places). [output], the picked app's action, after "Which maps app?" saved the app: says
 * once where to change it ("Saved. Change it in Settings, Navigate with."), before the action's own
 * speech and under its card. [output]'s plan stays, so its own questions still follow.
 */
class MapAppSavedOutput(val output: SkillOutput) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        listOf(ctx.getString(R.string.skill_places_app_saved), output.getSpeechOutput(ctx))
            .filter { it.isNotBlank() }
            .joinToString(" ")

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan = output.getInteractionPlan(ctx)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            output.GraphicalOutput(ctx)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.skill_places_app_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
