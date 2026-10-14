package dev.deeptelar.telar.openai

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

class OpenAiStreamTest {
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

    private fun model(body: String, status: HttpStatusCode = HttpStatusCode.OK): OpenAiChatModel =
        OpenAiChatModel(client(status to body), apiKey = "test-key", model = "gpt-5")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** The chunks as the API writes them: the data, and an empty line. */
    private fun chunks(vararg data: String): String = data.joinToString("") { "data: $it\n\n" }

    private fun delta(delta: String, finishReason: String? = null): String {
        val reason = finishReason?.let { "\"$it\"" }
        return """{"choices": [{"index": 0, "delta": $delta, "finish_reason": $reason}], "usage": null}"""
    }

    private val role = delta("""{"role": "assistant", "content": "", "refusal": null}""")

    private fun text(text: String): String = delta(buildJsonObject { put("content", text) }.toString())

    private fun finish(reason: String): String = delta("{}", reason)

    /** The last chunk, which has the usage and no choices. */
    private val usage = """{"choices": [], "usage": {"prompt_tokens": 25, "completion_tokens": 12, "total_tokens": 37}}"""

    private val done = "[DONE]"

    /** A whole answer of plain text that arrives in [pieces]. */
    private fun textAnswer(vararg pieces: String): String = chunks(role, *pieces.map(::text).toTypedArray(), finish("stop"), usage, done)

