package org.stypox.dicio.gar.online

import org.stypox.dicio.gar.call.showsOutput
import org.stypox.dicio.gar.ui.garble.GarblePrimaryButton // GAR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.GarYesNo
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.util.getString
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR

/**
 * "Search uses the internet: it asks DuckDuckGo. Turn it on?", with what the skill sends where and
 * Turn it on / Keep it off buttons. The user answers by voice ([GarOnlineAnswers]) or with the
 * buttons, which send their label as input to the evaluator, so both go through
 * [GarOnlineAnswerSkill]. Any other input leaves the question unanswered: GAR remembers nothing
 * and asks again next time.
 *
 * @param inputData what [gate]'s skill matched in the input, to answer it after a yes
 */
class GarOnlineAskOutput(
    val gate: GarOnlineGateSkill,
    private val inputData: Any?,
    private val settings: GarOnlineSettings,
) : SkillOutput {

    val online: GarOnlineSkill get() = gate.online

    /**
     * Dicio's ranker when GAR asked, which gets [GarOnlineAnswerSkill]. Dicio builds a new one
     * when the skills' switches change (in Settings > Skills while the question waits, say),
     * without the answer skill: the buttons would then send their label as a new command.
     */
    val askedRanker: SkillRanker? = settings.skillRankers?.value

    /** Set once the user answered, to disable the buttons. */
    var answered by mutableStateOf(false)

    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(online.question)

    /** The skills that wait for the answer; [yesLabel] and [noLabel] are the buttons' labels. */
    fun nextSkills(yesLabel: String, noLabel: String): List<Skill<*>> = listOf(
        GarOnlineAnswerSkill(gate, inputData, settings, yesLabel, noLabel) { answered = true },
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.StartSubInteraction(
            reopenMicrophone = true,
            nextSkills = nextSkills(
                ctx.getString(R.string.gar_online_turn_on),
                ctx.getString(R.string.gar_online_keep_off),
            ),
        )

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillEvaluator = remember {
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .skillEvaluator()
        }
        // the buttons only make sense while this card is the latest answer, and its answer skill
        // still waits in the ranker
        val log by skillEvaluator.state.collectAsState()
        val ranker by remember {
            settings.skillRankers ?: MutableStateFlow<SkillRanker?>(null)
        }.collectAsState()
        val isLatest = log.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer.showsOutput(this)
        val enabled = isLatest && !answered && ranker === askedRanker

        val yes = stringResource(R.string.gar_online_turn_on)
        val no = stringResource(R.string.gar_online_keep_off)
        val send = { label: String ->
            answered = true
            skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(label, 1.0f))))
        }

        Column {
            Headline(text = stringResource(online.question))
            Spacer(modifier = Modifier.height(8.dp))
            Body(text = stringResource(online.detail))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GarbleSecondaryButton(onClick = { send(no) }, enabled = enabled) { // GAR
                    Text(text = no)
                }
                GarblePrimaryButton(onClick = { send(yes) }, enabled = enabled) { // GAR
                    Text(text = yes)
                }
            }
        }
    }
}

/**
 * The answer to a [GarOnlineAskOutput]: true (yes, or the [yesLabel] button) turns the skill on,
 * remembers that the user chose and answers the input the question came from with the skill
 * itself; false (no, or the [noLabel] button) remembers the choice and keeps it off. Matches
 * nothing else, so that a new command goes to its own skill. A yes on a phone that was locked
 * meanwhile changes nothing, like the gate ([GarOnlineGateSkill.isDeviceLocked]).
 */
class GarOnlineAnswerSkill(
    private val gate: GarOnlineGateSkill,
    private val askedInputData: Any?,
    private val settings: GarOnlineSettings,
    private val yesLabel: String,
    private val noLabel: String,
    private val onAnswered: () -> Unit = {},
) : Skill<Boolean?>(gate.correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Boolean?> {
        val answer = when (normalizeAnswer(input)) {
            normalizeAnswer(yesLabel) -> true
            normalizeAnswer(noLabel) -> false
            else -> GarOnlineAnswers.classify(input, gate.online)
        }
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Boolean?): SkillOutput {
        onAnswered()
        val id = gate.online.id
        // AlwaysWorstScore is never chosen, so null can't happen
        return when {
            inputData != true -> {
                settings.markAsked(listOf(id))
                gate.offOutput(ctx, askedInputData, justDeclined = true)?.let(::GarFollowUpOutput)
                    ?: GarOnlineStaysOffOutput(gate.online)
            }
            // nothing is remembered: GAR asks again once the phone is unlocked
            gate.isDeviceLocked(ctx) -> GarLockedOutput()
            else -> {
                settings.setEnabled(listOf(id), true)
                // the skill's plan as if it had matched directly: GAR's question is done
                GarFollowUpOutput(gate.runWrapped(ctx, askedInputData))
            }
        }
    }
}

/** "OK, the weather skill stays off. You can turn it on in Settings, Skills." */
class GarOnlineStaysOffOutput(val online: GarOnlineSkill) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.gar_online_stays_off, ctx.getString(online.label))
}

/**
 * "That needs the weather skill, which is off. You can turn it on in Settings, Skills.": the user
 * already chose to keep it off, so GAR doesn't ask again.
 */
class GarOnlineIsOffOutput(val online: GarOnlineSkill) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.gar_online_is_off, ctx.getString(online.label))
}

