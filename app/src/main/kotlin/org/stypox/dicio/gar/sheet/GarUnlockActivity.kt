package org.stypox.dicio.gar.sheet

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import cc.aaro.gar.assist.GarAssist

/**
 * The sheet's way out over the lock screen: no UI, shown above the keyguard, asks the system to
 * unlock (the bouncer), then starts [GarAssist.EXTRA_UNLOCK_TARGETS] and finishes. If the user
 * cancels it just finishes, leaving them at the lock screen; a waiting permission hand-off
 * ([GarPermissionHandOff]) then gets false. Not exported: only GAR starts it, with
 * [GarAssist.unlockIntent].
 */
class GarUnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // recreated while the bouncer was up; the first instance's request is gone
            finish()
            return
        }
        setShowWhenLocked(true)
        val targets = targets()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (targets.isEmpty() || keyguard == null) {
            finish()
            return
        }
        if (!keyguard.isKeyguardLocked) {
            start(targets)
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                start(targets)
            }

            override fun onDismissCancelled() {
                cancelled(targets)
            }

            override fun onDismissError() {
                // e.g. the keyguard went away meanwhile, or this activity isn't on top
                if (keyguard.isKeyguardLocked) {
                    cancelled(targets)
                } else {
                    start(targets)
                }
            }
        })
    }

    private fun targets(): Array<Intent> {
        val parcelables: Array<out Parcelable>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(GarAssist.EXTRA_UNLOCK_TARGETS, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(GarAssist.EXTRA_UNLOCK_TARGETS)
            }
        return parcelables?.filterIsInstance<Intent>()?.toTypedArray() ?: arrayOf()
    }

    private fun start(targets: Array<Intent>) {
        try {
            val newTask = targets.map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (newTask.size == 1) {
                startActivity(newTask[0])
            } else {
                startActivities(newTask.toTypedArray())
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not start ${targets.toList()} after unlocking", e)
        }
        finish()
    }

    private fun cancelled(targets: Array<Intent>) {
        if (targets.any { it.hasExtra(GarSheet.EXTRA_PERMISSIONS) }) {
            GarPermissionHandOff.complete(false)
        }
        finish()
    }

    companion object {
        private val TAG = GarUnlockActivity::class.simpleName
    }
}
