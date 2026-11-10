package org.stypox.dicio.skills.random

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.io.graphical.Subtitle
import org.stypox.dicio.util.getString

/** "It's heads." */
class CoinOutput(val heads: Boolean) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(if (heads) R.string.skill_random_heads else R.string.skill_random_tails)
}

/** "You rolled a 4.", "You rolled 3 and 5, 8 in total." */
class DiceOutput(val sides: Int, val rolls: List<Int>) : SkillOutput {
    val total: Int get() = rolls.sum()

    override fun getSpeechOutput(ctx: SkillContext): String = if (rolls.size == 1) {
        val roll = rolls[0]
        ctx.getString(if (startsWithVowelSound(roll)) R.string.skill_random_rolled_an
            else R.string.skill_random_rolled_a, roll)
    } else {
        val list = ctx.getString(
            R.string.skill_random_list_and,
            rolls.dropLast(1).joinToString(", "),
            rolls.last(),
        )
        ctx.getString(R.string.skill_random_rolled_dice, list, total)
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = total.toString())
            Subtitle(text = if (rolls.size == 1) {
                ctx.getString(R.string.skill_random_dice_card_one, sides)
            } else {
                ctx.getString(
                    R.string.skill_random_dice_card, rolls.size, sides, rolls.joinToString(" + "),
                )
            })
        }
    }

    companion object {
        /** 8, 11, 18, 80 to 89 and 800 to 899 are said with "an": "an 8", "an 11". */
        fun startsWithVowelSound(number: Int): Boolean =
            number == 8 || number == 11 || number == 18 || number in 80..89 || number in 800..899
    }
}

/** "It's 7.", with the range on the card. */
class NumberOutput(val range: RandomRequest.Pick, val value: Long) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_random_number, value)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = value.toString())
            Subtitle(text = ctx.getString(R.string.skill_random_number_range, range.low, range.high))
        }
    }
}
