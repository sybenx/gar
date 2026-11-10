/*
 * GAR song skill: AudD and ACRCloud requests and responses.
 *
 * The request fields, the ACRCloud signature and the mapping of the services' result and error
 * codes are adapted from Audile (https://github.com/AudileTeam/Audile, formerly
 * aleksey-saenko/MusicRecognizer), core/recognition: AuddRecognitionService,
 * AuddResponseJsonMapper, AcrCloudRecognitionService and AcrCloudResponseJsonMapper.
 * Copyright (C) 2023-2026 Aleksey Saenko, GPL-3.0-or-later; ported from Ktor to OkHttp and
 * kotlinx.serialization by GAR, which is GPL-3.0 as well.
 */
package org.stypox.dicio.skills.song

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Which service, without its credentials: what a queued clip remembers. */
enum class SongProviderKind(val displayName: String) {
    AUDD("AudD"),
    ACRCLOUD("ACRCloud"),
}

/** The service the user set up, with their own credentials. */
sealed interface SongProviderConfig {
    val kind: SongProviderKind

    /** The service's name as the user sees it, e.g. in "Sends a recording to AudD". */
    val displayName: String get() = kind.displayName

    data class Audd(val apiToken: String) : SongProviderConfig {
        override val kind: SongProviderKind get() = SongProviderKind.AUDD
    }

    data class AcrCloud(
        val host: String,
        val accessKey: String,
        val accessSecret: String,
    ) : SongProviderConfig {
        override val kind: SongProviderKind get() = SongProviderKind.ACRCLOUD
    }

    companion object {
        /**
         * The service in [settings], or null when none is chosen or its credentials are
         * incomplete: then no recording is ever made or sent.
         */
        fun from(settings: SkillSettingsSong): SongProviderConfig? = when (settings.provider) {
            SongProvider.SONG_PROVIDER_AUDD -> settings.auddApiToken.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { Audd(it) }
            SongProvider.SONG_PROVIDER_ACRCLOUD -> {
                val host = normalizeHost(settings.acrcloudHost)
                val key = settings.acrcloudAccessKey.trim()
                val secret = settings.acrcloudAccessSecret.trim()
                if (host.isEmpty() || key.isEmpty() || secret.isEmpty()) null
                else AcrCloud(host, key, secret)
            }
            else -> null
        }

        /** "https://identify-eu-west-1.acrcloud.com/v1/identify" -> the host alone. */
        fun normalizeHost(raw: String): String = raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trim()

        /**
         * Whether the recordings waiting for the network are deleted when the settings change
         * from [before] to [after]: a service was set up, and now there is none or another one
         * (a card said where the recording goes).
         */
        fun discardsQueue(before: SkillSettingsSong, after: SkillSettingsSong): Boolean {
            val kind = from(before)?.kind ?: return false
            return from(after)?.kind != kind
        }

        /** Whether [host] (normalized) makes a valid ACRCloud URL: a host name, maybe with a port. */
        fun isValidHost(host: String): Boolean = host.isNotEmpty() && AcrCloudApi.url(host) != null
    }
}

/** A song a service recognized. */
data class SongTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    /** As the service gives it, usually "2014-11-10", sometimes only a year. */
    val releaseDate: String? = null,
    val spotifyUrl: String? = null,
    val youtubeUrl: String? = null,
)

/** What a service answered for one recording. */
sealed interface RecognitionResult {
    data class Match(val track: SongTrack) : RecognitionResult
    data object NoMatch : RecognitionResult

    sealed interface Failure : RecognitionResult

    /** The token, key or secret is wrong. */
    data object BadToken : Failure

    /** The ACRCloud host in the settings isn't a valid host name. */
    data object BadHost : Failure

    /** ACRCloud refused the request for another reason of its setup (its other 3xxx codes). */
    data object BadSettings : Failure

    /** The account's request limit is reached. */
    data object LimitReached : Failure

    /** The service couldn't fingerprint the recording. */
    data object BadRecording : Failure

    /** No answer: no network, DNS, TLS or a timeout. */
    data object NoConnection : Failure

    /** The service says it failed on its side (ACRCloud 3000, 2005). */
    data object ServiceDown : Failure

    data class HttpError(val code: Int) : Failure

    /** An answer GAR doesn't understand, or an error code it doesn't know. */
    data class ServiceError(val code: Int?, val message: String?) : Failure
}

/** Failures that may go away by themselves, so a queued recording is tried again later. */
val RecognitionResult.Failure.isTransient: Boolean
    get() = when (this) {
        RecognitionResult.NoConnection, RecognitionResult.ServiceDown -> true
        is RecognitionResult.HttpError -> code == 429 || code >= 500
        else -> false
    }

private val JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private val WAV = "audio/wav".toMediaType()

