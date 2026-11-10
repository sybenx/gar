package org.stypox.dicio.ui.home

import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput

data class QuestionAnswer(
    val question: String?,
    val answer: SkillOutput,
    // GAR: the answer is said by TTS, so screen readers don't announce it as well
    val spoken: Boolean = false,
)

data class Interaction(
    val skill: SkillInfo?,
    val questionsAnswers: List<QuestionAnswer>
)

data class PendingQuestion(
    val userInput: String,
    val continuesLastInteraction: Boolean,
    val skillBeingEvaluated: SkillInfo?,
)

data class InteractionLog(
    val interactions: List<Interaction>,
    val pendingQuestion: PendingQuestion?,
)
