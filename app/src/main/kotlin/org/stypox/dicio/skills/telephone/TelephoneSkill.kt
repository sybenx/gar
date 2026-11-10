package org.stypox.dicio.skills.telephone

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Telephone
import org.stypox.dicio.skills.calls.CallBack // GAR

class TelephoneSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Telephone>,
    val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
) : StandardRecognizerSkill<Telephone>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Telephone): SkillOutput {
        val contentResolver = ctx.android.contentResolver
        val userContactName = when (inputData) {
            // GAR: "no, to mom" in reply to GAR's "Who?" is mom (ContactWords.correction)
            is Telephone.Dial ->
                inputData.who?.let { TelephoneContacts.spokenName(ctx, it) }?.trim { it <= ' ' } ?: ""
        }
        // GAR: "back", "the last number", "mom back" (Dicio's "call .who." or the model's
        // call_contact): the number comes from the call log (gar/PARITY.md, batch 4)
        CallBack.telephoneOutput(ctx, this, userContactName)?.let { return it }
        // GAR: a number spelled out digit by digit is dialed as is, and "call myself" is the
        // owner's card, or GAR asks which contact is them
        TelephoneContacts.directOutput(ctx, this, userContactName)?.let { return it }
        val contacts = Contact.getFilteredSortedContacts(contentResolver, userContactName)
        // GAR: Dicio's choice among the contacts, with GAR's lines (by whole words, "I couldn't
        // find", corrections), moved to TelephoneContacts.choose to test it
        return TelephoneContacts.choose(
            ctx, this, userContactName, contacts, Contact::name, Contact::distance
        ) { it.getNumbers(contentResolver) }
    }

    companion object {
        fun call(context: Context, number: String?) {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$number")
            callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(callIntent)
        }
    }
}
