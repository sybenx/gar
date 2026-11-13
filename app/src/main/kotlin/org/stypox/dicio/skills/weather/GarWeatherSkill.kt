package org.stypox.dicio.skills.weather

import android.content.Context
import androidx.compose.runtime.Composable
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.SlotResolvers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.gar.call.GarCall
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.GarToolSkill
import org.stypox.dicio.gar.call.isCancelPhrase
import org.stypox.dicio.gar.online.GarOfflineFirstSkill
import org.stypox.dicio.sentences.Sentences.Weather
import org.stypox.dicio.skills.notes.isPhoneLocked
import org.stypox.dicio.skills.weather.WeatherInfo.weatherDataStore
import java.time.LocalDate

/**
 * GAR's weather skill (gar:weather, gar/PARITY.md batch 5), in place of Dicio's current-only
 * OpenWeatherMap skill behind the same online skill entry ("weather", asked once): forecasts by
 * day and hour, rain, snow, umbrella, highs and lows, wind, and when the rain stops. Breezy
 * Weather's shared data answers offline when it is fresh ([BreezyWeatherData]); otherwise
 * Open-Meteo ([OpenMeteo]) for the place said, the home city or the approximate location.
 *
 * In English, GAR reads the day and the place from the whole input ([WeatherWords]), for Dicio's
 * sentences (whose capture only takes the words) and the model's slotless `weather` call alike,
 * and keeps a match only when [WeatherWords] reads a weather request in it: a sentence matched
 * with a word swapped ("play cold by coldplay") never runs. Other languages get Dicio's sentences
 * as before: current conditions in the place captured.
 *
 * Asking which city the user is in, and its answers, save lasting choices (the default city,
 * "use my location"), so on a locked phone GAR says to unlock instead of asking; a request with a
 * place or a saved city still answers there.
 *
 * @param lookupFor where forecasts come from (fakes in tests)
 */
class GarWeatherSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Weather>,
    private val lookupFor: (SkillContext) -> WeatherLookup = ::defaultLookup,
    private val unitsFor: suspend (SkillContext) -> WeatherUnits = ::defaultUnits,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
    private val appsFor: (SkillContext) -> WeatherAppAccess = WeatherAppAccess::of,
) : Skill<WeatherRequest?>(correspondingSkillInfo, data.specificity), GarToolSkill, GarOfflineFirstSkill {

    override fun score(ctx: SkillContext, input: String): Pair<Score, WeatherRequest?> {
        val (score, match) = data.score(ctx, input)
        if (!isEnglish(ctx)) {
            return Pair(score, WeatherRequest.current((match as? Weather.Current)?.where))
        }
        val request = read(ctx, input) ?: return Pair(AlwaysWorstScore, null)
        return Pair(score, request)
    }

    /** The request in [input] (English), or null when it isn't about the weather. */
    fun read(ctx: SkillContext, input: String): WeatherRequest? =
        WeatherWords.parse(input, today()) { phrase ->
            // days WeatherWords doesn't read itself: "on the fifth", "november tenth"
            val resolution = SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage).date(phrase)
            (resolution as? Resolution.Resolved)?.value?.date
        }

    /** The model's slotless call: the request in its utterance. */
    override fun inputFromCall(ctx: SkillContext, call: GarCall): Any =
        read(ctx, call.utterance)
            ?: WeatherRequest(WeatherTopic.GENERAL, WeatherWhen.Now, null, utterance = call.utterance)

    override fun inputFromSlots(slots: Map<String, Any?>): Any =
        WeatherRequest(WeatherTopic.GENERAL, WeatherWhen.Now, null)

    override suspend fun offlineOutput(ctx: SkillContext, inputData: Any?): SkillOutput? {
        val request = inputData as? WeatherRequest ?: return null
        val answer = withContext(Dispatchers.IO) { lookupFor(ctx).offline(request) } ?: return null
        return WeatherAnswerOutput(answer, unitsFor(ctx))
    }

    /**
     * The skill is off (the user chose so, or just said to keep it off): the user's weather app,
     * "The weather skill is off, so I opened Breezy Weather.", or null for the gate's own answer.
     */
    override suspend fun offOutput(ctx: SkillContext, inputData: Any?, justDeclined: Boolean): SkillOutput? {
        val reason = if (justDeclined) WeatherAppReason.STAYS_OFF else WeatherAppReason.SKILL_OFF
        return withContext(Dispatchers.IO) { WeatherAppFallback.output(appsFor(ctx), reason, isLocked(ctx)) }
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: WeatherRequest?): SkillOutput {
        val request = inputData ?: WeatherRequest(WeatherTopic.GENERAL, WeatherWhen.Now, null)
        val lookup = lookupFor(ctx)
        val units = unitsFor(ctx)
        val result = withContext(Dispatchers.IO) { lookup.offline(request) ?: lookup.online(request) }
        if (result is WeatherResult.AskPlace && isLocked(ctx)) {
            return GarLockedOutput()
        }
        return weatherOutputOrApp(ctx, result, request, lookup, units, appsFor, isLocked)
    }

    companion object {
        private fun isEnglish(ctx: SkillContext): Boolean =
            ctx.sentencesLanguage.substringBefore('-').substringBefore('_') == "en"

        fun defaultLookup(ctx: SkillContext): WeatherLookup = WeatherLookup(
            openMeteo = OpenMeteo(),
            places = AndroidWeatherPlaces(ctx.android),
            breezy = { BreezyWeatherStore(ctx.android).read() },
        )

        suspend fun defaultUnits(ctx: SkillContext): WeatherUnits =
            WeatherUnits.from(ctx.android.weatherDataStore.data.first())
    }
}

