package dev.deeptelar.telar.openai

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.agent.Attachment
import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.ToolCall
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A [ChatModel] that calls a model through the Chat Completions API of OpenAI. Many other servers
 * have the same API, among them Ollama, LM Studio, vLLM, Groq and OpenRouter, so this one class
 * reaches the models of OpenAI and models that run on your own machine. It is built on Ktor, so it
 * runs on every Kotlin target.
 *
 * ```kotlin
 * val client = HttpClient()
 *
 * // OpenAI
 * val model = OpenAiChatModel(client, apiKey = System.getenv("OPENAI_API_KEY"), model = "gpt-5")
 *
 * // A model of Ollama on this machine. It needs no key.
 * val local = OpenAiChatModel.ollama(client, model = "llama3.2")
 *
 * // Any other server with this API: give the address that ends before "/chat/completions".
 * val hosted = OpenAiChatModel(client, apiKey = key, model = "llama-3.3-70b-versatile", baseUrl = "https://api.groq.com/openai/v1")
 *
 * val agent = toolAgent(model, tools = listOf(forecast))
 * ```
 *
 * `HttpClient()` uses the Ktor engine among your dependencies, for example `ktor-client-okhttp` on
 * Android, `ktor-client-darwin` on iOS or `ktor-client-js` in a browser. The client is yours: share
 * one between models, and close it when your app is done with it.
 *
 * A call may take five minutes, whatever limits the client and its engine have: the CIO engine, for
 * one, ends a request after 15 seconds. Change the limit with `timeout`.
 *
 * [chat] returns when the model has finished, and [stream] delivers the text while the model
 * writes it. A `toolAgent` uses [stream] when its run is collected with `stream`. What a server
 * wants back unchanged with a tool call, such as a thought signature of Gemini, travels in
 * [ChatMessage.Assistant.providerContent].
 *
 * Tools need a model that can call them. A small local model may answer in text where a larger one
 * would call a tool.
 *
 * Never ship an app that contains a key of yours: a key in an app can be read by everyone who has
 * the app. Send the requests through a server of your own, or let each user enter their key.
 *
 * @param client the Ktor client that sends the requests.
 * @param apiKey the key, sent as a bearer token. `null` for a server that needs none.
 * @param model the id of the model, for example `gpt-5`.
 * @param parameters more fields for the body of every request, such as `temperature`,
 * `max_completion_tokens`, `reasoning_effort` or `tool_choice`. A field given here replaces the one
 * this class would send.
 * @param headers more headers for every request, such as `OpenAI-Organization`.
 * @param baseUrl where the API lives, up to and including the version: this class adds
 * `/chat/completions` to it.
 * @param timeout how long one call may take, from the request to the end of the answer, and how
 * long it may wait for the next piece of the answer. It replaces the limits of [client] and of its
 * engine for the requests of this model. `null` leaves those limits in place.
 * @throws GraphValidationException if [timeout] is not positive.
 */
