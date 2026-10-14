package dev.deeptelar.telar.openai

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.agent.AgentState
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

class OpenAiChatModelTest {
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

    private fun model(body: String, status: HttpStatusCode = HttpStatusCode.OK): OpenAiChatModel =
        OpenAiChatModel(client(status to body), apiKey = "test-key", model = "gpt-5")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun sentBody(index: Int = 0): JsonObject = json((sent[index].body as TextContent).text) as JsonObject

    private fun answer(message: String, finishReason: String = "stop"): String =
        """{"choices": [{"index": 0, "finish_reason": "$finishReason", "message": $message}]}"""

    private fun text(text: String): String = answer("""{"role": "assistant", "content": "$text"}""")

    @Test
    fun `a request has the headers and the body the Chat Completions API expects`() =
        runTest {
            val model = model(text("Hi"))

            val response = model.chat(ChatRequest(listOf(ChatMessage.User("Hello")), "Be brief", listOf(menuPrice.spec)))

            assertEquals(ChatMessage.Assistant("Hi"), response.message)
            val request = sent.single()
            assertEquals("https://api.openai.com/v1/chat/completions", request.url.toString())
            assertEquals("Bearer test-key", request.headers["Authorization"])
            assertEquals("application/json", request.body.contentType.toString())
            assertEquals(
                json(
                    """
                    {
                      "model": "gpt-5",
                      "messages": [
                        {"role": "system", "content": "Be brief"},
                        {"role": "user", "content": "Hello"}
                      ],
                      "tools": [{
                        "type": "function",
                        "function": {
                          "name": "menu_price",
                          "description": "Returns the price of an item.",
                          "parameters": {
                            "type": "object",
                            "properties": {"item": {"type": "string"}},
                            "required": ["item"],
                            "additionalProperties": false
                          }
                        }
                      }]
                    }
                    """,
                ),
                sentBody(),
            )
        }

    @Test
    fun `a request without instructions and tools leaves those out`() =
        runTest {
            model(text("Hi")).chat(hello)

            assertEquals(setOf("model", "messages"), sentBody().keys)
            assertEquals(json("""[{"role": "user", "content": "Hello"}]"""), sentBody()["messages"])
        }

    @Test
    fun `parameters and headers of the model are added to every request`() =
        runTest {
            val model =
                OpenAiChatModel(
                    client(HttpStatusCode.OK to text("Hi")),
                    apiKey = "test-key",
                    model = "gpt-5-mini",
                    parameters =
                        buildJsonObject {
                            put("temperature", 0)
                            put("model", "gpt-5-nano")
                        },
                    headers = mapOf("OpenAI-Organization" to "org-1"),
                    baseUrl = "https://gateway.example.com/openai/v1/",
                )

            model.chat(hello)

            assertEquals("https://gateway.example.com/openai/v1/chat/completions", sent.single().url.toString())
            assertEquals("org-1", sent.single().headers["OpenAI-Organization"])
            assertEquals(json("0"), sentBody()["temperature"])
            assertEquals(json("\"gpt-5-nano\""), sentBody()["model"])
        }

    @Test
    fun `a model of Ollama is asked on this machine without a key`() =
        runTest {
            val model =
                OpenAiChatModel.ollama(
                    client(HttpStatusCode.OK to text("Hi")),
                    model = "llama3.2",
                    parameters =
                        buildJsonObject {
                            put("seed", 7)
                        },
                )

            assertEquals("Hi", model.chat(hello).message.text)

            assertEquals("http://localhost:11434/v1/chat/completions", sent.single().url.toString())
            assertNull(sent.single().headers["Authorization"])
            assertEquals(json("\"llama3.2\""), sentBody()["model"])
            assertEquals(json("7"), sentBody()["seed"])
        }

