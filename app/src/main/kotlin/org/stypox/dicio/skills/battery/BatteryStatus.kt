package org.stypox.dicio.skills.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.time.Duration

/**
 * The battery as Android reports it: no permission needed, also on the lock screen.
 *
 * @param percent the charge level, null when Android doesn't say
 * @param plugged connected to a charger (possibly not charging: full, or held at 80% by
 * adaptive charging)
 * @param timeToFull Android's estimate while charging, null when it has none
 */
data class BatteryStatus(
    val percent: Int?,
    val plugged: Boolean,
    val charging: Boolean,
    val full: Boolean,
    val timeToFull: Duration?,
) {
    companion object {
        fun read(context: Context): BatteryStatus {
            // the sticky broadcast: no receiver is registered
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val manager = context.getSystemService(BatteryManager::class.java)

            val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = capacity?.takeIf { it in 0..100 }
                ?: if (level >= 0 && scale > 0) level * 100 / scale else null

            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                (status == -1 && manager?.isCharging == true)
            val full = status == BatteryManager.BATTERY_STATUS_FULL || (plugged && percent == 100)
            val timeToFull = if (charging) {
                manager?.computeChargeTimeRemaining()?.takeIf { it > 0 }?.let(Duration::ofMillis)
            } else {
                null
            }
            return BatteryStatus(percent, plugged || charging, charging && !full, full, timeToFull)
        }
    }
}

/** What the user asked about the battery. */
enum class BatteryQuestion { LEVEL, CHARGING, CHARGE_TIME }

/** What GAR answers, by the question and the battery's state. */
enum class BatteryAnswer {
    /** "Your battery is at 76 percent." */
    LEVEL,
    /** "Your battery is at 76 percent and charging." */
    LEVEL_CHARGING,
    /** "Your battery is full." */
    FULL,
    /** "Yes, it's charging, at 76 percent." */
    CHARGING,
    /** "It's plugged in, but not charging right now. It's at 80 percent." */
    PLUGGED_NOT_CHARGING,
    /** "No, it isn't charging. It's at 54 percent." */
    NOT_CHARGING,
    /** "It'll be full in about 1 hour 20 minutes. It's at 63 percent." */
    TIME_TO_FULL,
    /** "It's charging, at 63 percent, but I can't tell how long it'll take." */
    CHARGING_NO_ESTIMATE,
    /** "I can't read the battery right now." */
    UNKNOWN;

    companion object {
        fun of(question: BatteryQuestion, status: BatteryStatus): BatteryAnswer = when {
            status.percent == null -> UNKNOWN
            status.full -> FULL
            question == BatteryQuestion.LEVEL -> if (status.charging) LEVEL_CHARGING else LEVEL
            status.charging && question == BatteryQuestion.CHARGING -> CHARGING
            status.charging ->
                if (status.timeToFull != null) TIME_TO_FULL else CHARGING_NO_ESTIMATE
            status.plugged -> PLUGGED_NOT_CHARGING
            else -> NOT_CHARGING
        }
    }
}