public class OpenAiChatModel(
    client: HttpClient,
    private val apiKey: String?,
    private val model: String,
    private val parameters: JsonObject = JsonObject(emptyMap()),
    private val headers: Map<String, String> = emptyMap(),
    private val baseUrl: String = OPENAI_BASE_URL,
    private val timeout: Duration? = 5.minutes,
) : ChatModel {
    init {
        if (timeout != null && !timeout.isPositive()) throw GraphValidationException("timeout must be positive, was $timeout.")
    }

    // Ktor applies the limit of a request only in a client that has the HttpTimeout plugin. This
    // client has it, with everything else of the client it was made from, and shares its engine.
    private val client: HttpClient = if (timeout == null) client else client.config { install(HttpTimeout) }

    /**
     * Sends [request] to the Chat Completions API and returns the model's answer.
     *
     * @throws ChatModelException when the API cannot be reached, when it returns an error, and when
     * the model declines the request.
     */
    override suspend fun chat(request: ChatRequest): ChatResponse {
        val body = body(request)
        val status: Int
        val text: String
        try {
            val response = client.post(url) { completion(body) }
            status = response.status.value
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failed(e)
        } catch (e: Error) {
            throw if (e.isFailedFetch()) failed(e) else e
        }

        if (status !in 200..299) throw apiError(status, text)
        return response(parse(text) ?: throw ChatModelException("The API at $baseUrl returned a response that is not a JSON object."))
    }

    /**
     * Sends [request] to the Chat Completions API with `stream` set, and returns the model's answer
     * as it arrives: the text piece by piece, then the whole answer. The whole answer is the one
     * [chat] returns for the same request, with its tool calls and usage.
     *
     * The request asks for the usage with `stream_options`. A server that does not know the field
     * ignores it, and the answer then has no usage.
     *
     * The flow fails with a [ChatModelException] when the API cannot be reached, when it returns an
     * error, when the connection ends before the answer does, and when the model declines the
     * request.
     */
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
        flow {
            val answer = StreamedCompletion()
            var failure: ChatModelException? = null
            coroutineScope {
                // A flow may only emit from the coroutine that collects it, and Ktor reads a streamed
                // body in a context of its own on some targets. So the reader hands each piece over a
                // channel, and keeps a failure until the pieces that came before it are emitted.
                val pieces = Channel<String>(Channel.BUFFERED)
                launch {
                    try {
                        client.preparePost(url) { completion(body(request, streamed = true)) }.execute { response ->
                            val status = response.status.value
                            if (status !in 200..299) throw apiError(status, response.bodyAsText())
                            val lines = response.bodyAsChannel()
                            do {
                                val line = lines.readLine()
                                // The end of the body completes an event that has no empty line after it.
                                answer.add(line.orEmpty())?.let { pieces.send(it) }
                            } while (line != null)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ChatModelException) {
                        failure = e
                    } catch (e: Exception) {
                        failure = failed(e)
                    } catch (e: Error) {
                        if (!e.isFailedFetch()) throw e
                        failure = failed(e)
                    }
                    pieces.close()
                }
                for (piece in pieces) emit(ChatEvent.TextDelta(piece))
            }
            failure?.let { throw it }
            emit(ChatEvent.Completed(response(answer.body())))
        }

    private val url: String get() = "${baseUrl.trimEnd('/')}/chat/completions"

    private fun HttpRequestBuilder.completion(body: JsonObject) {
        apiKey?.let { header("Authorization", "Bearer $it") }
        this@OpenAiChatModel.headers.forEach { (name, value) -> header(name, value) }
        contentType(ContentType.Application.Json)
        setBody(body.toString())
        this@OpenAiChatModel.timeout?.let { limit ->
            timeout {
                requestTimeoutMillis = limit.inWholeMilliseconds
                socketTimeoutMillis = limit.inWholeMilliseconds
            }
        }
    }

    /** The exception for a request that ended without a response, or in the middle of one. */
    private fun failed(cause: Throwable): ChatModelException =
        when {
            cause !is HttpRequestTimeoutException && cause !is SocketTimeoutException ->
                ChatModelException("Could not reach the API at $baseUrl: ${cause.message ?: cause::class.simpleName}", cause)
            timeout != null -> ChatModelException("The API at $baseUrl did not finish its answer within $timeout.", cause)
            else ->
                ChatModelException(
                    "The API at $baseUrl did not finish its answer in the time the client allows: ${cause.message}",
                    cause,
                )
        }

    private fun apiError(status: Int, text: String): ChatModelException =
        ChatModelException("API error $status ${describe(errorIn(text), text)}")

    private fun body(request: ChatRequest, streamed: Boolean = false): JsonObject {
        val body =
            buildJsonObject {
                put("model", model)
                put("messages", messages(request))
                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { tool ->
                            addJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.inputSchema)
                                }
                            }
                        }
                    }
                }
                if (streamed) {
                    put("stream", true)
                    putJsonObject("stream_options") { put("include_usage", true) }
                }
            }
        return JsonObject(body + parameters)
    }

    private fun response(body: JsonObject): ChatResponse {
        val choice =
            (body["choices"] as? JsonArray).orEmpty().firstOrNull() as? JsonObject
                ?: throw ChatModelException("The API at $baseUrl returned a response without an answer.")
        val finishReason = choice.string("finish_reason")
        val answer = choice["message"] as? JsonObject
        // A refusal is a successful response, so check for one before reading the content.
        answer.string("refusal")?.takeIf { it.isNotBlank() }?.let { throw ChatModelException("The model declined this request: $it") }
        if (finishReason == "content_filter") throw ChatModelException("A content filter stopped the model's answer.")

        val calls = (answer?.get("tool_calls") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val toolCalls =
            calls.mapIndexed { index, call ->
                val function = call["function"] as? JsonObject
                val arguments = function?.get("arguments")
                val input = input(arguments)
                ToolCall(
                    // A result names its call by this id, so a call that came without one gets one.
                    call.string("id")?.takeIf { it.isNotEmpty() } ?: "call_$index",
                    function.string("name").orEmpty(),
                    input ?: JsonObject(emptyMap()),
                    invalidInput = if (input == null) (arguments as? JsonPrimitive)?.content else null,
                )
            }
        val message =
            ChatMessage.Assistant(
                text = text(answer?.get("content")),
                toolCalls = toolCalls,
                providerContent = extraContent(answer, toolCalls.map { it.id }.zip(calls)),
                truncated = finishReason == "length",
            )
        val usage =
            (body["usage"] as? JsonObject)?.let {
                TokenUsage(
                    inputTokens = it.int("prompt_tokens"),
                    outputTokens = it.int("completion_tokens"),
                )
            }
        return ChatResponse(message, usage)
    }

    public companion object {
        /** The address of the API of OpenAI, the default for `baseUrl`. */
        public const val OPENAI_BASE_URL: String = "https://api.openai.com/v1"

        /** The address at which Ollama serves this API on the machine it runs on. */
        public const val OLLAMA_BASE_URL: String = "http://localhost:11434/v1"

        /**
         * Returns a model that calls [model] on an Ollama server, which needs no key. Pull the model
         * first (`ollama pull llama3.2`), and choose one that supports tools for an agent with tools.
         *
         * @param baseUrl where Ollama serves the API. Change it when Ollama runs on another machine;
         * an Android emulator reaches the machine it runs on at `http://10.0.2.2:11434/v1`.
         * @param timeout how long one call may take. A large model on a small machine can need more.
         */
        public fun ollama(
            client: HttpClient,
            model: String,
            parameters: JsonObject = JsonObject(emptyMap()),
            baseUrl: String = OLLAMA_BASE_URL,
            timeout: Duration? = 5.minutes,
        ): OpenAiChatModel =
            OpenAiChatModel(client, apiKey = null, model = model, parameters = parameters, baseUrl = baseUrl, timeout = timeout)
    }
}

