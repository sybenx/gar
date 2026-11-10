package cc.aaro.gar.router.eval

import cc.aaro.gar.router.engine.Completion
import cc.aaro.gar.router.engine.CompletionEngine
import cc.aaro.gar.router.engine.CompletionRequest
import cc.aaro.gar.router.engine.StopReason
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [CompletionEngine] over llama-server's HTTP API (`/completion` with `cache_prompt`, so the
 * server keeps the KV cache of the shared prefix). Greedy sampling (temperature 0). Generated
 * text is rebuilt with `/detokenize` from the returned tokens, because `/completion`'s `content`
 * leaves out the stop string, while the [CompletionEngine] contract includes it.
 *
 * The server's lifetime is managed outside (gar/eval/run-eval.sh); [close] does nothing.
 */
class LlamaServerEngine(
    baseUrl: String,
    private val requestTimeout: Duration = Duration.ofSeconds(120),
) : CompletionEngine {
    private val base = baseUrl.trimEnd('/')
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mutex = Mutex()

    override suspend fun complete(request: CompletionRequest): Completion = mutex.withLock {
        val response = post("/completion", buildJsonObject {
            put("prompt", request.prefix + request.suffix)
            put("n_predict", request.maxTokens)
            put("temperature", 0.0)
            put("seed", 0)
            put("cache_prompt", true)
            put("return_tokens", true)
            request.grammar?.let { put("grammar", it) }
            putJsonArray("stop") { request.stopStrings.forEach { add(it) } }
        })
        val tokens = response["tokens"]!!.jsonArray
        val text = post("/detokenize", buildJsonObject { put("tokens", tokens) })["content"]!!
            .jsonPrimitive.content
        val timings = response["timings"]!!.jsonObject
        val evaluated = timings.intOr("prompt_n")
        val cached = timings.intOr("cache_n")
        Completion(
            text = text,
            promptTokens = evaluated + cached,
            cachedTokens = cached,
            generatedTokens = timings.intOr("predicted_n"),
            prefillMillis = timings["prompt_ms"]?.jsonPrimitive?.content?.toDouble()?.toLong() ?: 0,
            generateMillis = timings["predicted_ms"]?.jsonPrimitive?.content?.toDouble()?.toLong()
                ?: 0,
            stopReason = when (response["stop_type"]?.jsonPrimitive?.content) {
                "eos" -> StopReason.END_OF_GENERATION
                "word" -> StopReason.STOP_STRING
                "limit" -> StopReason.MAX_TOKENS
                else -> StopReason.END_OF_GENERATION
            },
        )
    }

    override suspend fun warmUp(prefix: String) {
        mutex.withLock {
            post("/completion", buildJsonObject {
                put("prompt", prefix)
                put("n_predict", 0)
                put("cache_prompt", true)
            })
        }
    }

    override suspend fun close() {
    }

    /** POSTs [body] and returns the JSON response; throws on HTTP errors. */
    suspend fun post(path: String, body: JsonElement): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() != 200) {
            throw LlamaServerException("$path: HTTP ${response.statusCode()}: ${response.body()}")
        }
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun JsonObject.intOr(key: String): Int = this[key]?.jsonPrimitive?.int ?: 0
}

class LlamaServerException(message: String) : Exception(message)
