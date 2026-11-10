package org.stypox.dicio.gar.call

import cc.aaro.gar.router.CallFilter
import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.resolve.SlotPlausibilityFilter
import cc.aaro.gar.router.resolve.SlotResolvers
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.skills.navigation.NavigationWords
import org.stypox.dicio.skills.places.PlaceWords
import org.stypox.dicio.skills.song.SongWords

/**
 * The filter for the model's calls (gar/DESIGN.md, "Call filters"): the tool's keywords first,
 * then whether its typed slots resolve, with the resolvers and slot rewrites that [GarCallRunner]
 * uses, in the language [ctx] has when the call is made, then [SongCallFilter],
 * [NavigationCallFilter], the device settings' check of the setting the words name
 * (`DeviceSettingCalls.filter`) and [WeatherCallFilter].
 */
fun garCallFilter(ctx: SkillContext): CallFilter = CallFilter.allOf(
    KeywordFilter,
    SlotPlausibilityFilter(
        resolvers = { SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage) },
        rewrite = GarSlotRewrites::rewrite,
    ),
    SongCallFilter,
    NavigationCallFilter,
    org.stypox.dicio.skills.device_settings.DeviceSettingCalls.filter, // gar:settings
    WeatherCallFilter, // gar:weather
)

/**
 * gar:weather. A `weather` call must be a weather request as the weather skill reads it
 * ([org.stypox.dicio.skills.weather.WeatherWords]): "play cold by coldplay", "set the temperature
 * to 70" and "it's raining men" pass the tool's keywords but ask nothing about the weather. App
 * only: the eval in `:gar:router` doesn't run it.
 */
object WeatherCallFilter : CallFilter {
    const val TOOL = "weather"

    override fun check(call: RouterOutput.Call): CallVerdict =
        if (call.tool.name != TOOL ||
            org.stypox.dicio.skills.weather.WeatherWords.asksForWeather(call.utterance)
        ) {
            CallVerdict.Keep
        } else {
            CallVerdict.Drop("'${call.utterance}' doesn't ask about the weather")
        }
}

/**
 * gar:song. A `song_identify` call may record 10 s and send them to the user's service, so it
 * needs the input the song sentences need ([SongWords]): a keyword, a word that points at what
 * plays and no other thing ("who sings hello", "sing me a song", "track my package", "skip this
 * song" are dropped). App only: the eval in `:gar:router` doesn't run it.
 */
object SongCallFilter : CallFilter {
    const val TOOL = "song_identify"

    override fun check(call: RouterOutput.Call): CallVerdict =
        if (call.tool.name != TOOL || SongWords.asksForSong(call.utterance)) {
            CallVerdict.Keep
        } else {
            CallVerdict.Drop("'${call.utterance}' doesn't ask about what plays now")
        }
}

/**
 * A `navigate` call must be for a place: "where's my battery" (how Vosk hears "what's my
 * battery") and "where is my wifi" ask about the phone ([NavigationWords]), which no maps app
 * finds. The navigation skill's own sentence leaves them too. The utterance must not be
 * narration ("you're trying to get to the first half of your business", which the model
 * navigated) or a question about when ("when should I go to bed")
 * ([NavigationWords.refusesDirections]), and the place must be one ([PlaceWords.isNoPlace]: "get
 * me a coffee", "bed"). Every other navigate call is the model's to make: no list of ways to ask
 * the way is complete ("what's the fastest way home", "nearest gas station").
 */
object NavigationCallFilter : CallFilter {
    const val TOOL = "navigate"

    override fun check(call: RouterOutput.Call): CallVerdict = when {
        call.tool.name != TOOL -> CallVerdict.Keep
        NavigationWords.asksAboutPhone(call.utterance) ->
            CallVerdict.Drop("'${call.utterance}' asks about the phone, not a place to navigate to")
        NavigationWords.refusesDirections(call.utterance) ->
            CallVerdict.Drop("'${call.utterance}' doesn't ask for directions to navigate")
        PlaceWords.isNoPlace(call.utterance, call.rawSlots["where"]) ->
            CallVerdict.Drop("'${call.utterance}' names no place to navigate to")
        else -> CallVerdict.Keep
    }
}
