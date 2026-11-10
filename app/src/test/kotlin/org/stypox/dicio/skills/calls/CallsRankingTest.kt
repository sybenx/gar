package org.stypox.dicio.skills.calls

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.ENGLISH_PARSER_FORMATTER
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.gar.allEnglishSkills
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.sentences.Sentences.CallControl
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import java.io.File

/** Every English skill of a new install, ranked like the evaluator does. */
private val ranker = SkillRanker(
    GarOnlineSkills.forRanking(allEnglishSkills(), UserSettingsSerializer.defaultValue.enabledSkillsMap),
    MockSkill(Specificity.LOW, 0.0f),
)

private val matchCheck = GarMatchCheck.State(
    ToolCatalog.load(
        File("src/main/gar/tools.yml").readText(),
        File("src/main/sentences/skill_definitions.yml").readText(),
    ).tools,
    parserFormatter = ENGLISH_PARSER_FORMATTER,
)

/** The skill and input data that win [input], and whether GAR's match check would reject it. */
private fun rank(input: String): Triple<String?, Any?, Boolean> {
    val best = ranker.getBest(FakeSkillContext().forInput(input), input)
    return Triple(
        best?.skill?.correspondingSkillInfo?.id,
        best?.inputData,
        best != null && GarMatchCheck.rejects(matchCheck, best, input),
    )
}

private fun call(target: CallTarget) = CallLogRequest.Call(target)

/**
 * gar:calls (gar/PARITY.md, batch 4): the call log and the call controls take their phrasings
 * from every other English skill, unchecked by GAR's match check, and nothing else: Dicio's
 * matcher would give them inputs with a word left out or swapped.
 */
