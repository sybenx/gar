package org.stypox.dicio.skills.telephone

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
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.call.normalizeAnswer
import org.stypox.dicio.gar.call.parseOptionIndex
import org.stypox.dicio.gar.call.spokenPhoneNumber
import org.stypox.dicio.gar.conversation.GarOpenAnswer
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.sentences.Sentences.Telephone
import org.stypox.dicio.skills.sms.ContactWords
import org.stypox.dicio.skills.sms.OwnContact
import org.stypox.dicio.skills.sms.OwnContactAskOutput
import org.stypox.dicio.skills.sms.OwnNeed
import org.stypox.dicio.skills.sms.SmsNumbers
import org.stypox.dicio.util.getString

/**
 * GAR: the telephone skill's contacts, Google style (GAR lines in [TelephoneSkill] and
 * [TelephoneOutput]). "Call myself" is the owner's card ([ownOutput]); contacts match the name by
 * whole words ([ContactWords.matches]), so none is offered that doesn't ([choose]); a name no
 * contact has is said so and asked again ([TelephoneAskOutput]); "no, to Aaron" in reply to
 * "Which one?" looks Aaron up instead ([TelephoneCorrectionSkill]). English only, like GAR's
 * answers, except the whole words.
 */
object TelephoneContacts {
    /** "No, to mom" in reply to GAR's "Who?" is mom ([ContactWords.corrected]), in English. */
    fun spokenName(ctx: SkillContext, who: String): String =
        if (ctx.sentencesLanguage == ENGLISH) ContactWords.corrected(who) else who

    /**
     * What is called without looking at the contacts: a number spelled out digit by digit ("call
     * five five five one two one two") as is, or the owner ([ownOutput]). Null for anyone else.
     */
    suspend fun directOutput(
        ctx: SkillContext,
        telephone: TelephoneSkill,
        who: String,
        ownContact: (SkillContext) -> OwnContact = { OwnContact.of(it) },
    ): SkillOutput? {
        spokenPhoneNumber(who)?.let { return ConfirmCallOutput(it, it, telephone.yesNoData) }
        return ownOutput(ctx, telephone, who, ownContact)
    }

    /**
     * Dicio's choice among the contacts that [Contact.getFilteredSortedContacts] found for [who]
     * (moved here from [TelephoneSkill] to test it), with GAR's lines: only contacts that match by
     * whole words, one number stored in different forms once, and in English "I couldn't find
     * [who] in your contacts" when none is left and corrections in "Which one?".
     *
     * @param sorted the contacts, best first by [distanceOf] (lower is better)
     */
    fun <T> choose(
        ctx: SkillContext,
        telephone: TelephoneSkill,
        who: String,
        sorted: List<T>,
        nameOf: (T) -> String,
        distanceOf: (T) -> Int,
        numbersOf: (T) -> List<String>,
    ): SkillOutput {
        val english = ctx.sentencesLanguage == ENGLISH
        // GAR: by whole words, so that "erin" doesn't find "Katerina Book Club"
        val contacts = sorted.filter { ContactWords.matches(who, nameOf(it)) }
        val validContacts = ArrayList<Pair<String, List<String>>>()

        var i = 0
        while (validContacts.size < 5 && i < contacts.size) {
            val contact = contacts[i]
            // GAR: one number stored in different forms (435-555-0167, +14355550167) counts once
            val numbers = SmsNumbers.distinct(numbersOf(contact))
            if (numbers.isEmpty()) {
                ++i
                continue
            }
            if (validContacts.isEmpty()
                && distanceOf(contact) < 3
                && numbers.size == 1 // it has just one number
                && (contacts.size <= i + 1 // the next contact has a distance higher by 3+
                        || distanceOf(contacts[i + 1]) - 2 > distanceOf(contact))
            ) {
                // very close match with just one number and without distance ties: call it directly
                return ConfirmCallOutput(nameOf(contact), numbers[0], telephone.yesNoData)
            }
            validContacts.add(Pair(nameOf(contact), numbers))
            ++i
        }

        if (validContacts.size == 1 // there is exactly one valid contact and ...
            // ... either it has exactly one number, or we would be forced (because no number parser
            // is available) to use ContactChooserName, which only uses the first phone number
            // anyway
            && (validContacts[0].second.size == 1 || ctx.parserFormatter == null)
        ) {
            // not a good enough match, but since we have only this, call it directly
            val contact = validContacts[0]
            return ConfirmCallOutput(contact.first, contact.second[0], telephone.yesNoData)
        }

        // GAR: no contact matches: say so and ask again, instead of "No contact found, try again"
        if (validContacts.isEmpty() && english) {
            return TelephoneAskOutput(telephone, who)
        }

        // this point will not be reached if a very close match was found
        return TelephoneOutput(validContacts, telephone.yesNoData, telephone.takeIf { english })
    }

    /**
     * "Call myself", "call me": the owner's number, the numbers to choose from, or "Which contact
     * is you?" ([OwnContact]); null when [who] is someone else. English, like GAR's answers.
     */
    suspend fun ownOutput(
        ctx: SkillContext,
        telephone: TelephoneSkill,
        who: String,
        ownContact: (SkillContext) -> OwnContact = { OwnContact.of(it) },
    ): SkillOutput? {
        if (ctx.sentencesLanguage != ENGLISH || !ContactWords.isOwn(who)) {
            return null
        }
        val own = ownContact(ctx)
        val card = own.cards().firstOrNull { it.numbers.isNotEmpty() }
            ?: return OwnContactAskOutput(
                info = TelephoneInfo,
                own = own,
                then = { c, name -> telephone.generateOutput(c, Telephone.Dial(name)) },
                cancelled = { ConfirmedCallOutput(null) },
                needs = OwnNeed.NUMBER,
                nested = false,
            )
        val numbers = SmsNumbers.distinct(card.numbers)
        return if (numbers.size == 1) {
            ConfirmCallOutput(card.name, numbers[0], telephone.yesNoData)
        } else {
            TelephoneOutput(listOf(Pair(card.name, numbers)), telephone.yesNoData, telephone)
        }
    }

