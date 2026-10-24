package dev.deeptelar.telar.anthropic

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
import kotlinx.serialization.json.add
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
 * A [ChatModel] that calls Claude through the Messages API of Anthropic. It is built on Ktor, so it
 * runs on every Kotlin target.
 *
 * ```kotlin
 * val client = HttpClient()
 * val model = AnthropicChatModel(client, apiKey = System.getenv("ANTHROPIC_API_KEY"), model = "claude-opus-5-5")
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
 * [chat] returns when Claude has finished, and [stream] delivers the text while Claude writes it. A
 * `toolAgent` uses [stream] when its run is collected with `stream`. Claude's thinking and other
 * content that the API wants back unchanged travels in [ChatMessage.Assistant.providerContent].
 *
 * In a browser the API only accepts a request with the header
 * `anthropic-dangerous-direct-browser-access: true`; pass it in [headers]. That is acceptable when
 * each user enters a key of their own. Never ship an app that contains a key of yours.
 *
 * @param client the Ktor client that sends the requests.
 * @param apiKey the Anthropic API key.
 * @param model the id of the model, for example `claude-opus-5-5`.
 * @param maxTokens the most tokens the model may write in one answer. An answer that reaches the
 * limit comes back with [ChatMessage.Assistant.truncated] set.
 * @param parameters more fields for the body of every request, such as `temperature`, `thinking`
 * or `tool_choice`. A field given here replaces the one this class would send.
 * @param headers more headers for every request, such as `anthropic-beta`.
 * @param baseUrl where the API lives. Change it to go through a proxy or a gateway.
 * @param timeout how long one call may take, from the request to the end of the answer, and how
 * long it may wait for the next piece of the answer. It replaces the limits of [client] and of its
 * engine for the requests of this model. `null` leaves those limits in place.
 * @throws GraphValidationException if [timeout] is not positive.
 */
public class AnthropicChatModel(
    client: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val parameters: JsonObject = JsonObject(emptyMap()),
    private val headers: Map<String, String> = emptyMap(),
    private val baseUrl: String = "https://api.anthropic.com",
    private val timeout: Duration? = 5.minutes,
) : ChatModel {
    init {
        if (timeout != null && !timeout.isPositive()) throw GraphValidationException("timeout must be positive, was $timeout.")
    }

    // Ktor applies the limit of a request only in a client that has the HttpTimeout plugin. This
    // client has it, with everything else of the client it was made from, and shares its engine.
    private val client: HttpClient = if (timeout == null) client else client.config { install(HttpTimeout) }

    /**
     * Sends [request] to the Messages API and returns Claude's answer.
     *
     * @throws ChatModelException when the API cannot be reached, when it returns an error, and when
     * Claude declines the request.
     */
    override suspend fun chat(request: ChatRequest): ChatResponse {
        val body = body(request)
        val status: Int
        val text: String
        try {
            val response = client.post(url) { messages(body) }
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
        return response(parse(text) ?: throw ChatModelException("The Claude API returned a response that is not a JSON object."))
    }

    /**
     * Sends [request] to the Messages API with `stream` set, and returns Claude's answer as it
     * arrives: the text piece by piece, then the whole answer. The whole answer is the one [chat]
     * returns for the same request, with its tool calls, usage and provider content.
     *
     * The flow fails with a [ChatModelException] when the API cannot be reached, when it returns an
     * error, when the connection ends before the answer does, and when Claude declines the request.
     * Claude can decline after it wrote a part of an answer; that part is not an answer.
     */
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
        flow {
            val answer = StreamedAnswer()
            var failure: ChatModelException? = null
            coroutineScope {
                // A flow may only emit from the coroutine that collects it, and Ktor reads a streamed
                // body in a context of its own on some targets. So the reader hands each piece over a
                // channel, and keeps a failure until the pieces that came before it are emitted.
                val pieces = Channel<String>(Channel.BUFFERED)
                launch {
                    try {
                        val streamed = JsonObject(body(request) + ("stream" to JsonPrimitive(true)))
                        client.preparePost(url) { messages(streamed) }.execute { response ->
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

    private val url: String get() = "${baseUrl.trimEnd('/')}/v1/messages"

    private fun HttpRequestBuilder.messages(body: JsonObject) {
        header("x-api-key", apiKey)
        header("anthropic-version", API_VERSION)
        this@AnthropicChatModel.headers.forEach { (name, value) -> header(name, value) }
        contentType(ContentType.Application.Json)
        setBody(body.toString())
        this@AnthropicChatModel.timeout?.let { limit ->
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
                ChatModelException("Could not reach the Claude API: ${cause.message ?: cause::class.simpleName}", cause)
            timeout != null -> ChatModelException("The Claude API did not finish its answer within $timeout.", cause)
            else -> ChatModelException("The Claude API did not finish its answer in the time the client allows: ${cause.message}", cause)
        }

    private fun apiError(status: Int, text: String): ChatModelException {
        val error = parse(text)?.get("error") as? JsonObject
        return ChatModelException("Claude API error $status (${error.string("type") ?: "unknown"}): ${error.string("message") ?: text}")
    }

    private fun body(request: ChatRequest): JsonObject {
        val body =
            buildJsonObject {
                put("model", model)
                put("max_tokens", maxTokens)
                request.system?.let { put("system", it) }
                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { tool ->
                            addJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.inputSchema)
                            }
                        }
                    }
                }
                put("messages", turns(request.messages))
            }
        return JsonObject(body + parameters)
    }

    private fun response(body: JsonObject): ChatResponse {
        val stopReason = body.string("stop_reason")
        // A refusal is a successful response, so check the stop reason before reading the content.
        if (stopReason == "refusal") throw ChatModelException("Claude declined this request.")

        val blocks = (body["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        // With the `fallbacks` parameter, another model can take over an answer. A `fallback` block
        // marks where. Of what the first model wrote before it, only the text may be sent back.
        val takeover = blocks.indexOfLast { it.string("type") == "fallback" }
        val content = blocks.filterIndexed { index, block -> index > takeover || block.string("type") == "text" }
        val message =
            ChatMessage.Assistant(
                text = content.filter { it.string("type") == "text" }.joinToString("") { it.string("text").orEmpty() },
                toolCalls =
                    content.filter { it.string("type") == "tool_use" }.map {
                        ToolCall(
                            it.string("id").orEmpty(),
                            it.string("name").orEmpty(),
                            it["input"] as? JsonObject ?: JsonObject(emptyMap()),
                        )
                    },
                // Text and tool calls can be rebuilt from the message. Everything else, such as a
                // thinking block, must go back to the API exactly as it came.
                providerContent = JsonArray(content).takeIf { blocks -> blocks.any { (it as JsonObject).string("type") !in rebuilt } },
                truncated = stopReason in truncating,
            )
        val usage =
            (body["usage"] as? JsonObject)?.let { usage ->
                val input = inputCounters.sumOf { usage.int(it) }
                TokenUsage(inputTokens = input, outputTokens = usage.int("output_tokens"))
            }
        return ChatResponse(message, usage)
    }

    public companion object {
        /** The default for `maxTokens`. */
        public const val DEFAULT_MAX_TOKENS: Int = 16_000

        private const val API_VERSION = "2023-06-01"

        private val rebuilt = setOf("text", "tool_use")
        private val truncating = setOf("max_tokens", "model_context_window_exceeded")

        /** The API counts tokens that were written to or read from the prompt cache apart from the rest of the input. */
        private val inputCounters = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
    }
}

/**
 * Puts the events of a streamed answer together into the body the Messages API returns for the same
 * answer without streaming, so that both kinds of answer are read by the same code.
 */
private class StreamedAnswer {
    /** The `data` lines of the event that is being read. An empty line ends an event. */
    private val data = mutableListOf<String>()
    private val blocks = mutableMapOf<Int, Block>()
    private val usage = mutableMapOf<String, JsonElement>()
    private var stopReason: String? = null
    private var stopped = false

    private class Block(
        start: JsonObject,
    ) {
        val fields: MutableMap<String, JsonElement> = start.toMutableMap()

        /** The input of a tool call arrives as pieces of JSON text. */
        val input = StringBuilder()

        fun append(field: String, piece: String?) {
            fields[field] = JsonPrimitive(JsonObject(fields).string(field).orEmpty() + piece.orEmpty())
        }
    }

    /**
     * Takes the next line of the event stream. Returns the piece of text Claude wrote when [line]
     * completes an event that carries one, and `null` otherwise.
     *
     * @throws ChatModelException when the event is an error of the API.
     */
    fun add(line: String): String? {
        if (line.startsWith("data:")) data += line.removePrefix("data:").removePrefix(" ")
        // Other fields of an event, such as its name, repeat what the data says.
        if (line.isNotEmpty() || data.isEmpty()) return null
        val event = parse(data.joinToString("\n"))
        data.clear()
        return event?.let(::apply)
    }

    private fun apply(event: JsonObject): String? {
        val delta = event["delta"] as? JsonObject
        when (event.string("type")) {
            "message_start" -> (event["message"] as? JsonObject)?.let { count(it["usage"]) }
            "content_block_start" -> (event["content_block"] as? JsonObject)?.let { blocks[event.int("index")] = Block(it) }
            "content_block_delta" -> {
                val block = blocks[event.int("index")] ?: return null
                when (delta.string("type")) {
                    "text_delta" -> return delta.string("text").also { block.append("text", it) }
                    "input_json_delta" -> block.input.append(delta.string("partial_json").orEmpty())
                    "thinking_delta" -> block.append("thinking", delta.string("thinking"))
                    "signature_delta" -> delta?.get("signature")?.let { block.fields["signature"] = it }
                    "citations_delta" ->
                        delta?.get("citation")?.let {
                            block.fields["citations"] =
                                JsonArray((block.fields["citations"] as? JsonArray).orEmpty() + it)
                        }
                }
            }
            "message_delta" -> {
                delta.string("stop_reason")?.let { stopReason = it }
                // These counts are totals, so they replace the ones the stream started with.
                count(event["usage"])
            }
            "message_stop" -> stopped = true
            "error" -> {
                val error = event["error"] as? JsonObject
                throw ChatModelException(
                    "Claude API error (${error.string("type") ?: "unknown"}): ${error.string("message") ?: "no message"}",
                )
            }
            // The API may add kinds of event. One this class does not know changes nothing it reads.
        }
        return null
    }

    private fun count(usage: JsonElement?) {
        (usage as? JsonObject)?.forEach { (name, value) -> if (value !is JsonNull) this.usage[name] = value }
    }

    /** @throws ChatModelException when the stream ended before Claude's answer did. */
    fun body(): JsonObject {
        if (!stopped && stopReason == null) throw ChatModelException("The Claude API ended its answer before it was complete.")
        return buildJsonObject {
            stopReason?.let { put("stop_reason", it) }
            putJsonArray("content") {
                blocks.keys.sorted().forEach { index ->
                    val block = blocks.getValue(index)
                    // A tool call that was cut off has input that is not JSON yet. It keeps the empty input it started with.
                    if (block.input.isNotBlank()) parse(block.input.toString())?.let { block.fields["input"] = it }
                    add(JsonObject(block.fields))
                }
            }
            if (usage.isNotEmpty()) put("usage", JsonObject(usage))
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
 * Returns [messages] as the turns of the Messages API. Tool results travel in a user turn, and all
 * results of one assistant turn must be in the same one, so neighbouring user messages and tool
 * results become a single turn, with the results first.
 */
private fun turns(messages: List<ChatMessage>): JsonArray =
    buildJsonArray {
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message is ChatMessage.Assistant) {
                val content = message.providerContent as? JsonArray ?: blocks(message)
                if (content.isNotEmpty()) addTurn("assistant", content)
                index++
            } else {
                val end = (index until messages.size).firstOrNull { messages[it] is ChatMessage.Assistant } ?: messages.size
                val turn = messages.subList(index, end)
                addTurn(
                    "user",
                    buildJsonArray {
                        turn.filterIsInstance<ChatMessage.ToolResult>().forEach { result ->
                            addJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", result.toolCallId)
                                if (result.attachments.isEmpty()) {
                                    put("content", result.text)
                                } else {
                                    putJsonArray("content") {
                                        if (result.text.isNotEmpty()) add(textBlock(result.text))
                                        result.attachments.forEach { add(attachmentBlock(it)) }
                                    }
                                }
                                if (result.isError) put("is_error", true)
                            }
                        }
                        turn.filterIsInstance<ChatMessage.User>().forEach { message ->
                            // Claude reads a file best when it comes before the question about it.
                            message.attachments.forEach { add(attachmentBlock(it)) }
                            if (message.text.isNotEmpty() || message.attachments.isEmpty()) add(textBlock(message.text))
                        }
                    },
                )
                index = end
            }
        }
    }

private fun JsonArrayBuilder.addTurn(role: String, content: JsonArray) {
    addJsonObject {
        put("role", role)
        put("content", content)
    }
}

private fun blocks(message: ChatMessage.Assistant): JsonArray =
    buildJsonArray {
        if (message.text.isNotEmpty()) add(textBlock(message.text))
        message.toolCalls.forEach { call ->
            addJsonObject {
                put("type", "tool_use")
                put("id", call.id)
                put("name", call.name)
                put("input", call.input)
            }
        }
    }

private val IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/gif", "image/webp")

/**
 * Returns [attachment] as an image or a document block, with the file itself or its address.
 *
 * @throws ChatModelException if Claude does not read files of its type.
 */
private fun attachmentBlock(attachment: Attachment): JsonObject {
    val type =
        when (attachment.mediaType) {
            in IMAGE_TYPES -> "image"
            "application/pdf" -> "document"
            else -> throw ChatModelException(
                "AnthropicChatModel sends images (${IMAGE_TYPES.joinToString()}) and PDFs, but not ${attachment.mediaType}.",
            )
        }
    return buildJsonObject {
        put("type", type)
        putJsonObject("source") {
            when (attachment) {
                is Attachment.Data -> {
                    put("type", "base64")
                    put("media_type", attachment.mediaType)
                    put("data", attachment.base64)
                }
                is Attachment.Url -> {
                    put("type", "url")
                    put("url", attachment.url)
                }
            }
        }
    }
}

private fun textBlock(text: String): JsonObject =
    buildJsonObject {
        put("type", "text")
        put("text", text)
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
