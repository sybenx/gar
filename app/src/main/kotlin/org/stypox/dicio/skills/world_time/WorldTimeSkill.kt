package org.stypox.dicio.skills.world_time

import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.WorldTime
import java.time.ZonedDateTime

/** What [WorldTimeSkill] answers: a [WorldTimeRequest], or a place GAR doesn't know. */
sealed interface WorldTimeAnswer {
    data class Known(val request: WorldTimeRequest) : WorldTimeAnswer

    /** "What time is it in Moab?": "I don't know the time in Moab." */
    data class UnknownPlace(val name: String) : WorldTimeAnswer
}

/**
 * The time in another place and time differences, offline ([WorldPlaces]). The sentences capture
 * the place; a capture that isn't a place GAR knows doesn't match, so "what time is it in the
 * morning" stays with the other skills (Dicio's current time, or the model), nor does an input
 * without "time" or a difference word ("how far is tokyo"). Except for a whole "what time is it
 * in <place>" sentence whose place may well be one ([WorldTimePhrases.unknownPlace]): GAR says it
 * doesn't know that place, instead of Dicio's current time giving the local time as the answer.
 * The model's `current_time` calls that name a place get the same answer (`GarEverydayCalls`).
 */
class WorldTimeSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<WorldTime>,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : Skill<WorldTimeAnswer?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, WorldTimeAnswer?> {
        val (score, match) = data.score(ctx, input)
        // Dicio's matcher leaves a word out: "how far is tokyo" matched "how far ahead is .place."
        val words = Utterances.words(Utterances.normalize(input))
        val answer = when (match) {
            is WorldTime.TimeIn -> match.place?.takeIf { "time" in words }?.let { place ->
                WorldTimePhrases.timeIn(place)
                    ?.let { WorldTimeAnswer.Known(WorldTimeRequest.TimeIn(it)) }
                    ?: WorldTimePhrases.unknownPlace(place)
                        ?.takeIf { score.scoreIn01Range() >= UNKNOWN_PLACE_MIN_SCORE }
                        ?.let { WorldTimeAnswer.UnknownPlace(it) }
            }
            is WorldTime.Difference -> match.place
                ?.takeIf { words.any { it in DIFFERENCE_WORDS } }
                ?.let(WorldTimePhrases::difference)
                ?.let { WorldTimeAnswer.Known(it) }
        }
        return Pair(if (answer == null) AlwaysWorstScore else score, answer)
    }

    override suspend fun generateOutput(
        ctx: SkillContext,
        inputData: WorldTimeAnswer?,
    ): SkillOutput = when (inputData) {
        is WorldTimeAnswer.Known -> WorldTimeOutput(inputData.request, now())
        is WorldTimeAnswer.UnknownPlace -> WorldTimeUnknownOutput(inputData.name)
        null -> throw IllegalStateException("a world time match without a place ran")
    }

    companion object {
        private val DIFFERENCE_WORDS = setOf("difference", "ahead", "behind")

        /** A whole sentence, as said, for a place GAR doesn't know. */
        const val UNKNOWN_PLACE_MIN_SCORE = 0.95f
    }
}
