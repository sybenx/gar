package org.stypox.dicio.gar.call

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput

class GarShowsOutputTest : StringSpec({
    class Card : SkillOutput {
        override fun getSpeechOutput(ctx: SkillContext) = ""

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
        }
    }

    "the card itself is the latest answer" {
        val card = Card()
        card.showsOutput(card).shouldBeTrue()
    }

    "a card wrapped in a follow-up (weather place question after the online yes) still counts" {
        val card = Card()
        GarFollowUpOutput(card).showsOutput(card).shouldBeTrue()
    }

    "another answer, or none, does not" {
        val card = Card()
        Card().showsOutput(card).shouldBeFalse()
        GarFollowUpOutput(Card()).showsOutput(card).shouldBeFalse()
        (null as SkillOutput?).showsOutput(card).shouldBeFalse()
    }
})
