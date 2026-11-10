package org.stypox.dicio.skills.weather

import java.io.IOException
import java.time.ZonedDateTime

/** The places the weather skill knows: the home city, the phone's location (gar:weather). */
interface WeatherPlaces {
    /** The country of the phone's locale ("US"), to pick among places with the same name. */
    val countryCode: String?

    /** The home city: Dicio's "Default city" setting of the weather skill, or "" when unset. */
    suspend fun homeCity(): String

    suspend fun setHomeCity(name: String)

    /** Whether the user said "use my location" when GAR asked for the city. */
    suspend fun useLocation(): Boolean

    suspend fun setUseLocation(use: Boolean)

    /** Whether GAR may read the approximate location. */
    fun hasLocationPermission(): Boolean

    /** The phone's approximate location, or null when there is none (location off, no fix). */
    suspend fun location(): WeatherPlace?
}

/** What the weather skill answers. */
sealed interface WeatherResult {
    /** @param nearYou the forecast is for the phone's location, which has no name */
    data class Answer(
        val forecast: WeatherForecast,
        val reply: WeatherReply,
        val nearYou: Boolean = false,
    ) : WeatherResult

    /** GAR doesn't know where the user is: "Which city are you in?" */
    data class AskPlace(val reason: AskReason) : WeatherResult

    /** No place by that name. */
    data class PlaceNotFound(val name: String) : WeatherResult

    /** Open-Meteo couldn't be reached. */
    data object NoConnection : WeatherResult
}

/** Why GAR asks for the city. */
enum class AskReason {
    /** No home city yet, nor "use my location": asked once, then remembered. */
    FIRST,

    /** "Use my location" was chosen, but GAR may no longer read the location. */
    NO_PERMISSION,

    /** "Use my location", but the phone has no location now (off, or no fix). */
    NO_LOCATION,

    /** The city said in answer to the question wasn't found. */
    NOT_FOUND,
}

/**
 * Finds the forecast for a [WeatherRequest] (gar:weather):
 * 1. offline from Breezy Weather's last data ([offline]) when it is fresh and has the place;
 * 2. else from Open-Meteo ([online], behind the online skills' ask-once gate) for the place as
 *    said, else the home city, else the phone's approximate location when the user chose it,
 *    else GAR asks "Which city are you in?" once ([answerCity] and [useLocation] take the answer).
 */
class WeatherLookup(
    private val openMeteo: OpenMeteo,
    private val places: WeatherPlaces,
    private val breezy: () -> List<WeatherForecast>?,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    /** The answer from Breezy Weather's data, or null when it can't answer [request]. */
    fun offline(request: WeatherRequest): WeatherResult.Answer? {
        val time = now()
        val forecast = BreezyWeatherData.usable(request, breezy(), time.toInstant(), time.zone)
            ?: return null
        return WeatherResult.Answer(forecast, WeatherAnswer.reply(request, forecast))
    }

    /** The answer from Open-Meteo. Blocks on the network: call it off the main thread. */
    suspend fun online(request: WeatherRequest): WeatherResult {
        val said = request.place
        val home = places.homeCity().trim()
        return try {
            when {
                said != null -> forecastFor(request, said)
                home.isNotEmpty() -> forecastFor(request, home)
                places.useLocation() -> when {
                    !places.hasLocationPermission() -> WeatherResult.AskPlace(AskReason.NO_PERMISSION)
                    else -> places.location()
                        ?.let { answer(request, openMeteo.forecast(it), nearYou = true) }
                        ?: WeatherResult.AskPlace(AskReason.NO_LOCATION)
                }
                else -> WeatherResult.AskPlace(AskReason.FIRST)
            }
        } catch (_: IOException) {
            WeatherResult.NoConnection
        } catch (_: IllegalArgumentException) {
            // a response GAR can't read (kotlinx.serialization throws these)
            WeatherResult.NoConnection
        }
    }

    /**
     * The user named their city in answer to "Which city are you in?": GAR remembers it as the
     * home city (the weather skill's "Default city" setting) and answers [request] there.
     */
    suspend fun answerCity(request: WeatherRequest, city: String): WeatherResult = try {
        val place = openMeteo.findPlace(city, places.countryCode)
        if (place == null) {
            WeatherResult.AskPlace(AskReason.NOT_FOUND)
        } else {
            places.setHomeCity(listOfNotNull(place.name, place.region).joinToString(", "))
            places.setUseLocation(false)
            answer(request, openMeteo.forecast(place))
        }
    } catch (_: IOException) {
        WeatherResult.NoConnection
    } catch (_: IllegalArgumentException) {
        WeatherResult.NoConnection
    }

    /**
     * The user said "use my location" (and allowed it, which Dicio asks before this runs): GAR
     * remembers the choice and answers [request] at the approximate location.
     */
    suspend fun useLocation(request: WeatherRequest): WeatherResult {
        places.setUseLocation(true)
        // a home city would win over the location, which the user just asked for
        places.setHomeCity("")
        return online(request.copy(place = null))
    }

    private suspend fun forecastFor(request: WeatherRequest, name: String): WeatherResult {
        val place = openMeteo.findPlace(name, places.countryCode)
            ?: return WeatherResult.PlaceNotFound(name)
        return answer(request, openMeteo.forecast(place))
    }

    private fun answer(request: WeatherRequest, forecast: WeatherForecast, nearYou: Boolean = false) =
        WeatherResult.Answer(forecast, WeatherAnswer.reply(request, forecast), nearYou)
}
