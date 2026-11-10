package org.stypox.dicio.gar

import androidx.datastore.core.DataStore
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.FloatScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarLockedSkill
import org.stypox.dicio.gar.call.GarRoutedSkill
import org.stypox.dicio.gar.settings.GarModel
import org.stypox.dicio.gar.settings.GarModelFamily
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.settings.GarSettingsSerializer
import org.stypox.dicio.gar.tools.GarToolsProvider
import org.stypox.dicio.gar.tools.OfferedTool
import org.stypox.dicio.sentences.Sentences

private class FakeRouterSource(
    var modelAvailable: Boolean = true,
    var route: (String, List<ToolSpec>) -> RouterOutput = { _, _ -> RouterOutput.NoMatch },
    /** false: the model is on disk but failed to load */
    var loads: Boolean = true,
) : GarRouterSource {
    val calls = mutableListOf<String>()

    override fun isModelAvailable(settings: GarSettings) = modelAvailable

    override fun canRoute(settings: GarSettings) = modelAvailable && loads

    override suspend fun route(
        settings: GarSettings,
        utterance: String,
        tools: List<ToolSpec>,
    ): RouterOutput {
        calls.add(utterance)
        return route(utterance, tools)
    }
}

private val timerInfo = FakeSkillInfo("timer")
private val timerSkill = RecordingSkill(timerInfo)

private class Fixture(
    settings: GarSettings = GarSettingsSerializer.defaultValue,
    val router: FakeRouterSource = FakeRouterSource(),
    tools: List<OfferedTool> = listOf(OfferedTool(TIMER_SET_TOOL, timerInfo, timerSkill)),
    var locked: Boolean = false,
) {
    val logged = mutableListOf<Pair<String, Throwable?>>()
    val ranker = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))
    val fallback = GarFallbackImpl(
        settings = GarSettingsModule.newDataStoreForPreviews(settings),
        toolsProvider = object : GarToolsProvider {
            override suspend fun offeredTools(ctx: SkillContext) = tools
        },
        routerSource = router,
        logger = { message, throwable -> logged.add(Pair(message, throwable)) },
        isDeviceLocked = { locked },
    )

    suspend fun tryRoute(
        utterances: List<String> = listOf("give me ten minutes on the clock"),
        ctx: FakeSkillContext = FakeSkillContext(),
    ) = fallback.tryRoute(ctx, ranker, utterances)
}

private fun callOutput(utterance: String) = RouterOutput.Call(
    tool = TIMER_SET_TOOL,
    rawSlots = mapOf("duration" to "ten minutes"),
    missingRequired = listOf(),
    utterance = utterance,
)

private fun match(skillId: String, score: Float, inputData: Any?) =
    SkillWithResult(RecordingSkill(FakeSkillInfo(skillId)), FloatScore(score), inputData)

/** Always scores [score] with [data]. */
private class FixedSkill(info: SkillInfo, private val score: Float, private val data: String) :
    Skill<String>(info, Specificity.HIGH) {
    override fun score(ctx: SkillContext, input: String): Pair<Score, String> =
        Pair(FloatScore(score), data)

    override suspend fun generateOutput(ctx: SkillContext, inputData: String): SkillOutput =
        FakeOutput(inputData)
}

/** Rejects the matches [rejects] says, records what was checked and routed. */
private class CheckingFallback(
    val rejects: (SkillWithResult<*>, String) -> Boolean,
    val call: Pair<String, SkillWithResult<*>>? = null,
) : GarFallback {
    val checked = mutableListOf<String>()
    val routed = mutableListOf<List<String>>()
    var prepared = 0

    override suspend fun tryRoute(
        ctx: SkillContextInternal,
        ranker: SkillRanker,
        utterances: List<String>,
    ): Pair<String, SkillWithResult<*>>? {
        routed.add(utterances)
        return call
    }

    override suspend fun matchCheck(ctx: SkillContextInternal): GarMatchRejecter {
        prepared += 1
        return GarMatchRejecter { match, input ->
            checked.add(input)
            rejects(match, input)
        }
    }
}