    @Test
    fun `tool calls go back with their input as text and every result is a message of its own`() =
        runTest {
            val model = model(text("Done"))
            val cola = ToolCall("call_1", "menu_price", buildJsonObject { put("item", "cola") })
            val fries = ToolCall("call_2", "menu_price", buildJsonObject { put("item", "fries") })

            model.chat(
                ChatRequest(
                    listOf(
                        ChatMessage.User("How much are a cola and fries?"),
                        ChatMessage.Assistant("Let me look.", listOf(cola)),
                        ChatMessage.ToolResult("call_1", "menu_price", "One cola costs 2 euros."),
                        ChatMessage.Assistant(toolCalls = listOf(fries)),
                        ChatMessage.ToolResult("call_2", "menu_price", "We have no fries.", isError = true),
                        ChatMessage.User("And water?"),
                    ),
                ),
            )

            assertEquals(
                json(
                    """
                    [
                      {"role": "user", "content": "How much are a cola and fries?"},
                      {"role": "assistant", "content": "Let me look.", "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\":\"cola\"}"}}
                      ]},
                      {"role": "tool", "tool_call_id": "call_1", "content": "One cola costs 2 euros."},
                      {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_2", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\":\"fries\"}"}}
                      ]},
                      {"role": "tool", "tool_call_id": "call_2", "content": "We have no fries."},
                      {"role": "user", "content": "And water?"}
                    ]
                    """,
                ),
                sentBody()["messages"],
            )
        }

    @Test
    fun `the extra content of an answer goes back to the server where it was`() =
        runTest {
            // Gemini sends a thought signature with the first tool call of an answer, and wants it back.
            val signature = """{"google": {"thought_signature": "sig-a"}}"""
            val asksForTools =
                answer(
                    """
                    {"content": null, "extra_content": {"google": {"note": "n"}}, "tool_calls": [
                      {"id": "call_1", "type": "function", "extra_content": $signature, "function": {"name": "menu_price", "arguments": "{\"item\":\"cola\"}"}},
                      {"id": "call_2", "type": "function", "extra_content": null, "function": {"name": "menu_price", "arguments": "{\"item\":\"tea\"}"}}
                    ]}
                    """,
                    finishReason = "tool_calls",
                )
            val model =
                OpenAiChatModel(
                    client(HttpStatusCode.OK to asksForTools, HttpStatusCode.OK to text("Done")),
                    apiKey = "test-key",
                    model = "gemini",
                )

            val asked = model.chat(hello).message
            model.chat(
                ChatRequest(
                    hello.messages + asked +
                        ChatMessage.ToolResult("call_1", "menu_price", "2 euros") +
                        ChatMessage.ToolResult("call_2", "menu_price", "3 euros"),
                ),
            )

            assertEquals(
                json("""{"extra_content": {"google": {"note": "n"}}, "tool_calls": {"call_1": $signature}}"""),
                asked.providerContent,
            )
            assertEquals(
                json(
                    """
                    {"role": "assistant", "content": null, "extra_content": {"google": {"note": "n"}}, "tool_calls": [
                      {"id": "call_1", "type": "function", "extra_content": $signature, "function": {"name": "menu_price", "arguments": "{\"item\":\"cola\"}"}},
                      {"id": "call_2", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\":\"tea\"}"}}
                    ]}
                    """,
                ),
                sentBody(1)["messages"]!!.jsonArray[1],
            )
        }

    @Test
    fun `the content that another model kept is not sent`() =
        runTest {
            val model = model(text("Hi"))
            val ofClaude = json("""[{"type": "thinking", "thinking": "Hm", "signature": "s"}]""")

            model.chat(
                ChatRequest(
                    listOf(ChatMessage.User("Hello"), ChatMessage.Assistant("Hi", providerContent = ofClaude), ChatMessage.User("Anyone?")),
                ),
            )

            assertEquals(json("""{"role": "assistant", "content": "Hi"}"""), sentBody()["messages"]!!.jsonArray[1])
        }

    @Test
    fun `an assistant message without content is left out`() =
        runTest {
            val model = model(text("Hi"))

            model.chat(ChatRequest(listOf(ChatMessage.User("Hello"), ChatMessage.Assistant(), ChatMessage.User("Anyone?"))))

            assertEquals(
                json("""[{"role": "user", "content": "Hello"}, {"role": "user", "content": "Anyone?"}]"""),
                sentBody()["messages"],
            )
        }

