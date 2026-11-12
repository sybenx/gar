package org.stypox.dicio.skills.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.util.getString

/** What GAR did with the phone's call, or why it couldn't. */
sealed interface CallControlOutput : SkillOutput {

    /** The call was answered. Nothing is spoken: the call's audio has just started. */
    data object Answered : CallControlOutput {
        override fun getSpeechOutput(ctx: SkillContext): String = ""

        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Headline(text = ctx.getString(R.string.skill_call_control_answered))
        }
    }

    /** Asked to answer, but the call still rang after a while (Android refused, or it hung up). */
    class StillRinging(calls: PhoneCalls) : WithCallScreen(calls) {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_control_still_ringing)
    }

    /** "Answer" or "decline" with no call ringing. */
    data object NothingRinging : CallControlOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_control_nothing_ringing)
    }

    data object Declined : CallControlOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_control_declined)
    }

    /** "Decline" while another call is on: Android's only way would end that call instead. */
    class CantDeclineWaiting(calls: PhoneCalls) : WithCallScreen(calls) {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_control_waiting)
    }

    data object Ended : CallControlOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_control_ended)
    }

    /** Android won't end the call: an emergency call, or another app's call (Signal). */
    class CantEnd(calls: PhoneCalls) : WithCallScreen(calls) {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_control_cant_end)
    }

    /** "Hang up" or "speaker on" with no call. */
    data object NotInCall : CallControlOutput, HeadlineSpeechSkillOutput {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_control_not_in_call)
    }

    /**
     * "Speaker on" or "off" during a call: during a phone call Android routes the audio as the
     * phone app (Telecom) says, and ignores other apps' speakerphone requests, so GAR offers the
     * call screen instead.
     */
    class Speaker(calls: PhoneCalls) : WithCallScreen(calls) {
        override fun getSpeechOutput(ctx: SkillContext): String =
            ctx.getString(R.string.skill_call_control_speaker)
    }

    /** An answer with a button that brings up the phone app's call screen. */
    abstract class WithCallScreen(private val calls: PhoneCalls) : CallControlOutput {
        @Composable
        override fun GraphicalOutput(ctx: SkillContext) {
            Column {
                Headline(text = getSpeechOutput(ctx))
                Spacer(modifier = Modifier.height(8.dp))
                GarbleSecondaryButton( // GAR
                    onClick = { calls.showCallScreen() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(text = stringResource(R.string.skill_call_control_show_call))
                }
            }
        }
    }
}
