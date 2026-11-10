package org.stypox.dicio.gar.perf

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.settings.datastore.UserSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The user's settings read once off the main thread at process start (gar/DESIGN.md, "Frame
 * rate"), so that a screen can draw its first frame with the theme and locale without blocking on
 * DataStore. Callers fall back to their own read while a value is still null. After the first
 * read the values follow the DataStores. Not started yet: App.onCreate calls [start] once the
 * first reader lands, so that an unread thread doesn't skew the startup measurements.
 */
object GarStartupCache {
    private const val TAG = "GarStartupCache"
    private val started = AtomicBoolean(false)

    /** Null until read. */
    @Volatile
    var userSettings: UserSettings? = null
        private set

    /** Null until read. */
    @Volatile
    var garSettings: GarSettings? = null
        private set

    /** Starts the "GarStartup" thread that reads the settings; idempotent, any thread. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        Thread({
            try {
                garTrace("Gar:GarStartupCache") { read(appContext) }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not read the settings ahead", t)
            }
        }, "GarStartup").start()
    }

    private fun read(context: Context) {
        val entryPoint =
            EntryPointAccessors.fromApplication(context, GarStartupCacheEntryPoint::class.java)
        val userStore = entryPoint.userSettings()
        val garStore = entryPoint.garSettings()
        runBlocking {
            userSettings = userStore.data.first()
            garSettings = garStore.data.first()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { follow { userStore.data.collect { userSettings = it } } }
        scope.launch { follow { garStore.data.collect { garSettings = it } } }
    }

    private suspend fun follow(collect: suspend () -> Unit) {
        try {
            collect()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // the value read at start stays; readers fall back to their own read
            Log.w(TAG, "Could not follow the settings", t)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GarStartupCacheEntryPoint {
    fun userSettings(): DataStore<UserSettings>
    fun garSettings(): DataStore<GarSettings>
}