/**
 * Puts the chunks of a streamed answer together into the body the Chat Completions API returns for
 * the same answer without streaming, so that both kinds of answer are read by the same code.
 */
private class StreamedCompletion {
    /** The `data` lines of the event that is being read. An empty line ends an event. */
    private val data = mutableListOf<String>()
    private val content = StringBuilder()
    private val refusal = StringBuilder()
    private val calls = mutableListOf<Call>()

    /** The place in [calls] of the call that the chunks name by an index. */
    private val positions = mutableMapOf<Int, Int>()
    private var usage: JsonObject? = null
    private var finishReason: String? = null
    private var extraContent: JsonElement? = null
    private var done = false

    private class Call(
        val id: String?,
    ) {
        var name = ""
        var extraContent: JsonElement? = null

        /** The input of a tool call arrives as pieces of JSON text. */
        val arguments = StringBuilder()
    }

    /**
     * Takes the next line of the event stream. Returns the piece of text the model wrote when [line]
     * completes an event that carries one, and `null` otherwise.
     *
     * @throws ChatModelException when the event is an error of the API.
     */
    fun add(line: String): String? {
        if (line.startsWith("data:")) data += line.removePrefix("data:").removePrefix(" ")
        if (line.isNotEmpty() || data.isEmpty()) return null
        val text = data.joinToString("\n")
        data.clear()
        if (text.trim() == "[DONE]") {
            done = true
            return null
        }
        return parse(text)?.let(::apply)
    }

