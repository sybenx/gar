package org.stypox.dicio.skills.connectivity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import cc.aaro.gar.assist.GarAssist
import cc.aaro.gar.assist.VoiceActivityResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * An activity to start, kept as plain data so that unit tests can check which page a command
 * opens (Android's Intent is not available in JVM tests). Used by the device skills
 * (connectivity, Do Not Disturb, device settings).
 *
 * @param data the intent's data URI, e.g. "package:..." for an app's own settings page
 */
data class ActivityIntent(
    val action: String,
    val extras: Map<String, Any> = mapOf(),
    val data: String? = null,
) {
    /** @param newTask false for intents that are not started from a plain context */
    fun toIntent(newTask: Boolean = true): Intent {
        val intent = Intent(action)
        if (data != null) {
            intent.data = Uri.parse(data)
        }
        if (newTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        for ((key, value) in extras) {
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                else -> throw IllegalArgumentException("Unsupported extra $key=$value")
            }
        }
        return intent
    }

    /** @return false if no activity handles the intent or the system refused to start it */
    fun start(context: Context): Boolean {
        return try {
            context.startActivity(toIntent())
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $action", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start $action", e)
            false
        }
    }

    /**
     * Starts this intent as a voice activity of GAR's assistant service (only the active assistant
     * can: Settings' voice activities for Do Not Disturb, airplane mode and battery saver) and
     * waits for its result. @return the result, or null if it could not be started
     */
    suspend fun startAsVoiceActivity(): VoiceActivityResult? =
        withTimeoutOrNull(GarAssist.VOICE_ACTIVITY_TIMEOUT_MILLIS + EXTRA_WAIT_MILLIS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val started = GarAssist.startVoiceActivity(toIntent(newTask = false)) { result ->
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                    if (!started) {
                        continuation.resume(null)
                    }
                }
            }
        }.also { Log.d(TAG, "Voice activity $action: $it") }

    companion object {
        private const val TAG = "ActivityIntent"

        // GarAssist reports a timeout itself; this only guards against a lost callback
        private const val EXTRA_WAIT_MILLIS = 2_000L

        /** Starts the first of [intents] that can be started. */
        fun startFirst(context: Context, intents: List<ActivityIntent>): Boolean =
            intents.any { it.start(context) }
    }
}
