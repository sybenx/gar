package org.stypox.dicio.skills.stopwatch

import android.content.ContentResolver
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the stopwatch keeps its [StopwatchState.encode]d state between processes. */
interface StopwatchStore {
    fun load(): String?
    fun save(encoded: String)
}

fun interface StopwatchClock {
    fun now(): ClockReading
}

/**
 * The one stopwatch of the app, shared by the skill, its cards and its notification. Every change
 * is saved right away, so a new process (after the system killed this one, or after a reboot)
 * continues where the old one stopped.
 *
 * @param onChange called after each change, with the clock reading the change was made at
 */
class StopwatchController(
    private val store: StopwatchStore,
    val clock: StopwatchClock,
    private val onChange: (StopwatchState, ClockReading) -> Unit = { _, _ -> },
) {
    private val _state: MutableStateFlow<StopwatchState>
    val state: StateFlow<StopwatchState>

    init {
        val saved = StopwatchState.decode(store.load()) ?: StopwatchState()
        val restored = saved.restored(clock.now())
        if (restored != saved) {
            store.save(restored.encode())
        }
        _state = MutableStateFlow(restored)
        state = _state.asStateFlow()
    }

    @Synchronized
    fun perform(action: StopwatchAction): StopwatchEvent {
        val now = clock.now()
        val (newState, event) = _state.value.perform(action, now)
        if (newState != _state.value) {
            store.save(newState.encode())
            _state.value = newState
            onChange(newState, now)
        }
        return event
    }

    /** Calls `onChange` with the current state, e.g. to show the notification again after a reboot. */
    @Synchronized
    fun refresh() {
        onChange(_state.value, clock.now())
    }

    companion object {
        private const val PREFERENCES_NAME = "gar_stopwatch"
        private const val STATE_KEY = "state"

        @Volatile
        private var instance: StopwatchController? = null

        fun get(context: Context): StopwatchController {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val notification = StopwatchNotification(app)
                    StopwatchController(
                        store = preferencesStore(app),
                        clock = systemClock(app.contentResolver),
                        onChange = notification::update,
                    ).also { instance = it }
                }
            }
        }

        private fun preferencesStore(context: Context): StopwatchStore {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return object : StopwatchStore {
                override fun load(): String? = preferences.getString(STATE_KEY, null)

                override fun save(encoded: String) {
                    // commit, not apply: the process may be killed right after a notification button
                    preferences.edit().putString(STATE_KEY, encoded).commit()
                }
            }
        }

        fun systemClock(contentResolver: ContentResolver): StopwatchClock {
            // a process never outlives a boot, so the boot count is read once (the card reads the
            // clock many times a second)
            val bootCount by lazy {
                try {
                    Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
                } catch (e: Settings.SettingNotFoundException) {
                    null
                }
            }
            return StopwatchClock {
                ClockReading(SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount)
            }
        }
    }
}
