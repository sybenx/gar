package org.stypox.dicio.skills.messages

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.stypox.dicio.skills.notify.NotifyHandler

/**
 * The messaging apps' notifications through Dicio's notification listener ([NotifyHandler]),
 * which GAR has once the user gave it notification access.
 *
 * Messages come from the messaging style (Signal, WhatsApp, Messages, Molly and most chat apps
 * post one, with each message's sender), or from a notification of the message category without
 * it (title and text). Replies go through the notification's reply button: its [RemoteInput] is
 * filled in and its [PendingIntent] sent, like a reply typed into the notification shade.
 */
class NotificationMessageSource(context: Context) : MessageSource {
    private val context = context.applicationContext

    override fun conversations(): List<Conversation>? =
        activeNotifications()?.let(::conversationsOf)

    private fun conversationsOf(notifications: Array<StatusBarNotification>): List<Conversation> {
        val names = HashMap<String, String>()
        return Conversations.of(
            notifications = notifications.mapNotNull(::read),
            appName = { packageName -> names.getOrPut(packageName) { appName(packageName) } },
            ownPackage = context.packageName,
        )
    }

    override fun reply(conversation: Conversation, text: String): ReplyResult {
        val notifications = activeNotifications() ?: return ReplyResult.NO_ACCESS
        // the chat the user confirmed, never another one that the app now shows under its key
        val target = Conversations.replyTarget(conversationsOf(notifications), conversation)
            ?: return ReplyResult.GONE
        val notification = notifications.firstOrNull { it.key == target.key }
            ?: return ReplyResult.GONE
        val action = replyAction(notification.notification) ?: return ReplyResult.NO_REPLY

        val inputs = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }.toTypedArray()
        val results = Bundle()
        for (input in inputs) {
            results.putCharSequence(input.resultKey, text)
        }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return try {
            action.actionIntent.send(context, 0, intent)
            ReplyResult.SENT
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "The reply button of ${notification.packageName} was cancelled", e)
            ReplyResult.FAILED
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't reply through ${notification.packageName}", e)
            ReplyResult.FAILED
        }
    }

    override fun openApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Can't open $packageName", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to open $packageName", e)
            false
        }
    }

    private fun activeNotifications(): Array<StatusBarNotification>? {
        val listener = NotifyHandler.Instance
        if (listener == null) {
            // the access is there (Dicio checks it before the skill runs), but Android didn't
            // bind the listener (yet, or again after GAR was killed): ask it to
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, NotifyHandler::class.java)
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "Can't ask for the notification listener", e)
            }
            return null
        }
        return try {
            // null while the listener isn't connected
            listener.activeNotifications
        } catch (e: RuntimeException) {
            // SecurityException when the access was just taken away
            Log.w(TAG, "Can't read the notifications", e)
            null
        }
    }

    private fun read(sbn: StatusBarNotification): MessageNotification? = try {
        val notification = sbn.notification
        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val user = style?.user
        MessageNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            group = notification.group,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            isMessage = style != null || notification.category == Notification.CATEGORY_MESSAGE,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            conversationTitle = style?.conversationTitle?.toString(),
            isGroupConversation = style?.isGroupConversation == true,
            styleMessages = style?.messages?.map { message ->
                val person = message.person
                StyleMessage(
                    sender = person?.name?.toString(),
                    // the messaging style's messages without a person are the user's
                    fromUser = person == null || isUser(person, user),
                    text = message.text?.toString(),
                    time = message.timestamp,
                )
            },
            text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
            postTime = sbn.postTime,
            canReply = replyAction(notification) != null,
        )
    } catch (e: RuntimeException) {
        // a notification whose extras can't be read (BadParcelableException) is left out
        Log.w(TAG, "Can't read a notification of ${sbn.packageName}", e)
        null
    }

    private fun appName(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "No app $packageName", e)
        packageName
    }

    companion object {
        private const val TAG = "NotificationMessages"

        /** Whether [person] is the user of a messaging style whose user is [user]. */
        private fun isUser(person: Person, user: Person?): Boolean {
            if (user == null) {
                return false
            }
            val key = person.key
            return if (key != null && user.key != null) {
                key == user.key
            } else {
                person.name?.toString() == user.name?.toString()
            }
        }

        /**
         * The reply button of [notification]: an action with a free-form [RemoteInput] whose
         * intent GAR can fill in, the one marked as the reply first. Older apps put it only in
         * the wearable extender. Android's own suggestions (contextual actions) don't count.
         */
        fun replyAction(notification: Notification): Notification.Action? {
            val actions = notification.actions.orEmpty().toList() +
                Notification.WearableExtender(notification).actions
            val usable = actions.filter(::canFill)
            return usable.firstOrNull {
                it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
            } ?: usable.firstOrNull()
        }

        private fun canFill(action: Notification.Action): Boolean {
            val intent = action.actionIntent ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && action.isContextual) {
                return false
            }
            // an immutable intent drops the text GAR fills in (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && intent.isImmutable) {
                return false
            }
            return action.remoteInputs.orEmpty().any { it.allowFreeFormInput }
        }
    }
}
