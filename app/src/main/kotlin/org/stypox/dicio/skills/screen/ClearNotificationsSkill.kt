package org.stypox.dicio.skills.screen

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.ClearNotifications
import org.stypox.dicio.skills.notify.NotifyHandler
import org.stypox.dicio.util.PERMISSION_NOTIFICATION_LISTENER

object ClearNotificationsInfo : SkillInfo("clear_notifications") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_clear_notifications)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_clear_notifications)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.ClearAll)

    // the notification listener Dicio already declares: Dicio asks for the access (with a
    // button to its settings page) before the skill first runs
    override val neededPermissions: List<Permission>
        = listOf(PERMISSION_NOTIFICATION_LISTENER)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.ClearNotifications[ctx.sentencesLanguage] ?: return null
        return ClearNotificationsSkill(ClearNotificationsInfo, data)
    }
}

/** The notifications as the clear skill sees them, so that it can be tested. */
interface NotificationShade {
    fun isLocked(): Boolean

    /** How many notifications the user could swipe away, or null when GAR's listener isn't connected. */
    fun clearableCount(): Int?

    /** Clears what [clearableCount] counts; ongoing ones (music, calls, downloads) stay. */
    fun clearAll(): Boolean
}

/**
 * [NotificationShade] through Dicio's notification listener ([NotifyHandler]):
 * `NotificationListenerService.cancelAllNotifications`, which, like the shade's Clear all, only
 * removes notifications that are clearable.
 */
class AndroidNotificationShade(private val context: Context) : NotificationShade {
    override fun isLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    override fun clearableCount(): Int? = try {
        NotifyHandler.Instance?.activeNotifications?.count { it.isClearable }
    } catch (e: SecurityException) {
        // the listener isn't connected (yet or anymore)
        Log.w(TAG, "Can't read the notifications", e)
        null
    }

    override fun clearAll(): Boolean = try {
        val listener = NotifyHandler.Instance
        listener?.cancelAllNotifications()
        listener != null
    } catch (e: SecurityException) {
        Log.w(TAG, "Can't clear the notifications", e)
        false
    }

    companion object {
        private const val TAG = "AndroidNotificationShade"
    }
}

/** What clearing did. */
enum class ClearResult { CLEARED, NOTHING_TO_CLEAR, NO_LISTENER }

/**
 * GAR (gar:screen). "Clear my notifications", "dismiss all notifications": everything the shade's
 * Clear all would remove; ongoing notifications stay. "Unlock your phone first." on the lock
 * screen, like reading them. English only.
 */
class ClearNotificationsSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<ClearNotifications>,
    private val shadeFactory: (SkillContext) -> NotificationShade = { AndroidNotificationShade(it.android) },
) : StandardRecognizerSkill<ClearNotifications>(correspondingSkillInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, ClearNotifications> {
        val (score, sentence) = super.score(ctx, input)
        return Pair(if (ClearWords.clearsNotifications(input)) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: ClearNotifications): SkillOutput {
        val shade = shadeFactory(ctx)
        if (shade.isLocked()) {
            return GarLockedOutput()
        }
        return ScreenMessageOutput(
            when (clear(shade)) {
                ClearResult.CLEARED -> R.string.skill_clear_notifications_cleared
                ClearResult.NOTHING_TO_CLEAR -> R.string.skill_clear_notifications_none
                ClearResult.NO_LISTENER -> R.string.skill_clear_notifications_no_listener
            }
        )
    }

    companion object {
        fun clear(shade: NotificationShade): ClearResult {
            val count = shade.clearableCount() ?: return ClearResult.NO_LISTENER
            if (count == 0) {
                return ClearResult.NOTHING_TO_CLEAR
            }
            return if (shade.clearAll()) ClearResult.CLEARED else ClearResult.NO_LISTENER
        }
    }
}

/** Whether an input clears the notifications (English). */
object ClearWords {
    fun clearsNotifications(input: String): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        return words.any { it == "notification" || it == "notifications" } &&
            words.any { it in CLEAR_VERBS } && words.none { it in OTHER_THINGS }
    }

    private val CLEAR_VERBS = setOf(
        "clear", "dismiss", "delete", "remove", "swipe", "rid", "erase", "close",
    )
    private val OTHER_THINGS = setOf(
        "settings", "setting", "sound", "sounds", "history", "log", "read", "from", "app",
        "channel", "permission", "access",
    )
}
