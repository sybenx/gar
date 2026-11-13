package org.stypox.dicio.skills.sms

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.aaro.gar.router.resolve.NameMatcher
import cc.aaro.gar.router.resolve.Resolution
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
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.parseOptionIndex
import org.stypox.dicio.gar.conversation.GarOpenAnswer
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.util.getString

/** The contacts that an answer to "Which contact is you?" names, see [OwnContactAnswers.find]. */
sealed interface OwnAnswer {
    /**
     * @param sure whether the answer is that contact for certain: picked from the options, or
     * said as its whole name or one of its words ("alex" for Alex Herbert), not only close to
     * it ("erin" for Eric)
     */
    data class One(val name: String, val sure: Boolean) : OwnAnswer
    data class Several(val names: List<String>) : OwnAnswer
    data object NotFound : OwnAnswer
}

/** Understands the answers to "Which contact is you?". */
object OwnContactAnswers {
    /** At most this many contacts are offered to choose from, like for a text. */
    const val MAX_OPTIONS = 5

    /** The name in an answer: "it's Aaron", "I'm Aaron", "no, Aaron" -> "Aaron". */
    fun said(input: String): String {
        val words = ContactWords.corrected(input).trim().split(WHITESPACE).filter { it.isNotEmpty() }
        return words.dropWhile { normalizeAnswer(it) in LEADING_WORDS }
            .ifEmpty { words }
            .joinToString(" ")
            .trim { it in ",.;:!?" }
    }

    /**
     * The contact that [said] names: one of the last question's [options] by name or position,
     * else one of all the contacts' [names] that [NameMatcher] finds or that shares a whole word
     * with [said] (never a name that only has its letters, like Dicio's contact distance).
     */
    fun find(said: String, options: List<String>, names: () -> List<String>): OwnAnswer {
        if (options.isNotEmpty()) {
            val normalized = normalizeAnswer(said)
            options.firstOrNull { normalizeAnswer(it) == normalized }?.let { return OwnAnswer.One(it, true) }
            parseOptionIndex(said, options.size)?.let { return OwnAnswer.One(options[it], true) }
            (NameMatcher.match(said, options, { it }) as? Resolution.Resolved)
                ?.let { return OwnAnswer.One(it.value, true) }
        }
        val all = names()
        return when (val match = NameMatcher.match(said, all, { it }, MAX_OPTIONS)) {
            is Resolution.Resolved -> OwnAnswer.One(match.value, says(said, match.value))
            is Resolution.Ambiguous -> OwnAnswer.Several(match.options)
            is Resolution.Unresolvable -> {
                val sharing = all.filter { NameMatcher.sharesWord(said, it) }
                when (sharing.size) {
                    0 -> OwnAnswer.NotFound
                    1 -> OwnAnswer.One(sharing[0], says(said, sharing[0]))
                    else -> OwnAnswer.Several(sharing.take(MAX_OPTIONS))
                }
            }
        }
    }

    /** Whether [said] is [name] as a whole or one of its words: "alex" for Alex Herbert. */
    private fun says(said: String, name: String): Boolean {
        val normalized = normalizeAnswer(said)
        return normalized == normalizeAnswer(name) || normalized in normalizeAnswer(name).split(" ")
    }

    private val WHITESPACE = Regex("\\s+")
    private val LEADING_WORDS = setOf(
        "i'm", "im", "i", "am", "it's", "its", "it", "is", "that's", "thats", "my", "name", "me",
        "the", "one", "called", "saved", "as", "under",
    )
}

/** What a text, call or email needs of the owner's contact. */
enum class OwnNeed(val has: (OwnCard) -> Boolean, @StringRes val missing: Int) {
    NUMBER({ it.numbers.isNotEmpty() }, R.string.skill_own_contact_no_number),
    EMAIL({ it.emails.isNotEmpty() }, R.string.skill_own_contact_no_email),
}

