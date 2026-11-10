package org.stypox.dicio.skills.alarm

import android.app.AlarmManager
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.provider.AlarmClock
import android.util.Log
import org.stypox.dicio.skills.notify.NotifyHandler
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The next alarm of any app, as `AlarmManager.getNextAlarmClock()` reports it. */
data class NextAlarm(
    val time: LocalDateTime,
    /** The name of the app that set it, or null when a clock app set it (or it is unknown). */
    val setBy: String?,
)

/** What the alarm skill needs from the phone: [AndroidClockApp], or a fake in tests. */
interface ClockApp {
    /** Whether an app handles [intent]. */
    fun canHandle(intent: AlarmIntent): Boolean

    /** Starts the app that handles [intent]; false if there is none or it failed. */
    fun start(intent: AlarmIntent): Boolean

    fun nextAlarm(): NextAlarm?

    /**
     * Whether an alarm or a timer, of any app, is ringing right now. A ringing
     * alarm is no longer in [nextAlarm]. Which one rings only [FiringAlarms] can tell.
     */
    fun isAlarmRinging(): Boolean
}

/** Whether the clock app's alarm rings, as its notifications tell ([FiringAlarms]). */
sealed interface FiringAlarmCheck {
    /** GAR has no notification access, so it can't tell. */
    data object NoAccess : FiringAlarmCheck

    /** The clock app shows no ringing alarm (a timer or another app's alarm may still ring). */
    data object None : FiringAlarmCheck

    data object Firing : FiringAlarmCheck
}

/**
 * The clock app's ringing alarm, seen through Dicio's notification listener ([NotifyHandler]),
 * which GAR has when the user gave it notification access. AlarmClock intents can't tell whether
 * an alarm rings, and the sound can't tell an alarm from a timer ([ClockApp.isAlarmRinging]).
 *
 * AOSP DeskClock (GrapheneOS's clock, checked on its branch 17) posts its ringing alarm from a
 * foreground service with [Notification.CATEGORY_ALARM] and a full-screen intent. Its ringing
 * timers have no category, its running timers have the category but are neither, and its
 * upcoming, snoozed and missed alarms are [Notification.CATEGORY_EVENT]. Android 14+ drops the
 * full-screen intent when the user takes that permission away, so the foreground service counts
 * too. Only the app that the alarm intents reach counts: another app's ringing alarm would make
 * GAR dismiss the clock app's next alarm instead.
 */
object FiringAlarms {
    private const val TAG = "FiringAlarms"

    /** Asks the listener's notifications; answers at once and never throws. */
    fun current(): FiringAlarmCheck = scan(AlarmClock.ACTION_DISMISS_ALARM, ::isFiringAlarm)