/**
 * The weather skill's info for "use my location": Dicio asks for the approximate location before
 * [WeatherLocationAnswerSkill] runs (its [neededPermissions]), and says plainly that it is
 * missing when the user declines. The weather skill itself needs no permission.
 */
object WeatherLocationInfo : SkillInfo("weather_location") {
    override fun name(context: Context) = WeatherInfo.name(context)

    override fun sentenceExample(context: Context) = WeatherInfo.sentenceExample(context)

    @Composable
    override fun icon() = WeatherInfo.icon()

    override val neededPermissions: List<Permission> = listOf(PERMISSION_COARSE_LOCATION)

    /** Never in Dicio's list of skills: only the answer to GAR's question uses it. */
    override fun build(ctx: SkillContext): Skill<*>? = null
}

/** "Use my location" in answer to [WeatherPlaceQuestionOutput]; not on a locked phone. */
class WeatherLocationAnswerSkill(
    private val request: WeatherRequest,
    private val lookup: WeatherLookup,
    private val units: WeatherUnits,
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
    private val onAnswered: () -> Unit = {},
    private val appsFor: (SkillContext) -> WeatherAppAccess = WeatherAppAccess::of,
) : Skill<Unit>(WeatherLocationInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Unit> =
        Pair(if (WeatherPlaceAnswers.isLocation(input)) AlwaysBestScore else AlwaysWorstScore, Unit)

    override suspend fun generateOutput(ctx: SkillContext, inputData: Unit): SkillOutput {
        onAnswered()
        if (isLocked(ctx)) {
            return GarFollowUpOutput(GarLockedOutput())
        }
        val result = withContext(Dispatchers.IO) { lookup.useLocation(request) }
        return GarFollowUpOutput(weatherOutputOrApp(ctx, result, request, lookup, units, appsFor, isLocked))
    }
}

/**
 * A city in answer to [WeatherPlaceQuestionOutput]: remembered, then the forecast there; not on a
 * locked phone.
 */
class WeatherCityAnswerSkill(
    private val request: WeatherRequest,
    private val lookup: WeatherLookup,
    private val units: WeatherUnits,
    private val isLocked: (SkillContext) -> Boolean = ::isPhoneLocked,
    private val onAnswered: () -> Unit = {},
    private val appsFor: (SkillContext) -> WeatherAppAccess = WeatherAppAccess::of,
) : Skill<String?>(WeatherInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, String?> {
        val city = if (isCancelPhrase(input)) null else WeatherPlaceAnswers.city(input)
        return Pair(if (city == null) AlwaysWorstScore else AlwaysBestScore, city)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: String?): SkillOutput {
        onAnswered()
        if (isLocked(ctx)) {
            return GarFollowUpOutput(GarLockedOutput())
        }
        val city = inputData ?: return GarFollowUpOutput(
            WeatherPlaceQuestionOutput(request, AskReason.NOT_FOUND, lookup, units, appsFor, isLocked),
        )
        val result = withContext(Dispatchers.IO) { lookup.answerCity(request, city) }
        return GarFollowUpOutput(weatherOutputOrApp(ctx, result, request, lookup, units, appsFor, isLocked))
    }
}

/**
 * [weatherOutput], except that when Open-Meteo isn't answering GAR opens the user's weather app
 * ([WeatherAppFallback]): "Open-Meteo isn't answering, so I opened Breezy Weather."
 */
suspend fun weatherOutputOrApp(
    ctx: SkillContext,
    result: WeatherResult,
    request: WeatherRequest,
    lookup: WeatherLookup,
    units: WeatherUnits,
    appsFor: (SkillContext) -> WeatherAppAccess,
    isLocked: (SkillContext) -> Boolean,
): SkillOutput {
    if (result == WeatherResult.NoConnection) {
        withContext(Dispatchers.IO) {
            WeatherAppFallback.output(appsFor(ctx), WeatherAppReason.UNREACHABLE, isLocked(ctx))
        }?.let { return it }
    }
    return weatherOutput(result, request, lookup, units, appsFor, isLocked)
}
