package org.stypox.dicio.skills.places

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Places
import org.stypox.dicio.util.getString

object PlacesInfo : SkillInfo("places") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_places)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_places)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.MyLocation)

    // no permissions here: location access is asked only when a request needs the phone's
    // location (where am I, "use my current location"), not to save an address (PlacesFlow)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Places[ctx.sentencesLanguage] ?: return null
        return PlacesSkill(PlacesInfo, data)
    }
}

/**
 * GAR (gar:places). "Where am I", "find coffee near me" (the maps app's search for "coffee",
 * around the user), and the home and work that "take me home" goes to: "set my home address to 12
 * main street", "set this place as work", "what's my home address", "forget my work address"
 * (English). Where am I says the place's name when the phone can name it (Android's
 * Geocoder, which GrapheneOS doesn't have), else the coordinates and "Show it on the map?".
 */
class PlacesSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Places>,
    private val deps: PlacesDeps = PlacesDeps(),
) : StandardRecognizerSkill<Places>(correspondingSkillInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Places> {
        val (score, sentence) = super.score(ctx, input)
        val takes = when (sentence) {
            is Places.WhereAmI -> PlaceWords.savedPlace(input) == null && !mentionsSavedPlace(input)
            is Places.Set -> PlaceWords.savedPlace(sentence.kind) != null
            is Places.SetHere -> PlaceWords.savedPlace(sentence.kind) != null
            is Places.Forget -> PlaceWords.savedPlace(sentence.kind) != null
            is Places.Show -> PlaceWords.savedPlace(sentence.kind) != null
            Places.ShowHome, Places.ShowWork -> true
            is Places.Nearby -> PlaceWords.searchable(sentence.what) != null &&
                PlaceWords.saysNearby(input)
        }
        return Pair(if (takes) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Places): SkillOutput {
        val flow = PlacesFlow(correspondingSkillInfo, deps)
        // the saved places are personal: "Unlock your phone first." on the lock screen
        if (inputData !is Places.WhereAmI && inputData !is Places.Nearby && deps.isLocked(ctx)) {
            return GarLockedOutput()
        }
        return when (inputData) {
            is Places.WhereAmI -> whereAmI(ctx, flow)
            is Places.Set -> {
                val kind = kindOf(inputData.kind)
                if (inputData.address != null) {
                    flow.save(ctx, kind, inputData.address, SavedPlacePurpose.SAVE, nested = false)
                } else {
                    SavedPlaceAskOutput(flow, kind, SavedPlacePurpose.SAVE, nested = false)
                }
            }
            // "set this place as home", "this is my work"
            is Places.SetHere ->
                flow.save(ctx, kindOf(inputData.kind), "here", SavedPlacePurpose.SAVE, nested = false)
            is Places.Forget -> {
                val kind = kindOf(inputData.kind)
                ForgotSavedOutput(kind, deps.saved(ctx).forget(kind))
            }
            is Places.Show -> kindOf(inputData.kind).let { ShowSavedOutput(it, deps.saved(ctx).get(it)) }
            Places.ShowHome -> ShowSavedOutput(SavedPlaceKind.HOME, deps.saved(ctx).get(SavedPlaceKind.HOME))
            Places.ShowWork -> ShowSavedOutput(SavedPlaceKind.WORK, deps.saved(ctx).get(SavedPlaceKind.WORK))
            is Places.Nearby -> {
                val thing = PlaceWords.searchable(inputData.what) ?: inputData.what ?: ""
                PlaceRoutes(flow).search(ctx, thing) { NearbyOutput(thing, it) }
            }
        }
    }

    private suspend fun whereAmI(ctx: SkillContext, flow: PlacesFlow): SkillOutput =
        flow.locate(ctx, withName = true, nested = false) { found ->
            if (found.name != null) {
                WhereAmIOutput(found)
            } else {
                // GrapheneOS: no Geocoder, so the map, and the coordinates on the card only
                GarOfferOutput(
                    info = correspondingSkillInfo,
                    speech = { c -> c.getString(R.string.skill_places_you_are_at) },
                    details = { c ->
                        listOf(
                            spokenCoordinates(c, found.fix.point), accuracyText(c, found.fix),
                            c.getString(R.string.skill_places_no_names),
                        ).filter { it.isNotEmpty() }
                    },
                    yesLabel = R.string.skill_places_show_on_map,
                    noLabel = R.string.skill_places_not_now,
                    yesNoData = deps.yesNo(ctx),
                ) { c ->
                    val label = c.getString(R.string.skill_places_you_are_here)
                    val none = { PlacesProblemOutput(PlacesProblem.NO_MAPS_APP) }
                    PlaceRoutes(flow).withApp(c, none) { c2, app ->
                        when (deps.maps(c2).show(found.fix.point, label, app)) {
                            MapResult.NO_APP -> none()
                            else -> PlacesProblemOutput(PlacesProblem.PAGE_OPENED)
                        }
                    }
                }
            }
        }

    companion object {
        /** The place a kind capture names (score() only keeps matches where it names one). */
        private fun kindOf(kind: String?): SavedPlaceKind =
            PlaceWords.savedPlace(kind) ?: SavedPlaceKind.HOME

        /** "Where am I" never takes "what's my home location": that is a saved place's. */
        private fun mentionsSavedPlace(input: String): Boolean =
            Utterances.words(Utterances.normalize(input)).any { it in SAVED_PLACE_WORDS }

        private val SAVED_PLACE_WORDS = setOf("home", "work", "office", "house", "job")
    }
}
