package org.stypox.dicio.skills.places

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.ShareLocation
import org.stypox.dicio.skills.sms.PERMISSION_SEND_SMS
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.util.PERMISSION_READ_CONTACTS
import org.stypox.dicio.util.getString

object ShareLocationInfo : SkillInfo("share_location") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_share_location)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_share_location)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.ShareLocation)

    // what the sms skill needs for the text; location access is asked by the skill itself, since
    // Android lets the user give approximate location only, which Dicio's check would refuse
    override val neededPermissions: List<Permission>
        = listOf(PERMISSION_READ_CONTACTS, PERMISSION_SEND_SMS)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.ShareLocation[ctx.sentencesLanguage] ?: return null
        return ShareLocationSkill(ShareLocationInfo, data)
    }
}

/**
 * GAR (gar:places). "Share my location with mom", "text dad where I am": a text with an
 * OpenStreetMap link to where the phone is ([MapLinks.osmLink]), through the sms skill's flow, so
 * its card reads the text back and nothing is sent without a yes. English only; on a locked phone
 * "Unlock your phone first." like texts.
 */
class ShareLocationSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<ShareLocation>,
    private val deps: PlacesDeps = PlacesDeps(),
    private val sms: SmsFlow = SmsFlow(),
) : StandardRecognizerSkill<ShareLocation>(correspondingSkillInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, ShareLocation> {
        val (score, sentence) = super.score(ctx, input)
        val who = when (sentence) {
            is ShareLocation.Share -> sentence.who
        }
        return Pair(if (ShareLocationWords.shares(input, who)) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: ShareLocation): SkillOutput {
        val who = when (inputData) {
            is ShareLocation.Share -> inputData.who
        }
        if (deps.isLocked(ctx)) {
            return GarLockedOutput()
        }
        return PlacesFlow(correspondingSkillInfo, deps).locate(ctx, withName = false, nested = false) { found ->
            sms.start(ctx, who, message(ctx, found.fix))
        }
    }

    companion object {
        /** "My location: https://www.openstreetmap.org/?mlat=..." (approximate when it is). */
        fun message(ctx: SkillContext, fix: DeviceFix): String = ctx.getString(
            if (fix.precise) R.string.skill_share_location_message else R.string.skill_share_location_message_approximate,
            MapLinks.osmLink(fix.point),
        )
    }
}

/** Whether a share sentence's match is about the location (English). */
object ShareLocationWords {
    /**
     * The input names the location ("location", "position", "whereabouts", "where I am"), and the
     * recipient doesn't: "text mom my location" is mom.
     */
    fun shares(input: String, who: String?): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        val names = words.any { it in LOCATION_WORDS } ||
            Utterances.isSpan("where i am", words) || Utterances.isSpan("where i'm", words)
        val whoWords = Utterances.words(Utterances.normalize(who ?: ""))
        return names && whoWords.none { it in LOCATION_WORDS || it == "where" }
    }

    private val LOCATION_WORDS = setOf("location", "position", "whereabouts")
}
