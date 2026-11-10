package org.stypox.dicio.gar.sheet

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import cc.aaro.gar.assist.GarAssist

/**
 * Wraps [base]; any startActivity/startActivities first tells the sheet GAR is leaving
 * ([GarSheet.leaving]), so it goes away and stops speaking before the other app comes up. While
 * the sheet is shown over the lock screen, the intents go through [GarUnlockActivity] instead,
 * so the user unlocks first and then lands in the app. When the sheet isn't shown it is a plain
 * pass-through. Skills get it as `SkillContext.android`; the sheet's `LocalContext` is one too.
 * Intents with [AlarmClock.EXTRA_SKIP_UI] (timers and alarms set in the clock app) show nothing,
 * so they pass through too, also over the lock screen.
 */
class GarLeavingContext(base: Context) : ContextWrapper(base) {

    override fun startActivity(intent: Intent) {
        startActivity(intent, null)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        if (!GarSheet.isShown || headless(intent)) {
            super.startActivity(intent, options)
            return
        }
        GarSheet.leaving()
        if (keyguardLocked()) {
            super.startActivity(GarAssist.unlockIntent(this, arrayOf(intent)), options)
        } else {
            super.startActivity(intent, options)
        }
    }

    override fun startActivities(intents: Array<Intent>) {
        startActivities(intents, null)
    }

    override fun startActivities(intents: Array<Intent>, options: Bundle?) {
        if (!GarSheet.isShown || intents.all(::headless)) {
            super.startActivities(intents, options)
            return
        }
        GarSheet.leaving()
        if (keyguardLocked()) {
            super.startActivity(GarAssist.unlockIntent(this, intents), options)
        } else {
            super.startActivities(intents, options)
        }
    }

    // the clock app sets a timer or an alarm without coming up: GAR isn't leaving
    private fun headless(intent: Intent): Boolean =
        intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)

    private fun keyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
}