    @Test
    fun `an answer with tool calls has the calls and the text and the usage`() =
        runTest {
            val model =
                model(
                    """
                    {
                      "choices": [{
                        "index": 0,
                        "finish_reason": "tool_calls",
                        "message": {"role": "assistant", "content": "Let me look.", "refusal": null, "tool_calls": [
                          {"id": "call_1", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\": \"cola\"}"}},
                          {"id": "call_2", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\": \"water\"}"}}
                        ]}
                      }],
                      "usage": {"prompt_tokens": 40, "completion_tokens": 9, "total_tokens": 49}
                    }
                    """,
                )

            val response = model.chat(hello)

            assertEquals(
                ChatMessage.Assistant(
                    "Let me look.",
                    listOf(
                        ToolCall("call_1", "menu_price", buildJsonObject { put("item", "cola") }),
                        ToolCall("call_2", "menu_price", buildJsonObject { put("item", "water") }),
                    ),
                ),
                response.message,
            )
            assertEquals(TokenUsage(inputTokens = 40, outputTokens = 9), response.usage)
            assertFalse(response.message.truncated)
        }

    @Test
    fun `an answer that only asks for tools has no text and no usage`() =
        runTest {
            val response =
                model(
                    answer(
                        """{"content": null, "tool_calls": [{"id": "call_1", "function": {"name": "menu_price", "arguments": "{\"item\": \"cola\"}"}}]}""",
                        finishReason = "tool_calls",
                    ),
                ).chat(hello)

            assertEquals("", response.message.text)
            assertEquals(
                "call_1",
                response.message.toolCalls
                    .single()
                    .id,
            )
            assertNull(response.usage)
        }

    @Test
    fun `a server may send a call with an object as input or without an id and the text in parts`() =
        runTest {
            val response =
                model(
                    answer(
                        """
                        {"content": [{"type": "text", "text": "Let me "}, {"type": "text", "text": "look."}], "tool_calls": [
                          {"function": {"name": "menu_price", "arguments": {"item": "cola"}}},
                          {"id": "", "function": {"name": "menu_price", "arguments": "{\"item\": \"tea\"}"}}
                        ]}
                        """,
                    ),
                ).chat(hello)

            assertEquals(
                ChatMessage.Assistant(
                    "Let me look.",
                    listOf(
                        ToolCall("call_0", "menu_price", buildJsonObject { put("item", "cola") }),
                        ToolCall("call_1", "menu_price", buildJsonObject { put("item", "tea") }),
                    ),
                ),
                response.message,
            )
        }

    @Test
    fun `a call that was cut off gets an empty input and keeps the text it has`() =
        runTest {
            val response =
                model(
                    answer(
                        """{"content": null, "tool_calls": [{"id": "call_1", "function": {"name": "menu_price", "arguments": "{\"item\": \"co"}}]}""",
                        "length",
                    ),
                ).chat(hello)

            assertEquals(
                ToolCall("call_1", "menu_price", JsonObject(emptyMap()), invalidInput = """{"item": "co"""),
                response.message.toolCalls.single(),
            )
            assertTrue(response.message.truncated)
        }

    @Test
    fun `a call whose input is not a JSON object keeps the text and a call without input gets an empty one`() =
        runTest {
            val response =
                model(
                    answer(
                        """
                        {"content": null, "tool_calls": [
                          {"id": "call_1", "function": {"name": "menu_price", "arguments": "{item: cola}"}},
                          {"id": "call_2", "function": {"name": "menu_price", "arguments": "[\"cola\"]"}},
                          {"id": "call_3", "function": {"name": "opening_hours", "arguments": ""}},
                          {"id": "call_4", "function": {"name": "opening_hours"}}
                        ]}
                        """,
                        finishReason = "tool_calls",
                    ),
                ).chat(hello)

            val empty = JsonObject(emptyMap())
            assertEquals(
                listOf(
                    ToolCall("call_1", "menu_price", empty, invalidInput = "{item: cola}"),
                    ToolCall("call_2", "menu_price", empty, invalidInput = """["cola"]"""),
                    ToolCall("call_3", "opening_hours", empty),
                    ToolCall("call_4", "opening_hours", empty),
                ),
                response.message.toolCalls,
            )
        }

    @Test
    fun `a call whose input is not JSON goes back to the server with an empty input`() =
        runTest {
            val model = model(answer("""{"content": "Sorry."}"""))
            val broken = ToolCall("call_1", "menu_price", JsonObject(emptyMap()), invalidInput = "{item: cola}")

            model.chat(
                ChatRequest(
                    listOf(
                        ChatMessage.User("How much is a cola?"),
                        ChatMessage.Assistant(toolCalls = listOf(broken)),
                        ChatMessage.ToolResult("call_1", "menu_price", "The input is not a JSON object: {item: cola}", isError = true),
                    ),
                ),
            )

            val function =
                sentBody()["messages"]!!
                    .jsonArray[1]
                    .jsonObject["tool_calls"]!!
                    .jsonArray[0]
                    .jsonObject["function"]
            assertEquals(json("""{"name": "menu_price", "arguments": "{}"}"""), function)
        }