class CallsRankingTest : StringSpec({
    "the call log takes its questions and call backs" {
        val incoming = call(CallTarget.LastIncoming)
        val outgoing = call(CallTarget.LastOutgoing)
        val expected = listOf(
            "who called me" to CallLogRequest.LastCaller,
            "who just called" to CallLogRequest.LastCaller,
            "who called me today" to CallLogRequest.LastCaller,
            "who called me this morning" to CallLogRequest.LastCaller,
            "ok who just called me" to CallLogRequest.LastCaller,
            // one person's calls (they went to the model, which would call that person)
            "did mom call me" to CallLogRequest.From("mom"),
            "has mom called" to CallLogRequest.From("mom"),
            "did dad try to call me" to CallLogRequest.From("dad"),
            "did i miss a call from mom" to CallLogRequest.From("mom"),
            "any calls from mom" to CallLogRequest.From("mom"),
            "missed call from mom" to CallLogRequest.From("mom"),
            "when did mom call" to CallLogRequest.From("mom"),
            "who was calling" to CallLogRequest.LastCaller,
            "who's been calling me" to CallLogRequest.LastCaller,
            "who was the last caller" to CallLogRequest.LastCaller,
            "what was my last call" to CallLogRequest.LastCaller,
            "show my recent calls" to CallLogRequest.LastCaller,
            "did i miss any calls" to CallLogRequest.Missed,
            "did i miss a call" to CallLogRequest.Missed,
            "do i have any missed calls" to CallLogRequest.Missed,
            "any missed calls" to CallLogRequest.Missed,
            "how many missed calls do i have" to CallLogRequest.Missed,
            "show me my missed calls" to CallLogRequest.Missed,
            "missed calls" to CallLogRequest.Missed,
            "did anyone call" to CallLogRequest.Missed,
            "did anybody call me today" to CallLogRequest.Missed,
            "has anyone called" to CallLogRequest.Missed,
            "redial" to outgoing,
            "redial the last number" to outgoing,
            "call the last number again" to outgoing,
            "call the last number i dialed" to outgoing,
            "call again" to outgoing,
            "call back" to incoming,
            "call them back" to incoming,
            "call back the last number" to incoming,
            "call back the last caller" to incoming,
            "call back whoever just called" to incoming,
            "call whoever just called me" to incoming,
            "ring them back" to incoming,
            "return the call" to incoming,
            "return my last missed call" to incoming,
            "call the last number" to call(CallTarget.Last),
            "call back mom" to call(CallTarget.Named("mom", cameIn = true)),
            "call mom back" to call(CallTarget.Named("mom", cameIn = true)),
            "call john smith back" to call(CallTarget.Named("john smith", cameIn = true)),
            "return mom's call" to call(CallTarget.Named("moms", cameIn = true)),
            "call dad again" to call(CallTarget.Named("dad", cameIn = false)),
        )
        for ((input, request) in expected) {
            withClue(input) { rank(input) shouldBe Triple(CallLogInfo.id, request, false) }
        }
    }

    "the call controls take their commands" {
        val expected = listOf(
            "answer" to CallControl.Answer,
            "answer the call" to CallControl.Answer,
            "answer it" to CallControl.Answer,
            "answer the phone" to CallControl.Answer,
            "pick up" to CallControl.Answer,
            "pick up the phone" to CallControl.Answer,
            "pick it up" to CallControl.Answer,
            "accept the call" to CallControl.Answer,
            "take the call" to CallControl.Answer,
            "ok answer it" to CallControl.Answer,
            "decline" to CallControl.Decline,
            "decline the call" to CallControl.Decline,
            "reject the call" to CallControl.Decline,
            "ignore the call" to CallControl.Decline,
            "don't answer it" to CallControl.Decline,
            "send it to voicemail" to CallControl.Decline,
            "hang up" to CallControl.HangUp,
            "hang up the phone" to CallControl.HangUp,
            "end the call" to CallControl.HangUp,
            "end call" to CallControl.HangUp,
            "stop the call" to CallControl.HangUp,
            "disconnect the call" to CallControl.HangUp,
            "please hang up" to CallControl.HangUp,
            "speaker on" to CallControl.SpeakerOn,
            "turn on the speaker" to CallControl.SpeakerOn,
            "turn on speakerphone" to CallControl.SpeakerOn,
            "put it on speaker" to CallControl.SpeakerOn,
            "put the call on speaker" to CallControl.SpeakerOn,
            "switch to speaker" to CallControl.SpeakerOn,
            "speakerphone" to CallControl.SpeakerOn,
            "speaker off" to CallControl.SpeakerOff,
            "turn off the speaker" to CallControl.SpeakerOff,
            "take it off speaker" to CallControl.SpeakerOff,
            "turn the speakerphone off" to CallControl.SpeakerOff,
        )
        for ((input, command) in expected) {
            withClue(input) { rank(input) shouldBe Triple(CallControlInfo.id, command, false) }
        }
    }

    "other skills keep theirs" {
        val expected = listOf(
            "call mom" to "telephone",
            "call john smith" to "telephone",
            "phone dad" to "telephone",
            "text mom saying i'm on my way" to "sms",
            "stop" to "conversation",
            "never mind" to "conversation",
            "turn on bluetooth" to "connectivity",
            "turn up the volume" to "volume",
            "set a timer for ten minutes" to "timer",
        )
        for ((input, skill) in expected) {
            withClue(input) { rank(input).first shouldBe skill }
        }
    }

    "near misses aren't calls" {
        for (input in listOf(
            "who called the plumber", "who is calling the shots", "pick up milk",
            "pick up the kids at five", "end the meeting", "answer the question",
            "decline the invitation", "call back in five minutes", "call me back", "did i miss anything",
            "what did i miss", "turn on the lights", "put it on my calendar", "turn off the tv",
            "hang up the laundry", "who called it", "speaker volume up", "return the book",
            "send it to mom", "redial the dentist tomorrow", "did you call mom",
            "did someone call the plumber",
        )) {
            withClue(input) {
                val (skill, _, _) = rank(input)
                skill shouldNotBe CallLogInfo.id
                skill shouldNotBe CallControlInfo.id
            }
        }
    }
})
