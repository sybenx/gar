package org.stypox.dicio.skills.places

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.call.GarCancelSkill
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.conversation.GarOpenAnswer
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString
import kotlin.math.abs
import kotlin.math.roundToInt

/** "home", "work": what GAR calls the place in its answers and on the map's pin. */
@get:StringRes
val SavedPlaceKind.label: Int
    get() = when (this) {
        SavedPlaceKind.HOME -> R.string.skill_places_home
        SavedPlaceKind.WORK -> R.string.skill_places_work
    }

/** "40.7128 north, 74.006 west", for speech and the card. */
fun spokenCoordinates(ctx: SkillContext, point: GeoPoint): String = ctx.getString(
    R.string.skill_places_coordinates,
    MapLinks.format(abs(point.latitude)),
    ctx.getString(if (point.latitude >= 0) R.string.skill_places_north else R.string.skill_places_south),
    MapLinks.format(abs(point.longitude)),
    ctx.getString(if (point.longitude >= 0) R.string.skill_places_east else R.string.skill_places_west),
)

/** Why the places skills couldn't do what was asked, or that a settings page opened. */
enum class PlacesProblem(@StringRes val speech: Int, @StringRes val detail: Int? = null) {
    NO_ACCESS(R.string.skill_places_no_access, R.string.skill_places_no_access_detail),
    APPROXIMATE(R.string.skill_places_approximate_save, R.string.skill_places_approximate_save_detail),
    LOCATION_OFF(R.string.skill_places_location_off),
    NO_FIX(R.string.skill_places_no_fix),
    PAGE_OPENED(R.string.skill_places_page_opened),
    PAGE_FAILED(R.string.skill_places_page_failed),
    NO_MAPS_APP(R.string.skill_places_no_maps_app),
}

class PlacesProblemOutput(val problem: PlacesProblem) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(problem.speech)
}

/**
 * "Getting directions home." (route planning started), "Here's home on the map." (a pin or a
 * search: the user taps Route; "Tap Route." is said the first time), with the saved address or
 * point and how to change it on the card. GAR: on a [locked] phone the card shows no address or
 * point, only "Unlock to see the route.".
 */
class NavigateSavedOutput(
    val kind: SavedPlaceKind,
    val place: SavedPlace,
    val done: RouteDone,
    val locked: Boolean = false,
) : SkillOutput {
    val result: MapResult get() = done.result

    override fun getSpeechOutput(ctx: SkillContext): String = routeSpeech(ctx, done, ctx.getString(kind.label)) {
        ctx.getString(
            if (kind == SavedPlaceKind.HOME) R.string.skill_places_route_home else R.string.skill_places_route_work
        )
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = routeHeadline(ctx, done, ctx.getString(kind.label)) {
                ctx.getString(
                    if (kind == SavedPlaceKind.HOME) R.string.skill_places_route_home else R.string.skill_places_route_work
                )
            })
            if (locked) {
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = ctx.getString(R.string.skill_places_unlock_route))
                return@Column
            }
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = place.address ?: place.point?.let { spokenCoordinates(ctx, it) } ?: "")
            RouteHints(ctx, done)
            Spacer(modifier = Modifier.height(4.dp))
            Body(text = ctx.getString(R.string.skill_places_how_to_change, ctx.getString(kind.label)))
        }
    }
}

/** GAR (gar:places). "Getting directions to Sarah Lee.", with the contact's address on the card. */
class ContactRouteOutput(val name: String, val address: String, val done: RouteDone) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = routeSpeech(ctx, done, name) {
        ctx.getString(R.string.skill_places_route_to, name)
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = routeHeadline(ctx, done, name) { ctx.getString(R.string.skill_places_route_to, name) })
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = name)
            Body(text = address)
            RouteHints(ctx, done)
        }
    }
}

