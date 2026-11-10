package org.stypox.dicio.skills.online_skills

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.online.GarOnlineSettings
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.sentences.Sentences.OnlineSkills
import org.stypox.dicio.util.getString

/**
 * Turns all of Dicio's online skills ([GarOnlineSkill]) on or off at once, without another
 * question (the user asked for exactly that), and remembers that the user chose about each, so
 * that GAR doesn't ask about them anymore (gar/DESIGN.md, "Online skills").
 *
 * @param isDeviceLocked whether the phone is locked (with a PIN, pattern, ...): then it doesn't
 * turn them on, since Dicio's sentences also match on the lock screen (wake word) and that is the
 * owner's lasting choice; turning them off still works
 */
class OnlineSkillsSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<OnlineSkills>,
    private val isDeviceLocked: (SkillContext) -> Boolean = { ctx ->
        ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    },
    private val settingsFor: (SkillContext) -> GarOnlineSettings = GarOnlineSettings::forContext,
) : StandardRecognizerSkill<OnlineSkills>(correspondingSkillInfo, data) {

    // Only an input that asks to turn the online skills on or off (see [OnlineSkillsWords]):
    // Dicio scores a sentence with a word left out above its thresholds, so "turn on all the
    // lights", "are the online skills on" or "don't turn on the online skills" would otherwise
    // turn on every online skill.
    override fun score(ctx: SkillContext, input: String): Pair<Score, OnlineSkills> {
        val (score, sentence) = super.score(ctx, input)
        val on = when (sentence) {
            OnlineSkills.TurnOn -> true
            OnlineSkills.TurnOff -> false
        }
        return Pair(if (OnlineSkillsWords.turnsOn(input) == on) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: OnlineSkills): SkillOutput {
        val on = inputData == OnlineSkills.TurnOn
        if (on && isDeviceLocked(ctx)) {
            return GarLockedOutput() // "Unlock your phone first."
        }
        settingsFor(ctx).setEnabled(GarOnlineSkill.ids, on)
        return OnlineSkillsOutput(on)
    }
}

/** The words an input for [OnlineSkillsSkill] must have (English, like its sentences). */
object OnlineSkillsWords {
    private val ONLINE = listOf("online", "internet")
    private val SKILLS = listOf("skill", "feature", "service")

    /** "enable the online skills", "disable online services". */
    private val ON_VERBS = listOf("enable", "activate", "allow")
    private val OFF_VERBS = listOf("disable", "deactivate", "block", "stop")

    /** With "on" or "off" after them: "turn on the online skills", "switch them off". */
    private val SWITCH_VERBS = listOf("turn", "switch")

    /** Matched as whole words, also any word ending in "n't" ("doesn't", "can't"). */
    private val NEGATIONS = setOf("no", "not", "never", "dont", "doesnt", "didnt")

    /**
     * First words of a question about the skills ("are the online skills on"), not a request; "can
     * you", "could you", "will you" and "would you" are polite requests.
     */
    private val QUESTIONS = setOf(
        "is", "are", "which", "what", "what's", "whats", "why", "how", "do", "does", "did",
        "should",
    )

    /** Words before the question word that don't change it: "so are the online skills on". */
    private val LEADS = setOf("so", "and", "ok", "okay", "hey", "oh", "um", "uh", "well")

    /**
     * True if [input] asks to turn the online skills on, false if off, null if it doesn't name
     * them ("turn on all the lights", "turn on wifi"), says both, has no verb that turns them on
     * or off ("online skills on"), has a negation ("don't turn on the online skills") or is a
     * question ("are the online skills on", "how do I turn on the online skills").
     */
    fun turnsOn(input: String): Boolean? {
        val normalized = Utterances.normalize(input)
        val words = Utterances.words(normalized)
        if (!KeywordFilter.containsAny(normalized, ONLINE) ||
            !KeywordFilter.containsAny(normalized, SKILLS) ||
            words.any { it in NEGATIONS || it.endsWith("n't") } ||
            words.firstOrNull { it !in LEADS } in QUESTIONS
        ) {
            return null
        }
        val on = KeywordFilter.containsAny(normalized, ON_VERBS) || switches(words, "on")
        val off = KeywordFilter.containsAny(normalized, OFF_VERBS) || switches(words, "off")
        return if (on == off) null else on
    }

    /** Whether [words] have "turn" or "switch" (also "turned", ...) with [state] after it. */
    private fun switches(words: List<String>, state: String): Boolean =
        words.indices.any { index ->
            KeywordFilter.containsAny(words[index], SWITCH_VERBS) &&
                state in words.subList(index + 1, words.size)
        }
}

/**
 * "All online skills are on. They use the internet.", with each one and what it sends where on
 * the card (gar:knowledge: the list grows with [GarOnlineSkill], so the sentence names none).
 */
class OnlineSkillsOutput(val on: Boolean) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
        if (on) R.string.skill_online_skills_on else R.string.skill_online_skills_off
    )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            if (on) {
                for (online in GarOnlineSkill.entries) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(online.label).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(text = stringResource(online.detail), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = stringResource(
                if (on) R.string.skill_online_skills_on_detail else R.string.skill_online_skills_off_detail
            ))
        }
    }
}
