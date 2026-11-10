package org.stypox.dicio.skills.device_settings

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The brightness slider's curve, so that "fifty percent" is what Settings and the slider call
 * fifty percent. Android stores the brightness linearly (`Settings.System.SCREEN_BRIGHTNESS`, 1
 * to 255 in use, 0 is off), while the slider moves along a hybrid log-gamma curve: half way
 * along it is about a twelfth of the linear range. Same constants and formulas as SettingsLib's
 * `BrightnessUtils.convertLinearToGammaFloat` and `convertGammaToLinearFloat`.
 */
object BrightnessCurve {
    /** The darkest setting that isn't off. */
    const val MIN_SETTING = 1

    const val MAX_SETTING = 255

    private const val R = 0.5
    private const val A = 0.17883277
    private const val B = 0.28466892
    private const val C = 0.55991073

    /** HLG works on the range 0 to 12. */
    private const val HLG_MAX = 12.0

    /** The slider's position (0 to 100) for a `SCREEN_BRIGHTNESS` value. */
    fun percentOf(setting: Int): Int {
        val linear = (setting.coerceIn(MIN_SETTING, MAX_SETTING) - MIN_SETTING).toDouble() /
            (MAX_SETTING - MIN_SETTING) * HLG_MAX
        val gamma = if (linear <= 1.0) sqrt(linear) * R else A * ln(linear - B) + C
        return (gamma * 100.0).roundToInt().coerceIn(0, 100)
    }

    /** The `SCREEN_BRIGHTNESS` value for a slider position (0 to 100). */
    fun settingOf(percent: Int): Int {
        val gamma = percent.coerceIn(0, 100) / 100.0
        val linear = if (gamma <= R) (gamma / R) * (gamma / R) else exp((gamma - C) / A) + B
        val share = linear.coerceIn(0.0, HLG_MAX) / HLG_MAX
        return (MIN_SETTING + share * (MAX_SETTING - MIN_SETTING)).roundToInt()
            .coerceIn(MIN_SETTING, MAX_SETTING)
    }
}
