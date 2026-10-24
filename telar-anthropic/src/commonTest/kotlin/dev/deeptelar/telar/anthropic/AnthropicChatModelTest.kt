package dev.deeptelar.telar.anthropic

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.Attachment
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.toolAgent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Lookup(
    val item: String,
)

class AnthropicChatModelTest {
    private val sent = mutableListOf<HttpRequestData>()
    private val menuPrice = Tool<Lookup>("menu_price", "Returns the price of an item.") { "One ${it.item} costs 2 euros." }
    private val hello = ChatRequest(listOf(ChatMessage.User("Hello")))

    /** A client that answers the requests it gets with [replies], in order. */
    private fun client(vararg replies: Pair<HttpStatusCode, String>): HttpClient {
        val queue = ArrayDeque(replies.toList())
        return HttpClient(
            MockEngine { request ->
                sent += request
                val (status, body) = queue.removeFirst()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
    }

    private fun model(body: String, status: HttpStatusCode = HttpStatusCode.OK): AnthropicChatModel =
        AnthropicChatModel(client(status to body), apiKey = "test-key", model = "claude-opus-5-5")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun sentBody(index: Int = 0): JsonObject = json((sent[index].body as TextContent).text) as JsonObject

    private fun text(text: String): String = """{"stop_reason": "end_turn", "content": [{"type": "text", "text": "$text"}]}"""

    @Test
    fun `a request has the headers and the body the Messages API expects`() =
        runTest {
            val model = model(text("Hi"))

            val response = model.chat(ChatRequest(listOf(ChatMessage.User("Hello")), "Be brief", listOf(menuPrice.spec)))

            assertEquals(ChatMessage.Assistant("Hi"), response.message)
            val request = sent.single()
            assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
            assertEquals("test-key", request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
            assertEquals("application/json", request.body.contentType.toString())
            assertEquals(
                json(
                    """
                    {
                      "model": "claude-opus-5-5",
                      "max_tokens": 16000,
                      "system": "Be brief",
                      "tools": [{
                        "name": "menu_price",
                        "description": "Returns the price of an item.",
                        "input_schema": {
                          "type": "object",
                          "properties": {"item": {"type": "string"}},
                          "required": ["item"],
                          "additionalProperties": false
                        }
                      }],
                      "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}]
                    }
                    """,
                ),
                sentBody(),
            )
        }

    @Test
    fun `a request without instructions and tools leaves those fields out`() =
        runTest {
            model(text("Hi")).chat(hello)

            assertEquals(setOf("model", "max_tokens", "messages"), sentBody().keys)
        }

    @Test
    fun `parameters and headers of the model are added to every request`() =
        runTest {
            val model =
                AnthropicChatModel(
                    client(HttpStatusCode.OK to text("Hi")),
                    apiKey = "test-key",
                    model = "claude-haiku-4-5",
                    maxTokens = 500,
                    parameters =
                        buildJsonObject {
                            put("temperature", 0)
                            put("max_tokens", 900)
                        },
                    headers = mapOf("anthropic-dangerous-direct-browser-access" to "true"),
                    baseUrl = "https://gateway.example.com/claude/",
                )

            model.chat(hello)

            assertEquals("https://gateway.example.com/claude/v1/messages", sent.single().url.toString())
            assertEquals("true", sent.single().headers["anthropic-dangerous-direct-browser-access"])
            assertEquals(json("0"), sentBody()["temperature"])
            assertEquals(json("900"), sentBody()["max_tokens"])
        }

    @Test
    fun `tool results travel in one user turn with the results first`() =
        runTest {
            val input = buildJsonObject { put("item", "cola") }
            val messages =
                listOf(
                    ChatMessage.User("How much are a cola and a salad?"),
                    ChatMessage.Assistant(
                        "Let me look.",
                        listOf(ToolCall("call-1", "menu_price", input), ToolCall("call-2", "menu_price", input)),
                    ),
                    ChatMessage.ToolResult("call-1", "menu_price", "2 euros"),
                    ChatMessage.User("Quickly, please."),
                    ChatMessage.ToolResult("call-2", "menu_price", "We do not sell salad.", isError = true),
                )

            model(text("Hi")).chat(ChatRequest(messages))

            assertEquals(
                json(
                    """
                    [
                      {"role": "user", "content": [{"type": "text", "text": "How much are a cola and a salad?"}]},
                      {"role": "assistant", "content": [
                        {"type": "text", "text": "Let me look."},
                        {"type": "tool_use", "id": "call-1", "name": "menu_price", "input": {"item": "cola"}},
                        {"type": "tool_use", "id": "call-2", "name": "menu_price", "input": {"item": "cola"}}
                      ]},
                      {"role": "user", "content": [
                        {"type": "tool_result", "tool_use_id": "call-1", "content": "2 euros"},
                        {"type": "tool_result", "tool_use_id": "call-2", "content": "We do not sell salad.", "is_error": true},
                        {"type": "text", "text": "Quickly, please."}
                      ]}
                    ]
                    """,
                ),
                sentBody()["messages"],
            )
        }

    @Test
    fun `images and PDFs go before the text of a user message as bytes or addresses`() =
        runTest {
            val photo = Attachment.of(byteArrayOf(1, 2, 3), "image/png")
            val contract = Attachment.url("https://example.com/contract.pdf", "application/pdf")

            model(text("A receipt.")).chat(ChatRequest(listOf(ChatMessage.User("What is this?", listOf(photo, contract)))))

            assertEquals(
                json(
                    """
                    [{"role": "user", "content": [
                      {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "AQID"}},
                      {"type": "document", "source": {"type": "url", "url": "https://example.com/contract.pdf"}},
                      {"type": "text", "text": "What is this?"}
                    ]}]
                    """,
                ),
                sentBody()["messages"],
            )
        }

    @Test
    fun `a user message with only a file sends no empty text`() =
        runTest {
            model(text("A receipt.")).chat(ChatRequest(listOf(ChatMessage.User("", listOf(Attachment.of(byteArrayOf(1), "image/jpeg"))))))

            assertEquals(
                json("""[{"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "AQ=="}}]"""),
                ((sentBody()["messages"] as List<*>)[0] as Map<*, *>)["content"],
            )
        }

    @Test
    fun `the files of a tool result go in its content with its text`() =
        runTest {
            val input = buildJsonObject { put("url", "https://example.com") }
            val messages =
                listOf(
                    ChatMessage.User("Show me the page."),
                    ChatMessage.Assistant(toolCalls = listOf(ToolCall("call-1", "screenshot", input))),
                    ChatMessage.ToolResult(
                        "call-1",
                        "screenshot",
                        "The page.",
                        attachments = listOf(Attachment.of(byteArrayOf(1, 2, 3), "image/png")),
                    ),
                )

            model(text("A page.")).chat(ChatRequest(messages))

            assertEquals(
                json(
                    """
                    {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "call-1", "content": [
                      {"type": "text", "text": "The page."},
                      {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "AQID"}}
                    ]}]}
                    """,
                ),
                (sentBody()["messages"] as List<*>)[2],
            )
        }

    @Test
    fun `a file of a type Claude does not read fails before the request`() =
        runTest {
            val voice = ChatMessage.User("Listen.", listOf(Attachment.of(byteArrayOf(1), "audio/wav")))

            val failure = assertFailsWith<ChatModelException> { model(text("Hi")).chat(ChatRequest(listOf(voice))) }

            assertTrue("audio/wav" in failure.message.orEmpty())
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `an assistant message goes back as the API returned it when it has provider content`() =
        runTest {
            val thinking = """[{"type": "thinking", "thinking": "Hm.", "signature": "abc"}, {"type": "text", "text": "Hi"}]"""
            val messages =
                listOf(ChatMessage.User("Hello"), ChatMessage.Assistant("Hi", providerContent = json(thinking)), ChatMessage.User("Bye"))

            model(text("Bye")).chat(ChatRequest(messages))

            assertEquals(json("""{"role": "assistant", "content": $thinking}"""), (sentBody()["messages"] as List<*>)[1])
        }

    @Test
    fun `an assistant message without content is left out`() =
        runTest {
            model(text("Hi")).chat(ChatRequest(listOf(ChatMessage.User("Hello"), ChatMessage.Assistant(), ChatMessage.User("Anyone?"))))

            assertEquals(
                json(
                    """[{"role": "user", "content": [{"type": "text", "text": "Hello"}]}, {"role": "user", "content": [{"type": "text", "text": "Anyone?"}]}]""",
                ),
                sentBody()["messages"],
            )
        }

    @Test
    fun `an answer with tool calls has the calls and the text`() =
        runTest {
            val model =
                model(
                    """
                    {
                      "stop_reason": "tool_use",
                      "content": [
                        {"type": "text", "text": "Let me "},
                        {"type": "text", "text": "look."},
                        {"type": "tool_use", "id": "toolu_1", "name": "menu_price", "input": {"item": "cola"}},
                        {"type": "tool_use", "id": "toolu_2", "name": "opening_hours"}
                      ],
                      "usage": {"input_tokens": 10, "cache_creation_input_tokens": 5, "cache_read_input_tokens": 100, "output_tokens": 7}
                    }
                    """,
                )

            val response = model.chat(hello)

            assertEquals("Let me look.", response.message.text)
            assertEquals(
                listOf(
                    ToolCall("toolu_1", "menu_price", buildJsonObject { put("item", "cola") }),
                    ToolCall("toolu_2", "opening_hours", JsonObject(emptyMap())),
                ),
                response.message.toolCalls,
            )
            assertNull(response.message.providerContent)
            assertFalse(response.message.truncated)
            assertEquals(TokenUsage(inputTokens = 115, outputTokens = 7), response.usage)
        }

    @Test
    fun `an answer with content other than text and tool calls keeps what the API returned`() =
        runTest {
            val content = """[{"type": "thinking", "thinking": "Hm.", "signature": "abc"}, {"type": "text", "text": "Hi"}]"""

            val response = model("""{"stop_reason": "end_turn", "content": $content}""").chat(hello)

            assertEquals("Hi", response.message.text)
            assertEquals(json(content), response.message.providerContent)
            assertNull(response.usage)
        }

    @Test
    fun `an answer that reached the output limit is truncated`() =
        runTest {
            val response = model("""{"stop_reason": "max_tokens", "content": [{"type": "text", "text": "Once upon a"}]}""").chat(hello)

            assertTrue(response.message.truncated)
            assertEquals("Once upon a", response.message.text)
        }

    @Test
    fun `a refusal is an error`() =
        runTest {
            val failure = assertFailsWith<ChatModelException> { model("""{"stop_reason": "refusal", "content": []}""").chat(hello) }

            assertEquals("Claude declined this request.", failure.message)
        }

    @Test
    fun `an error of the API is reported with its status and type and message`() =
        runTest {
            val model =
                model(
                    """{"type": "error", "error": {"type": "rate_limit_error", "message": "Slow down."}}""",
                    HttpStatusCode.TooManyRequests,
                )

            val failure = assertFailsWith<ChatModelException> { model.chat(hello) }

            assertEquals("Claude API error 429 (rate_limit_error): Slow down.", failure.message)
        }

    @Test
    fun `an error that is not JSON is reported with its text`() =
        runTest {
            val failure = assertFailsWith<ChatModelException> { model("Bad gateway", HttpStatusCode.BadGateway).chat(hello) }

            assertEquals("Claude API error 502 (unknown): Bad gateway", failure.message)
        }

    @Test
    fun `a successful response that is not a JSON object is an error`() =
        runTest {
            assertFailsWith<ChatModelException> { model("<html>").chat(hello) }
            assertFailsWith<ChatModelException> { model("[]").chat(hello) }
        }

    @Test
    fun `a request has the time limit of the model and none when the model has none`() =
        runTest {
            val limits = mutableListOf<Pair<Long?, Long?>?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        limits +=
                            request.getCapabilityOrNull(HttpTimeoutCapability)?.let { it.requestTimeoutMillis to it.socketTimeoutMillis }
                        respond(text("Hi"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )

            AnthropicChatModel(client, "test-key", "claude-opus-5-5").chat(hello)
            AnthropicChatModel(client, "test-key", "claude-opus-5-5", timeout = 30.seconds).chat(hello)
            AnthropicChatModel(client, "test-key", "claude-opus-5-5", timeout = null).chat(hello)

            assertEquals(listOf<Pair<Long?, Long?>?>(300_000L to 300_000L, 30_000L to 30_000L, null), limits)
        }

    @Test
    fun `a call that takes longer than the time limit of the model fails`() =
        runTest {
            val silent = HttpClient(MockEngine { awaitCancellation() })
            val model = AnthropicChatModel(silent, "test-key", "claude-opus-5-5", timeout = 50.milliseconds)

            val asked = assertFailsWith<ChatModelException> { model.chat(hello) }
            val streamed = assertFailsWith<ChatModelException> { model.stream(hello).toList() }

            assertEquals("The Claude API did not finish its answer within 50ms.", asked.message)
            assertEquals(asked.message, streamed.message)
        }

    @Test
    fun `a time limit of the client is named as one when the model has none`() =
        runTest {
            val silent = HttpClient(MockEngine { awaitCancellation() }) { install(HttpTimeout) { requestTimeoutMillis = 50 } }

            val failure =
                assertFailsWith<ChatModelException> {
                    AnthropicChatModel(
                        silent,
                        "test-key",
                        "claude-opus-5-5",
                        timeout = null,
                    ).chat(hello)
                }

            assertTrue(
                failure.message!!.startsWith("The Claude API did not finish its answer in the time the client allows: "),
                failure.message,
            )
        }

    @Test
    fun `a time limit that is not positive is rejected`() {
        val client = HttpClient(MockEngine { respond("") })

        assertFailsWith<GraphValidationException> { AnthropicChatModel(client, "test-key", "claude-opus-5-5", timeout = Duration.ZERO) }
    }

    @Test
    fun `a failure to reach the API is an error with the cause`() =
        runTest {
            val unreachable = HttpClient(MockEngine { throw IllegalStateException("No network") })

            val failure = assertFailsWith<ChatModelException> { AnthropicChatModel(unreachable, "test-key", "claude-opus-5-5").chat(hello) }

            assertEquals("Could not reach the Claude API: No network", failure.message)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun `a request that fails in a browser is an error of the model too`() =
        runTest {
            // Ktor's engine for the browser throws this, an Error and not an exception, when a
            // request gets no response.
            val offline = HttpClient(MockEngine { throw Error("Fail to fetch") })

            val failure = assertFailsWith<ChatModelException> { AnthropicChatModel(offline, "test-key", "claude-opus-5-5").chat(hello) }

            assertEquals("Could not reach the Claude API: Fail to fetch", failure.message)
        }

    @Test
    fun `an error that is not a failed request stays what it is`() =
        runTest {
            val broken = HttpClient(MockEngine { throw AssertionError("Out of memory") })

            val failure = runCatching { AnthropicChatModel(broken, "test-key", "claude-opus-5-5").chat(hello) }.exceptionOrNull()

            assertIs<AssertionError>(failure)
        }

    @Test
    fun `a cancellation is not turned into an error of the model`() =
        runTest {
            val cancelling = HttpClient(MockEngine { throw CancellationException("Stopped") })

            val failure = runCatching { AnthropicChatModel(cancelling, "test-key", "claude-opus-5-5").chat(hello) }.exceptionOrNull()

            assertIs<CancellationException>(failure)
        }

    @Test
    fun `an agent runs a tool for Claude and sends the result back`() =
        runTest {
            val client =
                client(
                    HttpStatusCode.OK to
                        """
                        {"stop_reason": "tool_use", "content": [
                          {"type": "thinking", "thinking": "A price.", "signature": "abc"},
                          {"type": "tool_use", "id": "toolu_1", "name": "menu_price", "input": {"item": "cola"}}
                        ]}
                        """,
                    HttpStatusCode.OK to text("A cola costs 2 euros."),
                )
            val agent = toolAgent(AnthropicChatModel(client, "test-key", "claude-opus-5-5"), listOf(menuPrice))

            val state = agent.invoke(AgentState("How much is a cola?")).state

            assertEquals("A cola costs 2 euros.", state.answer)
            assertEquals(
                json(
                    """
                    [
                      {"role": "user", "content": [{"type": "text", "text": "How much is a cola?"}]},
                      {"role": "assistant", "content": [
                        {"type": "thinking", "thinking": "A price.", "signature": "abc"},
                        {"type": "tool_use", "id": "toolu_1", "name": "menu_price", "input": {"item": "cola"}}
                      ]},
                      {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "content": "One cola costs 2 euros."}]}
                    ]
                    """,
                ),
                sentBody(1)["messages"],
            )
        }
}
