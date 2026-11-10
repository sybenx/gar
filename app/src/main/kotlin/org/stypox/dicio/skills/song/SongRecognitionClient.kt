package org.stypox.dicio.skills.song

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.settings.datastore.UserSettings
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Sends one recording to the user's service. The only network access of the song skill. */
fun interface SongRecognizer {
    suspend fun recognize(config: SongProviderConfig, wav: ByteArray): RecognitionResult
}

/** Dicio's singletons for code that Hilt doesn't inject (skills, workers). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SongEntryPoint {
    fun okHttpClient(): OkHttpClient

    /** Dicio's settings, for the enabled skills. */
    fun userSettings(): DataStore<UserSettings>

    /** Dicio's skills, for the ranker that GAR's question about Audile is asked in. */
    fun skillHandler(): SkillHandler
}

class SongRecognitionClient(
    httpClient: OkHttpClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SongRecognizer {

    // Dicio's client has no timeouts; a recognition shouldn't hang the card or the queue
    private val http = httpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(config: SongProviderConfig, wav: ByteArray): RecognitionResult {
        val request = when (config) {
            is SongProviderConfig.Audd -> AuddApi.request(config, wav)
            is SongProviderConfig.AcrCloud -> try {
                AcrCloudApi.request(config, wav, nowMillis() / 1000)
            } catch (e: IllegalArgumentException) { // the settings' host isn't one
                return RecognitionResult.BadHost
            }
        }
        val (code, body) = execute(request) ?: return RecognitionResult.NoConnection
        return when (config) {
            is SongProviderConfig.Audd -> AuddApi.parse(code, body)
            is SongProviderConfig.AcrCloud -> AcrCloudApi.parse(code, body)
        }
    }

    /** @return the status code and body, or null on an I/O failure */
    private suspend fun execute(request: Request): Pair<Int, String>? =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { Pair(it.code, it.body.string()) }
                    } catch (e: IOException) {
                        null
                    }
                    continuation.resume(result)
                }
            })
        }

    companion object {
        fun forContext(context: Context) = SongRecognitionClient(
            EntryPointAccessors.fromApplication(context.applicationContext, SongEntryPoint::class.java)
                .okHttpClient()
        )
    }
}