/** What a trip says: [route] when route planning started, the lookup's problem, the pin or search. */
private fun routeSpeech(ctx: SkillContext, done: RouteDone, label: String, route: () -> String): String = when {
    done.result == MapResult.NO_APP -> ctx.getString(R.string.skill_places_no_maps_app)
    done.problem == LookupProblem.NOT_FOUND -> ctx.getString(R.string.skill_places_lookup_not_found)
    done.problem == LookupProblem.FAILED -> ctx.getString(R.string.skill_places_lookup_failed)
    done.result == MapResult.SHOWN && done.sayTapRoute -> ctx.getString(R.string.skill_places_shown_tap_route, label)
    done.result == MapResult.SHOWN -> ctx.getString(R.string.skill_places_shown, label)
    else -> route()
}

/** The card's headline: the speech without the one-time "Tap Route." (the card shows it below). */
private fun routeHeadline(ctx: SkillContext, done: RouteDone, label: String, route: () -> String): String =
    routeSpeech(ctx, done.copy(sayTapRoute = false), label, route)

/** What the user does next in the maps app, and OpenStreetMap's credit for a looked-up point. */
@Composable
private fun RouteHints(ctx: SkillContext, done: RouteDone) {
    val hint = when {
        done.result == MapResult.ROUTE_DRAWN -> R.string.skill_places_route_drawn_hint
        done.result == MapResult.SHOWN && done.searched -> R.string.skill_places_search_tap_route
        done.result == MapResult.SHOWN -> R.string.skill_places_tap_route
        else -> null
    }
    if (hint != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Body(text = ctx.getString(hint))
    }
    if (done.lookedUp) {
        Spacer(modifier = Modifier.height(4.dp))
        Body(text = ctx.getString(R.string.skill_places_lookup_attribution))
    }
}

/**
 * "What's your home address? Say the address or a place name, or "here" if you're there now."
 * (the user may not know the address by heart), with Settings' "Home and work" on the card. The
 * answer goes through [PlacesFlow.save]; a new command wins over it ([GarOpenAnswer]).
 */
class SavedPlaceAskOutput(
    val flow: PlacesFlow,
    val kind: SavedPlaceKind,
    val purpose: SavedPlacePurpose,
    val nested: Boolean,
) : SkillOutput {
    private fun question(ctx: SkillContext): String = ctx.getString(
        if (kind == SavedPlaceKind.HOME) R.string.skill_places_ask_home else R.string.skill_places_ask_work
    )

    override fun getSpeechOutput(ctx: SkillContext): String =
        question(ctx) + " " + ctx.getString(R.string.skill_places_ask_hint)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan {
        val skills = listOf(SavedPlaceAnswerSkill(this), GarCancelSkill(flow.info))
        return if (nested) {
            InteractionPlan.ReplaceSubInteraction(reopenMicrophone = true, nextSkills = skills)
        } else {
            InteractionPlan.StartSubInteraction(reopenMicrophone = true, nextSkills = skills)
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = question(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = ctx.getString(R.string.skill_places_ask_hint))
            Spacer(modifier = Modifier.height(4.dp))
            Body(text = ctx.getString(R.string.skill_places_ask_detail))
        }
    }
}