    private fun apply(chunk: JsonObject): String? {
        chunk["error"]?.takeIf { it !is JsonNull }?.let { throw ChatModelException("API error ${describe(it, "no message")}") }
        // The chunk with the usage has no choices.
        (chunk["usage"] as? JsonObject)?.let { usage = it }
        val choice = (chunk["choices"] as? JsonArray).orEmpty().firstOrNull() as? JsonObject ?: return null
        choice.string("finish_reason")?.let { finishReason = it }
        val delta = choice["delta"] as? JsonObject ?: return null
        delta.string("refusal")?.let { refusal.append(it) }
        delta.extraContent()?.let { extraContent = it }
        (delta["tool_calls"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach(::addCall)
        return delta.string("content")?.takeIf { it.isNotEmpty() }?.also { content.append(it) }
    }

    /**
     * The first chunk of a call has its id and its name, and the later ones only its index and the
     * next piece of its input. Some servers give every call the same index, or none: there, a chunk
     * with another id starts another call.
     */
    private fun addCall(piece: JsonObject) {
        val index = (piece["index"] as? JsonPrimitive)?.intOrNull ?: 0
        val id = piece.string("id")?.takeIf { it.isNotEmpty() }
        val known = positions[index]?.let { calls[it] }?.takeIf { id == null || it.id == null || it.id == id }
        val call =
            known ?: Call(id).also {
                positions[index] = calls.size
                calls += it
            }
        piece.extraContent()?.let { call.extraContent = it }
        val function = piece["function"] as? JsonObject
        function.string("name")?.let { call.name += it }
        when (val arguments = function?.get("arguments")) {
            is JsonObject -> call.arguments.append(arguments.toString())
            else -> call.arguments.append(function.string("arguments").orEmpty())
        }
    }

    /** @throws ChatModelException when the stream ended before the model's answer did. */
    fun body(): JsonObject {
        if (!done && finishReason == null) throw ChatModelException("The API ended its answer before it was complete.")
        return buildJsonObject {
            putJsonArray("choices") {
                addJsonObject {
                    finishReason?.let { put("finish_reason", it) }
                    putJsonObject("message") {
                        put("content", content.toString())
                        if (refusal.isNotEmpty()) put("refusal", refusal.toString())
                        extraContent?.let { put(EXTRA_CONTENT, it) }
                        putJsonArray("tool_calls") {
                            calls.forEach { call ->
                                addJsonObject {
                                    call.id?.let { put("id", it) }
                                    call.extraContent?.let { put(EXTRA_CONTENT, it) }
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.arguments.toString())
                                    }
                                }
                            }
                        }
                    }
                }
            }
            usage?.let { put("usage", it) }
        }
    }
}

/** Returns [text] as a JSON object, or `null` when it is not one. */
private fun parse(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

/**
 * The field in which a server sends what it wants back unchanged with the next request. Gemini puts
 * the thought signature of a tool call there, and rejects a request that comes without it.
 */
private const val EXTRA_CONTENT = "extra_content"

private fun JsonObject.extraContent(): JsonElement? = this[EXTRA_CONTENT]?.takeIf { it !is JsonNull }

/**
 * Returns the `extra_content` of [answer] and of its tool calls as the value of
 * [ChatMessage.Assistant.providerContent], or `null` when the answer has none. The one of the
 * message keeps its name, and the ones of the calls are under `tool_calls` and the id of their call.
 */
private fun extraContent(answer: JsonObject?, calls: List<Pair<String, JsonObject>>): JsonObject? {
    val ofMessage = answer?.extraContent()
    val ofCalls = calls.mapNotNull { (id, call) -> call.extraContent()?.let { id to it } }.toMap()
    if (ofMessage == null && ofCalls.isEmpty()) return null
    return buildJsonObject {
        ofMessage?.let { put(EXTRA_CONTENT, it) }
        if (ofCalls.isNotEmpty()) put("tool_calls", JsonObject(ofCalls))
    }
}

/**
 * Returns the `error` of the body of a failed request, or `null` when [text] has none. OpenAI sends
 * an object with the error. Gemini sends a list with that object as its only item.
 */
private fun errorIn(text: String): JsonElement? {
    val body =
        try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return null
        }
    val holder = body as? JsonObject ?: (body as? JsonArray)?.firstOrNull() as? JsonObject
    return holder?.get("error")
}

/**
 * Describes the `error` of a response: its type, status or code and its message, or [fallback] when
 * it has neither.
 */
private fun describe(error: JsonElement?, fallback: String): String {
    val details = error as? JsonObject
    val kind = details.string("type") ?: details.string("status") ?: details.string("code") ?: "unknown"
    val message = details.string("message") ?: (error as? JsonPrimitive)?.contentOrNull ?: fallback
    return "($kind): $message"
}

/**
 * Returns the conversation of [request] as the messages of the Chat Completions API: the
 * instructions first, as a system message, and every tool result as a message of its own.
 *
 * A tool message holds text only, so the files of tool results follow the results in a user message.
 */
