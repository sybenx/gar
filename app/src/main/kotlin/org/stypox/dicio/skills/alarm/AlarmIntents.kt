package org.stypox.dicio.skills.alarm

import android.content.Intent
import android.provider.AlarmClock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.util.Calendar

/**
 * The `AlarmClock` intents the alarm skill sends to the clock app, as plain data so that they can
 * be checked without Android ([toIntent] builds the real intent).
 */
data class AlarmIntent(
    val action: String,
    /** Values are [Int], [Boolean], [String] or a list of [Int]. */
    val extras: Map<String, Any> = mapOf(),
) {
    fun toIntent(): Intent {
        val intent = Intent(action)
        for ((key, value) in extras) {
            when (value) {
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                is List<*> -> intent.putIntegerArrayListExtra(
                    key, ArrayList(value.map { it as Int })
                )
                else -> throw IllegalArgumentException("Unsupported extra $key=$value")
            }
        }
        // the skill runs outside of an activity context
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    companion object {
        /**
         * Sets an alarm without showing the clock app's UI. [days] become `EXTRA_DAYS`
         * (`Calendar.SUNDAY`..`Calendar.SATURDAY`), so the alarm repeats on them.
         */
        fun set(time: LocalTime, days: Set<DayOfWeek>, label: String?): AlarmIntent {
            val extras = linkedMapOf<String, Any>(
                AlarmClock.EXTRA_HOUR to time.hour,
                AlarmClock.EXTRA_MINUTES to time.minute,
                AlarmClock.EXTRA_SKIP_UI to true,
            )
            if (days.isNotEmpty()) {
                extras[AlarmClock.EXTRA_DAYS] = calendarDays(days)
            }
            if (!label.isNullOrBlank()) {
                extras[AlarmClock.EXTRA_MESSAGE] = label
            }
            return AlarmIntent(AlarmClock.ACTION_SET_ALARM, extras)
        }

        /**
         * Dismisses the alarms [target] selects. AOSP DeskClock opens itself for this, and only
         * dismisses alarms that ring within the next 24 hours. An upcoming alarm only skips its
         * next ring (a one-off alarm is then gone, a repeating one rings again on its next day);
         * a ringing or snoozed one stops. [CancelTarget.Next] is the ringing alarm while one
         * rings (`FetchMatchingAlarmsAction`: firing alarms first).
         *
         * By time, the hour is sent in 12-hour form with `EXTRA_IS_PM`: DeskClock rejects an hour
         * above 12 together with `EXTRA_IS_PM` = true, and adds 12 to hours below 12 when it is
         * true (12:xx stays 12, 0:xx is sent as hour 0 without pm).
         */
        fun dismiss(target: CancelTarget): AlarmIntent {
            val extras = when (target) {
                is CancelTarget.At -> mapOf(
                    AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_TIME,
                    AlarmClock.EXTRA_HOUR to
                        if (target.time.hour > 12) target.time.hour - 12 else target.time.hour,
                    AlarmClock.EXTRA_MINUTES to target.time.minute,
                    AlarmClock.EXTRA_IS_PM to (target.time.hour >= 12),
                )
                CancelTarget.Next -> mapOf(
                    AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_NEXT,
                )
                CancelTarget.All -> mapOf(
                    AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_ALL,
                )
            }
            return AlarmIntent(AlarmClock.ACTION_DISMISS_ALARM, extras)
        }

        /**
         * Snoozes the ringing alarms, without showing the clock app. AOSP DeskClock ignores
         * [duration] (`EXTRA_ALARM_SNOOZE_DURATION`, whole minutes, rounded up) and snoozes for
         * its own snooze length; other clock apps may take it.
         */
        fun snooze(duration: Duration?): AlarmIntent {
            val minutes = duration?.let {
                ((it.seconds + 59) / 60).coerceIn(1L, MAX_SNOOZE_MINUTES).toInt()
            }
            return AlarmIntent(
                AlarmClock.ACTION_SNOOZE_ALARM,
                minutes?.let { mapOf(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION to it) } ?: mapOf(),
            )
        }

        /** A day, more than any clock app would snooze. */
        private const val MAX_SNOOZE_MINUTES = 24 * 60L

        /** Opens the clock app's list of alarms. */
        fun show(): AlarmIntent = AlarmIntent(AlarmClock.ACTION_SHOW_ALARMS)

        /** `java.util.Calendar` day constants, Monday first, as `EXTRA_DAYS` expects them. */
        fun calendarDays(days: Set<DayOfWeek>): List<Int> = days.sorted().map {
            when (it) {
                DayOfWeek.MONDAY -> Calendar.MONDAY
                DayOfWeek.TUESDAY -> Calendar.TUESDAY
                DayOfWeek.WEDNESDAY -> Calendar.WEDNESDAY
                DayOfWeek.THURSDAY -> Calendar.THURSDAY
                DayOfWeek.FRIDAY -> Calendar.FRIDAY
                DayOfWeek.SATURDAY -> Calendar.SATURDAY
                DayOfWeek.SUNDAY -> Calendar.SUNDAY
            }
        }
    }
}
