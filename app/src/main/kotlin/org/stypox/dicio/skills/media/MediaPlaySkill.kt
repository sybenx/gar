package org.stypox.dicio.skills.media

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.delay
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.media.AndroidMediaPlayers
import org.stypox.dicio.gar.media.AndroidMediaSessions
import org.stypox.dicio.gar.media.GarMediaPlay
import org.stypox.dicio.gar.media.MediaPlayRequest
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.MediaPlay

object MediaPlayInfo : SkillInfo("media_play") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_media_play)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_media_play)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.PlayCircle)

    // no permissions: play-from-search intents are public; a player's media session, seen with
    // the notification access the song skill asks for, is only a quieter way to the same

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.MediaPlay[ctx.sentencesLanguage] ?: return null
        return MediaPlaySkill(MediaPlayInfo, data)
    }
}

/**
 * "Play taylor swift on spotify", "put on my workout playlist", "play the latest episode of the
 * daily" (gar/PARITY.md, batch 4): plays something by name in the app named or the default
 * player ([GarMediaPlay]). English sentences for the common shapes, and the model's `media_play`
 * tool (LLM-only, [inputFromSlots]) for the rest ("put on dancing queen by abba").
 *
 * Dicio's matcher takes a sentence with a word left out, so a match only counts when
 * [MediaPlayWords] reads the input as a request to play something named.
 *
 * @param player plays a request on the phone
 */
class MediaPlaySkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<MediaPlay>,
    private val player: (SkillContext) -> GarMediaPlay = { ctx ->
        GarMediaPlay(AndroidMediaSessions(ctx.android), AndroidMediaPlayers(ctx.android)) { delay(it) }
    },
) : Skill<MediaPlayRequest?>(correspondingSkillInfo, data.specificity), GarToolSkill {

    override fun score(ctx: SkillContext, input: String): Pair<Score, MediaPlayRequest?> {
        val request = MediaPlayWords.parse(input) ?: return Pair(AlwaysWorstScore, null)
        val (score, _) = data.score(ctx, input)
        return Pair(score, request)
    }

    /** Null for a query that is no request to play something named, see [MediaPlayWords.fromSlots]. */
    override fun inputFromSlots(slots: Map<String, Any?>): MediaPlayRequest? =
        MediaPlayWords.fromSlots(slots[QUERY_SLOT] as? String ?: "", slots[APP_SLOT] as? String)

    override suspend fun generateOutput(ctx: SkillContext, inputData: MediaPlayRequest?): SkillOutput {
        // a match without a request scores AlwaysWorstScore: only the model's call gets here
        val request = inputData ?: return MediaAnswers.cantPlay()
        return MediaAnswers.play(player(ctx).play(request))
    }

    companion object {
        /** The `media_play` tool's slots (`tools.yml`). */
        const val QUERY_SLOT = "query"
        const val APP_SLOT = "app"

        /**
         * gar:media. [APP_SLOT] when the model's `media_play` [call] has an app the user didn't
         * say after on, in or with ("put on the new kendrick lamar album" -> app "album"), so
         * that GAR leaves it out instead of asking "Which app?"; null otherwise.
         */
        fun unsaidAppSlot(call: GarCall): String? {
            if (call.tool.skillId != MediaPlayInfo.id || call.utterance.isBlank() ||
                APP_SLOT in call.values
            ) {
                return null
            }
            val app = call.rawSlots[APP_SLOT]?.takeIf { it.isNotBlank() } ?: return null
            return APP_SLOT.takeUnless { MediaPlayWords.saysApp(call.utterance, app) }
        }
    }
}