    private fun callDelta(index: Int, arguments: String, id: String? = null, name: String? = null): String =
        delta(
            buildJsonObject {
                put(
                    "tool_calls",
                    json(
                        buildJsonObject {
                            put("index", index)
                            id?.let { put("id", it) }
                            id?.let { put("type", "function") }
                            put(
                                "function",
                                buildJsonObject {
                                    name?.let { put("name", it) }
                                    put("arguments", arguments)
                                },
                            )
                        }.let { "[$it]" },
                    ),
                )
            }.toString(),
        )

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
            assertEquals(json("""{"include_usage": true}"""), body["stream_options"])
            assertEquals("Bearer test-key", sent.single().headers["Authorization"])
            assertEquals("https://api.openai.com/v1/chat/completions", sent.single().url.toString())
        }

    @Test
    fun `streamed tool calls have the input that arrived in pieces`() =
        runTest {
            val model =
                model(
                    chunks(
                        role,
                        text("Let me look."),
                        callDelta(0, "", id = "call_1", name = "menu_price"),
                        callDelta(0, "{\"item\":"),
                        callDelta(0, " \"cola\"}"),
                        callDelta(1, "", id = "call_2", name = "menu_price"),
                        callDelta(1, "{\"item\": \"tea\"}"),
                        finish("tool_calls"),
                        usage,
                        done,
                    ),
                )

            val events = model.stream(hello).toList()

            assertEquals(ChatEvent.TextDelta("Let me look."), events.first())
            assertEquals(
                ChatMessage.Assistant(
                    "Let me look.",
                    listOf(
                        ToolCall("call_1", "menu_price", buildJsonObject { put("item", "cola") }),
                        ToolCall("call_2", "menu_price", buildJsonObject { put("item", "tea") }),
                    ),
                ),
                assertIs<ChatEvent.Completed>(events.last()).response.message,
            )
        }

    @Test
    fun `the extra content of a streamed answer is kept with its call`() =
        runTest {
            val signature = """{"google": {"thought_signature": "sig-a"}}"""
            val model =
                model(
                    chunks(
                        delta("""{"role": "assistant", "extra_content": {"google": {"note": "n"}}}"""),
                        delta(
                            """{"tool_calls": [{"index": 0, "id": "call_a", "type": "function", "extra_content": $signature, "function": {"name": "menu_price", "arguments": "{\"item\":"}}]}""",
                        ),
                        callDelta(0, " \"cola\"}"),
                        callDelta(1, "{\"item\": \"tea\"}", id = "call_b", name = "menu_price"),
                        finish("tool_calls"),
                        done,
                    ),
                )

            val message = assertIs<ChatEvent.Completed>(model.stream(hello).toList().single()).response.message

            assertEquals(
                listOf(
                    ToolCall("call_a", "menu_price", buildJsonObject { put("item", "cola") }),
                    ToolCall("call_b", "menu_price", buildJsonObject { put("item", "tea") }),
                ),
                message.toolCalls,
            )
            assertEquals(
                json("""{"extra_content": {"google": {"note": "n"}}, "tool_calls": {"call_a": $signature}}"""),
                message.providerContent,
            )
        }

    @Test
    fun `calls that a server sends whole and with the same index are told apart by their ids`() =
        runTest {
            val whole = { id: String, item: String ->
                delta(
                    """{"tool_calls": [{"index": 0, "id": "$id", "function": {"name": "menu_price", "arguments": {"item": "$item"}}}]}""",
                )
            }
            val model = model(chunks(whole("call_a", "cola"), whole("call_b", "tea"), finish("tool_calls"), done))

            val message = assertIs<ChatEvent.Completed>(model.stream(hello).toList().single()).response.message

            assertEquals(
                listOf(
                    ToolCall("call_a", "menu_price", buildJsonObject { put("item", "cola") }),
                    ToolCall("call_b", "menu_price", buildJsonObject { put("item", "tea") }),
                ),
                message.toolCalls,
            )
        }

    @Test
    fun `a streamed call whose input is not a JSON object keeps the text`() =
        runTest {
            val model =
                model(
                    chunks(
                        callDelta(0, "{item: ", id = "call_a", name = "menu_price"),
                        callDelta(0, "cola}"),
                        finish("tool_calls"),
                        done,
                    ),
                )

            val message = assertIs<ChatEvent.Completed>(model.stream(hello).toList().single()).response.message

            assertEquals(
                listOf(ToolCall("call_a", "menu_price", JsonObject(emptyMap()), invalidInput = "{item: cola}")),
                message.toolCalls,
            )
        }

    @Test
    fun `a call without an id and without an index is still a call`() =
        runTest {
            val model =
                model(
                    chunks(
                        delta("""{"tool_calls": [{"function": {"name": "menu_price", "arguments": "{\"item\": \"cola\"}"}}]}"""),
                        finish("tool_calls"),
                        done,
                    ),
                )

            val message = assertIs<ChatEvent.Completed>(model.stream(hello).toList().single()).response.message

            assertEquals(listOf(ToolCall("call_0", "menu_price", buildJsonObject { put("item", "cola") })), message.toolCalls)
        }

    @Test
    fun `chunks the model does not know and lines that are not events are skipped`() =
        runTest {
            val model =
                model(
                    ": keep-alive\n\n" +
                        "event: ping\n\n" +
                        chunks(role, """{"object": "something.new"}""", "not json", text("Hello")) +
                        "retry: 5\n" +
                        chunks(finish("stop"), done),
                )

            val events = model.stream(hello).toList()

            assertEquals(listOf(ChatEvent.TextDelta("Hello"), ChatEvent.Completed(ChatResponse(ChatMessage.Assistant("Hello")))), events)
        }

    @Test
    fun `a stream without usage and without a last line still has its answer`() =
        runTest {
            // The body ends right after the reason, with no empty line and no [DONE].
            val model = model(chunks(role, text("Hello")) + "data: ${finish("stop")}")

            val completed = assertIs<ChatEvent.Completed>(model.stream(hello).toList().last())

            assertEquals(ChatResponse(ChatMessage.Assistant("Hello")), completed.response)
            assertNull(completed.response.usage)
        }

    @Test
    fun `a streamed answer that reached the output limit is truncated`() =
        runTest {
            val model =
                model(
                    chunks(
                        role,
                        text("Once upon a"),
                        callDelta(0, "{\"item\": \"co", id = "call_1", name = "menu_price"),
                        finish("length"),
                        done,
                    ),
                )

            val message = assertIs<ChatEvent.Completed>(model.stream(hello).toList().last()).response.message

            assertTrue(message.truncated)
            assertEquals("Once upon a", message.text)
            assertEquals(
                ToolCall("call_1", "menu_price", JsonObject(emptyMap()), invalidInput = "{\"item\": \"co"),
                message.toolCalls.single(),
            )
        }

    @Test
    fun `an error in the stream fails it after the pieces so far`() =
        runTest {
            val model =
                model(chunks(role, text("Hello"), """{"error": {"message": "The server is overloaded.", "type": "server_error"}}"""))
            val seen = mutableListOf<ChatEvent>()

            val failure = assertFailsWith<ChatModelException> { model.stream(hello).collect { seen += it } }

            assertEquals("API error (server_error): The server is overloaded.", failure.message)
            assertEquals(listOf<ChatEvent>(ChatEvent.TextDelta("Hello")), seen)
        }

    @Test
    fun `an error without details is still an error`() =
        runTest {
            val failure = assertFailsWith<ChatModelException> { model(chunks("""{"error": {}}""")).stream(hello).toList() }

            assertEquals("API error (unknown): no message", failure.message)
        }

    @Test
    fun `a stream that ends before the answer does is an error`() =
        runTest {
            val failure = assertFailsWith<ChatModelException> { model(chunks(role, text("Hello"))).stream(hello).toList() }

            assertEquals("The API ended its answer before it was complete.", failure.message)
        }

    @Test
    fun `a refusal in a stream is an error`() =
        runTest {
            val model =
                model(chunks(role, delta("""{"refusal": "I can't "}"""), delta("""{"refusal": "help with that."}"""), finish("stop"), done))

            val failure = assertFailsWith<ChatModelException> { model.stream(hello).toList() }

            assertEquals("The model declined this request: I can't help with that.", failure.message)
        }

    @Test
    fun `an error status of a streamed request is reported like any other`() =
        runTest {
            val failure =
                assertFailsWith<ChatModelException> {
                    model(
                        """{"error": {"message": "Slow down.", "type": "rate_limit_error"}}""",
                        HttpStatusCode.TooManyRequests,
                    ).stream(hello).toList()
                }

            assertEquals("API error 429 (rate_limit_error): Slow down.", failure.message)
        }

    @Test
    fun `a failure to reach the API fails the stream with the cause`() =
        runTest {
            val unreachable = HttpClient(MockEngine { throw IllegalStateException("No network") })

            val failure = assertFailsWith<ChatModelException> { OpenAiChatModel(unreachable, "test-key", "gpt-5").stream(hello).toList() }

            assertEquals("Could not reach the API at https://api.openai.com/v1: No network", failure.message)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun `a request that fails in a browser fails the stream with an error of the model`() =
        runTest {
            // Ktor's engine for the browser throws this, an Error and not an exception, when a
            // request gets no response.
            val offline = HttpClient(MockEngine { throw Error("Fail to fetch") })

            val failure = assertFailsWith<ChatModelException> { OpenAiChatModel(offline, "test-key", "gpt-5").stream(hello).toList() }

            assertEquals("Could not reach the API at https://api.openai.com/v1: Fail to fetch", failure.message)
        }

    @Test
    fun `an error that is not a failed request stays what it is in a stream`() =
        runTest {
            val broken = HttpClient(MockEngine { throw AssertionError("Out of memory") })

            val failure = runCatching { OpenAiChatModel(broken, "test-key", "gpt-5").stream(hello).toList() }.exceptionOrNull()

            assertIs<AssertionError>(failure)
        }

    @Test
    fun `a failure of the collector is not turned into an error of the model`() =
        runTest {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    model(textAnswer("Hello")).stream(hello).collect { require(false) { "My bug" } }
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
            val model = OpenAiChatModel(client, "test-key", "gpt-5")
            val firstPiece = CompletableDeferred<String>()
            val seen = mutableListOf<ChatEvent>()

            val collecting =
                launch {
                    model.stream(hello).collect {
                        seen += it
                        if (it is ChatEvent.TextDelta) firstPiece.complete(it.text)
                    }
                }
            body.writeStringUtf8(chunks(role, text("Hello")))

            // The answer is not over, and the piece is already here.
            assertEquals("Hello", firstPiece.await())
            assertEquals(1, seen.size)
            body.writeStringUtf8(chunks(finish("stop"), done))
            body.close()
            collecting.join()
            assertEquals("Hello", assertIs<ChatEvent.Completed>(seen.last()).response.message.text)
        }

    @Test
    fun `a streamed agent shows the model's text and runs its tool`() =
        runTest {
            val menuPrice = Tool<Lookup>("menu_price", "Returns the price of an item.") { "One ${it.item} costs 2 euros." }
            val client =
                client(
                    HttpStatusCode.OK to
                        chunks(
                            role,
                            text("Let me look. "),
                            callDelta(0, "{\"item\": \"cola\"}", id = "call_1", name = "menu_price"),
                            finish("tool_calls"),
                            done,
                        ),
                    HttpStatusCode.OK to textAnswer("A cola costs ", "2 euros."),
                )
            val agent = toolAgent(OpenAiChatModel(client, "test-key", "gpt-5"), listOf(menuPrice))

            val events = agent.stream(AgentState("How much is a cola?")).toList()

            assertEquals(listOf("Let me look. ", "A cola costs ", "2 euros."), events.mapNotNull { it.textDelta })
            assertEquals("A cola costs 2 euros.", events.last().state.answer)
            val second = json((sent[1].body as TextContent).text) as JsonObject
            assertEquals(
                json("""{"role": "tool", "tool_call_id": "call_1", "content": "One cola costs 2 euros."}"""),
                (second["messages"] as List<*>).last(),
            )
        }
}
