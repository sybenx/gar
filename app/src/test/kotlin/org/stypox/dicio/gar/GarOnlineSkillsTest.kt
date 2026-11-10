package org.stypox.dicio.gar

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.online.GarCheckedOnlineSkill
import org.stypox.dicio.gar.online.GarOnlineAnswerSkill
import org.stypox.dicio.gar.online.GarOnlineAnswers
import org.stypox.dicio.gar.online.GarOnlineAskOutput
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.online.GarOnlineIsOffOutput
import org.stypox.dicio.gar.online.GarOnlineSettings
import org.stypox.dicio.gar.online.GarOnlineSettingsImpl
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.gar.online.GarOnlineStaysOffOutput
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.lyrics.LyricsInfo
import org.stypox.dicio.skills.online_skills.OnlineSkillsInfo
import org.stypox.dicio.skills.online_skills.OnlineSkillsOutput
import org.stypox.dicio.skills.online_skills.OnlineSkillsSkill
import org.stypox.dicio.skills.online_skills.OnlineSkillsWords
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.translation.TranslationInfo
import org.stypox.dicio.skills.weather.WeatherInfo

/** GAR's settings and Dicio's on a new install, in memory: every online skill off, none asked. */
private fun newSettings() = GarOnlineSettingsImpl(
    UserSettingsModule.newDataStoreForPreviews(),
    GarSettingsModule.newDataStoreForPreviews(),
)

private val PARIS = Sentences.Weather.Current(where = "paris")

/** A weather gate around a skill that records what it runs with, on a phone that may be locked. */
private class Gate(val settings: GarOnlineSettingsImpl = newSettings()) {
    var locked = false
    val weather = RecordingSkill(WeatherInfo)
    val gate = GarOnlineGateSkill(GarOnlineSkill.WEATHER, weather, isDeviceLocked = { locked }) {
        settings
    }
}

/** The sweep with [settings], on a phone that is [locked] or not. */
private fun sweepSkill(settings: GarOnlineSettingsImpl, locked: Boolean = false) =
    OnlineSkillsSkill(
        OnlineSkillsInfo, Sentences.OnlineSkills["en"]!!, isDeviceLocked = { locked },
    ) { settings }

private fun answerSkill(ask: GarOnlineAskOutput): GarOnlineAnswerSkill =
    ask.nextSkills("Turn it on", "Keep it off").single().shouldBeInstanceOf<GarOnlineAnswerSkill>()

