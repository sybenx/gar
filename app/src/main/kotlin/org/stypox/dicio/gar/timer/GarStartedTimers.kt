package org.stypox.dicio.gar.timer

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.stypox.dicio.skills.stopwatch.ClockReading
import org.stypox.dicio.skills.stopwatch.StopwatchController

/**
 * A timer that GAR started in the clock app: what it was called ([name], null for none), how long
 * it was set for and when it should end. The clock app may have changed it since.
 */
@Serializable
data class StartedTimer(
    val name: String? = null,
    val durationMillis: Long,
    val end: Moment,
) {
    /** Milliseconds until [end], negative once it has passed. */
    fun remainingMillis(now: ClockReading): Long = end.millisFrom(now)
}

/**
 * The timers GAR started in the clock app (gar/DESIGN.md, "Timers"). `AlarmClock` has no intent
 * to read the clock app's timers, so GAR remembers the ones it started: for "how long is left"
 * (an estimate, since the user may have changed the timer in the clock app) and for the names of
 * the timers (`GarMatchCheck`, the timer controls). A timer is forgotten [FORGET_AFTER_MILLIS]
 * after its end, and when GAR stops the ringing timers ([forgetEnded]).
 */
class GarStartedTimers(
    private val store: GarTimerStore,
    val clock: GarTimerClock,
) {
    private var timers: List<StartedTimer> = decode(store.load())

    /** Remembers a timer of [durationMillis] called [name] that starts now. */
    @Synchronized
    fun add(durationMillis: Long, name: String?): StartedTimer {
        val now = clock.now()
        val timer = StartedTimer(name, durationMillis, Moment.after(now, durationMillis))
        save(current(now) + timer)
        return timer
    }

    /** The timers GAR started and still remembers, oldest first. Never touches the disk. */
    @Synchronized
    fun current(): List<StartedTimer> = current(clock.now())

    /** The remembered timers that have ended: the ones that may ring now, oldest first. */
    @Synchronized
    fun ended(): List<StartedTimer> {
        val now = clock.now()
        return current(now).filter { it.remainingMillis(now) <= 0 }
    }

    /** Forgets the timers that have ended: the ringing ones were just stopped. */
    @Synchronized
    fun forgetEnded() {
        val now = clock.now()
        val kept = timers.filter { it.remainingMillis(now) > 0 }
        if (kept != timers) {
            save(kept)
        }
    }

    private fun current(now: ClockReading): List<StartedTimer> =
        timers.filter { it.remainingMillis(now) > -FORGET_AFTER_MILLIS }

    private fun save(newTimers: List<StartedTimer>) {
        timers = newTimers.takeLast(MAX_TIMERS)
        store.save(JSON.encodeToString(SERIALIZER, timers))
    }

    companion object {
        /** How long after its end a timer is still remembered (it may still ring). */
        const val FORGET_AFTER_MILLIS = 60 * 60_000L

        /** More than anyone runs at once. */
        const val MAX_TIMERS = 20

        private const val PREFERENCES_NAME = "gar_clock_timers"
        private const val STATE_KEY = "started"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = ListSerializer(StartedTimer.serializer())

        @Volatile
        private var instance: GarStartedTimers? = null

        /** The app's started timers, loaded on first use. */
        fun get(context: Context): GarStartedTimers {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val stopwatchClock = StopwatchController.systemClock(app.contentResolver)
                    GarStartedTimers(
                        store = preferencesStore(app),
                        clock = { stopwatchClock.now() },
                    ).also { instance = it }
                }
            }
        }

        /** The started timers if this process loaded them, without loading them (for `score`). */
        fun peek(): GarStartedTimers? = instance

        /** The saved timers, or none if there are none or they can't be read. */
        fun decode(text: String?): List<StartedTimer> {
            if (text.isNullOrEmpty()) {
                return listOf()
            }
            return try {
                JSON.decodeFromString(SERIALIZER, text)
            } catch (e: IllegalArgumentException) {
                // also kotlinx.serialization's SerializationException
                listOf()
            }
        }

        private fun preferencesStore(context: Context): GarTimerStore {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return object : GarTimerStore {
                override fun load(): String? = preferences.getString(STATE_KEY, null)

                override fun save(encoded: String) {
                    preferences.edit().putString(STATE_KEY, encoded).apply()
                }
            }
        }
    }
}
