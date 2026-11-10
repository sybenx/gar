package org.stypox.dicio.gar.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.stypox.dicio.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One HTTP GET for GAR's own online skills (knowledge, currency), behind an interface so that
 * tests use fakes and no test touches the network.
 */
fun interface GarHttp {
    /**
     * The body of [url] when the server answers 200, null when it answers 404 (no such page).
     * @throws IOException without a connection, on a timeout or on any other status
     */
    suspend fun get(url: String): String?

    companion object {
        /** Wikimedia asks every client to name itself; nothing about the user is in it. */
        val USER_AGENT = "GAR/${BuildConfig.VERSION_NAME} (offline phone assistant, a Dicio fork)"

        /** The app's client, for skills that Hilt doesn't build. */
        val default: GarHttp by lazy {
            OkHttpGarHttp(
                OkHttpClient.Builder()
                    .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
            )
        }

        private const val CALL_TIMEOUT_SECONDS = 15L
    }
}

/** A status other than 200 and 404, kept so that gar:places can honour Nominatim's 429. */
class GarHttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** [GarHttp] through OkHttp, which follows redirects (Wikipedia's titles redirect). */
class OkHttpGarHttp(private val client: OkHttpClient) : GarHttp {
    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GarHttp.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> null
                !response.isSuccessful -> throw GarHttpStatusException(response.code, url)
                else -> response.body.string()
            }
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