/**
 * Short spoken answers to GAR's "Turn it on?", English only like GAR's other answers: true to turn
 * the skill on ("yes", "sure", "turn it on", "go ahead"), false to keep it off ("no", "not now",
 * "keep it off", "never mind", "I'm good"), null for anything else. Only answers made of
 * [ANSWER_WORDS] and the asked skill's own name count, so a new command ("what's the weather in
 * paris", "turn on the lights") is no answer, nor is a reply about another online skill ("no, turn
 * on search" to the weather question). Unclear replies are no answer either ("it's fine", "all
 * good", "not sure", "no wait", a bare "do"), so that only a clear yes turns a skill on and only a
 * clear no is remembered. The yes/no core is [GarYesNo], shared with the sms card; Dicio's own
 * yes/no words (`util_yes_no`) are among the words.
 */
object GarOnlineAnswers {
    private val YES_WORDS = GarYesNo.YES_WORDS + setOf("course")

    /**
     * Yes words that aren't a yes after [POLITE_LEADS] ("that's okay", "it's fine", "all good",
     * often a polite no thanks) and are a no after "I'm" ("I'm good").
     */
    private val WEAK_YES_WORDS = setOf("ok", "okay", "alright", "right", "fine", "good")
    private val POLITE_LEADS = setOf("it's", "its", "that's", "thats", "all")
    private val IM = setOf("i'm", "im")

    /** Words that always keep the skill off: "don't", "not now", "keep it off", "maybe later". */
    private val NEGATIONS = GarYesNo.NEGATIONS + setOf("off", "disable", "later")

    /** An explicit go-ahead on their own: "enable it", "start it". */
    private val ON_VERBS = setOf("enable", "activate", "allow", "start", "use")

    /** An explicit go-ahead with "on": "turn it on", "switch on weather". */
    private val SWITCH_VERBS = setOf("turn", "switch", "put")

    /** "please do", "yes do", "I do", but not "do" alone, which may be a misheard "no". */
    private val DO_LEADS = setOf("please", "yes", "i")

    // no online skill's name: each question takes only its own skill's ([names])
    private val ANSWER_WORDS = YES_WORDS + GarYesNo.NO_WORDS + NEGATIONS + ON_VERBS +
        SWITCH_VERBS + IM + setOf(
        "on", "it", "it's", "its", "them", "that", "that's", "thats", "this", "the", "all", "do",
        "go", "ahead", "for", "let", "let's", "lets", "why", "of", "now", "yet", "i", "you", "can",
        "could", "would", "want", "like", "to", "keep", "leave", "stay", "mind", "maybe", "wait",
        "actually", "thanks", "thank", "please", "just", "so", "well", "oh", "uh", "um", "hmm",
        "and", "then", "sounds", "away", "need", "skill", "online", "internet",
    )

    /** The skill's own name in an answer: "yes, turn on the weather skill". */
    private fun names(online: GarOnlineSkill): Set<String> = when (online) {
        GarOnlineSkill.WEATHER -> setOf("weather")
        GarOnlineSkill.SEARCH -> setOf("search")
        GarOnlineSkill.LYRICS -> setOf("lyrics", "lyric")
        GarOnlineSkill.TRANSLATION -> setOf("translation", "translate")
        GarOnlineSkill.JOKE -> setOf("joke", "jokes")
        // gar:knowledge ("yes, download it" to the dictionary's question)
        GarOnlineSkill.KNOWLEDGE -> setOf("knowledge", "wikipedia")
        GarOnlineSkill.DICTIONARY -> setOf("dictionary", "definitions", "download")
        GarOnlineSkill.CURRENCY -> setOf("currency", "currencies", "conversion", "exchange")
    }

    /** What [input] answers to the question about [online]. */
    fun classify(input: String, online: GarOnlineSkill): Boolean? {
        val names = names(online)
        val said = normalizeAnswer(input).split(" ").filter { it.isNotEmpty() }
        if (said.isEmpty() || said.any { it !in ANSWER_WORDS && it !in names }) {
            return null
        }
        // "no wait" may be cut off before what follows: "no wait, turn it on"
        if (said.last() == "wait" && said.getOrNull(said.size - 2) in GarYesNo.NO_WORDS) {
            return null
        }
        // "why not" and "sure, why not" are a yes, "not sure" is unsure: neither is a negation
        val words = said.joinToString(" ").replace("why not", "yes").replace("not sure", "maybe")
            .split(" ")
        if (words.indices.any { GarYesNo.isNegation(words, it, NEGATIONS) }) {
            return false
        }
        return GarYesNo.answer(
            words,
            isYes = { words[it] in YES_WORDS && !isPolite(words, it) && !isNoThanks(words, it) },
            isGoAhead = { isGoAhead(words, it) },
            isNo = { isNoThanks(words, it) },
        )
    }

    /** "that's okay", "it's fine", "all good", but not "all right", which is a yes. */
    private fun isPolite(words: List<String>, index: Int): Boolean {
        val before = words.getOrNull(index - 1)
        return words[index] in WEAK_YES_WORDS && before in POLITE_LEADS &&
            !(before == "all" && words[index] == "right")
    }

    /** "I'm good", "I'm fine": no thanks. */
    private fun isNoThanks(words: List<String>, index: Int): Boolean =
        words[index] in WEAK_YES_WORDS && words.getOrNull(index - 1) in IM

    /**
     * "turn it on", "enable it", "do it", "please do", "go ahead", "go for it", "let's go", but not
     * "go" or "do" alone, which may be a misheard "no".
     */
    private fun isGoAhead(words: List<String>, index: Int): Boolean {
        val word = words[index]
        return word in ON_VERBS ||
            (word in SWITCH_VERBS && "on" in words.subList(index + 1, words.size)) ||
            (word == "do" && (words.getOrNull(index + 1) == "it" ||
                (index == words.lastIndex && words.getOrNull(index - 1) in DO_LEADS))) ||
            (word == "go" && (words.getOrNull(index + 1) in setOf("ahead", "for") ||
                words.getOrNull(index - 1) in setOf("let's", "lets")))
    }
}