    /**
     * The telephone skill's answer for [name], said in reply to one of its questions: a number
     * spelled out digit by digit is dialed as is, a name is looked up.
     */
    suspend fun lookUp(ctx: SkillContext, telephone: TelephoneSkill, name: String): SkillOutput {
        spokenPhoneNumber(name)?.let { return ConfirmCallOutput(it, it, telephone.yesNoData) }
        // the plan as if the user had said "call <name>", without the question's batch
        return GarFollowUpOutput(telephone.generateOutput(ctx, Telephone.Dial(name)))
    }

    private const val ENGLISH = "en"
}

/**
 * GAR: "I couldn't find Erin in your contacts. Who should I call?", or "Who should I call?" with
 * no name, instead of Dicio's "No contact found, try again" or a list of contacts that only share
 * some letters with the name.
 *
 * @param notFound the name as said
 */
class TelephoneAskOutput(
    private val telephone: TelephoneSkill,
    val notFound: String,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = if (notFound.isBlank()) {
        ctx.getString(R.string.skill_telephone_ask_who)
    } else {
        ctx.getString(R.string.skill_telephone_not_found, notFound)
    }

    // like the telephone skill's ConfirmCallOutput
    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.ReplaceSubInteraction(
            reopenMicrophone = true,
            nextSkills = listOf(TelephoneAnswerSkill(telephone)),
        )
}

/**
 * GAR: an answer to [TelephoneAskOutput], a name or a number. It gives way to a new command
 * ("what time is it", "call mom"), like the answer to GAR's "Who?" ([GarOpenAnswer]).
 */
class TelephoneAnswerSkill(
    private val telephone: TelephoneSkill,
) : Skill<String>(TelephoneInfo, Specificity.HIGH), GarOpenAnswer {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String> {
        val trimmed = input.trim()
        return Pair(if (trimmed.isEmpty()) AlwaysWorstScore else AlwaysBestScore, trimmed)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput =
        if (isCancelPhrase(inputData)) {
            ConfirmedCallOutput(null)
        } else {
            TelephoneContacts.lookUp(ctx, telephone, recipientAnswer(inputData))
        }

    override fun yieldsTo(command: SkillWithResult<*>): Boolean = true

    companion object {
        private val LEADING_WORDS = setOf("call", "dial", "ring", "phone", "to", "try")

        /** "call mom", "no, to mom" -> "mom": the words that repeat the command are not the name. */
        fun recipientAnswer(input: String): String {
            val words = ContactWords.corrected(input).trim().split(Regex("\\s+"))
            return words.dropWhile { it.lowercase() in LEADING_WORDS }
                .ifEmpty { words }
                .joinToString(" ")
        }
    }
}

/** What [TelephoneCorrectionSkill] makes of an answer to "Which one?". */
sealed interface TelephoneCorrection {
    /** "No, not the first one, the second one": the number offered at that position. */
    data class Pick(val name: String, val number: String) : TelephoneCorrection

    /** "Not the first one", "no, not him": who it isn't, so "Which one?" is asked again. */
    data object Again : TelephoneCorrection

    /** "No, to Aaron": someone else, to look up. */
    data class LookUp(val name: String) : TelephoneCorrection
}

/**
 * GAR: in reply to the telephone skill's "Which one?", "no, to Aaron", "no I meant mom" and "not
 * him, dad" look the new name up instead of picking one of the contacts offered
 * ([ContactWords.correction]). A position after a no picks here, without the one after "not" ("no,
 * not the first one, the second one"), since Dicio's `ContactChooserIndex` reads the first number
 * said; a position alone is left to Dicio.
 *
 * @param contacts the contacts offered, with their numbers
 */
class TelephoneCorrectionSkill(
    private val telephone: TelephoneSkill,
    private val contacts: List<Pair<String, List<String>>>,
) : Skill<TelephoneCorrection?>(TelephoneInfo, Specificity.HIGH) {
    private val entries = contacts.flatMap { contact -> contact.second.map { Pair(contact.first, it) } }

    override fun score(ctx: SkillContext, input: String): Pair<Score, TelephoneCorrection?> {
        val answer = answerOf(input)
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    /** [input] as a correction, or null if it isn't one. */
    fun answerOf(input: String): TelephoneCorrection? {
        val names = contacts.map { it.first }
        if (ContactWords.rejectsOnly(input, names)) {
            return TelephoneCorrection.Again
        }
        val name = ContactWords.correction(input, names) ?: return null
        parseOptionIndex(name, entries.size)?.let {
            return TelephoneCorrection.Pick(entries[it].first, entries[it].second)
        }
        return TelephoneCorrection.LookUp(name).takeIf { normalizeAnswer(name).isNotEmpty() }
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: TelephoneCorrection?): SkillOutput =
        // AlwaysWorstScore is never chosen, so inputData can't be null here
        when (val answer = inputData!!) {
            is TelephoneCorrection.Pick ->
                ConfirmCallOutput(answer.name, answer.number, telephone.yesNoData)
            TelephoneCorrection.Again ->
                GarFollowUpOutput(TelephoneOutput(contacts, telephone.yesNoData, telephone))
            is TelephoneCorrection.LookUp -> TelephoneContacts.lookUp(ctx, telephone, answer.name)
        }
}
