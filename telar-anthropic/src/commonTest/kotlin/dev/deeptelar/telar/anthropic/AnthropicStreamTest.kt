package dev.deeptelar.telar.anthropic

import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.textDelta
import dev.deeptelar.telar.agent.toolAgent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicStreamTest {
    private val sent = mutableListOf<HttpRequestData>()
    private val hello = ChatRequest(listOf(ChatMessage.User("Hello")))

    /** A client that answers the requests it gets with [replies], in order. */
    private fun client(vararg replies: Pair<HttpStatusCode, String>): HttpClient {
        val queue = ArrayDeque(replies.toList())
        return HttpClient(
            MockEngine { request ->
                sent += request
                val (status, body) = queue.removeFirst()
                respond(body, status, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            },
        )
    }

    private fun model(body: String, status: HttpStatusCode = HttpStatusCode.OK): AnthropicChatModel =
        AnthropicChatModel(client(status to body), apiKey = "test-key", model = "claude-opus-5-5")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** The events as the API writes them: a name, the data, and an empty line. */
    private fun events(vararg data: String): String =
        data.joinToString("") {
            "event: ${(json(it) as JsonObject)["type"]?.let { type ->
                (type as JsonPrimitive).content
            }}\ndata: $it\n\n"
        }

    private val start = """{"type": "message_start", "message": {"content": [], "usage": {"input_tokens": 25, "output_tokens": 1}}}"""

    private fun stop(reason: String, outputTokens: Int = 12): Array<String> =
        arrayOf(
            """{"type": "message_delta", "delta": {"stop_reason": "$reason", "stop_sequence": null}, "usage": {"output_tokens": $outputTokens}}""",
            """{"type": "message_stop"}""",
        )

    private fun blockStart(index: Int, block: String) = """{"type": "content_block_start", "index": $index, "content_block": $block}"""

    private fun delta(index: Int, delta: String) = """{"type": "content_block_delta", "index": $index, "delta": $delta}"""

    private fun blockStop(index: Int) = """{"type": "content_block_stop", "index": $index}"""

    private fun textDelta(index: Int, text: String) =
        delta(
            index,
            buildJsonObject {
                put("type", "text_delta")
                put("text", text)
            }.toString(),
        )

    private fun inputDelta(index: Int, json: String) =
        delta(
            index,
            buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", json)
            }.toString(),
        )

    private val textBlock = """{"type": "text", "text": ""}"""

    /** A whole answer of plain text that arrives in [pieces]. */
    private fun textAnswer(vararg pieces: String): String =
        events(start, blockStart(0, textBlock), *pieces.map { textDelta(0, it) }.toTypedArray(), blockStop(0), *stop("end_turn"))

    @Test
    fun `a stream delivers the text in pieces and then the whole answer`() =
        runTest {
            val model = model(textAnswer("Hello", " there", "!"))

            val events = model.stream(hello).toList()

            assertEquals(
                listOf(
                    ChatEvent.TextDelta("Hello"),
                    ChatEvent.TextDelta(" there"),
                    ChatEvent.TextDelta("!"),
                    ChatEvent.Completed(
                        ChatResponse(ChatMessage.Assistant("Hello there!"), TokenUsage(inputTokens = 25, outputTokens = 12)),
                    ),
                ),
                events,
            )
            val body = json((sent.single().body as TextContent).text) as JsonObject
            assertEquals(JsonPrimitive(true), body["stream"])
            assertEquals("test-key", sent.single().headers["x-api-key"])
        }

    @Test
    fun `a stream reports the reused tokens the API sends when it starts`() =
        runTest {
            val usage = """{"input_tokens": 25, "cache_read_input_tokens": 900, "cache_creation_input_tokens": 75, "output_tokens": 1}"""
            val cached = """{"type": "message_start", "message": {"content": [], "usage": $usage}}"""

            val completed = model(events(cached, *stop("end_turn"))).stream(hello).toList().last()

            assertEquals(
                TokenUsage(inputTokens = 1000, outputTokens = 12, cacheReadTokens = 900, cacheWriteTokens = 75),
                assertIs<ChatEvent.Completed>(completed).response.usage,
            )
        }

    @Test
    fun `a streamed tool call has the input that arrived in pieces`() =
        runTest {
            val model =
                model(
                    events(
                        start,
                        blockStart(0, textBlock),
                        textDelta(0, "Let me look."),
                        blockStop(0),
                        blockStart(1, """{"type": "tool_use", "id": "toolu_1", "name": "menu_price", "input": {}}"""),
                        inputDelta(1, ""),
                        inputDelta(1, """{"item":"""),
                        inputDelta(1, """ "cola"}"""),
                        blockStop(1),
                        blockStart(2, """{"type": "tool_use", "id": "toolu_2", "name": "opening_hours", "input": {}}"""),
                        inputDelta(2, ""),
                        blockStop(2),
                        *stop("tool_use"),
                    ),
                )

            val response = assertIs<ChatEvent.Completed>(model.stream(hello).toList().last()).response

            assertEquals("Let me look.", response.message.text)
            assertEquals(
                listOf(
                    ToolCall("toolu_1", "menu_price", buildJsonObject { put("item", "cola") }),
                    ToolCall("toolu_2", "opening_hours", JsonObject(emptyMap())),
                ),
                response.message.toolCalls,
            )
            assertNull(response.message.providerContent)
        }

    @Test
    fun `a streamed thinking block is put together as the API would return it`() =
        runTest {
            val model =
                model(
                    events(
                        start,
                        blockStart(0, """{"type": "thinking", "thinking": "", "signature": ""}"""),
                        delta(0, """{"type": "thinking_delta", "thinking": "A price"}"""),
                        delta(0, """{"type": "thinking_delta", "thinking": "."}"""),
                        delta(0, """{"type": "signature_delta", "signature": "abc"}"""),
                        blockStop(0),
                        blockStart(1, """{"type": "text", "text": "", "citations": []}"""),
                        delta(1, """{"type": "citations_delta", "citation": {"type": "char_location", "cited_text": "2 euros"}}"""),
                        textDelta(1, "Hi"),
                        blockStop(1),
                        *stop("end_turn"),
                    ),
                )

            val events = model.stream(hello).toList()

            assertEquals(listOf("Hi"), events.filterIsInstance<ChatEvent.TextDelta>().map { it.text })
            assertEquals(
                json(
                    """
                    [
                      {"type": "thinking", "thinking": "A price.", "signature": "abc"},
                      {"type": "text", "text": "Hi", "citations": [{"type": "char_location", "cited_text": "2 euros"}]}
                    ]
                    """,
                ),
                assertIs<ChatEvent.Completed>(events.last()).response.message.providerContent,
            )
        }

    @Test
    fun `events the model does not know and lines that are not events are skipped`() =
        runTest {
            val body =
                ": a comment\r\n" +
                    events(start, """{"type": "ping"}""", blockStart(0, textBlock)).replace("\n", "\r\n") +
                    "data: [DONE]\n\n" +
                    events(
                        """{"type": "content_block_delta", "index": 7, "delta": {"type": "text_delta", "text": "lost"}}""",
                        delta(0, """{"type": "a_new_delta", "value": 1}"""),
                        textDelta(0, "Hi"),
                        """{"type": "a_new_event"}""",
                        blockStop(0),
                    ) +
                    // The last event has no empty line after it.
                    "data: {\"type\": \"message_delta\", \"delta\": {\"stop_reason\": \"end_turn\"}, \"usage\": {\"output_tokens\": 3, \"input_tokens\": null}}"

            val events = model(body).stream(hello).toList()

            assertEquals(
                listOf(
                    ChatEvent.TextDelta("Hi"),
                    ChatEvent.Completed(ChatResponse(ChatMessage.Assistant("Hi"), TokenUsage(inputTokens = 25, outputTokens = 3))),
                ),
                events,
            )
        }

    @Test
    fun `a streamed answer that reached the output limit is truncated`() =
        runTest {
            val model =
                model(
                    events(
                        start,
                        blockStart(0, """{"type": "tool_use", "id": "toolu_1", "name": "menu_price", "input": {}}"""),
                        inputDelta(0, """{"item": "co"""),
                        blockStop(0),
                        *stop("max_tokens"),
                    ),
                )

            val message = assertIs<ChatEvent.Completed>(model.stream(hello).toList().last()).response.message

            assertTrue(message.truncated)
            // The input was cut off in the middle, so it is not JSON.
            assertEquals(listOf(ToolCall("toolu_1", "menu_price", JsonObject(emptyMap()))), message.toolCalls)
        }

    @Test
    fun `an error in the stream fails it after the pieces so far`() =
        runTest {
            val model =
                model(
                    events(
                        start,
                        blockStart(0, textBlock),
                        textDelta(0, "Hel"),
                        """{"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}""",
                    ),
                )

            val seen = mutableListOf<ChatEvent>()
            val failure = assertFailsWith<ChatModelException> { model.stream(hello).collect { seen += it } }

            assertEquals("Claude API error (overloaded_error): Overloaded", failure.message)
            assertEquals(listOf<ChatEvent>(ChatEvent.TextDelta("Hel")), seen)
        }

    @Test
    fun `an error without details is still an error`() =
        runTest {
            val failure = assertFailsWith<ChatModelException> { model(events(start, """{"type": "error"}""")).stream(hello).toList() }

            assertEquals("Claude API error (unknown): no message", failure.message)
        }

    @Test
    fun `a stream that ends before the answer does is an error`() =
        runTest {
            val model = model(events(start, blockStart(0, textBlock), textDelta(0, "Hel")))

            val failure = assertFailsWith<ChatModelException> { model.stream(hello).toList() }

            assertEquals("The Claude API ended its answer before it was complete.", failure.message)
        }

    @Test
    fun `a refusal in a stream is an error`() =
        runTest {
            val model = model(events(start, blockStart(0, textBlock), textDelta(0, "Sure, "), blockStop(0), *stop("refusal")))

            val failure = assertFailsWith<ChatModelException> { model.stream(hello).toList() }

            assertEquals("Claude declined this request.", failure.message)
        }

    @Test
    fun `an error status of a streamed request is reported like any other`() =
        runTest {
            val model =
                model(
                    """{"type": "error", "error": {"type": "rate_limit_error", "message": "Slow down."}}""",
                    HttpStatusCode.TooManyRequests,
                )

            val failure = assertFailsWith<ChatModelException> { model.stream(hello).toList() }

            assertEquals("Claude API error 429 (rate_limit_error): Slow down.", failure.message)
        }

    @Test
    fun `a failure to reach the API fails the stream with the cause`() =
        runTest {
            val unreachable = HttpClient(MockEngine { throw IllegalStateException("No network") })

            val failure =
                assertFailsWith<ChatModelException> {
                    AnthropicChatModel(
                        unreachable,
                        "test-key",
                        "claude-opus-5-5",
                    ).stream(hello).toList()
                }

            assertEquals("Could not reach the Claude API: No network", failure.message)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun `a request that fails in a browser fails the stream with an error of the model`() =
        runTest {
            // Ktor's engine for the browser throws this, an Error and not an exception, when a
            // request gets no response.
            val offline = HttpClient(MockEngine { throw Error("Fail to fetch") })
            val model = AnthropicChatModel(offline, "test-key", "claude-opus-5-5")

            val failure = assertFailsWith<ChatModelException> { model.stream(hello).toList() }

            assertEquals("Could not reach the Claude API: Fail to fetch", failure.message)
        }

    @Test
    fun `an error that is not a failed request stays what it is in a stream`() =
        runTest {
            val broken = HttpClient(MockEngine { throw AssertionError("Out of memory") })
            val model = AnthropicChatModel(broken, "test-key", "claude-opus-5-5")

            val failure = runCatching { model.stream(hello).toList() }.exceptionOrNull()

            assertIs<AssertionError>(failure)
        }

    @Test
    fun `a failure of the collector is not turned into an error of the model`() =
        runTest {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    model(
                        textAnswer("Hello"),
                    ).stream(hello).collect { require(false) { "My bug" } }
                }

            assertEquals("My bug", failure.message)
        }

    @Test
    fun `a collector can stop after the first piece`() =
        runTest {
            assertEquals(ChatEvent.TextDelta("Hello"), model(textAnswer("Hello", " there")).stream(hello).first())
        }

    @Test
    fun `a piece is delivered before the rest of the answer arrives`() =
        runTest {
            val body = ByteChannel(autoFlush = true)
            val client =
                HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) })
            val model = AnthropicChatModel(client, "test-key", "claude-opus-5-5")
            val firstPiece = CompletableDeferred<String>()
            val seen = mutableListOf<ChatEvent>()

            val collecting =
                launch {
                    model.stream(hello).collect {
                        seen += it
                        if (it is ChatEvent.TextDelta) firstPiece.complete(it.text)
                    }
                }
            body.writeStringUtf8(events(start, blockStart(0, textBlock), textDelta(0, "Hello")))

            // The answer is not over, and the piece is already here.
            assertEquals("Hello", firstPiece.await())
            assertEquals(1, seen.size)
            body.writeStringUtf8(events(blockStop(0), *stop("end_turn")))
            body.close()
            collecting.join()
            assertEquals("Hello", assertIs<ChatEvent.Completed>(seen.last()).response.message.text)
        }

    @Test
    fun `after another model took over only the text of the first one is kept`() =
        runTest {
            val model =
                model(
                    events(
                        start,
                        blockStart(0, """{"type": "thinking", "thinking": "", "signature": "first"}"""),
                        blockStop(0),
                        blockStart(1, textBlock),
                        textDelta(1, "A cola "),
                        blockStop(1),
                        blockStart(2, """{"type": "tool_use", "id": "toolu_1", "name": "menu_price", "input": {}}"""),
                        blockStop(2),
                        blockStart(3, """{"type": "fallback", "model": "claude-opus-4-8"}"""),
                        blockStop(3),
                        blockStart(4, """{"type": "thinking", "thinking": "", "signature": "second"}"""),
                        blockStop(4),
                        blockStart(5, textBlock),
                        textDelta(5, "costs 2 euros."),
                        blockStop(5),
                        *stop("end_turn"),
                    ),
                )

            val message = assertIs<ChatEvent.Completed>(model.stream(hello).toList().last()).response.message

            assertEquals("A cola costs 2 euros.", message.text)
            assertEquals(emptyList(), message.toolCalls)
            assertEquals(
                json(
                    """
                    [
                      {"type": "text", "text": "A cola "},
                      {"type": "thinking", "thinking": "", "signature": "second"},
                      {"type": "text", "text": "costs 2 euros."}
                    ]
                    """,
                ),
                message.providerContent,
            )
        }

    @Test
    fun `a streamed agent shows Claude's text and runs its tool`() =
        runTest {
            val menuPrice = Tool<Lookup>("menu_price", "Returns the price of an item.") { "One ${it.item} costs 2 euros." }
            val client =
                client(
                    HttpStatusCode.OK to
                        events(
                            start,
                            blockStart(0, textBlock),
                            textDelta(0, "Let me "),
                            textDelta(0, "look."),
                            blockStop(0),
                            blockStart(1, """{"type": "tool_use", "id": "toolu_1", "name": "menu_price", "input": {}}"""),
                            inputDelta(1, """{"item": "cola"}"""),
                            blockStop(1),
                            *stop("tool_use"),
                        ),
                    HttpStatusCode.OK to textAnswer("A cola costs ", "2 euros."),
                )
            val agent = toolAgent(AnthropicChatModel(client, "test-key", "claude-opus-5-5"), listOf(menuPrice))

            val events = agent.stream(AgentState("How much is a cola?")).toList()

            assertEquals(listOf("Let me ", "look.", "A cola costs ", "2 euros."), events.mapNotNull { it.textDelta })
            assertEquals("A cola costs 2 euros.", events.last().state.answer)
            assertEquals(
                json("""{"type": "tool_result", "tool_use_id": "toolu_1", "content": "One cola costs 2 euros."}"""),
                (((json((sent[1].body as TextContent).text) as JsonObject)["messages"] as List<*>)[2] as JsonObject)["content"]
                    .let { (it as List<*>).single() },
            )
        }
}
