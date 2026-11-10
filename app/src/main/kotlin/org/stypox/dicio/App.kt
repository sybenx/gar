package org.stypox.dicio

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.StrictMode // GAR
import android.util.Log // GAR
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.HiltAndroidApp
import org.stypox.dicio.gar.perf.garTrace // GAR
import org.stypox.dicio.gar.timer.GarOldTimers // GAR
import org.stypox.dicio.gar.timer.GarStartedTimers // GAR
import org.stypox.dicio.util.checkPermissions

// IMPORTANT NOTE: beware of this nasty bug related to allowBackup=true
// https://medium.com/p/924c91bafcac
@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkPermissions(this, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            initNotificationChannels()
        }
        // GAR: the trace sections and disk checks of gar/DESIGN.md, "Frame rate"; StrictMode only
        // on request (`adb shell setprop log.tag.GarStrictMode DEBUG`), so that it doesn't slow
        // down the starts being measured
        if (BuildConfig.DEBUG && Log.isLoggable("GarStrictMode", Log.DEBUG)) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog()
                    .build()
            )
        }
        // GAR: timers moved to the clock app; drop the old engine's
        garTrace("Gar:GarOldTimers.drop") { GarOldTimers.drop(this) }
        // GAR: the names of a ringing clock app timer, for ranking
        garTrace("Gar:GarStartedTimers.get") { GarStartedTimers.get(this) }
        // GAR: remembered facts, for ranking
        garTrace("Gar:GarNotes.get") { org.stypox.dicio.skills.notes.GarNotes.get(this) }
        // GAR: the voice log
        garTrace("Gar:GarVoiceLog.install") {
            org.stypox.dicio.gar.voicelog.GarVoiceLog.install(this)
        }
    }

    private fun initNotificationChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    getString(R.string.error_report_channel_id),
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(getString(R.string.error_report_channel_name))
                    .setDescription(getString(R.string.error_report_channel_description))
                    .build()
            )
        )
    }
}