private fun messages(request: ChatRequest): JsonArray =
    buildJsonArray {
        request.system?.let { system ->
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
        }
        val filesOfResults = mutableListOf<ChatMessage.ToolResult>()
        for (message in request.messages) {
            if (message !is ChatMessage.ToolResult) addFilesOf(filesOfResults)
            when (message) {
                is ChatMessage.User ->
                    addJsonObject {
                        put("role", "user")
                        if (message.attachments.isEmpty()) {
                            put("content", message.text)
                        } else {
                            putJsonArray("content") {
                                if (message.text.isNotEmpty()) add(textPart(message.text))
                                message.attachments.forEach { add(filePart(it)) }
                            }
                        }
                    }
                is ChatMessage.Assistant -> {
                    // The API rejects an assistant message that says nothing and asks for nothing.
                    if (message.text.isEmpty() && message.toolCalls.isEmpty()) continue
                    // What the server sent as extra_content goes back to it where it was.
                    val kept = message.providerContent as? JsonObject
                    val keptOfCalls = kept?.get("tool_calls") as? JsonObject
                    addJsonObject {
                        put("role", "assistant")
                        // A message that only asks for tools has no text, which the API writes as null.
                        put("content", if (message.text.isEmpty()) JsonNull else JsonPrimitive(message.text))
                        kept?.extraContent()?.let { put(EXTRA_CONTENT, it) }
                        if (message.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                message.toolCalls.forEach { call ->
                                    addJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        keptOfCalls?.get(call.id)?.let { put(EXTRA_CONTENT, it) }
                                        putJsonObject("function") {
                                            put("name", call.name)
                                            // The API takes the input as JSON in a string.
                                            put("arguments", call.input.toString())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is ChatMessage.ToolResult -> {
                    addJsonObject {
                        put("role", "tool")
                        put("tool_call_id", message.toolCallId)
                        put("content", message.text)
                    }
                    if (message.attachments.isNotEmpty()) filesOfResults += message
                }
            }
        }
        addFilesOf(filesOfResults)
    }

/** Adds a user message with the files of [results], after the tool messages they belong to, and empties [results]. */
private fun JsonArrayBuilder.addFilesOf(results: MutableList<ChatMessage.ToolResult>) {
    if (results.isEmpty()) return
    addJsonObject {
        put("role", "user")
        putJsonArray("content") {
            results.forEach { result ->
                add(textPart("The files that the tool call ${result.toolCallId} of ${result.toolName} returned:"))
                result.attachments.forEach { add(filePart(it)) }
            }
        }
    }
    results.clear()
}

private val IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/gif", "image/webp")

private fun textPart(text: String): JsonObject =
    buildJsonObject {
        put("type", "text")
        put("text", text)
    }

/**
 * Returns [attachment] as a part of a user message: an image from its bytes or its address, or a
 * PDF from its bytes.
 *
 * @throws ChatModelException if the API does not take files of its type, or a PDF from an address.
 */
private fun filePart(attachment: Attachment): JsonObject =
    buildJsonObject {
        when (attachment.mediaType) {
            in IMAGE_TYPES -> {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put(
                        "url",
                        when (attachment) {
                            is Attachment.Data -> attachment.dataUrl()
                            is Attachment.Url -> attachment.url
                        },
                    )
                }
            }
            "application/pdf" -> {
                if (attachment !is Attachment.Data) {
                    throw ChatModelException("OpenAiChatModel sends a PDF with its bytes, but not from an address: use Attachment.of.")
                }
                put("type", "file")
                putJsonObject("file") {
                    put("filename", "document.pdf")
                    put("file_data", attachment.dataUrl())
                }
            }
            else -> throw ChatModelException(
                "OpenAiChatModel sends images (${IMAGE_TYPES.joinToString()}) and PDFs, but not ${attachment.mediaType}.",
            )
        }
    }

private fun Attachment.Data.dataUrl(): String = "data:$mediaType;base64,$base64"

/** The text of a message: a string, or for some servers a list of parts with a `text` each. */
private fun text(content: JsonElement?): String =
    when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.filterIsInstance<JsonObject>().joinToString("") { it.string("text").orEmpty() }
        else -> ""
    }

/**
 * The input of a tool call: JSON in a string, or for some servers a JSON object. A call without
 * arguments has an empty input. Returns `null` when the arguments are text that is not a JSON
 * object, which a model can write, and which a call that was cut off has.
 */
private fun input(arguments: JsonElement?): JsonObject? {
    if (arguments is JsonObject) return arguments
    val text = (arguments as? JsonPrimitive)?.contentOrNull
    return if (text.isNullOrBlank()) JsonObject(emptyMap()) else parse(text)
}

private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/**
 * Whether this is a request that failed in a browser. Ktor's engine for JS and Wasm reports a
 * request without a response as a plain `Error`, not as an exception: the server is not running,
 * the device is offline, or the server does not take requests from the page. Every other error,
 * such as one of the virtual machine, is of a subclass and is not a failure of the request.
 */
private fun Error.isFailedFetch(): Boolean = this::class == Error::class