/** https://docs.audd.io: a multipart POST of the recording with the user's api_token. */
object AuddApi {
    const val URL = "https://api.audd.io/"

    /** Only Spotify's data: it has the track's link. No artwork or lyrics are used. */
    const val RETURN = "spotify"

    fun request(config: SongProviderConfig.Audd, wav: ByteArray): Request = Request.Builder()
        .url(URL)
        .post(
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("api_token", config.apiToken)
                .addFormDataPart("return", RETURN)
                .addFormDataPart("file", "sample.wav", wav.toRequestBody(WAV))
                .build()
        )
        .build()

    fun parse(httpCode: Int, body: String): RecognitionResult {
        if (httpCode !in 200..299) {
            return RecognitionResult.HttpError(httpCode)
        }
        val response = try {
            JSON.decodeFromString(AuddResponse.serializer(), body)
        } catch (e: IllegalArgumentException) { // SerializationException is one
            return RecognitionResult.ServiceError(null, e.message)
        }
        return when (response.status) {
            "success" -> response.result?.toTrack()
                ?.let { RecognitionResult.Match(it) }
                ?: RecognitionResult.NoMatch
            "error" -> errorFor(response.error?.code, response.error?.message)
            else -> RecognitionResult.ServiceError(null, response.status)
        }
    }

    /** AudD's error codes (docs.audd.io, "Common errors"), mapped as Audile does. */
    fun errorFor(code: Int?, message: String?): RecognitionResult.Failure = when (code) {
        900 -> RecognitionResult.BadToken
        901, 902 -> RecognitionResult.LimitReached
        300, 400, 500, 700 -> RecognitionResult.BadRecording
        else -> RecognitionResult.ServiceError(code, message)
    }

    private fun AuddResponse.Result.toTrack(): SongTrack? {
        // the top-level fields are AudD's own; Spotify's name sometimes adds the version
        val trackTitle = title.nonBlank() ?: spotify?.name.nonBlank() ?: return null
        val trackArtist = artist.nonBlank()
            ?: spotify?.artists?.firstNotNullOfOrNull { it.name.nonBlank() }
            ?: return null
        return SongTrack(
            title = trackTitle,
            artist = trackArtist,
            album = album.nonBlank() ?: spotify?.album?.name.nonBlank(),
            releaseDate = releaseDate.nonBlank() ?: spotify?.album?.releaseDate.nonBlank(),
            spotifyUrl = spotify?.externalUrls?.spotify.httpsUrl("open.spotify.com"),
        )
    }
}

/** https://docs.acrcloud.com/reference/identification-api: a signed multipart POST. */
object AcrCloudApi {
    const val ENDPOINT = "/v1/identify"
    private const val METHOD = "POST"
    private const val DATA_TYPE = "audio"
    private const val SIGNATURE_VERSION = "1"

