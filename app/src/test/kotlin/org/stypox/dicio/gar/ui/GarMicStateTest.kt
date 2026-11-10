package org.stypox.dicio.gar.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillInfo
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.util.Progress

private val EMPTY = InteractionLog(interactions = listOf(), pendingQuestion = null)
private val WEATHER = FakeSkillInfo("weather")

private fun pending(skill: FakeSkillInfo? = null) = InteractionLog(
    interactions = listOf(),
    pendingQuestion = PendingQuestion("what's the weather", false, skill),
)

private fun state(
    stt: SttState? = SttState.Loaded,
    briefEndsAt: Long? = null,
    log: InteractionLog = EMPTY,
    pendingSince: Long? = null,
    now: Long = 10_000L,
    routing: GarThinking.Routing? = null,
    modelLoading: Boolean = false,
    speaking: Boolean = false,
) = garMicState(stt, briefEndsAt, log, pendingSince, now, routing, modelLoading, speaking)

class GarMicStateTest : StringSpec({
    "a loaded or idle device is Idle, a speaking GAR is Speaking" {
        state() shouldBe GarMicState.Idle
        state(stt = SttState.NotLoaded) shouldBe GarMicState.Idle
        state(stt = SttState.Loading(thenStartListening = false)) shouldBe GarMicState.Idle
        state(speaking = true) shouldBe GarMicState.Speaking
    }

    "listening wins over everything, and carries the continued window's end" {
        state(
            stt = SttState.Listening, log = pending(), pendingSince = 0L, speaking = true,
            routing = GarThinking.Routing(0L),
        ) shouldBe GarMicState.Listening(null)
        state(stt = SttState.Listening, briefEndsAt = 12_000L) shouldBe GarMicState.Listening(12_000L)
    }

    "loading to listen is Starting, transcribing and waiting are Transcribing" {
        state(stt = SttState.Loading(thenStartListening = true), speaking = true) shouldBe
            GarMicState.Starting
        state(stt = SttState.Transcribing, log = pending(), pendingSince = 0L) shouldBe
            GarMicState.Transcribing
        state(stt = SttState.WaitingForResult) shouldBe GarMicState.Transcribing
    }

    "a pending question is Thinking only once it is 300 ms old" {
        state(log = pending(), pendingSince = 9_800L, now = 10_000L) shouldBe GarMicState.Idle
        state(log = pending(), pendingSince = 9_800L, now = 10_000L, speaking = true) shouldBe
            GarMicState.Speaking
        state(log = pending(WEATHER), pendingSince = 9_700L, now = 10_000L) shouldBe
            GarMicState.Thinking(since = 9_700L, skill = WEATHER, model = false, loadingModel = false)
    }

    "Thinking wins over Speaking and over an unavailable microphone" {
        state(log = pending(), pendingSince = 0L, speaking = true)
            .shouldBeInstanceOf<GarMicState.Thinking>()
        state(stt = SttState.NotDownloaded, log = pending(), pendingSince = 0L)
            .shouldBeInstanceOf<GarMicState.Thinking>()
    }

    "a model route is Thinking at once; loading the model counts only during a route" {
        val routing = GarThinking.Routing(startedAt = 9_950L)
        state(log = pending(), pendingSince = 9_900L, routing = routing) shouldBe
            GarMicState.Thinking(since = 9_900L, skill = null, model = true, loadingModel = false)
        state(log = pending(), pendingSince = 9_900L, routing = routing, modelLoading = true) shouldBe
            GarMicState.Thinking(since = 9_900L, skill = null, model = true, loadingModel = true)
        state(log = pending(), pendingSince = 0L, modelLoading = true) shouldBe
            GarMicState.Thinking(since = 0L, skill = null, model = false, loadingModel = false)
        // a route with no pending question (none in the log yet) still thinks, since it started
        state(routing = routing) shouldBe
            GarMicState.Thinking(since = 9_950L, skill = null, model = true, loadingModel = false)
    }

    "states the microphone can't listen in are Unavailable, with the reason" {
        state(stt = null) shouldBe GarMicState.Unavailable(null)
        state(stt = SttState.NotDownloaded) shouldBe GarMicState.Unavailable(SttState.NotDownloaded)
        state(stt = SttState.NoMicrophonePermission) shouldBe
            GarMicState.Unavailable(SttState.NoMicrophonePermission)
        val downloading = SttState.Downloading(Progress.UNKNOWN)
        state(stt = downloading) shouldBe GarMicState.Unavailable(downloading)
        state(stt = SttState.NotInitialized) shouldBe GarMicState.Unavailable(SttState.NotInitialized)
    }

    "the continued window's end moves from the nanoTime clock to elapsedRealtime" {
        garBriefEndsAtElapsed(null, nanoNow = 1_000L, elapsedNow = 50_000L).shouldBeNull()
        garBriefEndsAtElapsed(4_000L, nanoNow = 1_000L, elapsedNow = 50_000L) shouldBe 53_000L
    }

    "state tags name each state for the frame log" {
        GarMicState.Listening(null).tag shouldBe "listening"
        GarMicState.Listening(1L).tag shouldBe "follow_up"
        GarMicState.Thinking(0L, null, model = true, loadingModel = false).tag shouldBe "thinking"
        GarMicState.Unavailable(null).tag shouldBe "unavailable"
    }

    "routing start and end set and clear the routing state" {
        GarThinking.routingStarted()
        (GarThinking.routing.value != null).shouldBeTrue()
        GarThinking.routingEnded()
        GarThinking.routing.value.shouldBeNull()
    }

    "speaking stays true until every source is done" {
        val a = Any()
        val b = Any()
        GarSpeaking.update(a, pending = true)
        GarSpeaking.update(b, pending = true)
        GarSpeaking.update(a, pending = false)
        GarSpeaking.speaking.value.shouldBeTrue()
        GarSpeaking.update(b, pending = false)
        GarSpeaking.speaking.value.shouldBeFalse()
    }
})
