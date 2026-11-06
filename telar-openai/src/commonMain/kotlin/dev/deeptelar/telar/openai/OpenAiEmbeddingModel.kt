package dev.deeptelar.telar.openai

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.agent.EmbeddingModel
import dev.deeptelar.telar.agent.EmbeddingModelException
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * An [EmbeddingModel] that calls the embeddings API of OpenAI. Ollama, Gemini and many other
 * servers have the same API, so this one class reaches them all, chosen by [baseUrl] as for
 * [OpenAiChatModel].
 *
 * ```kotlin
 * val client = HttpClient()
 *
 * // OpenAI
 * val embeddings = OpenAiEmbeddingModel(client, apiKey = System.getenv("OPENAI_API_KEY"))
 *
 * // A model of Ollama on this machine, pulled first with `ollama pull nomic-embed-text`.
 * val local = OpenAiEmbeddingModel.ollama(client, model = "nomic-embed-text")
 *
 * // Gemini
 * val gemini = OpenAiEmbeddingModel(
 *     client,
 *     apiKey = geminiKey,
 *     model = "gemini-embedding-001",
 *     baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
 * )
 *
 * val store = SemanticStore(FileStore(directory), embeddings)
 * ```
 *
 * One call sends all the texts it gets in one request. The client is yours, as for
 * [OpenAiChatModel]: share one between models, and close it when your app is done with it.
 *
 * @param client the Ktor client that sends the requests.
 * @param apiKey the key, sent as a bearer token. `null` for a server that needs none.
 * @param model the id of the model.
 * @param dimensions how long the vectors are, for a model that can make shorter ones, such as the
 * `text-embedding-3` models. `null` leaves the length of the model.
 * @param headers more headers for every request.
 * @param baseUrl where the API lives, up to and including the version: this class adds
 * `/embeddings` to it.
 * @param timeout how long one call may take. `null` leaves the limits of [client] in place.
 * @throws GraphValidationException if [dimensions] or [timeout] is not positive.
 */
public class OpenAiEmbeddingModel(
    client: HttpClient,
    private val apiKey: String?,
    private val model: String = DEFAULT_MODEL,
    private val dimensions: Int? = null,
    private val headers: Map<String, String> = emptyMap(),
    private val baseUrl: String = OpenAiChatModel.OPENAI_BASE_URL,
    private val timeout: Duration? = 1.minutes,
) : EmbeddingModel {
    init {
        if (dimensions != null && dimensions <= 0) throw GraphValidationException("dimensions must be positive, was $dimensions.")
        if (timeout != null && !timeout.isPositive()) throw GraphValidationException("timeout must be positive, was $timeout.")
    }

    private val client: HttpClient = if (timeout == null) client else client.config { install(HttpTimeout) }

    /** The address of the API, the model and the [dimensions]: `https://api.openai.com/v1 text-embedding-3-small`. */
    override val id: String = "${baseUrl.trimEnd('/')} $model" + (dimensions?.let { " dimensions=$it" } ?: "")

    /**
     * Sends [texts] to the embeddings API and returns their vectors, in the same order.
     *
     * @throws EmbeddingModelException when the API cannot be reached, returns an error, or returns
     * something that is not a vector for each text.
     */
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val body =
            buildJsonObject {
                put("model", model)
                putJsonArray("input") { texts.forEach { add(it) } }
                dimensions?.let { put("dimensions", it) }
            }
        val status: Int
        val text: String
        try {
            val response =
                client.post("${baseUrl.trimEnd('/')}/embeddings") {
                    apiKey?.let { header("Authorization", "Bearer $it") }
                    this@OpenAiEmbeddingModel.headers.forEach { (name, value) -> header(name, value) }
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                    this@OpenAiEmbeddingModel.timeout?.let { limit -> timeout { requestTimeoutMillis = limit.inWholeMilliseconds } }
                }
            status = response.status.value
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failed(e)
        } catch (e: Error) {
            throw if (e.isFailedFetch()) failed(e) else e
        }
        if (status !in 200..299) throw EmbeddingModelException("API error $status ${describe(errorIn(text), text)}")
        return vectors(text, texts.size)
    }

    /** Reads the vectors from the response, ordered by their `index`. */
    private fun vectors(text: String, count: Int): List<FloatArray> {
        val data =
            try {
                (Json.parseToJsonElement(text) as? JsonObject)?.get("data") as? JsonArray
            } catch (_: SerializationException) {
                null
            } ?: throw EmbeddingModelException("The API at $baseUrl returned a response without the vectors.")
        val vectors = arrayOfNulls<FloatArray>(count)
        data.forEachIndexed { position, element ->
            val entry = element as? JsonObject
            val index = (entry?.get("index") as? JsonPrimitive)?.intOrNull ?: position
            val numbers = entry?.get("embedding") as? JsonArray
            if (numbers == null || index !in 0 until count) {
                throw EmbeddingModelException("The API at $baseUrl returned a vector that is not a list of numbers for one of the texts.")
            }
            vectors[index] =
                FloatArray(numbers.size) {
                    (numbers[it] as? JsonPrimitive)?.floatOrNull
                        ?: throw EmbeddingModelException("The API at $baseUrl returned a vector that is not a list of numbers.")
                }
        }
        return vectors.mapIndexed { index, vector ->
            vector
                ?: throw EmbeddingModelException(
                    "The API at $baseUrl returned ${data.size} vectors for $count texts, none for text $index.",
                )
        }
    }

    private fun failed(cause: Throwable): EmbeddingModelException =
        if (cause is HttpRequestTimeoutException || cause is SocketTimeoutException) {
            EmbeddingModelException("The API at $baseUrl did not answer within ${timeout ?: "the time the client allows"}.", cause)
        } else {
            EmbeddingModelException("Could not reach the API at $baseUrl: ${cause.message ?: cause::class.simpleName}", cause)
        }

    public companion object {
        /** The model of OpenAI that [OpenAiEmbeddingModel] uses when it is given none. */
        public const val DEFAULT_MODEL: String = "text-embedding-3-small"

        /**
         * Returns a model that calls [model] on an Ollama server, which needs no key. Pull the model
         * first, for example `ollama pull nomic-embed-text`.
         *
         * @param baseUrl where Ollama serves the API. Change it when Ollama runs on another machine.
         */
        public fun ollama(
            client: HttpClient,
            model: String,
            baseUrl: String = OpenAiChatModel.OLLAMA_BASE_URL,
            timeout: Duration? = 1.minutes,
        ): OpenAiEmbeddingModel = OpenAiEmbeddingModel(client, apiKey = null, model = model, baseUrl = baseUrl, timeout = timeout)
    }
}