    @Test
    fun `an answer that reached the output limit is truncated`() =
        runTest {
            val response = model(answer("""{"content": "Once upon a"}""", finishReason = "length")).chat(hello)

            assertEquals("Once upon a", response.message.text)
            assertTrue(response.message.truncated)
        }

    @Test
    fun `a refusal is an error`() =
        runTest {
            val failure =
                assertFailsWith<ChatModelException> {
                    model(answer("""{"content": null, "refusal": "I can't help with that."}""")).chat(hello)
                }

            assertEquals("The model declined this request: I can't help with that.", failure.message)
        }

    @Test
    fun `an answer that a content filter stopped is an error`() =
        runTest {
            val failure =
                assertFailsWith<ChatModelException> {
                    model(answer("""{"content": ""}""", finishReason = "content_filter")).chat(hello)
                }

            assertEquals("A content filter stopped the model's answer.", failure.message)
        }

    @Test
    fun `an error of the API is reported with its status and type and message`() =
        runTest {
            val failure =
                assertFailsWith<ChatModelException> {
                    model(
                        """{"error": {"message": "Incorrect API key provided.", "type": "invalid_request_error", "code": "invalid_api_key"}}""",
                        HttpStatusCode.Unauthorized,
                    ).chat(hello)
                }

            assertEquals("API error 401 (invalid_request_error): Incorrect API key provided.", failure.message)
        }

    @Test
    fun `an error with a code or with only a text is reported too`() =
        runTest {
            val coded =
                assertFailsWith<ChatModelException> {
                    model(
                        """{"error": {"message": "Slow down.", "code": "rate_limit_exceeded"}}""",
                        HttpStatusCode.TooManyRequests,
                    ).chat(hello)
                }
            val plain =
                assertFailsWith<ChatModelException> {
                    model("""{"error": "model 'llama9' not found"}""", HttpStatusCode.NotFound).chat(hello)
                }

            assertEquals("API error 429 (rate_limit_exceeded): Slow down.", coded.message)
            assertEquals("API error 404 (unknown): model 'llama9' not found", plain.message)
        }

    @Test
    fun `an error that arrives in a list is reported with its status and message`() =
        runTest {
            // What Gemini answers through its API for OpenAI clients.
            val body = """[{"error": {"code": 404, "message": "This model is no longer available.", "status": "NOT_FOUND"}}]"""

            val asked = assertFailsWith<ChatModelException> { model(body, HttpStatusCode.NotFound).chat(hello) }
            val streamed = assertFailsWith<ChatModelException> { model(body, HttpStatusCode.NotFound).stream(hello).toList() }
            val empty = assertFailsWith<ChatModelException> { model("[]", HttpStatusCode.NotFound).chat(hello) }

            assertEquals("API error 404 (NOT_FOUND): This model is no longer available.", asked.message)
            assertEquals(asked.message, streamed.message)
            assertEquals("API error 404 (unknown): []", empty.message)
        }

    @Test
    fun `an error that is not JSON is reported with its text`() =
        runTest {
            val failure = assertFailsWith<ChatModelException> { model("Bad gateway", HttpStatusCode.BadGateway).chat(hello) }

            assertEquals("API error 502 (unknown): Bad gateway", failure.message)
        }

    @Test
    fun `a successful response that is not a JSON object or has no answer is an error`() =
        runTest {
            val noObject = assertFailsWith<ChatModelException> { model("[]").chat(hello) }
            val noAnswer = assertFailsWith<ChatModelException> { model("""{"choices": []}""").chat(hello) }

            assertEquals("The API at https://api.openai.com/v1 returned a response that is not a JSON object.", noObject.message)
            assertEquals("The API at https://api.openai.com/v1 returned a response without an answer.", noAnswer.message)
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

            OpenAiChatModel(client, "test-key", "gpt-5").chat(hello)
            OpenAiChatModel(client, "test-key", "gpt-5", timeout = 30.seconds).chat(hello)
            OpenAiChatModel(client, "test-key", "gpt-5", timeout = null).chat(hello)

            assertEquals(listOf<Pair<Long?, Long?>?>(300_000L to 300_000L, 30_000L to 30_000L, null), limits)
        }

