package org.stypox.dicio.skills.notes

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.skills.agenda.AgendaInfo
import org.stypox.dicio.skills.agenda.AgendaWords
import org.stypox.dicio.skills.agenda.ReminderWords
import org.stypox.dicio.skills.agenda.RemindersInfo
import org.stypox.dicio.skills.contact_info.ContactInfoInfo
import org.stypox.dicio.skills.contact_info.ContactInfoWords

/**
 * gar:notes. The model's calls of existing tools that gar:notes' skills answer, read in Kotlin
 * from the utterance so that the prompt stays as it is (gar/PARITY.md, batch 6, "Keeping the
 * model's prompt small"): a calendar or reminder call (`calendar_add`, `calendar_query`,
 * `reminder_add`) for "what's my next meeting", "what are my reminders", "add milk to my list",
 * "take a note ..." or "remember that ...", and a call or text (`call_contact`, `sms_send`) for
 * "what's mom's number". Only when the skill's own reading takes the whole utterance, as its
 * sentences would have; each skill checks the lock and its permissions itself. Null leaves the
 * call to its skill.
 */
object NotesCalls {
    /**
     * @param build the skill of an info, for [ctx] (tests record what runs)
     */
    suspend fun output(
        ctx: SkillContext,
        call: GarCall,
        build: (SkillInfo, SkillContext) -> Skill<*>? = { info, c -> info.build(c) },
    ): SkillOutput? {
        val utterance = call.utterance
        suspend fun run(info: SkillInfo, request: Any): SkillOutput? {
            @Suppress("UNCHECKED_CAST")
            val skill = build(info, ctx) as? Skill<Any?> ?: return null
            return skill.generateOutput(ctx, request)
        }
        return when (call.tool.skillId) {
            CALENDAR_SKILL ->
                AgendaWords.request(utterance)?.let { run(AgendaInfo, it) }
                    ?: ReminderWords.request(utterance)?.let { run(RemindersInfo, it) }
                    ?: ListWords.request(utterance)?.let { run(ListsInfo, it) }
                    ?: NoteWords.request(utterance)?.let { run(NotesInfo, it) }
                    ?: FactWords.request(utterance)
                        ?.takeIf {
                            it is FactRequest.Remember || it is FactRequest.AskWhat ||
                                it is FactRequest.Recall && it.always
                        }
                        ?.let { run(FactsInfo, it) }
            TELEPHONE_SKILL, SMS_SKILL ->
                ContactInfoWords.request(utterance)?.let { run(ContactInfoInfo, it) }
            else -> null
        }
    }

    private const val CALENDAR_SKILL = "calendar"
    private const val TELEPHONE_SKILL = "telephone"
    private const val SMS_SKILL = "sms"
}
