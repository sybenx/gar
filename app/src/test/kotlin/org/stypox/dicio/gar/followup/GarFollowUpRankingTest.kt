package org.stypox.dicio.gar.followup

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.calls.PhoneCallState
import org.stypox.dicio.skills.conversation.ConversationInfo
import org.stypox.dicio.skills.conversation.ConversationSkill
import org.stypox.dicio.skills.media.MediaControlsInfo
import org.stypox.dicio.skills.media.MediaControlsSkill

/**
 * Every English skill of a new install in `SkillHandler`'s order, the follow-up skill first, while
 * media plays or not ([media]), something rings or not ([ringing]) and a call is on or not ([call]).
 */
private class FollowUpPhone(
    val media: Boolean = false,
    val ringing: Boolean = false,
    val call: Boolean = false,
) {
    val skills: List<Skill<*>> = GarOnlineSkills.forRanking(
        listOf<Skill<*>>(
            GarFollowUpSkill(
                GarFollowUpInfo,
                isLocked = { false },
                mediaPlaying = { media },
                callState = { if (call) PhoneCallState.IN_CALL else PhoneCallState.IDLE },
                ringingTakes = { _, _ -> ringing },
                now = { FOLLOW_UP_NOW },
            ),
        ) + allEnglishSkills().map(::withState),
        UserSettingsSerializer.defaultValue.enabledSkillsMap,
    )

    val ranker = SkillRanker(skills, MockSkill(Specificity.LOW, 0.0f))

    private fun withState(skill: Skill<*>): Skill<*> = when (skill) {
        is MediaControlsSkill -> MediaControlsSkill(
            MediaControlsInfo, Sentences.MediaControls["en"]!!, isPlaying = { media }, isRinging = { ringing },
        )
        is ConversationSkill ->
            ConversationSkill(ConversationInfo, Sentences.Conversation["en"]!!, isRinging = { ringing })
        else -> skill
    }

    fun skillOf(input: String): String? =
        ranker.getBest(FakeSkillContext().forInput(input), input)?.skill?.correspondingSkillInfo?.id

    /** The skills that score [input] the best there is. */
    fun alwaysBest(input: String): Set<String> = skills.filter {
        it.score(FakeSkillContext().forInput(input), input).first === AlwaysBestScore
    }.map { it.correspondingSkillInfo.id }.toSet()
}

/** Pronoun phrases that other skills read themselves, and their owners while media plays. */
private val MEDIA_CORPUS = listOf("play it again", "start it over", "restart it")
private val CALL_LOG_CORPUS = listOf("call them back", "ring them back", "call her back", "redial him")
private val MESSAGES_CORPUS = listOf("reply to her", "text him back")
private val CONVERSATION_CORPUS = listOf("cancel it", "stop it", "cancel that")
private val CORPUS = MEDIA_CORPUS + CALL_LOG_CORPUS + MESSAGES_CORPUS + CONVERSATION_CORPUS + "send it to voicemail"

/** The skills that may score a pronoun phrase the best there is, besides the follow-up skill. */
private val ALWAYS_BEST_OWNERS = setOf(
    MediaControlsInfo.id, "call_control", "call_log", "messages", "alarm", "timer_control", GarFollowUpInfo.id,
)

