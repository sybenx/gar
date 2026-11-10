package org.stypox.dicio.skills.song

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okio.Buffer

fun fixture(name: String): String =
    SongProvidersTest::class.java.getResource("/song/$name")?.readText()
        ?: throw AssertionError("no fixture $name")

/** The form fields of a multipart request: name to (filename, content type, text). */
private fun fields(body: MultipartBody): Map<String, Triple<String?, String?, String>> =
    body.parts.associate { part ->
        val disposition = part.headers!!["Content-Disposition"]!!
        val name = Regex("name=\"([^\"]*)\"").find(disposition)!!.groupValues[1]
        val filename = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1)
        val content = Buffer().also { part.body.writeTo(it) }.readUtf8()
        name to Triple(filename, part.body.contentType()?.toString(), content)
    }

private val WAV = "RIFF....WAVEfmt fake".toByteArray()

class SongProvidersTest : StringSpec({
    "the settings make a service only with complete credentials" {
        fun settings(build: SkillSettingsSong.Builder.() -> Unit) =
            SkillSettingsSong.newBuilder().apply(build).build()

        SongProviderConfig.from(SkillSettingsSongSerializer.defaultValue) shouldBe null
        SongProviderConfig.from(settings { auddApiToken = "token" }) shouldBe null // no service
        SongProviderConfig.from(settings { provider = SongProvider.SONG_PROVIDER_AUDD }) shouldBe null
        SongProviderConfig.from(settings {
            provider = SongProvider.SONG_PROVIDER_AUDD
            auddApiToken = "  abc123 "
        }) shouldBe SongProviderConfig.Audd("abc123")

        SongProviderConfig.from(settings {
            provider = SongProvider.SONG_PROVIDER_ACRCLOUD
            acrcloudHost = "identify-eu-west-1.acrcloud.com"
            acrcloudAccessKey = "key"
        }) shouldBe null
        SongProviderConfig.from(settings {
            provider = SongProvider.SONG_PROVIDER_ACRCLOUD
            acrcloudHost = "https://identify-eu-west-1.acrcloud.com/v1/identify"
            acrcloudAccessKey = "key"
            acrcloudAccessSecret = "secret"
        }) shouldBe SongProviderConfig.AcrCloud("identify-eu-west-1.acrcloud.com", "key", "secret")
    }

    "the AudD request posts the token, the return list and the recording" {
        val request = AuddApi.request(SongProviderConfig.Audd("abc123"), WAV)
        request.method shouldBe "POST"
        request.url.toString() shouldBe "https://api.audd.io/"
        val body = request.body.shouldBeInstanceOf<MultipartBody>()
        body.type.toString() shouldBe "multipart/form-data"
        val fields = fields(body)
        fields.keys.toList() shouldContainExactly listOf("api_token", "return", "file")
        fields["api_token"]!!.third shouldBe "abc123"
        fields["return"]!!.third shouldBe "spotify"
        fields["file"]!!.first shouldBe "sample.wav"
        fields["file"]!!.second shouldBe "audio/wav"
        fields["file"]!!.third shouldBe String(WAV)
    }

    "AudD's real answer to its example clip parses" {
        // recorded 2026-10-26 with the public "test" token:
        // curl -F api_token=test -F url=https://audd.tech/example.mp3 -F return=spotify https://api.audd.io/
        // (only Spotify's market lists were shortened)
        AuddApi.parse(200, fixture("audd_match.json")) shouldBe RecognitionResult.Match(
            SongTrack(
                title = "Everybody Wants To Rule The World",
                artist = "Tears For Fears",
                album = "Songs From The Big Chair",
                releaseDate = "2014-11-10",
                spotifyUrl = "https://open.spotify.com/track/5B9qVIyjqeWkeOAp2tJgqL",
                youtubeUrl = null,
            )
        )
    }

    "AudD's no match, errors and broken answers" {
        AuddApi.parse(200, fixture("audd_no_match.json")) shouldBe RecognitionResult.NoMatch
        AuddApi.parse(200, fixture("audd_bad_token.json")) shouldBe RecognitionResult.BadToken
        AuddApi.parse(200, fixture("audd_limit.json")) shouldBe RecognitionResult.LimitReached
        AuddApi.parse(200, fixture("audd_bad_file.json")) shouldBe RecognitionResult.BadRecording
        AuddApi.parse(200, """{"status":"error","error":{"error_code":19,"error_message":"x"}}""") shouldBe
            RecognitionResult.ServiceError(19, "x")
        AuddApi.parse(502, "<html>Bad gateway</html>") shouldBe RecognitionResult.HttpError(502)
        AuddApi.parse(200, "<html>not json</html>").shouldBeInstanceOf<RecognitionResult.ServiceError>()
        // a result without an artist isn't a song GAR can name
        AuddApi.parse(200, """{"status":"success","result":{"title":"Untitled"}}""") shouldBe
            RecognitionResult.NoMatch
    }

    "AudD's links are only Spotify's own" {
        val result = AuddApi.parse(200, """{"status":"success","result":{"artist":"A","title":"T",
            "spotify":{"external_urls":{"spotify":"intent://evil#Intent;end"}}}}""")
        result.shouldBeInstanceOf<RecognitionResult.Match>().track.spotifyUrl shouldBe null
    }

    "the ACRCloud signature matches a known vector" {
        // python3 -c "import hmac,hashlib,base64; print(base64.b64encode(hmac.new(
        //   b'example_access_secret', b'POST\n/v1/identify\nexample_access_key\naudio\n1\n1700000000',
        //   hashlib.sha1).digest()).decode())"
        // (and the same with `openssl dgst -sha1 -hmac example_access_secret -binary | base64`)
        AcrCloudApi.signature("example_access_key", "example_access_secret", 1_700_000_000) shouldBe
            "B2OJVenVFV4SlbzjYebNC/EwAw8="
    }

    "the ACRCloud request is signed and carries the sample" {
        val config = SongProviderConfig.AcrCloud(
            "identify-eu-west-1.acrcloud.com", "example_access_key", "example_access_secret",
        )
        val request = AcrCloudApi.request(config, WAV, 1_700_000_000)
        request.method shouldBe "POST"
        request.url.toString() shouldBe "https://identify-eu-west-1.acrcloud.com/v1/identify"
        val fields = fields(request.body.shouldBeInstanceOf<MultipartBody>())
        fields.mapValues { it.value.third }.filterKeys { it != "sample" } shouldBe mapOf(
            "access_key" to "example_access_key",
            "data_type" to "audio",
            "signature_version" to "1",
            "signature" to "B2OJVenVFV4SlbzjYebNC/EwAw8=",
            "timestamp" to "1700000000",
            "sample_bytes" to WAV.size.toString(),
        )
        fields["sample"] shouldBe Triple("sample.wav", "audio/wav", String(WAV))
    }

    "ACRCloud's best match parses, with its Spotify and YouTube links" {
        AcrCloudApi.parse(200, fixture("acrcloud_match.json")) shouldBe RecognitionResult.Match(
            SongTrack(
                title = "Everybody Wants To Rule The World",
                artist = "Tears For Fears",
                album = "Songs From The Big Chair",
                releaseDate = "1985-02-25",
                spotifyUrl = "https://open.spotify.com/track/4RvWPyQ5RL0ao9LPZeSouE",
                youtubeUrl = "https://www.youtube.com/watch?v=aGCdLKXNF3w",
            )
        )
    }

    "ACRCloud's no result, errors and broken answers" {
        AcrCloudApi.parse(200, fixture("acrcloud_no_result.json")) shouldBe RecognitionResult.NoMatch
        AcrCloudApi.parse(200, fixture("acrcloud_invalid_key.json")) shouldBe RecognitionResult.BadToken
        AcrCloudApi.parse(200, fixture("acrcloud_limit.json")) shouldBe RecognitionResult.LimitReached
        AcrCloudApi.parse(200, """{"status":{"msg":"Invalid signature","code":3014}}""") shouldBe
            RecognitionResult.BadToken
        AcrCloudApi.parse(200, """{"status":{"msg":"Can't generate fingerprint","code":2004}}""") shouldBe
            RecognitionResult.BadRecording
        AcrCloudApi.parse(200, """{"status":{"msg":"Service error","code":3000}}""") shouldBe
            RecognitionResult.ServiceDown
        // its other 3xxx codes are the request's setup: the settings, not a later try
        AcrCloudApi.parse(200, """{"status":{"msg":"Invalid arguments","code":3006}}""") shouldBe
            RecognitionResult.BadSettings
        AcrCloudApi.parse(200, """{"status":{"msg":"HTTP method error","code":3002}}""") shouldBe
            RecognitionResult.BadSettings
        AcrCloudApi.parse(200, """{"status":{"msg":"Unknown","code":2099}}""") shouldBe
            RecognitionResult.ServiceError(2099, "Unknown")
        AcrCloudApi.parse(401, "") shouldBe RecognitionResult.HttpError(401)
        AcrCloudApi.parse(200, "{}").shouldBeInstanceOf<RecognitionResult.ServiceError>()
        // an id that isn't one doesn't become a link
        val odd = AcrCloudApi.parse(200, """{"status":{"code":0},"metadata":{"music":[{"title":"T",
            "artists":[{"name":"A"}],"external_metadata":{"youtube":{"vid":"a/../b"}}}]}}""")
        odd.shouldBeInstanceOf<RecognitionResult.Match>().track.youtubeUrl shouldBe null
    }

    "only connection problems and the services' own outages are retried" {
        listOf(
            RecognitionResult.NoConnection, RecognitionResult.ServiceDown,
            RecognitionResult.HttpError(429), RecognitionResult.HttpError(503),
        ).forEach { it.isTransient shouldBe true }
        listOf(
            RecognitionResult.BadToken, RecognitionResult.LimitReached,
            RecognitionResult.BadRecording, RecognitionResult.HttpError(401),
            RecognitionResult.ServiceError(19, null), RecognitionResult.BadHost,
            RecognitionResult.BadSettings,
        ).forEach { it.isTransient shouldBe false }
    }

    "an ACRCloud host is only a host" {
        listOf(
            "identify-eu-west-1.acrcloud.com", "identify-ap-southeast-1.acrcloud.cn",
            "identify-us-west-2.acrcloud.com:443",
        ).forEach { SongProviderConfig.isValidHost(it) shouldBe true }
        listOf(
            "", "identify eu.acrcloud.com", "host?x=1", "host#frag", "user@host",
            "identify-eu-west-1.acrcloud.com:port",
        ).forEach { SongProviderConfig.isValidHost(SongProviderConfig.normalizeHost(it)) shouldBe false }
    }

    "an invalid host stored before GAR checked it is a clear failure, not a crash" {
        SongRecognitionClient(OkHttpClient()).recognize(
            SongProviderConfig.AcrCloud("identify eu.acrcloud.com", "key", "secret"), WAV,
        ) shouldBe RecognitionResult.BadHost
    }

    "turning the service off or to another one discards the waiting recordings" {
        fun settings(build: SkillSettingsSong.Builder.() -> Unit) =
            SkillSettingsSong.newBuilder().apply(build).build()
        val audd = settings {
            provider = SongProvider.SONG_PROVIDER_AUDD
            auddApiToken = "token"
        }
        val acr = settings {
            provider = SongProvider.SONG_PROVIDER_ACRCLOUD
            acrcloudHost = "identify-eu-west-1.acrcloud.com"
            acrcloudAccessKey = "key"
            acrcloudAccessSecret = "secret"
        }
        val off = audd.toBuilder().setProvider(SongProvider.SONG_PROVIDER_NONE).build()
        SongProviderConfig.discardsQueue(audd, off) shouldBe true
        SongProviderConfig.discardsQueue(audd, audd.toBuilder().setAuddApiToken("").build()) shouldBe true
        SongProviderConfig.discardsQueue(audd, acr) shouldBe true
        // a corrected token or another setting keeps them
        SongProviderConfig.discardsQueue(audd, audd.toBuilder().setAuddApiToken("other").build()) shouldBe false
        SongProviderConfig.discardsQueue(audd, audd.toBuilder().setNetworkNoticeSpoken(true).build()) shouldBe false
        SongProviderConfig.discardsQueue(off, audd) shouldBe false
    }
})
