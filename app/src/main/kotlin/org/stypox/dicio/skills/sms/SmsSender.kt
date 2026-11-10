package org.stypox.dicio.skills.sms

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.dicio.skill.skill.Permission
import org.stypox.dicio.R

/**
 * In [SmsInfo.neededPermissions], so Dicio asks for it before the skill first runs: texts are
 * confirmed and sent by voice. If it is revoked later, sending opens the messages app instead.
 */
val PERMISSION_SEND_SMS = Permission.NormalPermission(
    name = R.string.perm_send_sms,
    id = Manifest.permission.SEND_SMS,
)

/** What the network said about a text sent with [SmsSender.send]. */
enum class SendResult { SENT, FAILED, NO_REPORT }

/**
 * The two ways a text leaves GAR. Only [SmsDraft] calls them, once the user said or tapped send.
 */
interface SmsSender {
    /**
     * Whether GAR may send texts itself: `SEND_SMS` is granted and the phone is unlocked (a card
     * left on the lock screen opens the messages app instead, which asks to unlock).
     */
    fun canSendDirectly(): Boolean

    /**
     * Sends [message] to [number] in the background.
     *
     * @param onResult called on the main thread once the network took or refused every part
     * @throws Exception if sending can't start
     */
    fun send(number: String, message: String, onResult: (SendResult) -> Unit)

    /**
     * Opens the default messages app with [message] to [number] filled in, so that the user taps
     * send there.
     *
     * @return false if no app can show it
     */
    fun openSmsApp(number: String, message: String): Boolean
}

/**
 * The intent that opens a messages app with a text filled in, free of Android types so that its
 * construction can be unit tested. See [toIntent].
 *
 * @param packageName the default messages app, to skip the app chooser, or null for any
 */
data class SmsAppIntent(
    val action: String,
    val scheme: String,
    val recipient: String,
    val extras: Map<String, String>,
    val packageName: String?,
) {
    fun toIntent(): Intent {
        val intent = Intent(action, Uri.fromParts(scheme, recipient, null))
        for ((key, value) in extras) {
            intent.putExtra(key, value)
        }
        packageName?.let { intent.setPackage(it) }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        const val SCHEME_SMSTO = "smsto"
        const val EXTRA_SMS_BODY = "sms_body"

        /** `ACTION_SENDTO smsto:<number>` with `sms_body`, as messages apps expect it. */
        fun compose(number: String, message: String, packageName: String?) = SmsAppIntent(
            action = Intent.ACTION_SENDTO,
            scheme = SCHEME_SMSTO,
            recipient = dialable(number),
            extras = mapOf(EXTRA_SMS_BODY to message),
            packageName = packageName,
        )

        /**
         * [number] without its formatting ("+1 (555) 010-0123" -> "+15550100123"). Keeps what
         * the network needs: digits, a leading plus, and the star and pound of service codes.
         */
        fun dialable(number: String): String {
            val kept = number.filter { it.isDigit() || it == '*' || it == '#' }
            return if (number.trim().startsWith("+")) "+$kept" else kept
        }
    }
}

/**
 * Sends with [SmsManager] on the default SMS subscription and reports the result from the sent
 * [PendingIntent]s. Android writes texts sent this way to the messages app's history.
 */
class AndroidSmsSender(context: Context) : SmsSender {
    private val context = context.applicationContext

    override fun canSendDirectly(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED &&
                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != true

    override fun send(number: String, message: String, onResult: (SendResult) -> Unit) {
        val manager = smsManager()
        val parts = manager.divideMessage(message)
        val action = "$ACTION_SENT_PREFIX${System.nanoTime()}"
        val receiver = SentReceiver(parts.size, onResult)
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val sentIntents = ArrayList(parts.indices.map { part ->
            PendingIntent.getBroadcast(
                context,
                part,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        })

        try {
            manager.sendMultipartTextMessage(
                SmsAppIntent.dialable(number), null, parts, sentIntents, null
            )
        } catch (e: Exception) {
            receiver.unregister()
            throw e
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { receiver.finish(SendResult.NO_REPORT) },
            REPORT_TIMEOUT_MS,
        )
    }

    override fun openSmsApp(number: String, message: String): Boolean {
        val defaultApp = try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (e: Exception) {
            Log.w(TAG, "Can't get the default SMS app", e)
            null
        }
        // the default app first, then any app (null), in case the default doesn't handle smsto:
        for (packageName in listOf(defaultApp, null).distinct()) {
            try {
                context.startActivity(SmsAppIntent.compose(number, message, packageName).toIntent())
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No activity for smsto: in ${packageName ?: "any app"}", e)
            }
        }
        return false
    }

    private fun smsManager(): SmsManager {
        val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val valid = subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (valid) manager.createForSubscriptionId(subscriptionId) else manager
        } else if (valid) {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    /** Collects the result of each part; any failed part fails the text. */
    private inner class SentReceiver(
        private var pending: Int,
        private val onResult: (SendResult) -> Unit,
    ) : BroadcastReceiver() {
        private var failed = false
        private var done = false

        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "Sending a part failed with result code $resultCode")
                failed = true
            }
            if (--pending <= 0) {
                finish(if (failed) SendResult.FAILED else SendResult.SENT)
            }
        }

        fun finish(result: SendResult) {
            if (done) {
                return
            }
            unregister()
            onResult(result)
        }

        fun unregister() {
            if (done) {
                return
            }
            done = true
            try {
                context.unregisterReceiver(this)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Sent receiver was not registered", e)
            }
        }
    }

    companion object {
        private const val TAG = "AndroidSmsSender"
        private const val ACTION_SENT_PREFIX = "org.stypox.dicio.skills.sms.SENT."
        private const val REPORT_TIMEOUT_MS = 60_000L
    }
}