/**
 * "Which contact is you?", asked when the user texts, calls or emails themselves and GAR knows
 * no card of theirs with a number or address ([OwnContact]). The answer names a contact
 * ([OwnContactAnswers]): GAR remembers it, says how to change that, and goes on with [then] as if
 * the user had said that contact's name. A contact only close to what was heard ("erin" for Eric)
 * is gone on with this once, never remembered. Several matches are asked about, a name no contact
 * has, or a contact without what [needs] says, is said so and asked again.
 *
 * @param then the text, call or email to the contact's name, as the target skill answers it
 * @param cancelled what "cancel" answers
 * @param needs what the target needs of the contact
 * @param options the contacts the last answer matched, to pick one of
 * @param notFound the last answer, when it matched no contact
 * @param lacking the contact the last answer named, when it lacks what [needs] says
 */
class OwnContactAskOutput(
    val info: SkillInfo,
    private val own: OwnContact,
    private val then: suspend (SkillContext, String) -> SkillOutput,
    private val cancelled: () -> SkillOutput,
    private val needs: OwnNeed,
    private val nested: Boolean,
    val options: List<String> = listOf(),
    val notFound: String? = null,
    val lacking: String? = null,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when {
        options.isNotEmpty() -> ctx.getString(
            R.string.skill_own_contact_which,
            options.joinToString(ctx.getString(R.string.skill_sms_contacts_separator)),
        )
        notFound != null -> ctx.getString(R.string.skill_own_contact_not_found, notFound)
        lacking != null -> ctx.getString(needs.missing, lacking)
        else -> ctx.getString(R.string.skill_own_contact_ask)
    }

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        SmsFlow.plan(nested, listOf(OwnContactAnswerSkill(this)))

    /** What GAR answers to [input]. */
    suspend fun answer(ctx: SkillContext, input: String): SkillOutput {
        if (isCancelPhrase(input)) {
            return cancelled()
        }
        val said = OwnContactAnswers.said(input)
        return when (val found = OwnContactAnswers.find(said, options, own::contactNames)) {
            is OwnAnswer.One -> {
                if (own.card(found.name)?.let(needs.has) != true) {
                    return OwnContactAskOutput(
                        info, own, then, cancelled, needs, nested = true, lacking = found.name,
                    )
                }
                if (!found.sure) {
                    // only close to what was heard: the read-back or the call's confirmation says
                    // the name, and the next "text myself" asks again
                    return GarFollowUpOutput(then(ctx, found.name))
                }
                own.remember(found.name)
                // the target's output as if it answered the command, without this question's batch
                OwnContactRememberedOutput(found.name, GarFollowUpOutput(then(ctx, found.name)))
            }
            is OwnAnswer.Several ->
                OwnContactAskOutput(info, own, then, cancelled, needs, nested = true, options = found.names)
            OwnAnswer.NotFound ->
                OwnContactAskOutput(info, own, then, cancelled, needs, nested = true, notFound = said)
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            if (options.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Body(text = options.withIndex().joinToString("\n") { (i, name) -> "${i + 1}. $name" })
            }
        }
    }
}

/**
 * Follow-up skill for [OwnContactAskOutput]: any answer, and "cancel" stops. A new command ("what
 * time is it") wins over it, like over the answer to GAR's "Who?" ([GarOpenAnswer]).
 */
class OwnContactAnswerSkill(
    private val question: OwnContactAskOutput,
) : Skill<String>(question.info, Specificity.HIGH), GarOpenAnswer {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String> {
        val trimmed = input.trim()
        return Pair(if (trimmed.isEmpty()) AlwaysWorstScore else AlwaysBestScore, trimmed)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput =
        question.answer(ctx, inputData)

    override fun yieldsTo(command: SkillWithResult<*>): Boolean = true
}

/**
 * "OK, you're Aaron. You can change that in Settings, You and places." (GAR's settings
 * page), then what [then] says.
 */
class OwnContactRememberedOutput(
    val name: String,
    val then: SkillOutput,
) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        (ctx.getString(R.string.skill_own_contact_remembered, name) + " " + then.getSpeechOutput(ctx))
            .trim()

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        then.getInteractionPlan(ctx)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Body(text = ctx.getString(R.string.skill_own_contact_remembered, name))
            Spacer(modifier = Modifier.height(8.dp))
            then.GraphicalOutput(ctx)
        }
    }
}
