package org.stypox.dicio.gar.sheet

import android.content.Context
import android.content.Intent
import cc.aaro.gar.assist.GarAssist
import cc.aaro.gar.assist.GarSessionHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.dicio.skill.skill.Permission
import org.stypox.dicio.util.checkPermissions
import org.stypox.dicio.util.getNonGrantedSecurePermissions

/**
 * Asking for permissions from the sheet, which has no Activity to ask with: the sheet hands the
 * request to MainActivity ([GarSheet.EXTRA_PERMISSIONS]), goes away, and the evaluator's turn
 * waits here until the home screen reports the user's answer with [complete]. The turn then
 * goes on and its answer lands in the home screen's log, which is on screen by then.
 */
object GarPermissionHandOff {
    /** How long a turn waits for the user's answer; then it counts as not granted. */
    const val TIMEOUT_MILLIS = 60_000L

    // main thread only
    private var waiting: CompletableDeferred<Boolean>? = null

    /**
     * Opens MainActivity through [host] (unlocking first when locked) asking for [permissions],
     * dismisses the sheet, and returns whether they were granted: false on timeout, when the
     * user cancels unlocking, or when a newer request replaced this one.
     */
    suspend fun request(host: GarSessionHost, permissions: Array<String>): Boolean =
        withContext(Dispatchers.Main) {
            waiting?.complete(false)
            val deferred = CompletableDeferred<Boolean>()
            waiting = deferred
            try {
                host.openApp(
                    Intent(Intent.ACTION_MAIN)
                        .setClassName(host.context.packageName, GarAssist.MAIN_ACTIVITY_CLASS)
                        .putExtra(GarSheet.EXTRA_PERMISSIONS, permissions)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                )
                withTimeoutOrNull(TIMEOUT_MILLIS) { deferred.await() } ?: false
            } finally {
                if (waiting === deferred) {
                    waiting = null
                }
            }
        }

    /** The home screen's answer to the request in its intent. Main thread; no-op if none waits. */
    fun complete(granted: Boolean) {
        waiting?.complete(granted)
        waiting = null
    }

    /**
     * The evaluator's permission requester while the sheet is shown: true if [permissions] are
     * granted already; false for secure ones (as the home screen, which doesn't ask for them
     * directly); otherwise the hand-off above.
     */
    internal suspend fun requester(
        context: Context,
        host: GarSessionHost,
        permissions: List<Permission>,
    ): Boolean {
        val secure = permissions.filterIsInstance<Permission.SecurePermission>()
        if (getNonGrantedSecurePermissions(context, secure).isNotEmpty()) {
            return false
        }
        val normal = permissions.filterIsInstance<Permission.NormalPermission>()
            .map { it.id }.toTypedArray()
        if (checkPermissions(context, *normal)) {
            return true
        }
        return request(host, normal)
    }
}
