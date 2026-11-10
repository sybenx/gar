package org.stypox.dicio.skills.battery

import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Battery

/**
 * "What's my battery", "is my phone charging", "how long until it's charged". Dicio's matcher
 * takes a sentence with a word left out or swapped, so a match only counts when the input names
 * the battery or charging and no other thing ([BatteryWords]): "turn on battery saver", "open
 * battery settings" and "is my watch charging" belong to other skills, or to none.
 */
class BatterySkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Battery>,
) : StandardRecognizerSkill<Battery>(correspondingSkillInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Battery> {
        val (score, sentence) = super.score(ctx, input)
        return Pair(if (BatteryWords.asksAboutBattery(input)) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Battery): SkillOutput {
        val question = when (inputData) {
            is Battery.Level -> BatteryQuestion.LEVEL
            is Battery.Charging -> BatteryQuestion.CHARGING
            is Battery.ChargeTime -> BatteryQuestion.CHARGE_TIME
        }
        return BatteryOutput(question, BatteryStatus.read(ctx.android))
    }
}

/** Whether an input is a question about the phone's own battery (English). */
object BatteryWords {
    fun asksAboutBattery(input: String): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        // "what percent is my phone at"; a percentage alone is math ("fifteen percent of eighty")
        val names = words.any { it in BATTERY_WORDS } ||
            ("phone" in words && words.any { it == "percent" || it == "percentage" })
        return names && words.none { it in OTHER_THINGS }
    }

    private val BATTERY_WORDS = setOf(
        "battery", "battery's", "batteries", "charge", "charged", "charging", "charger",
        "plugged", "power",
    )

    /**
     * Settings ("battery saver", "low power mode", "fast charging", "battery usage"), commands,
     * and other devices.
     */
    private val OTHER_THINGS = setOf(
        "saver", "saving", "save", "settings", "setting", "usage", "health", "mode", "wireless",
        "fast", "optimization", "optimisation", "optimized", "adaptive", "turn", "switch",
        "enable", "disable", "open", "off", "restart", "reboot", "bank", "button",
        "case", "car", "laptop", "computer", "watch", "headphones", "earbuds", "airpods",
        "buds", "controller", "mouse", "keyboard", "tablet", "ipad", "tesla", "cost", "costs",
        "remind", "reminder", "timer", "alarm", "buy", "order",
    )
}