    /** Whether a notification is the ringing alarm of [clockPackage], see [FiringAlarms]. */
    fun isFiringAlarm(
        packageName: String,
        clockPackage: String?,
        category: String?,
        flags: Int,
        hasFullScreenIntent: Boolean,
        sortKey: String? = null,
    ): Boolean = packageName == clockPackage &&
        category == Notification.CATEGORY_ALARM &&
        !isExpiredTimer(sortKey) &&
        (hasFullScreenIntent || (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0)

    /**
     * Google Clock's ringing timer has [Notification.CATEGORY_ALARM] like its ringing alarm; only
     * its sort key tells them apart ("...|EXPIRED|..."). Seen on the emulator's Google Clock.
     */
    internal fun isExpiredTimer(sortKey: String?): Boolean = sortKey?.contains("|EXPIRED|") == true

    /**
     * Whether a notification of the app that handles [action] passes [test], as [FiringAlarmCheck]
     * (GAR: shared by the ringing alarm and the ringing timer, [FiringTimers]).
     */
    internal fun scan(
        action: String,
        test: (packageName: String, clockPackage: String?, category: String?, flags: Int,
               hasFullScreenIntent: Boolean, sortKey: String?) -> Boolean,
    ): FiringAlarmCheck {
        val listener = NotifyHandler.Instance ?: return FiringAlarmCheck.NoAccess
        return try {
            // null while the listener isn't connected
            val notifications = listener.activeNotifications ?: return FiringAlarmCheck.NoAccess
            val clock = clockPackage(listener.packageManager, action)
            val firing = notifications.any {
                test(
                    it.packageName,
                    clock,
                    it.notification.category,
                    it.notification.flags,
                    it.notification.fullScreenIntent != null,
                    it.notification.sortKey,
                )
            }
            if (firing) FiringAlarmCheck.Firing else FiringAlarmCheck.None
        } catch (e: RuntimeException) {
            // SecurityException when the access was just taken away
            Log.w(TAG, "Can't read the notifications", e)
            FiringAlarmCheck.NoAccess
        }
    }

    /**
     * The app that [action] reaches, or null if none or the user would have to pick one (the
     * chooser is the system's).
     */
    private fun clockPackage(packageManager: PackageManager, action: String): String? =
        packageManager.resolveActivity(
            Intent(action), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName?.takeIf { it != "android" }
}

/**
 * The clock app's ringing timer (GAR, gar/DESIGN.md "Timers"), seen like its ringing alarm
 * ([FiringAlarms], the same [FiringAlarmCheck] answers). AOSP DeskClock (GrapheneOS's clock,
 * branch 17: `TimerNotificationBuilder.buildHeadsUp`, `TimerModel.updateHeadsUpNotification`)
 * posts its ringing timers from its foreground `TimerService` on the channel
 * `firingAlarmsAndTimersNotification`, which its ringing alarm shares, with a full-screen intent
 * and no category. Its running timers (channel `timerNotification`) and its ringing alarm have
 * [Notification.CATEGORY_ALARM]; its other alarm notifications [Notification.CATEGORY_EVENT]; its
 * stopwatch is no foreground service. Google Clock's ringing timer has the alarm's category and
 * "|EXPIRED|" in its sort key ([FiringAlarms.isExpiredTimer]). Only the app that
 * `ACTION_DISMISS_TIMER` reaches counts.
 */
object FiringTimers {
    /** Asks the listener's notifications; answers at once and never throws. */
    fun current(): FiringAlarmCheck = FiringAlarms.scan(AlarmClock.ACTION_DISMISS_TIMER, ::isFiringTimer)

    /** Whether a notification is the ringing timer of [clockPackage], see [FiringTimers]. */
    fun isFiringTimer(
        packageName: String,
        clockPackage: String?,
        category: String?,
        flags: Int,
        hasFullScreenIntent: Boolean,
        sortKey: String? = null,
    ): Boolean = packageName == clockPackage &&
        (category == null || FiringAlarms.isExpiredTimer(sortKey)) &&
        (hasFullScreenIntent || (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0)
}

class AndroidClockApp(private val context: Context) : ClockApp {

    override fun canHandle(intent: AlarmIntent): Boolean =
        context.packageManager.resolveActivity(
            intent.toIntent(), PackageManager.MATCH_DEFAULT_ONLY
        ) != null

    override fun start(intent: AlarmIntent): Boolean {
        if (!canHandle(intent)) {
            return false
        }
        return try {
            context.startActivity(intent.toIntent())
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app for ${intent.action}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start ${intent.action}", e)
            false
        }
    }

    override fun nextAlarm(): NextAlarm? {
        val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            ?: return null
        val time = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(info.triggerTime), ZoneId.systemDefault()
        )
        val creator = info.showIntent?.creatorPackage
        // the package name if there's no label: an unnamed app must not pass for the clock app
        val setBy = creator?.takeIf { !isClockApp(it) }?.let { appLabel(it) ?: it }
        return NextAlarm(time, setBy)
    }

    /**
     * AOSP DeskClock plays alarms and timers with [AudioAttributes.USAGE_ALARM] and
     * [AudioAttributes.CONTENT_TYPE_SONIFICATION]. Other apps' players are listed without who
     * plays them, but with their attributes, and no permission is needed. The content type keeps
     * out Dicio's own listening sound, which can use the alarm usage (a setting) without a
     * content type, and whose players are never released.
     */
    override fun isAlarmRinging(): Boolean = try {
        context.getSystemService(AudioManager::class.java)?.activePlaybackConfigurations
            ?.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_ALARM &&
                    it.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION
            } == true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Can't list the playing audio", e)
        false
    }

    /** Whether [packageName] is an app that sets alarms for others, i.e. a clock app. */
    private fun isClockApp(packageName: String): Boolean = try {
        context.packageManager.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), 0)
            .any { it.activityInfo.packageName == packageName }
    } catch (e: Exception) {
        Log.w(TAG, "Can't list the clock apps", e)
        true // then don't name the app
    }

    private fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        private const val TAG = "AlarmSkill"
    }
}