class GarFollowUpRankingTest : StringSpec({
    "with nothing remembered, the follow-up skill takes none of the pronoun phrases" {
        resetMemory()
        for (phone in listOf(FollowUpPhone(), FollowUpPhone(media = true), FollowUpPhone(ringing = true), FollowUpPhone(call = true))) {
            for (input in CORPUS) {
                withClue("$input (media ${phone.media}, ringing ${phone.ringing}, call ${phone.call})") {
                    phone.skillOf(input) shouldNotBe GarFollowUpInfo.id
                }
            }
        }
    }

    "with a turn remembered, the owners keep their phrases" {
        resetMemory()
        remember(flashlightOn())
        remember(answered(
            "contact_info", "what's erin's number", null,
            org.stypox.dicio.skills.contact_info.ContactInfoOutput.Found(
                "Erin", org.stypox.dicio.skills.contact_info.ContactField.NUMBER, listOf(), own = false,
            ),
        ))
        val playing = FollowUpPhone(media = true)
        for (input in MEDIA_CORPUS) {
            withClue(input) { playing.skillOf(input) shouldBe MediaControlsInfo.id }
        }
        val phone = FollowUpPhone()
        for (input in CALL_LOG_CORPUS + MESSAGES_CORPUS + CONVERSATION_CORPUS + "send it to voicemail") {
            withClue(input) { phone.skillOf(input) shouldNotBe GarFollowUpInfo.id }
        }
        phone.skillOf("send it to voicemail") shouldBe "call_control"
        phone.skillOf("call them back") shouldBe "call_log"
        phone.skillOf("cancel it") shouldBe ConversationInfo.id
        // its own forms
        phone.skillOf("turn it off") shouldBe GarFollowUpInfo.id
        phone.skillOf("call her") shouldBe GarFollowUpInfo.id
        // a call on, something ringing: never the follow-up skill
        FollowUpPhone(call = true).skillOf("turn it off") shouldNotBe GarFollowUpInfo.id
        FollowUpPhone(ringing = true).skillOf("turn it off") shouldNotBe GarFollowUpInfo.id
    }

    "cancel it right after an alarm is the follow-up's, else the conversation's" {
        val clock = resetMemory()
        remember(alarmAt(7))
        FollowUpPhone().skillOf("cancel it") shouldBe GarFollowUpInfo.id
        // a ringing alarm or timer still takes it
        FollowUpPhone(ringing = true).skillOf("cancel it") shouldNotBe GarFollowUpInfo.id
        clock.advance(61_000L)
        FollowUpPhone().skillOf("cancel it") shouldBe ConversationInfo.id
        // small talk answered after the alarm: the conversation's again
        resetMemory()
        remember(alarmAt(7))
        remember(answered("small_talk", "how are you", null, org.stypox.dicio.gar.FakeOutput(null)))
        FollowUpPhone().skillOf("cancel it") shouldBe ConversationInfo.id
    }

    "turn it off after the music pauses it, not an older setting" {
        resetMemory()
        remember(flashlightOn())
        remember(answered("media_play", "play my road trip playlist", null, org.stypox.dicio.gar.FakeOutput(null)))
        val playing = FollowUpPhone(media = true)
        playing.skillOf("turn it off") shouldBe GarFollowUpInfo.id
        GarFollowUpSkill.scored = null
        playing.ranker.getBest(FakeSkillContext().forInput("turn it off"), "turn it off")!!.inputData shouldBe
            GarFollowUpMatch(
                cc.aaro.gar.router.followup.FollowUpForm.Switch(on = false, back = false),
                GarFollowUpOutcome.Rewritten(GarFollowUpReach.PAUSE_MUSIC),
                null,
            )
    }

    "whole sentences of other skills win over the follow-up skill" {
        resetMemory()
        remember(flashlightOn())
        val phone = FollowUpPhone()
        for ((input, skill) in listOf(
            "turn off the flashlight" to "flashlight",
            "what's the weather" to "weather",
            "set an alarm for 7 am" to "alarm",
            "repeat that" to ConversationInfo.id,
            "stop" to ConversationInfo.id,
        )) {
            withClue(input) { phone.skillOf(input) shouldBe skill }
        }
    }

    "the skills that score a pronoun phrase best" {
        resetMemory()
        remember(alarmAt(7))
        for (phone in listOf(FollowUpPhone(), FollowUpPhone(media = true), FollowUpPhone(call = true))) {
            for (input in CORPUS) {
                for (id in phone.alwaysBest(input)) {
                    withClue("$input: $id") { id shouldBeIn ALWAYS_BEST_OWNERS }
                }
            }
        }
    }
})