    @Test
    fun `a call that takes longer than the time limit of the model fails`() =
        runTest {
            val silent = HttpClient(MockEngine { awaitCancellation() })
            val model = OpenAiChatModel(silent, "test-key", "gpt-5", timeout = 50.milliseconds)

            val asked = assertFailsWith<ChatModelException> { model.chat(hello) }
            val streamed = assertFailsWith<ChatModelException> { model.stream(hello).toList() }

            assertEquals("The API at https://api.openai.com/v1 did not finish its answer within 50ms.", asked.message)
            assertEquals(asked.message, streamed.message)
        }

    @Test
    fun `a time limit of the client is named as one when the model has none`() =
        runTest {
            val silent = HttpClient(MockEngine { awaitCancellation() }) { install(HttpTimeout) { requestTimeoutMillis = 50 } }

            val failure = assertFailsWith<ChatModelException> { OpenAiChatModel(silent, "test-key", "gpt-5", timeout = null).chat(hello) }

            assertTrue(
                failure.message!!.startsWith(
                    "The API at https://api.openai.com/v1 did not finish its answer in the time the client allows: ",
                ),
                failure.message,
            )
        }

    @Test
    fun `a time limit that is not positive is rejected`() {
        val client = HttpClient(MockEngine { respond("") })

        assertFailsWith<GraphValidationException> { OpenAiChatModel(client, "test-key", "gpt-5", timeout = Duration.ZERO) }
    }

    @Test
    fun `a failure to reach the API is an error with the address and the cause`() =
        runTest {
            val unreachable = HttpClient(MockEngine { throw IllegalStateException("Connection refused") })

            val failure = assertFailsWith<ChatModelException> { OpenAiChatModel.ollama(unreachable, "llama3.2").chat(hello) }

            assertEquals("Could not reach the API at http://localhost:11434/v1: Connection refused", failure.message)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun `a request that fails in a browser is an error of the model too`() =
        runTest {
            // Ktor's engine for the browser throws this, an Error and not an exception, when a
            // request gets no response.
            val offline = HttpClient(MockEngine { throw Error("Fail to fetch") })

            val failure = assertFailsWith<ChatModelException> { OpenAiChatModel.ollama(offline, "llama3.2").chat(hello) }

            assertEquals("Could not reach the API at http://localhost:11434/v1: Fail to fetch", failure.message)
        }

    @Test
    fun `an error that is not a failed request stays what it is`() =
        runTest {
            val broken = HttpClient(MockEngine { throw AssertionError("Out of memory") })

            val failure = runCatching { OpenAiChatModel(broken, "test-key", "gpt-5").chat(hello) }.exceptionOrNull()

            assertIs<AssertionError>(failure)
        }

    @Test
    fun `a cancellation is not turned into an error of the model`() =
        runTest {
            val cancelling = HttpClient(MockEngine { throw CancellationException("Stopped") })

            val failure = runCatching { OpenAiChatModel(cancelling, "test-key", "gpt-5").chat(hello) }.exceptionOrNull()

            assertIs<CancellationException>(failure)
        }

    @Test
    fun `an agent runs a tool for the model and sends the result back`() =
        runTest {
            val client =
                client(
                    HttpStatusCode.OK to
                        answer(
                            """{"content": null, "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\": \"cola\"}"}}]}""",
                            finishReason = "tool_calls",
                        ),
                    HttpStatusCode.OK to text("A cola costs 2 euros."),
                )
            val agent = toolAgent(OpenAiChatModel(client, "test-key", "gpt-5"), listOf(menuPrice))

            val state = agent.invoke(AgentState("How much is a cola?")).state

            assertEquals("A cola costs 2 euros.", state.answer)
            assertEquals(
                json(
                    """
                    [
                      {"role": "user", "content": "How much is a cola?"},
                      {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "menu_price", "arguments": "{\"item\":\"cola\"}"}}
                      ]},
                      {"role": "tool", "tool_call_id": "call_1", "content": "One cola costs 2 euros."}
                    ]
                    """,
                ),
                sentBody(1)["messages"],
            )
        }
}