/** A [GarFallbackImpl] whose tools provider offers nothing and has the catalog [catalog]. */
private fun checkingFallback(
    logged: MutableList<Pair<String, Throwable?>>,
    catalog: () -> List<ToolSpec> = { listOf() },
    timerNames: () -> List<String?> = { listOf() },
    settings: DataStore<GarSettings> = GarSettingsModule.newDataStoreForPreviews(),
) = GarFallbackImpl(
    settings = settings,
    toolsProvider = object : GarToolsProvider {
        override suspend fun offeredTools(ctx: SkillContext) = listOf<OfferedTool>()
        override fun allTools() = catalog()
    },
    routerSource = FakeRouterSource(route = { u, _ -> callOutput(u) }),
    logger = { message, throwable -> logged.add(Pair(message, throwable)) },
    timerNames = timerNames,
)

/** Settings whose read is cancelled, as when the input's coroutine is cancelled. */
private val cancelledSettings = object : DataStore<GarSettings> {
    override val data: Flow<GarSettings> = flow { throw CancellationException("cancelled") }

    override suspend fun updateData(transform: suspend (t: GarSettings) -> GarSettings): GarSettings =
        throw UnsupportedOperationException()
}

class GarFallbackTest : StringSpec({
    "a call becomes a skill for the target, with the target's skill info" {
        val fixture = Fixture(router = FakeRouterSource(route = { u, _ -> callOutput(u) }))
        val result = fixture.tryRoute().shouldNotBeNull()

        result.first shouldBe "give me ten minutes on the clock"
        val skillWithResult = result.second
        skillWithResult.skill.shouldBeInstanceOf<GarRoutedSkill>()
        skillWithResult.skill.correspondingSkillInfo shouldBeSameInstanceAs timerInfo
        skillWithResult.score shouldBeSameInstanceAs AlwaysBestScore
        val call = skillWithResult.inputData.shouldBeInstanceOf<GarCall>()
        call.tool shouldBe TIMER_SET_TOOL
        call.rawSlots shouldBe mapOf("duration" to "ten minutes")
    }

    "only the best alternative is routed" {
        val fixture = Fixture(router = FakeRouterSource(route = { u, _ -> callOutput(u) }))
        fixture.tryRoute(listOf("first", "second"))?.first shouldBe "first"
        fixture.router.calls shouldBe listOf("first")
    }

    "disabled in settings" {
        val fixture = Fixture(
            settings = GarSettingsSerializer.defaultValue.toBuilder().setLlmEnabled(false).build(),
            router = FakeRouterSource(route = { u, _ -> callOutput(u) }),
        )
        fixture.tryRoute().shouldBeNull()
        fixture.router.calls shouldHaveSize 0
    }

    "a pending follow-up question that didn't match doesn't keep the model away" {
        // e.g. GAR asked "How long?" and the user said something else entirely
        val fixture = Fixture(router = FakeRouterSource(route = { u, _ -> callOutput(u) }))
        fixture.ranker.addBatchToTop(listOf(MockSkill(Specificity.HIGH, 0.0f)))
        fixture.tryRoute().shouldNotBeNull()
        fixture.router.calls shouldHaveSize 1
        // a new command ends the old conversation, like a match in the default batch
        fixture.ranker.hasAnyBatches().shouldBeFalse()
    }

    "without a call, a pending follow-up question stays for another try" {
        val fixture = Fixture(router = FakeRouterSource(route = { _, _ -> RouterOutput.NoMatch }))
        fixture.ranker.addBatchToTop(listOf(MockSkill(Specificity.HIGH, 0.0f)))
        fixture.tryRoute().shouldBeNull()
        fixture.ranker.hasAnyBatches().shouldBeTrue()
    }

    "on a locked phone, only tools allowed on the lock screen run" {
        val fixture = Fixture(
            router = FakeRouterSource(route = { u, _ -> callOutput(u) }),
            locked = true,
        )
        fixture.ranker.addBatchToTop(listOf(MockSkill(Specificity.HIGH, 0.0f)))
        val (_, locked) = fixture.tryRoute().shouldNotBeNull()
        locked.skill.shouldBeInstanceOf<GarLockedSkill>()
        locked.skill.correspondingSkillInfo shouldBeSameInstanceAs timerInfo
        fixture.ranker.hasAnyBatches().shouldBeFalse()

        val allowed = Fixture(
            router = FakeRouterSource(route = { u, _ ->
                callOutput(u).copy(tool = TIMER_SET_TOOL.copy(onLockScreen = true))
            }),
            tools = listOf(
                OfferedTool(TIMER_SET_TOOL.copy(onLockScreen = true), timerInfo, timerSkill)
            ),
            locked = true,
        )
        allowed.tryRoute().shouldNotBeNull().second.skill.shouldBeInstanceOf<GarRoutedSkill>()

        fixture.locked = false
        fixture.tryRoute().shouldNotBeNull().second.skill.shouldBeInstanceOf<GarRoutedSkill>()
    }

    "with FunctionGemma every tool is confirmed" {
        val qwen = Fixture(router = FakeRouterSource(route = { u, _ -> callOutput(u) }))
        qwen.tryRoute().shouldNotBeNull().second.skill.shouldBeInstanceOf<GarRoutedSkill>()
            .confirmAllTools shouldBe false

        val gemma = Fixture(
            settings = GarSettingsSerializer.defaultValue.toBuilder()
                .setModel(GarModel.GAR_MODEL_FUNCTION_GEMMA_270M).build(),
            router = FakeRouterSource(route = { u, _ -> callOutput(u) }),
        )
        gemma.tryRoute().shouldNotBeNull().second.skill.shouldBeInstanceOf<GarRoutedSkill>()
            .confirmAllTools shouldBe true

        val customGemma = Fixture(
            settings = GarSettingsSerializer.defaultValue.toBuilder()
                .setModel(GarModel.GAR_MODEL_CUSTOM)
                .setCustomModelFamily(GarModelFamily.GAR_MODEL_FAMILY_FUNCTION_GEMMA).build(),
            router = FakeRouterSource(route = { u, _ -> callOutput(u) }),
        )
        customGemma.tryRoute().shouldNotBeNull().second.skill.shouldBeInstanceOf<GarRoutedSkill>()
            .confirmAllTools shouldBe true
    }

    "non-English input" {
        val fixture = Fixture(router = FakeRouterSource(route = { u, _ -> callOutput(u) }))
        fixture.tryRoute(ctx = FakeSkillContext(sentencesLanguage = "it")).shouldBeNull()
        fixture.router.calls shouldHaveSize 0
    }

    "no model file" {
        val fixture = Fixture(
            router = FakeRouterSource(modelAvailable = false, route = { u, _ -> callOutput(u) })
        )
        fixture.tryRoute().shouldBeNull()
        fixture.router.calls shouldHaveSize 0
    }

    "no offered tools" {
        val fixture = Fixture(
            router = FakeRouterSource(route = { u, _ -> callOutput(u) }),
            tools = listOf(),
        )
        fixture.tryRoute().shouldBeNull()
        fixture.router.calls shouldHaveSize 0
    }

    "empty input" {
        val fixture = Fixture(router = FakeRouterSource(route = { u, _ -> callOutput(u) }))
        fixture.tryRoute(listOf()).shouldBeNull()
        fixture.tryRoute(listOf(" ")).shouldBeNull()
        fixture.router.calls shouldHaveSize 0
    }

    "no match" {
        val fixture = Fixture(router = FakeRouterSource(route = { _, _ -> RouterOutput.NoMatch }))
        fixture.tryRoute().shouldBeNull()
        fixture.router.calls shouldHaveSize 1
    }

    "a call the router's filter dropped is no match, and a pending question stays" {
        val fixture = Fixture(router = FakeRouterSource(route = { u, _ ->
            RouterOutput.Dropped(callOutput(u), "not a duration")
        }))
        fixture.ranker.addBatchToTop(listOf(MockSkill(Specificity.HIGH, 0.0f)))
        fixture.tryRoute().shouldBeNull()
        fixture.ranker.hasAnyBatches().shouldBeTrue()
        fixture.logged shouldHaveSize 0 // not a failure
    }

    "a model still loading after a moment gets one spoken wait, a loaded or quick one none" {
        var said = 0
        var loaded = false
        var routeMillis = 200L
        val router = object : GarRouterSource {
            override fun isModelAvailable(settings: GarSettings) = true
            override fun isLoaded(settings: GarSettings) = loaded
            override suspend fun route(
                settings: GarSettings,
                utterance: String,
                tools: List<ToolSpec>,
            ): RouterOutput {
                delay(routeMillis)
                return callOutput(utterance)
            }
        }
        val fallback = GarFallbackImpl(
            settings = GarSettingsModule.newDataStoreForPreviews(),
            toolsProvider = object : GarToolsProvider {
                override suspend fun offeredTools(ctx: SkillContext) =
                    listOf(OfferedTool(TIMER_SET_TOOL, timerInfo, timerSkill))
            },
            routerSource = router,
            logger = { _, _ -> },
            sayLoading = { said++ },
            slowLoadMillis = 50,
        )
        val ranker = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))
        suspend fun route() = fallback.tryRoute(FakeSkillContext(), ranker, listOf("ten minutes"))

        route().shouldNotBeNull()
        said shouldBe 1 // loading, and slower than the moment: said once

        routeMillis = 10
        route().shouldNotBeNull()
        said shouldBe 1 // loading, but quick: nothing said

        loaded = true
        routeMillis = 200
        route().shouldNotBeNull()
        said shouldBe 1 // loaded: a slow answer is just thinking
    }

    "router failure is logged" {
        val fixture = Fixture(
            router = FakeRouterSource(route = { _, _ -> RouterOutput.Failed("bad json") })
        )
        fixture.tryRoute().shouldBeNull()
        fixture.logged shouldHaveSize 1
    }

    "exceptions and errors never escape" {
        val exception = Fixture(
            router = FakeRouterSource(route = { _, _ -> throw IllegalStateException("boom") })
        )
        exception.tryRoute().shouldBeNull()
        exception.logged.single().second.shouldBeInstanceOf<IllegalStateException>()

        // e.g. the TODO() bodies of modules that haven't landed yet
        val error = Fixture(router = FakeRouterSource(route = { _, _ -> TODO("not yet") }))
        error.tryRoute().shouldBeNull()
        error.logged.single().second.shouldBeInstanceOf<NotImplementedError>()
    }

    "a call to a tool that wasn't offered" {
        val other = TIMER_SET_TOOL.copy(name = "timer_other")
        val fixture = Fixture(
            router = FakeRouterSource(route = { u, _ ->
                RouterOutput.Call(other, mapOf(), listOf(), u)
            })
        )
        fixture.ranker.addBatchToTop(listOf(MockSkill(Specificity.HIGH, 0.0f)))
        fixture.tryRoute().shouldBeNull()
        // like no match: a pending question stays
        fixture.ranker.hasAnyBatches().shouldBeTrue()
        fixture.logged.single().first shouldContain "wasn't offered"
    }

    "the confirm setting reaches the routed skill" {
        val fixture = Fixture(
            settings = GarSettingsSerializer.defaultValue.toBuilder()
                .setConfirmLlmActions(false).build(),
            router = FakeRouterSource(route = { u, _ -> callOutput(u) }),
        )
        val (_, skillWithResult) = fixture.tryRoute().shouldNotBeNull()
        skillWithResult.skill.shouldBeInstanceOf<GarRoutedSkill>().confirm shouldBe false
    }

    "only one input uses the model at a time" {
        var running = 0
        var maxRunning = 0
        val router = object : GarRouterSource {
            override fun isModelAvailable(settings: GarSettings) = true
            override suspend fun route(
                settings: GarSettings,
                utterance: String,
                tools: List<ToolSpec>,
            ): RouterOutput {
                running += 1
                maxRunning = maxOf(maxRunning, running)
                delay(50)
                running -= 1
                return callOutput(utterance)
            }
        }
        val fallback = GarFallbackImpl(
            settings = GarSettingsModule.newDataStoreForPreviews(),
            toolsProvider = object : GarToolsProvider {
                override suspend fun offeredTools(ctx: SkillContext) =
                    listOf(OfferedTool(TIMER_SET_TOOL, timerInfo, timerSkill))
            },
            routerSource = router,
            logger = { _, _ -> },
        )
        val ranker = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))

        val results = coroutineScope {
            (1..3).map { i ->
                async { fallback.tryRoute(FakeSkillContext(), ranker, listOf("input $i")) }
            }.awaitAll()
        }
        results.map { it?.first } shouldBe listOf("input 1", "input 2", "input 3")
        maxRunning shouldBe 1
    }

    "a Dicio match without its skill's keywords goes to the model, when the model can take it" {
        val tools = listOf(FLASHLIGHT_TOOL.copy(keywords = listOf("flashlight", "light")))
        fun fallback(
            settings: GarSettings = GarSettingsSerializer.defaultValue,
            router: FakeRouterSource = FakeRouterSource(),
        ) = GarFallbackImpl(
            settings = GarSettingsModule.newDataStoreForPreviews(settings),
            toolsProvider = object : GarToolsProvider {
                override suspend fun offeredTools(ctx: SkillContext) = listOf<OfferedTool>()
                override fun allTools() = tools
            },
            routerSource = router,
            logger = { _, _ -> },
        )
        val ctx = FakeSkillContext()
        val off = match("flashlight", 0.75f, Sentences.Flashlight.TurnOff)
        val check = fallback().matchCheck(ctx).shouldNotBeNull()
        check.rejects(off, "switch off the music") shouldBe true
        check.rejects(off, "switch off the light") shouldBe false
        // skills without keywords aren't checked
        check.rejects(match("open", 0.8f, null), "open the tv") shouldBe false

        // with the model off, missing or broken, Dicio's match beats its text fallback
        fallback(router = FakeRouterSource(modelAvailable = false)).matchCheck(ctx).shouldBeNull()
        fallback(router = FakeRouterSource(loads = false)).matchCheck(ctx).shouldBeNull()
        fallback(
            settings = GarSettingsSerializer.defaultValue.toBuilder().setLlmEnabled(false).build(),
        ).matchCheck(ctx).shouldBeNull()
        // keywords are English
        fallback().matchCheck(FakeSkillContext(sentencesLanguage = "it")).shouldBeNull()
    }

    "the timer check knows the running timers" {
        suspend fun check(vararg timers: String?) = GarFallbackImpl(
            settings = GarSettingsModule.newDataStoreForPreviews(),
            toolsProvider = object : GarToolsProvider {
                override suspend fun offeredTools(ctx: SkillContext) = listOf<OfferedTool>()
            },
            routerSource = FakeRouterSource(),
            logger = { _, _ -> },
            timerNames = { timers.toList() },
        ).matchCheck(FakeSkillContext()).shouldNotBeNull()
        val cancelPasta = match("timer", 0.8f, Sentences.Timer.Cancel(" pasta"))
        check().rejects(cancelPasta, "cancel the pasta") shouldBe true
        check(null).rejects(cancelPasta, "cancel the pasta") shouldBe true
        check(" pasta ", null).rejects(cancelPasta, "cancel the pasta") shouldBe false
    }

    "the evaluator checks only matches of the default skills, before dropping a pending question" {
        val timer = FakeSkillInfo("timer")
        val fallbackSkill = MockSkill(Specificity.LOW, 0.0f)
        // the timer's "For how long?" is pending; the default batch has the timer's cancel
        fun ranker(answers: Boolean) = SkillRanker(
            listOf(FixedSkill(timer, 0.8f, "cancel")), fallbackSkill,
        ).apply { addBatchToTop(listOf(FixedSkill(timer, if (answers) 1.0f else 0.0f, "answer"))) }

        // an answer to the question is never checked, even with the timer's info
        val answered = ranker(answers = true)
        val rejectAll = CheckingFallback(rejects = { _, _ -> true })
        val (input, chosen) =
            garMatchUtterances(FakeSkillContext(), answered, rejectAll, listOf("five minutes"))
        input shouldBe "five minutes"
        chosen.inputData shouldBe "answer"
        rejectAll.checked shouldBe listOf()
        rejectAll.routed shouldBe listOf()
        answered.hasAnyBatches().shouldBeTrue()

        // a rejected match of the default batch is no match: every alternative is checked and
        // the model gets the input. It is a new command all the same, not an answer, so it ends
        // the question, also without a call (see GarConversationTest)
        val unanswered = ranker(answers = false)
        val (_, fallen) = garMatchUtterances(
            FakeSkillContext(), unanswered, rejectAll, listOf("stop the alarm", "stop the alarms"),
        )
        rejectAll.checked shouldBe listOf("stop the alarm", "stop the alarms")
        rejectAll.routed shouldBe listOf(listOf("stop the alarm", "stop the alarms"))
        fallen.skill shouldBeSameInstanceAs fallbackSkill
        unanswered.hasAnyBatches().shouldBeFalse()

        // the model's call wins
        val call = Pair("stop the alarm", SkillWithResult(timerSkill, AlwaysBestScore, null))
        garMatchUtterances(
            FakeSkillContext(), ranker(answers = false), CheckingFallback({ _, _ -> true }, call),
            listOf("stop the alarm"),
        ) shouldBe call

        // like with no match, a lower alternative may match
        val rejectFirst = CheckingFallback(rejects = { _, input -> input == "stop the alarm" })
        val other = ranker(answers = false)
        val (otherInput, otherChosen) = garMatchUtterances(
            FakeSkillContext(), other, rejectFirst, listOf("stop the alarm", "stop the timer"),
        )
        otherInput shouldBe "stop the timer"
        otherChosen.inputData shouldBe "cancel"
        rejectFirst.routed shouldBe listOf()
        other.hasAnyBatches().shouldBeFalse() // an accepted match of the default batch, as upstream

        // without a check (model off), Dicio's match runs as upstream
        val unchecked = ranker(answers = false)
        garMatchUtterances(FakeSkillContext(), unchecked, object : GarFallback {
            override suspend fun tryRoute(
                ctx: SkillContextInternal,
                ranker: SkillRanker,
                utterances: List<String>,
            ) = null
        }, listOf("stop the alarm")).second.inputData shouldBe "cancel"
        unchecked.hasAnyBatches().shouldBeFalse()
    }

    "the input keeps its alternative as said, the call the router's normalized utterance" {
        val fixture = Fixture(
            router = FakeRouterSource(route = { _, _ -> callOutput("give me ten minutes") })
        )
        val (input, routed) = fixture.tryRoute(listOf("Give me TEN minutes!")).shouldNotBeNull()
        input shouldBe "Give me TEN minutes!"
        routed.inputData.shouldBeInstanceOf<GarCall>().utterance shouldBe "give me ten minutes"
    }

    "a model that failed to load still gets the input, but Dicio's matches aren't checked" {
        // routing with a model whose load failed fails right away in GarModelManager (the failed
        // load is remembered, the load guard refuses it) and the input goes to Dicio's fallback,
        // as without the model; a checked match would go there too, instead of running
        val fixture = Fixture(router = FakeRouterSource(loads = false, route = { u, _ -> callOutput(u) }))
        fixture.fallback.matchCheck(FakeSkillContext()).shouldBeNull()
        fixture.tryRoute().shouldNotBeNull()
        fixture.router.calls shouldHaveSize 1
    }

    "failures while preparing the match check only skip the check of this input" {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        // timers are added, expire and are cancelled on other threads while their names are read
        checkingFallback(logged, timerNames = { throw ConcurrentModificationException() })
            .matchCheck(FakeSkillContext()).shouldBeNull()
        logged.single().first shouldBe "Error while preparing the match check"
        logged.single().second.shouldBeInstanceOf<ConcurrentModificationException>()

        logged.clear()
        checkingFallback(logged, catalog = { throw IllegalStateException("no catalog") })
            .matchCheck(FakeSkillContext()).shouldBeNull()
        logged.single().first shouldBe "Error while preparing the match check"
    }

    "a match the check fails on is kept, and the error logged" {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val check = checkingFallback(
            logged, catalog = { listOf(FLASHLIGHT_TOOL.copy(keywords = listOf("flashlight"))) },
        ).matchCheck(FakeSkillContext()).shouldNotBeNull()
        val brokenScore = object : Score {
            override fun scoreIn01Range(): Float = throw IllegalStateException("bad score")
            override fun isBetterThan(other: Score) = false
        }
        val match = SkillWithResult(
            RecordingSkill(FakeSkillInfo("flashlight")), brokenScore, Sentences.Flashlight.TurnOff,
        )
        check.rejects(match, "switch off the music") shouldBe false
        logged.single().first shouldBe "Error while checking a match"
        logged.single().second.shouldBeInstanceOf<IllegalStateException>()
    }

    "a failure to list the offered tools is logged" {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val router = FakeRouterSource(route = { u, _ -> callOutput(u) })
        GarFallbackImpl(
            settings = GarSettingsModule.newDataStoreForPreviews(),
            toolsProvider = object : GarToolsProvider {
                override suspend fun offeredTools(ctx: SkillContext): List<OfferedTool> =
                    throw IllegalStateException("no assets")
            },
            routerSource = router,
            logger = { message, throwable -> logged.add(Pair(message, throwable)) },
        ).tryRoute(FakeSkillContext(), SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f)), listOf("x"))
            .shouldBeNull()
        logged.single().first shouldBe "Error while routing with the model"
        router.calls shouldHaveSize 0
    }

    "cancellation is passed on, not logged" {
        val fixture = Fixture(
            router = FakeRouterSource(route = { _, _ -> throw CancellationException("cancelled") })
        )
        shouldThrow<CancellationException> { fixture.tryRoute() }
        fixture.logged.shouldBeEmpty()

        val logged = mutableListOf<Pair<String, Throwable?>>()
        val fallback = checkingFallback(logged, settings = cancelledSettings)
        shouldThrow<CancellationException> { fallback.matchCheck(FakeSkillContext()) }
        shouldThrow<CancellationException> {
            fallback.tryRoute(
                FakeSkillContext(), SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f)), listOf("x"),
            )
        }
        logged.shouldBeEmpty()
    }

    "the match check is prepared once per input, not once per alternative" {
        val fallbackSkill = MockSkill(Specificity.LOW, 0.0f)
        val ranker = SkillRanker(listOf(FixedSkill(FakeSkillInfo("timer"), 0.0f, "cancel")), fallbackSkill)
        val fallback = CheckingFallback(rejects = { _, _ -> false })
        val (input, chosen) = garMatchUtterances(
            FakeSkillContext(), ranker, fallback, listOf("first", "second", "third"),
        )
        input shouldBe "first"
        chosen.skill shouldBeSameInstanceAs fallbackSkill
        fallback.prepared shouldBe 1
        fallback.routed shouldBe listOf(listOf("first", "second", "third"))
    }
})
