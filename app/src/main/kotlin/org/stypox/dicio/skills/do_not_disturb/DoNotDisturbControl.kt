package org.stypox.dicio.skills.do_not_disturb

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import cc.aaro.gar.assist.GarAssist
import cc.aaro.gar.assist.VoiceActivityResult
import org.stypox.dicio.skills.connectivity.ActivityIntent

/** What the Do Not Disturb skill does on the device, so that its decisions can be tested. */
interface DoNotDisturbControl {
    /** Whether Do Not Disturb is on, for whatever reason (GAR, the user, a schedule, an app). */
    fun isOn(): Boolean

    /** Whether GAR may change Do Not Disturb (notification policy access). */
    fun hasAccess(): Boolean

    /**
     * Turns Do Not Disturb on (priority only) or off. From Android 15 (apps targeting API 35+)
     * this only switches GAR's own rule, so it can't end Do Not Disturb that something else
     * turned on.
     * @throws SecurityException without notification policy access
     */
    fun set(on: Boolean)

    /** Whether GAR is the active digital assistant, which may run Settings' voice activity. */
    fun isActiveAssistant(): Boolean

    /**
     * Asks Settings to turn Do Not Disturb off through its voice activity, which only the active
     * assistant can start. @return the activity's result, or null if it could not be started
     */
    suspend fun turnOffWithVoiceActivity(): VoiceActivityResult?

    /** Opens the Do Not Disturb settings. @return false if no page could be opened */
    fun openDoNotDisturbSettings(): Boolean

    /** Opens the page where the user allows GAR to change Do Not Disturb. */
    fun openAccessSettings(): Boolean
}

/** The outcome of a Do Not Disturb command, see [DoNotDisturbLogic]. */
enum class DoNotDisturbResult {
    TURNED_ON,
    ALREADY_ON,
    TURNED_OFF,
    ALREADY_OFF,
    IS_ON,
    IS_OFF,

    /** Something else turned Do Not Disturb on: its settings were opened to turn it off. */
    OFF_IN_SETTINGS,

    /** GAR has no notification policy access: the page to allow it was opened. */
    NO_ACCESS,
    FAILED,
}

object DoNotDisturbLogic {
    fun query(control: DoNotDisturbControl): DoNotDisturbResult =
        if (control.isOn()) DoNotDisturbResult.IS_ON else DoNotDisturbResult.IS_OFF

    fun turnOn(control: DoNotDisturbControl): DoNotDisturbResult {
        if (control.isOn()) {
            return DoNotDisturbResult.ALREADY_ON
        }
        if (!control.hasAccess()) {
            control.openAccessSettings()
            return DoNotDisturbResult.NO_ACCESS
        }
        try {
            control.set(true)
        } catch (e: SecurityException) {
            control.openAccessSettings()
            return DoNotDisturbResult.NO_ACCESS
        }
        return if (control.isOn()) DoNotDisturbResult.TURNED_ON else DoNotDisturbResult.FAILED
    }

    /**
     * Ends GAR's own Do Not Disturb first; if it is still on, Settings' voice activity turns it
     * off when GAR is the active assistant; otherwise (or if that fails) the settings page opens,
     * since apps can't end Do Not Disturb they didn't start.
     */
    suspend fun turnOff(control: DoNotDisturbControl): DoNotDisturbResult {
        if (!control.isOn()) {
            return DoNotDisturbResult.ALREADY_OFF
        }

        if (control.hasAccess()) {
            try {
                control.set(false)
            } catch (e: SecurityException) {
                // access was revoked in the meantime: the other ways may still work
            }
            if (!control.isOn()) {
                return DoNotDisturbResult.TURNED_OFF
            }
        }

        if (control.isActiveAssistant()) {
            // the activity's own answer doesn't matter, only whether DND is off afterwards
            control.turnOffWithVoiceActivity()
            if (!control.isOn()) {
                return DoNotDisturbResult.TURNED_OFF
            }
        }

        return if (control.openDoNotDisturbSettings()) {
            DoNotDisturbResult.OFF_IN_SETTINGS
        } else {
            DoNotDisturbResult.FAILED
        }
    }
}

object DoNotDisturbIntents {
    /** Settings' ZenModeVoiceActivity, started as a voice activity by GAR's assistant service. */
    val VOICE_OFF = ActivityIntent(
        Settings.ACTION_VOICE_CONTROL_DO_NOT_DISTURB_MODE,
        mapOf(Settings.EXTRA_DO_NOT_DISTURB_MODE_ENABLED to false),
    )

    /** The Do Not Disturb page, or the sound page that links to it on devices without one. */
    val SETTINGS = listOf(
        ActivityIntent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
        ActivityIntent(Settings.ACTION_SOUND_SETTINGS),
    )

    val ACCESS_SETTINGS = ActivityIntent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}

class AndroidDoNotDisturbControl(
    private val context: Context,
    private val notificationManager: NotificationManager,
) : DoNotDisturbControl {

    override fun isOn(): Boolean = when (notificationManager.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL,
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> false
        else -> true
    }

    override fun hasAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    override fun set(on: Boolean) {
        notificationManager.setInterruptionFilter(
            if (on) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
        )
    }

    override fun isActiveAssistant(): Boolean = try {
        GarAssist.isActiveAssistant(context)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Can't tell whether GAR is the assistant", e)
        false
    }

    // the device settings skill starts Settings' other voice activities the same way
    override suspend fun turnOffWithVoiceActivity(): VoiceActivityResult? =
        DoNotDisturbIntents.VOICE_OFF.startAsVoiceActivity()

    override fun openDoNotDisturbSettings(): Boolean =
        ActivityIntent.startFirst(context, DoNotDisturbIntents.SETTINGS)

    override fun openAccessSettings(): Boolean = DoNotDisturbIntents.ACCESS_SETTINGS.start(context)

    companion object {
        private const val TAG = "DoNotDisturbControl"
    }
}