/** Follow-up skill for [SavedPlaceAskOutput]: any words are the address, or "here". */
class SavedPlaceAnswerSkill(
    private val question: SavedPlaceAskOutput,
) : Skill<String>(question.flow.info, Specificity.HIGH), GarOpenAnswer {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String> {
        val trimmed = input.trim()
        return Pair(if (trimmed.isEmpty()) AlwaysWorstScore else AlwaysBestScore, trimmed)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput =
        GarFollowUpOutput(
            question.flow.save(ctx, question.kind, inputData, question.purpose, nested = true)
        )

    override fun yieldsTo(command: SkillWithResult<*>): Boolean = true
}

/**
 * "Saved your home address: 12 Main Street." (read back, so a misheard address is heard at once)
 * or "Saved this place as home.", then what [then] says (going there), with how to change it on
 * the card.
 */
class SavedPlaceOutput(
    val kind: SavedPlaceKind,
    val place: SavedPlace,
    val then: SkillOutput?,
) : SkillOutput {
    private fun saved(ctx: SkillContext): String = if (place.point != null) {
        ctx.getString(R.string.skill_places_saved_here, ctx.getString(kind.label))
    } else {
        ctx.getString(R.string.skill_places_saved_address, ctx.getString(kind.label))
    }

    override fun getSpeechOutput(ctx: SkillContext): String {
        val said = place.address?.let {
            ctx.getString(R.string.skill_places_saved_address_said, ctx.getString(kind.label), it)
        } ?: saved(ctx)
        return (said + " " + (then?.getSpeechOutput(ctx) ?: "")).trim()
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        then?.getInteractionPlan(ctx) ?: InteractionPlan.FinishInteraction

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = saved(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = place.address ?: place.point?.let { spokenCoordinates(ctx, it) } ?: "")
            Spacer(modifier = Modifier.height(4.dp))
            Body(text = ctx.getString(R.string.skill_places_how_to_change, ctx.getString(kind.label)))
            if (then != null) {
                Spacer(modifier = Modifier.height(8.dp))
                then.GraphicalOutput(ctx)
            }
        }
    }
}

/** "Your home address is 12 Main Street.", "I don't have a work address yet. ..." */
class ShowSavedOutput(val kind: SavedPlaceKind, val place: SavedPlace?) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String {
        val label = ctx.getString(kind.label)
        return when {
            place == null -> ctx.getString(R.string.skill_places_none_saved, label)
            place.address != null -> ctx.getString(R.string.skill_places_saved_is, label, place.address)
            else -> ctx.getString(R.string.skill_places_saved_point, label)
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            place?.point?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = spokenCoordinates(ctx, it))
                MapButton(it, ctx.getString(kind.label))
            }
            if (place != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Body(text = ctx.getString(R.string.skill_places_how_to_change, ctx.getString(kind.label)))
            }
        }
    }
}

/** "Forgot your home address." or "I didn't have a home address saved." */
class ForgotSavedOutput(val kind: SavedPlaceKind, val had: Boolean) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
        if (had) R.string.skill_places_forgot else R.string.skill_places_forgot_none,
        ctx.getString(kind.label),
    )
}

/** "Looking for coffee nearby." */
class NearbyOutput(val thing: String, val result: MapResult) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = if (result == MapResult.NO_APP) {
        ctx.getString(R.string.skill_places_no_maps_app)
    } else {
        ctx.getString(R.string.skill_places_nearby, thing)
    }
}

/**
 * "You're near 12 Main Street, Springfield." when the phone can name the place, with the
 * coordinates, how sure Android is and a map button on the card. Without a name GAR asks
 * "Show it on the map?" instead ([PlacesSkill]), so this is only the named answer.
 */
class WhereAmIOutput(val found: LocationResult.Found) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_places_you_are_near, found.name ?: "")

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            Spacer(modifier = Modifier.height(8.dp))
            LocationDetails(ctx, found.fix)
            MapButton(found.fix.point, ctx.getString(R.string.skill_places_you_are_here))
        }
    }
}

/** The coordinates, and how close they are: "Within about 20 meters", "Approximate location". */
@Composable
fun LocationDetails(ctx: SkillContext, fix: DeviceFix) {
    Body(text = spokenCoordinates(ctx, fix.point))
    Spacer(modifier = Modifier.height(4.dp))
    Body(text = accuracyText(ctx, fix))
}

fun accuracyText(ctx: SkillContext, fix: DeviceFix): String = when {
    !fix.precise -> ctx.getString(R.string.skill_places_approximate)
    fix.accuracyMeters != null ->
        ctx.getString(R.string.skill_places_accuracy, fix.accuracyMeters.roundToInt())
    else -> ""
}

/** "Show on map": a pin at [point] in the default maps app. */
@Composable
fun MapButton(point: GeoPoint, label: String) {
    val context = LocalContext.current
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(MapLinks.pin(point, label)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            // no maps app: nothing to show it in
        }
    }) {
        Text(text = stringResource(R.string.skill_places_show_on_map))
    }
}
