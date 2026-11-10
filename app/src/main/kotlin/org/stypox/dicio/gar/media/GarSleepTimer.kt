package org.stypox.dicio.gar.media

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json
import org.stypox.dicio.gar.timer.GarTimerClock
import org.stypox.dicio.gar.timer.GarTimerScheduler
import org.stypox.dicio.gar.timer.GarTimerStore
import org.stypox.dicio.gar.timer.Moment
import org.stypox.dicio.skills.stopwatch.StopwatchController

/**
 * The media sleep timer: "stop the music in thirty minutes" pauses what plays when it ends, and
 * rings nothing. One at a time; a new one replaces it.
 *
 * The clock app's timers ring and can't carry another action, so this is a small exact alarm of
 * GAR's own: the end is a [Moment] (elapsedRealtime within a boot), saved with `commit()` so a new
 * process still knows it, and woken by `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`, also
 * in Doze. An inexact alarm may come up to three quarters of its delay late (twenty minutes on a
 * thirty minute timer), which is why GAR keeps the exact alarm permissions for it. A reboot ends
 * it: the alarm is gone, and so is the music.
 *
 * @param pauseMedia pauses what plays, when the timer ends
 */
class GarSleepTimer(
    private val store: GarTimerStore,
    private val clock: GarTimerClock,
    private val scheduler: GarTimerScheduler,
    private val pauseMedia: () -> Unit,
) {
    /** The end of the running sleep timer, null if none (also after a reboot). */
    @Synchronized
    fun end(): Moment? {
        val saved = decode(store.load()) ?: return null
        if (!saved.isSameBoot(clock.now())) {
            clear()
            return null
        }
        return saved
    }

    /** The time left on the sleep timer, null if none runs. */
    fun remainingMillis(): Long? = end()?.millisFrom(clock.now())?.coerceAtLeast(0)

    /** Starts a sleep timer of [durationMillis], replacing the one that ran; returns its end. */
    @Synchronized
    fun set(durationMillis: Long): Moment {
        val end = Moment.after(clock.now(), durationMillis)
        store.save(Json.encodeToString(Moment.serializer(), end))
        scheduler.schedule(end.elapsed)
        return end
    }

    /** Ends the sleep timer without pausing anything; false if none ran. */
    @Synchronized
    fun cancel(): Boolean {
        val had = end() != null
        clear()
        return had
    }

    /**
     * The alarm came: pauses the media and ends the timer once its end has come. An alarm that
     * comes early (or a stale one) sets it again.
     */
    @Synchronized
    fun onAlarm() {
        val end = end() ?: return
        if (end.millisFrom(clock.now()) > EARLY_TOLERANCE_MILLIS) {
            scheduler.schedule(end.elapsed)
            return
        }
        clear()
        pauseMedia()
    }

    /**
     * Sets the alarm again for a saved timer: a force stop drops the app's alarms. A timer that
     * ended a while ago without its alarm only ends: what plays now isn't what it was for.
     */
    @Synchronized
    fun refresh() {
        val end = end() ?: return
        val left = end.millisFrom(clock.now())
        when {
            left > EARLY_TOLERANCE_MILLIS -> scheduler.schedule(end.elapsed)
            left < -STALE_AFTER_MILLIS -> clear()
            else -> onAlarm()
        }
    }

    private fun clear() {
        store.save("")
        scheduler.cancel()
    }

    companion object {
        private const val PREFERENCES_NAME = "gar_media_sleep_timer"
        private const val END_KEY = "end"
        const val ACTION_SLEEP_TIMER_ENDS = "org.stypox.dicio.gar.media.SLEEP_TIMER_ENDS"

        /** An alarm this close to the end counts as the end (Doze may deliver it early). */
        const val EARLY_TOLERANCE_MILLIS = 1000L

        /** A timer that ended this long ago without its alarm is dropped by [refresh]. */
        const val STALE_AFTER_MILLIS = 60_000L

        @Volatile
        private var instance: GarSleepTimer? = null

        /** The app's sleep timer; its first use in a process sets the alarm again. */
        fun get(context: Context): GarSleepTimer {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val stopwatchClock = StopwatchController.systemClock(app.contentResolver)
                    GarSleepTimer(
                        store = preferencesStore(app),
                        clock = { stopwatchClock.now() },
                        scheduler = SleepTimerAlarm(app),
                        pauseMedia = { GarMediaSessions.pauseEverything(app) },
                    ).also {
                        instance = it
                        it.refresh()
                    }
                }
            }
        }

        fun decode(encoded: String?): Moment? {
            if (encoded.isNullOrEmpty()) {
                return null
            }
            return try {
                Json.decodeFromString(Moment.serializer(), encoded)
            } catch (e: IllegalArgumentException) {
                // also kotlinx.serialization's SerializationException: a broken timer is none
                null
            }
        }

        private fun preferencesStore(context: Context): GarTimerStore {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return object : GarTimerStore {
                override fun load(): String? = preferences.getString(END_KEY, null)

                override fun save(encoded: String) {
                    // commit, not apply: the alarm may come in a process that starts for it
                    preferences.edit().putString(END_KEY, encoded).commit()
                }
            }
        }
    }
}

/**
 * The sleep timer's exact alarm. GAR holds `USE_EXACT_ALARM` (Android 13+, granted at install)
 * and `SCHEDULE_EXACT_ALARM` before that, which Android 12 lets the user turn off: then the alarm
 * is inexact.
 */
private class SleepTimerAlarm(private val context: Context) : GarTimerScheduler {

    override fun schedule(elapsedRealtime: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = alarmIntent()
        if (canScheduleExact()) {
            try {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedRealtime, operation,
                )
                return
            } catch (e: SecurityException) {
                // the permission was revoked in the meantime
                Log.w(TAG, "Can't set an exact alarm for the sleep timer", e)
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedRealtime, operation)
    }

    override fun cancel() {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent())
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GarSleepTimerReceiver::class.java)
            .setAction(GarSleepTimer.ACTION_SLEEP_TIMER_ENDS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Whether GAR may set exact alarms; Android before 12 always lets it. */
    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private companion object {
        const val TAG = "SleepTimerAlarm"
    }
}

/** The sleep timer's alarm: pauses the media when the timer ends. Not exported. */
class GarSleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GarSleepTimer.ACTION_SLEEP_TIMER_ENDS) {
            GarSleepTimer.get(context).onAlarm()
        }
    }
}
