package org.stypox.dicio.gar.followup

import cc.aaro.gar.router.followup.CorrectionKind
import cc.aaro.gar.router.followup.FollowUpForm
import cc.aaro.gar.router.followup.FollowUpForms
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.followup.adapters.AgendaFollowUps
import org.stypox.dicio.gar.shouldBeTakenBy
import org.stypox.dicio.skills.agenda.AgendaRequest

private fun search(about: String): GarRememberedTurn {
    val request = AgendaRequest.Next(about)
    return rememberedBy(AgendaFollowUps, answered("agenda", "when is my $about appointment", request, FakeOutput(request)))
}

/** What the follow-up [said] after [turn] becomes, through the real form parser. */
private fun rewrite(said: String, turn: GarRememberedTurn): String? {
    val form = FollowUpForms.parse(said) ?: return null
    return rewriteOf(AgendaFollowUps, form, turn).command
}

class AgendaFollowUpsTest : StringSpec({
    "after a search by name, another name searches again (2026-11-05)" {
        val miss = search("haircut")
        miss.turn.kind shouldBe GarTurnKind.ANSWER
        for (said in listOf(
            "Any haircut?", "No, check for haircut appointment.",
            "what about haircut", "is there any haircut", "I meant haircut",
        )) {
            withClue(said) { rewrite(said, miss) shouldBe "when is my haircut appointment" }
        }
        rewrite("any dentist appointments", miss) shouldBe "when is my dentist appointment"
        shouldBeTakenBy("when is my haircut appointment", "agenda", mapOf("about" to "haircut"))
    }

    "no event name, a day or something else is no new search" {
        val miss = search("haircut")
        for (said in listOf("any messages", "what about tomorrow", "anything else", "no check for it")) {
            withClue(said) { rewrite(said, miss).shouldBeNull() }
        }
        AgendaFollowUps.about("check for") shouldBe null
        AgendaFollowUps.about("check for the next appointment") shouldBe null
    }

    "moving and deleting stay bare changes" {
        val move = rememberedBy(
            AgendaFollowUps,
            answered("agenda", "move my meeting to 3", AgendaRequest.Move("my meeting", "", "3"), FakeOutput("")),
        )
        move.turn.kind shouldBe GarTurnKind.MODIFIABLE
        rewriteOf(AgendaFollowUps, FollowUpForm.AnyOf("haircut"), move).shouldBeNull()
        rewriteOf(AgendaFollowUps, FollowUpForm.Correction("haircut", CorrectionKind.NO), move).shouldBeNull()
    }
})
