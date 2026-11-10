package org.stypox.dicio.gar.sheet

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.gar.GarKeepScreenOn
import org.stypox.dicio.gar.ui.GarMicState
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.Interaction
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.home.QuestionAnswer

private class Answer(val name: String) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = name

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
    }
}

private fun qa(question: String) = QuestionAnswer(question, Answer(question))

private fun pending(text: String) = PendingQuestion(
    userInput = text,
    continuesLastInteraction = false,
    skillBeingEvaluated = null,
)

private fun log(vararg interactions: List<String>, pending: String? = null) = InteractionLog(
    interactions = interactions.map { questions ->
        Interaction(skill = null, questionsAnswers = questions.map { qa(it) })
    },
    pendingQuestion = pending?.let { pending(it) },
)

private fun GarSheetTurns.questions() = answered.map { it.question }

class GarSheetStateTest : StringSpec({
    "the baseline counts every answer however it is grouped, and a pending question" {
        val before = log(listOf("a", "b"), listOf("c"))
        GarSheetState.baseline(before) shouldBe 3
        GarSheetState.baseline(log(listOf("a"), pending = "b")) shouldBe 2
        // the wake word's capture is the sheet's own
        GarSheetState.baseline(log(listOf("a"), pending = "b"), countPending = false) shouldBe 1
        GarSheetState.baseline(log()) shouldBe 0
    }

    "only the turns after the show are the sheet's" {
        val base = GarSheetState.baseline(log(listOf("a", "b")))
        val atShow = GarSheetState.select(log(listOf("a", "b")), base)
        atShow.answered.shouldBeEmpty()
        atShow.pending.shouldBeNull()
        atShow.latest.shouldBeNull()

        val listening = GarSheetState.select(log(listOf("a", "b"), pending = "what"), base)
        listening.pending?.userInput shouldBe "what"

        // continuing the last interaction or starting a new one: the same answer is the sheet's
        GarSheetState.select(log(listOf("a", "b", "c")), base).questions() shouldBe listOf("c")
        val answered = GarSheetState.select(log(listOf("a", "b"), listOf("c")), base)
        answered.questions() shouldBe listOf("c")
        answered.latest?.question shouldBe "c"
        answered.pending.shouldBeNull()
    }

    "a route in flight at show is not shown" {
        val atShow = log(listOf("a"), pending = "old")
        val base = GarSheetState.baseline(atShow)
        val first = GarSheetState.select(atShow, base)
        first.pending.shouldBeNull()
        first.answered.shouldBeEmpty()

        // it lands after the show: still not the sheet's
        val landed = GarSheetState.select(log(listOf("a"), listOf("old")), first.baseline)
        landed.answered.shouldBeEmpty()
        landed.pending.shouldBeNull()

        // the sheet's own question and answer come after it
        val asked =
            GarSheetState.select(log(listOf("a"), listOf("old"), pending = "new"), landed.baseline)
        asked.pending?.userInput shouldBe "new"
        GarSheetState.select(log(listOf("a"), listOf("old"), listOf("new")), asked.baseline)
            .questions() shouldBe listOf("new")
    }

    "a question pending at show that ends in silence gives the baseline back" {
        val base = GarSheetState.baseline(log(listOf("a"), pending = "um"))
        val silent = GarSheetState.select(log(listOf("a")), base)
        silent.baseline shouldBe 1
        silent.answered.shouldBeEmpty()
        // so the sheet's first answer is shown
        GarSheetState.select(log(listOf("a"), listOf("mine")), silent.baseline)
            .questions() shouldBe listOf("mine")
    }

    "a cleared log shows what comes after" {
        val base = GarSheetState.baseline(log(listOf("a", "b")))
        val cleared = GarSheetState.select(log(), base)
        cleared.baseline shouldBe 0
        GarSheetState.select(log(listOf("x")), cleared.baseline).questions() shouldBe listOf("x")
    }

    "listening, loading, transcribing, thinking and speaking are busy" {
        val busy = listOf(
            GarMicState.Listening(followUpEndsAt = null),
            GarMicState.Listening(followUpEndsAt = 5_000L),
            GarMicState.Starting,
            GarMicState.Transcribing,
            GarMicState.Thinking(since = 0L, skill = null, model = true, loadingModel = false),
            GarMicState.Speaking,
        )
        for (state in busy) {
            GarSheetState.busy(state) shouldBe true
        }
        GarSheetState.busy(GarMicState.Idle) shouldBe false
        GarSheetState.busy(GarMicState.Unavailable(SttState.NotDownloaded)) shouldBe false
    }

    "while busy, typing or explored by touch nothing is dismissed" {
        val timeouts = GarSheetTimeouts()
        val speaking = GarSheetState.idleDecision(
            GarMicState.Speaking, hasAnswer = true, continuedOpen = false, locked = false,
            lastBusyAt = 0L, now = 60_000L, timeouts = timeouts,
        )
        speaking.busy shouldBe true
        speaking.dismissAt.shouldBeNull()
        speaking.keepScreenOn shouldBe true

        // a continued-listening window counts as busy even before the mic state shows it
        GarSheetState.idleDecision(
            GarMicState.Idle, hasAnswer = true, continuedOpen = true, locked = false,
            lastBusyAt = 0L, now = 60_000L, timeouts = timeouts,
        ).dismissAt.shouldBeNull()

        GarSheetState.idleDecision(
            GarMicState.Idle, hasAnswer = true, continuedOpen = false, locked = false,
            lastBusyAt = 0L, now = 60_000L, timeouts = timeouts, typing = true,
        ).dismissAt.shouldBeNull()

        // TalkBack: swipes and focus moves send no touches, so the sheet doesn't go by itself
        GarSheetState.idleDecision(
            GarMicState.Idle, hasAnswer = true, continuedOpen = false, locked = false,
            lastBusyAt = 0L, now = 60_000L, timeouts = timeouts, touchExploration = true,
        ).dismissAt.shouldBeNull()
    }

    "idle: answered, empty and unavailable time out from the last busy moment" {
        val timeouts = GarSheetTimeouts(
            answeredMillis = 8_000, emptyMillis = 6_000, unavailableMillis = 15_000,
        )
        fun dismissAt(state: GarMicState, hasAnswer: Boolean) = GarSheetState.idleDecision(
            state, hasAnswer = hasAnswer, continuedOpen = false, locked = false,
            lastBusyAt = 1_000L, now = 2_000L, timeouts = timeouts,
        ).dismissAt
        dismissAt(GarMicState.Idle, hasAnswer = true) shouldBe 9_000L
        // silence, a false wake word in a pocket, nothing understood
        dismissAt(GarMicState.Idle, hasAnswer = false) shouldBe 7_000L
        dismissAt(GarMicState.Unavailable(SttState.NotDownloaded), hasAnswer = true) shouldBe
            16_000L
    }

    "the screen stays on only while busy, and over the lock screen a while after" {
        val timeouts = GarSheetTimeouts()
        fun keep(busy: Boolean, locked: Boolean, sinceBusy: Long, settingOn: Boolean = true) =
            GarSheetState.idleDecision(
                if (busy) GarMicState.Speaking else GarMicState.Idle,
                hasAnswer = true, continuedOpen = false, locked = locked,
                lastBusyAt = 10_000L, now = 10_000L + sinceBusy, timeouts = timeouts,
                settingOn = settingOn,
            ).keepScreenOn
        keep(busy = true, locked = false, sinceBusy = 0) shouldBe true
        keep(busy = false, locked = false, sinceBusy = 0) shouldBe false
        keep(busy = false, locked = true, sinceBusy = 1_000) shouldBe true
        keep(busy = false, locked = true, sinceBusy = GarKeepScreenOn.LOCKED_WINDOW_MILLIS) shouldBe
            false
        keep(busy = true, locked = true, sinceBusy = 0, settingOn = false) shouldBe false
    }
})
