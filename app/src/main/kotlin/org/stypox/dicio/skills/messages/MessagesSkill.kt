package org.stypox.dicio.skills.messages

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.sentences.Sentences.Messages

/** What a match of the messages sentences asks for. */
sealed interface MessagesCommand {
    data class Read(val request: ReadRequest) : MessagesCommand

    /** @param who as said, or null for the message GAR read last; [what] the reply as said */
    data class Reply(val who: String?, val what: String?) : MessagesCommand
}

/**
 * gar:messages (gar/PARITY.md, batch 4): reads the messages among the notifications by person or
 * app ("read my messages from mom", "any new messages on signal", "what did dad say") and replies
 * through a notification's reply button ("reply to mom saying i'm on my way", "reply ok" after
 * GAR read a message), with the text card's read-back and spoken yes. English only, no tool: the
 * model's read_notifications and sms_send calls reach the same flow (`MessagesCalls`).
 *
 * Dicio's matcher accepts a sentence with a word left out or swapped, and the captures take any
 * words, so a match only counts when [MessageWords] says the input is about messages ("read my
 * notifications" stays with Dicio's notify skill, "text mom" with the sms skill).
 */
class MessagesSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Messages>,
    private val flow: MessagesFlow,
) : Skill<MessagesCommand?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, MessagesCommand?> {
        val (score, match) = data.score(ctx, input)
        val command = when (match) {
            is Messages.Read -> MessageWords.readRequest(input, match.source)
                ?.let { MessagesCommand.Read(it) }
            Messages.Who -> MessageWords.readRequest(input, null, namesOnly = true)
                ?.let { MessagesCommand.Read(it) }
            is Messages.Reply -> if (MessageWords.isReply(input, match.who, match.what)) {
                // captures keep the spaces around them
                MessagesCommand.Reply(
                    match.who?.trim()?.takeIf { it.isNotEmpty() },
                    match.what?.trim()?.takeIf { it.isNotEmpty() },
                )
            } else {
                null
            }
        }
        return Pair(if (command == null) AlwaysWorstScore else score, command)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: MessagesCommand?): SkillOutput =
        when (inputData) {
            is MessagesCommand.Read -> flow.read(ctx, inputData.request)
            is MessagesCommand.Reply -> flow.reply(ctx, inputData.who, inputData.what, nested = false)
            null -> throw IllegalStateException("a messages match that isn't about messages ran")
        }
}