class GarOnlineSkillsTest : StringSpec({
    "the online skills are the ones GAR turns off on a new install" {
        UserSettingsSerializer.defaultValue.enabledSkillsMap.filterValues { !it }.keys shouldBe
            GarOnlineSkill.ids.toSet()
        // the skills' own ids ("Joke" has a capital J)
        GarOnlineSkill.ids shouldContainExactlyInAnyOrder
            listOf(WeatherInfo, SearchInfo, LyricsInfo, TranslationInfo, JokeInfo).map { it.id } +
            // gar:knowledge
            listOf(
                org.stypox.dicio.skills.knowledge.KnowledgeInfo,
                org.stypox.dicio.skills.dictionary.DictionaryInfo,
                org.stypox.dicio.skills.currency.CurrencyInfo,
            ).map { it.id }
    }

    "never asked: the gate asks, and remembers nothing yet" {
        val setup = Gate()
        val ask = setup.gate.generateOutput(FakeSkillContext(), PARIS)
            .shouldBeInstanceOf<GarOnlineAskOutput>()
        ask.online shouldBe GarOnlineSkill.WEATHER
        // the answer goes to the question's skill (its plan reads the buttons' labels from the app)
        answerSkill(ask).correspondingSkillInfo shouldBe WeatherInfo
        setup.weather.inputs.shouldBeEmpty()
        setup.settings.wasAsked("weather") shouldBe false
        setup.settings.isEnabled("weather") shouldBe false
    }

    "yes: the skill is on, the choice remembered, and the request answered with the same match" {
        for (answer in listOf("yes", "sure", "turn it on", "Turn it on", "yes please", "go ahead")) {
            withClue(answer) {
                val setup = Gate()
                val ask = setup.gate.generateOutput(FakeSkillContext(), PARIS) as GarOnlineAskOutput
                val skill = answerSkill(ask)
                val (score, data) = skill.score(FakeSkillContext(), answer)
                score shouldBe AlwaysBestScore
                data shouldBe true

                val output = skill.generateOutput(FakeSkillContext(), data)
                // the weather skill's own output, as if it had matched directly
                output.shouldBeInstanceOf<GarFollowUpOutput>().output
                    .shouldBeInstanceOf<FakeOutput>().input shouldBe PARIS
                setup.weather.inputs shouldContainExactly listOf(PARIS)
                setup.settings.isEnabled("weather") shouldBe true
                setup.settings.wasAsked("weather") shouldBe true
                ask.answered shouldBe true
                // the other online skills are untouched
                setup.settings.isEnabled("search") shouldBe false
                setup.settings.wasAsked("search") shouldBe false
            }
        }
    }

    "no: the choice is remembered and the skill stays off" {
        for (answer in listOf("no", "not now", "Keep it off", "no thanks", "never mind", "nope")) {
            withClue(answer) {
                val setup = Gate()
                val ask = setup.gate.generateOutput(FakeSkillContext(), PARIS) as GarOnlineAskOutput
                val skill = answerSkill(ask)
                val (score, data) = skill.score(FakeSkillContext(), answer)
                score shouldBe AlwaysBestScore
                data shouldBe false

                skill.generateOutput(FakeSkillContext(), data)
                    .shouldBeInstanceOf<GarOnlineStaysOffOutput>().online shouldBe GarOnlineSkill.WEATHER
                setup.weather.inputs.shouldBeEmpty()
                setup.settings.isEnabled("weather") shouldBe false
                setup.settings.wasAsked("weather") shouldBe true
            }
        }
    }

    "any other reply answers nothing, and GAR asks again next time" {
        val setup = Gate()
        val ask = setup.gate.generateOutput(FakeSkillContext(), PARIS) as GarOnlineAskOutput
        val skill = answerSkill(ask)
        for (reply in listOf(
            "set a timer for ten minutes", "what's the weather in london", "turn on the lights",
            "", "hmm what",
        )) {
            withClue(reply) { skill.score(FakeSkillContext(), reply).first shouldBe AlwaysWorstScore }
        }

        // a new command goes to its own skill, and drops the question
        val timer = TimerInfo.build(FakeSkillContext())!!
        val ranker = SkillRanker(listOf(timer), MockSkill(Specificity.LOW, 0.0f))
        ranker.addBatchToTop(ask.nextSkills("Turn it on", "Keep it off"))
        val input = "set a timer for ten minutes"
        ranker.getBest(FakeSkillContext().forInput(input), input)?.skill shouldBeSameInstanceAs timer
        ranker.hasAnyBatches() shouldBe false

        setup.settings.wasAsked("weather") shouldBe false
        setup.settings.isEnabled("weather") shouldBe false
        setup.gate.generateOutput(FakeSkillContext(), PARIS).shouldBeInstanceOf<GarOnlineAskOutput>()
    }

    "asked and off: a short answer that it is off, without a question" {
        val setup = Gate()
        setup.settings.markAsked(listOf("weather"))
        val output = setup.gate.generateOutput(FakeSkillContext(), PARIS)
            .shouldBeInstanceOf<GarOnlineIsOffOutput>()
        output.online shouldBe GarOnlineSkill.WEATHER
        output.getInteractionPlan(FakeSkillContext()) shouldBe InteractionPlan.FinishInteraction
        setup.weather.inputs.shouldBeEmpty()
    }

    "locked: the gate doesn't ask, and remembers nothing" {
        val setup = Gate()
        setup.locked = true
        setup.gate.generateOutput(FakeSkillContext(), PARIS).shouldBeInstanceOf<GarLockedOutput>()
        setup.settings.wasAsked("weather") shouldBe false
        setup.settings.isEnabled("weather") shouldBe false
        setup.weather.inputs.shouldBeEmpty()

        // already asked, it still says that the skill is off
        setup.settings.markAsked(listOf("weather"))
        setup.gate.generateOutput(FakeSkillContext(), PARIS)
            .shouldBeInstanceOf<GarOnlineIsOffOutput>()
    }

    "locked before the answer: a yes changes nothing, a no is remembered" {
        val setup = Gate()
        val ask = setup.gate.generateOutput(FakeSkillContext(), PARIS) as GarOnlineAskOutput
        setup.locked = true
        answerSkill(ask).generateOutput(FakeSkillContext(), true)
            .shouldBeInstanceOf<GarLockedOutput>()
        setup.settings.isEnabled("weather") shouldBe false
        setup.settings.wasAsked("weather") shouldBe false
        setup.weather.inputs.shouldBeEmpty()

        answerSkill(ask).generateOutput(FakeSkillContext(), false)
            .shouldBeInstanceOf<GarOnlineStaysOffOutput>()
        setup.settings.wasAsked("weather") shouldBe true
    }

    "the question's buttons belong to the ranker it was asked in" {
        val first = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))
        val rankers = MutableStateFlow(first)
        val settings = GarOnlineSettingsImpl(
            UserSettingsModule.newDataStoreForPreviews(),
            GarSettingsModule.newDataStoreForPreviews(),
            rankers,
        )
        val ask = Gate(settings).gate.generateOutput(FakeSkillContext(), PARIS)
            .shouldBeInstanceOf<GarOnlineAskOutput>()
        ask.askedRanker shouldBeSameInstanceAs first
        // e.g. a skill switched in Settings > Skills: the card's buttons compare with the new one
        rankers.value = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))
        ask.askedRanker shouldBeSameInstanceAs first
    }

    "turned on meanwhile: the gate just runs the skill" {
        val setup = Gate()
        setup.settings.setEnabled(listOf("weather"), true)
        setup.gate.generateOutput(FakeSkillContext(), PARIS).shouldBeInstanceOf<FakeOutput>()
        setup.weather.inputs shouldContainExactly listOf(PARIS)
    }

    "on: no gate at all, only GAR's check, and the online skills rank last" {
        val ctx = FakeSkillContext()
        val calculator = CalculatorInfo.build(ctx)!!
        val weather = WeatherInfo.build(ctx)!!
        val search = SearchInfo.build(ctx)!!
        val ranked = GarOnlineSkills.rankedSkills(
            allSkills = listOf(WeatherInfo, SearchInfo, CalculatorInfo, JokeInfo),
            enabledSkills = mapOf("weather" to true, "search" to false, "Joke" to false),
            built = listOf(Pair(WeatherInfo, weather), Pair(CalculatorInfo, calculator)),
            ctx = ctx,
        )
        ranked.map { it.correspondingSkillInfo.id } shouldContainExactly
            listOf("calculator", "weather", "search", "Joke")
        ranked[0] shouldBeSameInstanceAs calculator
        ranked[1].shouldBeInstanceOf<GarCheckedOnlineSkill>().let {
            (it is GarOnlineGateSkill) shouldBe false
            it.wrapped shouldBeSameInstanceAs weather
        }
        ranked[2].shouldBeInstanceOf<GarOnlineGateSkill>().wrapped.correspondingSkillInfo shouldBe
            search.correspondingSkillInfo
        ranked[3].shouldBeInstanceOf<GarOnlineGateSkill>()

        // a skill that isn't online and is off isn't ranked at all
        GarOnlineSkills.forRanking(listOf(calculator), mapOf("calculator" to false)).shouldBeEmpty()
    }

    "a gate scores exactly like its skill for the inputs it takes, and worst for others" {
        val weather = WeatherInfo.build(FakeSkillContext())!!
        val gate = GarOnlineGateSkill(GarOnlineSkill.WEATHER, weather) { newSettings() }
        val checked = GarCheckedOnlineSkill(GarOnlineSkill.WEATHER, weather)
        for (input in listOf("what's the weather like in paris", "is it raining", "how is it outside")) {
            withClue(input) {
                val expected = weather.score(FakeSkillContext().forInput(input), input)
                gate.score(FakeSkillContext().forInput(input), input) shouldBe expected
                checked.score(FakeSkillContext().forInput(input), input) shouldBe expected
            }
        }
        // "how late is it" matched "how is it outside" with a word swapped
        gate.score(FakeSkillContext().forInput("how late is it"), "how late is it").first shouldBe
            AlwaysWorstScore
        // the gate is English only (its question is); a skill that is on is unchecked elsewhere
        val german = FakeSkillContext(sentencesLanguage = "de")
        val input = "what's the weather like in paris"
        german.forInput(input)
        gate.score(german, input).first shouldBe AlwaysWorstScore
        checked.score(german, input) shouldBe weather.score(german, input)
    }

    "switching an online skill in the settings counts as a choice" {
        val settings = newSettings()
        // what SkillSettingsViewModel's GAR line calls, with GAR's settings of the app
        for (id in listOf("Joke", "calculator", OnlineSkillsInfo.id)) {
            GarOnlineSettings.skillSwitched(settings, id)
        }
        settings.wasAsked("Joke") shouldBe true
        settings.wasAsked("calculator") shouldBe false
        settings.wasAsked(OnlineSkillsInfo.id) shouldBe false
        settings.wasAsked("weather") shouldBe false
    }

    "turning a skill on waits for Dicio's new ranker, and only when something changed" {
        val userSettings = UserSettingsModule.newDataStoreForPreviews()
        val first = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))
        val second = SkillRanker(listOf(), MockSkill(Specificity.LOW, 0.0f))
        val rankers = MutableStateFlow(first)
        val settings = GarOnlineSettingsImpl(
            userSettings, GarSettingsModule.newDataStoreForPreviews(), rankers,
        )
        coroutineScope {
            // stands in for SkillHandler, which builds a new ranker when the switches change
            val handler = async {
                userSettings.data.first { it.enabledSkillsMap["weather"] == true }
                yield()
                rankers.value = second
            }
            settings.setEnabled(listOf("weather"), true)
            rankers.value shouldBeSameInstanceAs second
            handler.await()
        }
        // nothing changes: no new ranker to wait for (it would time out)
        val start = System.nanoTime()
        settings.setEnabled(listOf("weather"), true)
        ((System.nanoTime() - start) / 1_000_000 < GarOnlineSettingsImpl.RANKER_TIMEOUT_MILLIS) shouldBe true
    }

    "the sweep turns every online skill on or off, and GAR won't ask about them" {
        val settings = newSettings()
        val sweep = sweepSkill(settings)

        sweep.generateOutput(FakeSkillContext(), Sentences.OnlineSkills.TurnOn)
            .shouldBeInstanceOf<OnlineSkillsOutput>().on shouldBe true
        for (id in GarOnlineSkill.ids) {
            withClue(id) {
                settings.isEnabled(id) shouldBe true
                settings.wasAsked(id) shouldBe true
            }
        }

        sweep.generateOutput(FakeSkillContext(), Sentences.OnlineSkills.TurnOff)
            .shouldBeInstanceOf<OnlineSkillsOutput>().on shouldBe false
        for (id in GarOnlineSkill.ids) {
            withClue(id) {
                settings.isEnabled(id) shouldBe false
                settings.wasAsked(id) shouldBe true
            }
        }
        // a gate then only says that the skill is off
        GarOnlineGateSkill(GarOnlineSkill.WEATHER, RecordingSkill(WeatherInfo)) { settings }
            .generateOutput(FakeSkillContext(), PARIS).shouldBeInstanceOf<GarOnlineIsOffOutput>()
    }

    "locked: the sweep doesn't turn them on, but off" {
        val settings = newSettings()
        sweepSkill(settings, locked = true)
            .generateOutput(FakeSkillContext(), Sentences.OnlineSkills.TurnOn)
            .shouldBeInstanceOf<GarLockedOutput>()
        for (id in GarOnlineSkill.ids) {
            withClue(id) {
                settings.isEnabled(id) shouldBe false
                settings.wasAsked(id) shouldBe false
            }
        }

        settings.setEnabled(listOf("weather"), true)
        sweepSkill(settings, locked = true)
            .generateOutput(FakeSkillContext(), Sentences.OnlineSkills.TurnOff)
            .shouldBeInstanceOf<OnlineSkillsOutput>().on shouldBe false
        settings.isEnabled("weather") shouldBe false
        settings.wasAsked("search") shouldBe true
    }

    "the sweep needs the online skills named, and on or off" {
        for ((input, on) in listOf(
            "turn on all online skills" to true, "enable the internet features" to true,
            "turn the online skills on" to true, "can you turn on the online skills" to true,
            "please turn on all online skills" to true,
            "will you switch on the internet features" to true,
            "turn off all online skills" to false, "disable online services" to false,
            "turn the internet skills off" to false,
        )) {
            withClue(input) { OnlineSkillsWords.turnsOn(input) shouldBe on }
        }
        for (input in listOf(
            "turn on all the lights", "turn on wifi", "turn off all alarms", "turn on bluetooth",
            "turn on do not disturb", "turn on the internet", "turn on online banking",
            "online skills", "turn on and off the online skills", "online skills on",
            // questions and negations ask for nothing
            "are the online skills on", "is the online feature on",
            "are the internet features turned on", "why are the online skills off",
            "should I turn on online skills",
            "how do I turn on the online skills", "so are the online skills enabled",
            "don't turn on the online skills", "do not enable online features",
            "do not turn on the online skills", "never turn on online skills",
            "no, don't enable online features", "please don’t turn off the internet skills",
        )) {
            withClue(input) { OnlineSkillsWords.turnsOn(input).shouldBeNull() }
        }
        // the skill's own score: a match without those words is no match
        val sweep = OnlineSkillsInfo.build(FakeSkillContext()).shouldBeInstanceOf<OnlineSkillsSkill>()
        for (input in listOf(
            "turn on all the lights", "turn off all alarms", "are the online skills on",
            "don't turn on the online skills",
        )) {
            withClue(input) {
                sweep.score(FakeSkillContext().forInput(input), input).first shouldBe AlwaysWorstScore
            }
        }
        val input = "turn on all online skills"
        sweep.score(FakeSkillContext().forInput(input), input).second shouldBe
            Sentences.OnlineSkills.TurnOn
    }

    "spoken answers to the question" {
        val weather = GarOnlineSkill.WEATHER
        for (yes in listOf(
            "yes", "yeah", "sure", "ok", "okay", "yes please", "turn it on", "switch it on",
            "turn on weather", "yes turn on the weather skill", "enable it", "go ahead", "do it",
            "please do", "i do", "go for it", "let's go", "sure why not", "of course", "absolutely",
            "continue", "no wait turn it on", "Yes!", "all right", "alright", "yes that's fine",
            "that's fine turn it on", "okay thanks",
        )) {
            withClue(yes) { GarOnlineAnswers.classify(yes, weather) shouldBe true }
        }
        for (no in listOf(
            "no", "nope", "nah", "no thanks", "no thank you", "not now", "not yet", "maybe later",
            "keep it off", "leave it off", "turn it off", "don't", "don't turn it on", "do not",
            "never mind", "cancel", "stop", "no it's fine", "yes no", "turn it on no", "I'm good",
            "no I'm good", "nah I'm fine", "not sure keep it off",
        )) {
            withClue(no) { GarOnlineAnswers.classify(no, weather) shouldBe false }
        }
        for (other in listOf(
            "", "go", "do", "maybe", "thanks", "what's the weather in paris", "turn on the lights",
            "turn on wifi", "set a timer for ten minutes", "what", "the weather", "tell me a joke",
            // unclear: nothing is remembered, and GAR asks again next time
            "that's okay", "it's fine", "that's alright", "all good", "not sure", "i'm not sure",
            "no wait",
            // about another online skill
            "no just turn on search", "no turn on search", "turn on jokes",
            "yes turn on the lyrics skill", "turn on the joke skill",
        )) {
            withClue(other) { GarOnlineAnswers.classify(other, weather).shouldBeNull() }
        }
        // each question takes its own skill's name
        GarOnlineAnswers.classify("turn on search", GarOnlineSkill.SEARCH) shouldBe true
        GarOnlineAnswers.classify("yes translate", GarOnlineSkill.TRANSLATION) shouldBe true
        for (other in listOf("no turn on weather", "enable weather", "turn on jokes")) {
            withClue(other) {
                GarOnlineAnswers.classify(other, GarOnlineSkill.SEARCH).shouldBeNull()
            }
        }
    }

    "the answer skill knows its buttons" {
        val setup = Gate()
        val ask = setup.gate.generateOutput(FakeSkillContext(), PARIS) as GarOnlineAskOutput
        val skill = GarOnlineAnswerSkill(ask.gate, PARIS, setup.settings, "Allow", "Deny")
        skill.score(FakeSkillContext(), "Allow").second shouldBe true
        skill.score(FakeSkillContext(), "deny").second shouldBe false
        skill.correspondingSkillInfo shouldBe WeatherInfo
    }
})
