package org.stypox.dicio.skills.small_talk

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.gar.conversation.GarEndsConversation
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.ui.home.WhatICanDo
import org.stypox.dicio.util.getString

enum class PartOfDay { MORNING, AFTERNOON, EVENING }

/** The answers that need nothing but their words. */
enum class SmallTalkAnswer(@StringRes val answer: Int, val reopenMicrophone: Boolean) {
    THANKS(R.string.skill_small_talk_thanks, false),
    WHO(R.string.skill_small_talk_who, false),
    HOW(R.string.skill_small_talk_how, true),
    GOODBYE(R.string.skill_small_talk_goodbye, false),
}

/**
 * "You're welcome!", "I'm GAR, ...", "I'm doing well, ..." Thanks and goodbye end the
 * conversation, and the others either ask back or need no follow-up: no continued-listening
 * window after any of them (GarEndsConversation).
 */
class SmallTalkOutput(
    val answer: SmallTalkAnswer,
) : HeadlineSpeechSkillOutput, GarEndsConversation {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(answer.answer)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        if (answer.reopenMicrophone) {
            // a question back ("What can I do for you?"): listen for the answer
            InteractionPlan.FinishSubInteraction(reopenMicrophone = true)
        } else {
            InteractionPlan.FinishInteraction
        }
}

/** "Hi! What can I do for you?", "Good morning! ...", and the microphone opens again. */
class GreetingOutput(val partOfDay: PartOfDay?) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(
        when (partOfDay) {
            PartOfDay.MORNING -> R.string.skill_small_talk_good_morning
            PartOfDay.AFTERNOON -> R.string.skill_small_talk_good_afternoon
            PartOfDay.EVENING -> R.string.skill_small_talk_good_evening
            null -> R.string.skill_small_talk_hello
        }
    )

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan =
        InteractionPlan.FinishSubInteraction(reopenMicrophone = true)
}

/** "What can you do": a few things in words, and the list of the enabled skills on the card. */
class HelpOutput : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_small_talk_help)

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        val skillHandler = remember {
            EntryPointAccessors.fromApplication(
                context.applicationContext, SmallTalkEntryPoint::class.java,
            ).skillHandler()
        }
        val skills by skillHandler.enabledSkillsInfo.collectAsState()
        Column {
            Headline(text = getSpeechOutput(ctx))
            skills?.let { WhatICanDo(skills = it) }
        }
    }
}

/** For the help card, which Hilt doesn't inject. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmallTalkEntryPoint {
    fun skillHandler(): SkillHandler
}
