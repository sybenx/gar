package org.stypox.dicio.skills.sms

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.sentences.Sentences.Sms

/**
 * Texts a contact or a number. Nothing is sent from here: the flow ends at a card that reads the
 * text back and sends it when the user says yes or taps Send (see [SmsFlow] and [SmsCardOutput]).
 *
 * Based on DicioTeam/dicio-android#375 by tylxr59, with the contact lookup of the telephone skill.
 */
class SmsSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Sms>,
    private val flow: SmsFlow,
) : StandardRecognizerSkill<Sms>(correspondingSkillInfo, data), GarToolSkill {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Sms): SkillOutput {
        return when (inputData) {
            is Sms.Send -> flow.start(ctx, inputData.who, inputData.what)
        }
    }

    /**
     * GAR's `sms_send` tool is LLM-only: its slots are named for the model (`to`, `message`),
     * while the sentence keeps the captures of the upstream PR (`who`, `what`). Both paths end in
     * the same [Sms.Send]. A message the model made up from the command is dropped (see
     * [SmsMessage.fromModel]), so the skill asks for it.
     */
    override fun inputFromSlots(slots: Map<String, Any?>): Any {
        val to = slots[SLOT_TO] as String?
        return Sms.Send(who = to, what = SmsMessage.fromModel(to, slots[SLOT_MESSAGE] as String?))
    }

    companion object {
        const val SLOT_TO = "to"
        const val SLOT_MESSAGE = "message"
    }
}
