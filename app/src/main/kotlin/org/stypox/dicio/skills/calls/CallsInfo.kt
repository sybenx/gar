package org.stypox.dicio.skills.calls

import android.Manifest
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.telephone.TelephoneSkill

/** A runtime permission: who called, and the numbers to call back. */
val PERMISSION_READ_CALL_LOG = Permission.NormalPermission(
    name = R.string.perm_read_call_log,
    id = Manifest.permission.READ_CALL_LOG,
)

/** A runtime permission: `TelecomManager.acceptRingingCall` and `endCall`. */
val PERMISSION_ANSWER_PHONE_CALLS = Permission.NormalPermission(
    name = R.string.perm_answer_phone_calls,
    id = Manifest.permission.ANSWER_PHONE_CALLS,
)

/**
 * A runtime permission: whether a call rings or is on (`TelephonyManager.getCallState`), and the
 * call screen (`TelecomManager.showInCallScreen`). In Android's phone group, like
 * `ANSWER_PHONE_CALLS` and the telephone skill's `CALL_PHONE`. `TelecomManager.isRinging` would
 * need `MODIFY_PHONE_STATE` outside the phone app.
 */
val PERMISSION_READ_PHONE_STATE = Permission.NormalPermission(
    name = R.string.perm_read_phone_state,
    id = Manifest.permission.READ_PHONE_STATE,
)

/** "Who called me", "did I miss any calls", "redial", "call mom back": the call log. */
object CallLogInfo : SkillInfo("call_log") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_call_log)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_call_log)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.AutoMirrored.Filled.PhoneCallback)

    // only the call log: "who called me" reads nothing else. Calling back checks the telephone
    // skill's contacts and CALL_PHONE itself, and asks for them like the telephone skill
    // (CallBack.output)
    override val neededPermissions: List<Permission> = listOf(PERMISSION_READ_CALL_LOG)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.CallLog[ctx.sentencesLanguage] ?: return null
        val telephoneData = Sentences.Telephone[ctx.sentencesLanguage] ?: return null
        val yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage] ?: return null
        return CallLogSkill(CallLogInfo, data, TelephoneSkill(TelephoneInfo, telephoneData, yesNoData))
    }
}

/** "Answer", "decline", "hang up", "speaker on" while a call rings or is on. */
object CallControlInfo : SkillInfo("call_control") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_call_control)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_call_control)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.PhoneInTalk)

    override val neededPermissions: List<Permission> =
        listOf(PERMISSION_ANSWER_PHONE_CALLS, PERMISSION_READ_PHONE_STATE)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.CallControl[ctx.sentencesLanguage] ?: return null
        return CallControlSkill(CallControlInfo, data)
    }
}
