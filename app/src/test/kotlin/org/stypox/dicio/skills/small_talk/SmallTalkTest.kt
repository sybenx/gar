package org.stypox.dicio.skills.small_talk

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.SmallTalk

private val skill = SmallTalkSkill(SmallTalkInfo, Sentences.SmallTalk["en"]!!)

private fun match(input: String): SmallTalkMatch? {
    val (score, match) = skill.score(FakeSkillContext().forInput(input), input)
    return match.takeIf { score != AlwaysWorstScore }
}

class SmallTalkTest : StringSpec({
    "small talk as people say it" {
        for ((input, sentence) in listOf(
            "hello" to SmallTalk.Hello, "hi there" to SmallTalk.Hello, "hey gar" to SmallTalk.Hello,
            "good morning" to SmallTalk.Hello, "bye" to SmallTalk.Goodbye,
            "good night" to SmallTalk.Goodbye, "thank you" to SmallTalk.Thanks,
            "thanks a lot" to SmallTalk.Thanks, "thank you so much" to SmallTalk.Thanks,
            "you're the best" to SmallTalk.Thanks, "who are you" to SmallTalk.Who,
            "what's your name" to SmallTalk.Who, "how are you" to SmallTalk.How,
            "how's it going" to SmallTalk.How, "what can you do" to SmallTalk.Help,
            "help" to SmallTalk.Help, "what can i say" to SmallTalk.Help,
            "ok thank you" to SmallTalk.Thanks, "perfect thanks" to SmallTalk.Thanks,
            "thanks google" to SmallTalk.Thanks, "goodnight" to SmallTalk.Goodbye,
        )) {
            withClue(input) { match(input)?.sentence shouldBe sentence }
        }
        match("good morning")?.partOfDay shouldBe PartOfDay.MORNING
        match("good evening gar")?.partOfDay shouldBe PartOfDay.EVENING
        match("hello")?.partOfDay.shouldBeNull()
    }

    "inputs with other words aren't small talk" {
        for (input in listOf(
            "who are you calling", "thank you set a timer", "help me find my phone",
            "what can you do about the noise", "hello world", "say hello to mom",
            "what's your favorite color", "how are you doing that", "what's up with the weather",
            "no thanks", "never mind", "stop",
        )) {
            withClue(input) { match(input).shouldBeNull() }
        }
    }

    "greetings and how are you listen for the answer, thanks don't" {
        val ctx = FakeSkillContext()
        GreetingOutput(null).getInteractionPlan(ctx)
            .shouldBeInstanceOf<InteractionPlan.FinishSubInteraction>().reopenMicrophone shouldBe true
        SmallTalkOutput(SmallTalkAnswer.HOW).getInteractionPlan(ctx)
            .shouldBeInstanceOf<InteractionPlan.FinishSubInteraction>().reopenMicrophone shouldBe true
        SmallTalkOutput(SmallTalkAnswer.THANKS).getInteractionPlan(ctx) shouldBe
            InteractionPlan.FinishInteraction
    }
})