    /**
     * The request signature: base64 of HMAC-SHA1 over "POST\n/v1/identify\n<access key>\naudio
     * \n1\n<timestamp>" keyed with the access secret.
     */
    fun signature(accessKey: String, accessSecret: String, timestampSeconds: Long): String {
        val toSign = listOf(
            METHOD, ENDPOINT, accessKey, DATA_TYPE, SIGNATURE_VERSION, timestampSeconds.toString(),
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(accessSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(toSign.toByteArray(Charsets.UTF_8)))
    }

    /**
     * The endpoint on [host], or null when [host] isn't only a host name (and maybe a port): a
     * space, a path, a query or a user name in the settings.
     */
    fun url(host: String): HttpUrl? = "https://$host$ENDPOINT".toHttpUrlOrNull()
        ?.takeIf {
            it.encodedPath == ENDPOINT && it.query == null && it.fragment == null &&
                it.username.isEmpty() && it.password.isEmpty()
        }

    /** @throws IllegalArgumentException when the host isn't valid, see [url] */
    fun request(
        config: SongProviderConfig.AcrCloud,
        wav: ByteArray,
        timestampSeconds: Long,
    ): Request = Request.Builder()
        .url(url(config.host) ?: throw IllegalArgumentException("Not a host: ${config.host}"))
        .post(
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("access_key", config.accessKey)
                .addFormDataPart("data_type", DATA_TYPE)
                .addFormDataPart("signature_version", SIGNATURE_VERSION)
                .addFormDataPart(
                    "signature",
                    signature(config.accessKey, config.accessSecret, timestampSeconds),
                )
                .addFormDataPart("timestamp", timestampSeconds.toString())
                .addFormDataPart("sample_bytes", wav.size.toString())
                .addFormDataPart("sample", "sample.wav", wav.toRequestBody(WAV))
                .build()
        )
        .build()

    fun parse(httpCode: Int, body: String): RecognitionResult {
        if (httpCode !in 200..299) {
            return RecognitionResult.HttpError(httpCode)
        }
        val response = try {
            JSON.decodeFromString(AcrCloudResponse.serializer(), body)
        } catch (e: IllegalArgumentException) {
            return RecognitionResult.ServiceError(null, e.message)
        }
        val status = response.status
            ?: return RecognitionResult.ServiceError(null, "no status")
        return when (status.code) {
            0 -> bestMatch(response.metadata)?.let { RecognitionResult.Match(it) }
                ?: RecognitionResult.NoMatch
            else -> errorFor(status.code, status.msg)
        }
    }

    /**
     * ACRCloud's status codes (docs.acrcloud.com, "Error Codes"), mapped as Audile does; GAR adds
     * that its other 3xxx codes (3002 HTTP method, 3006 invalid arguments, ...) are the request's
     * setup, which the user can only fix in the settings, not by trying again.
     */
    fun errorFor(code: Int, message: String?): RecognitionResult = when (code) {
        1001 -> RecognitionResult.NoMatch
        2000, 2004 -> RecognitionResult.BadRecording
        3001, 3014 -> RecognitionResult.BadToken
        3003, 3015 -> RecognitionResult.LimitReached
        3000, 2005 -> RecognitionResult.ServiceDown
        in 3001..3999 -> RecognitionResult.BadSettings
        else -> RecognitionResult.ServiceError(code, message)
    }

    /** The music match with the highest score, else the best humming match. */
    private fun bestMatch(metadata: AcrCloudResponse.Metadata?): SongTrack? {
        fun best(list: List<AcrCloudResponse.Music>?) = list.orEmpty()
            .sortedByDescending { it.score ?: 0.0 }
            .firstNotNullOfOrNull { it.toTrack() }
        return best(metadata?.music) ?: best(metadata?.humming)
    }

    private fun AcrCloudResponse.Music.toTrack(): SongTrack? {
        val trackTitle = title.nonBlank() ?: return null
        val trackArtist = artists.orEmpty().mapNotNull { it.name.nonBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" & ")
            ?: return null
        return SongTrack(
            title = trackTitle,
            artist = trackArtist,
            album = album?.name.nonBlank(),
            releaseDate = releaseDate.nonBlank()?.takeIf { it != "None" },
            spotifyUrl = externalMetadata?.spotify?.track?.id.nonBlank()
                ?.takeIf { ID.matches(it) }
                ?.let { "https://open.spotify.com/track/$it" },
            youtubeUrl = externalMetadata?.youtube?.vid.nonBlank()
                ?.takeIf { ID.matches(it) }
                ?.let { "https://www.youtube.com/watch?v=$it" },
        )
    }

    private val ID = Regex("[A-Za-z0-9_-]+")
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** [this] if it is an https link to [host], so a response can't make GAR open anything else. */
private fun String?.httpsUrl(host: String): String? =
    nonBlank()?.takeIf { it.startsWith("https://$host/") }

@Serializable
private data class AuddResponse(
    val status: String? = null,
    val result: Result? = null,
    val error: Error? = null,
) {
    @Serializable
    data class Result(
        val artist: String? = null,
        val title: String? = null,
        val album: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        val spotify: Spotify? = null,
    )

    @Serializable
    data class Spotify(
        val name: String? = null,
        val artists: List<Named>? = null,
        val album: Album? = null,
        @SerialName("external_urls") val externalUrls: ExternalUrls? = null,
    )

    @Serializable
    data class Album(
        val name: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
    )

    @Serializable
    data class ExternalUrls(val spotify: String? = null)

    @Serializable
    data class Named(val name: String? = null)

    @Serializable
    data class Error(
        @SerialName("error_code") val code: Int? = null,
        @SerialName("error_message") val message: String? = null,
    )
}

@Serializable
private data class AcrCloudResponse(
    val status: Status? = null,
    val metadata: Metadata? = null,
) {
    @Serializable
    data class Status(val code: Int, val msg: String? = null)

    @Serializable
    data class Metadata(
        val music: List<Music>? = null,
        val humming: List<Music>? = null,
    )

    @Serializable
    data class Music(
        val title: String? = null,
        val artists: List<Named>? = null,
        val album: Named? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        val score: Double? = null,
        @SerialName("external_metadata") val externalMetadata: ExternalMetadata? = null,
    )

    @Serializable
    data class Named(val name: String? = null)

    @Serializable
    data class ExternalMetadata(
        val spotify: Spotify? = null,
        val youtube: Youtube? = null,
    )

    @Serializable
    data class Spotify(val track: Track? = null)

    @Serializable
    data class Track(val id: String? = null)

    @Serializable
    data class Youtube(val vid: String? = null)
}
