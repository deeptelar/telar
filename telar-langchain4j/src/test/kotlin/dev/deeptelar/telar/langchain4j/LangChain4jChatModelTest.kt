package dev.deeptelar.telar.langchain4j

import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.Description
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.ToolSpec
import dev.deeptelar.telar.agent.toolAgent
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonEnumSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNullSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonRawSchema
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.output.FinishReason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.langchain4j.model.chat.ChatModel as LangChain4jModel
import dev.langchain4j.model.chat.request.ChatRequest as LangChain4jRequest
import dev.langchain4j.model.chat.response.ChatResponse as LangChain4jResponse
import dev.langchain4j.model.output.TokenUsage as LangChain4jUsage

enum class Size { SMALL, LARGE }

@Serializable
data class Topping(
    val name: String,
)

@Serializable
data class Order(
    @Description("Who the pizza is for") val customer: String,
    val size: Size,
    val quantity: Int,
    val price: Double,
    val delivery: Boolean,
    val toppings: List<Topping>,
    val notes: Map<String, String> = emptyMap(),
)

/** Answers with [replies] in order and records what it was asked. */
class RecordingModel(
    vararg replies: LangChain4jResponse,
) : LangChain4jModel {
    private val replies = ArrayDeque(replies.toList())
    val requests = mutableListOf<LangChain4jRequest>()

    override fun doChat(chatRequest: LangChain4jRequest): LangChain4jResponse {
        requests += chatRequest
        return replies.removeFirst()
    }
}

class LangChain4jChatModelTest {
    private val hello = ChatRequest(listOf(ChatMessage.User("Hello")))
    private val cola = buildJsonObject { put("item", "cola") }

    private fun reply(message: AiMessage, finishReason: FinishReason = FinishReason.STOP): LangChain4jResponse =
        LangChain4jResponse
            .builder()
            .aiMessage(message)
            .finishReason(finishReason)
            .build()

    private fun request(id: String?, name: String, arguments: String?): ToolExecutionRequest =
        ToolExecutionRequest
            .builder()
            .id(id)
            .name(name)
            .arguments(arguments)
            .build()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a request becomes LangChain4j messages with the instructions first`() =
        runTest {
            val model = RecordingModel(reply(AiMessage.from("Hi")))
            val messages =
                listOf(
                    ChatMessage.User("How much are a cola and a salad?"),
                    ChatMessage.Assistant("Let me look.", listOf(ToolCall("call-1", "menu_price", cola))),
                    ChatMessage.ToolResult("call-1", "menu_price", "We do not sell salad.", isError = true),
                    ChatMessage.Assistant(),
                    ChatMessage.Assistant(toolCalls = listOf(ToolCall("call-2", "menu_price", cola))),
                    ChatMessage.ToolResult("call-2", "menu_price", "2 euros"),
                )

            LangChain4jChatModel(model).chat(ChatRequest(messages, system = "Be brief"))

            val sent = model.requests.single()
            assertEquals(
                listOf(
                    SystemMessage.from("Be brief"),
                    UserMessage.from("How much are a cola and a salad?"),
                    AiMessage.from("Let me look.", listOf(request("call-1", "menu_price", """{"item":"cola"}"""))),
                    ToolExecutionResultMessage
                        .builder()
                        .id("call-1")
                        .toolName("menu_price")
                        .text("We do not sell salad.")
                        .isError(true)
                        .build(),
                    AiMessage.from(listOf(request("call-2", "menu_price", """{"item":"cola"}"""))),
                    ToolExecutionResultMessage
                        .builder()
                        .id("call-2")
                        .toolName("menu_price")
                        .text("2 euros")
                        .isError(false)
                        .build(),
                ),
                sent.messages(),
            )
            assertTrue(sent.toolSpecifications().isNullOrEmpty())
        }

    @Test
    fun `the schema of a tool becomes LangChain4j schema elements`() =
        runTest {
            val model = RecordingModel(reply(AiMessage.from("Hi")))
            val order = Tool<Order>("order", "Places an order.") { "ok" }

            LangChain4jChatModel(model).chat(hello.copy(tools = listOf(order.spec)))

            val specification =
                model.requests
                    .single()
                    .toolSpecifications()
                    .single()
            assertEquals("order", specification.name())
            assertEquals("Places an order.", specification.description())
            assertEquals(
                JsonObjectSchema
                    .builder()
                    .addProperty("customer", JsonStringSchema.builder().description("Who the pizza is for").build())
                    .addProperty("size", JsonEnumSchema.builder().enumValues("SMALL", "LARGE").build())
                    .addProperty("quantity", JsonIntegerSchema())
                    .addProperty("price", JsonNumberSchema())
                    .addProperty("delivery", JsonBooleanSchema())
                    .addProperty(
                        "toppings",
                        JsonArraySchema
                            .builder()
                            .items(
                                JsonObjectSchema
                                    .builder()
                                    .addStringProperty("name")
                                    .required("name")
                                    .additionalProperties(false)
                                    .build(),
                            ).build(),
                    ).addProperty("notes", JsonRawSchema.from("""{"type":"object","additionalProperties":{"type":"string"}}"""))
                    .required("customer", "size", "quantity", "price", "delivery", "toppings")
                    .additionalProperties(false)
                    .build(),
                specification.parameters(),
            )
        }

    @Test
    fun `a schema written by hand keeps the keywords LangChain4j has no element for`() =
        runTest {
            val model = RecordingModel(reply(AiMessage.from("Hi")))
            val schema =
                json(
                    """
                    {
                      "type": "object",
                      "description": "A search",
                      "title": "Search",
                      "properties": {
                        "limit": {"type": "integer", "minimum": 1},
                        "tag": {"description": "A tag or nothing", "anyOf": [{"type": "string"}, {"type": "null"}]},
                        "mode": {"type": "strange"},
                        "anything": true
                      }
                    }
                    """,
                )

            LangChain4jChatModel(model).chat(hello.copy(tools = listOf(ToolSpec("search", "Searches.", schema))))

            assertEquals(
                JsonObjectSchema
                    .builder()
                    .description("A search")
                    .addProperty("limit", JsonRawSchema.from("""{"type":"integer","minimum":1}"""))
                    .addProperty(
                        "tag",
                        JsonAnyOfSchema
                            .builder()
                            .description("A tag or nothing")
                            .anyOf(JsonStringSchema(), JsonNullSchema())
                            .build(),
                    ).addProperty("mode", JsonRawSchema.from("""{"type":"strange"}"""))
                    .addProperty("anything", JsonRawSchema.from("{}"))
                    .build(),
                model.requests
                    .single()
                    .toolSpecifications()
                    .single()
                    .parameters(),
            )
        }

    @Test
    fun `an answer has the text, the tool calls and the usage`() =
        runTest {
            val answer =
                AiMessage.from(
                    "Let me look.",
                    listOf(
                        request("call-1", "menu_price", """{"item":"cola"}"""),
                        request(null, "opening_hours", null),
                        request("call-3", "menu_price", "not json"),
                        request("call-4", "menu_price", "[]"),
                    ),
                )
            val model =
                RecordingModel(
                    LangChain4jResponse
                        .builder()
                        .aiMessage(answer)
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .tokenUsage(LangChain4jUsage(10, 7))
                        .build(),
                )

            val response = LangChain4jChatModel(model).chat(hello)

            assertEquals("Let me look.", response.message.text)
            assertEquals(ToolCall("call-1", "menu_price", cola), response.message.toolCalls[0])
            assertEquals("opening_hours", response.message.toolCalls[1].name)
            assertTrue(
                response.message.toolCalls[1]
                    .id
                    .startsWith("call-"),
            )
            assertEquals(JsonObject(emptyMap()), response.message.toolCalls[1].input)
            assertNull(response.message.toolCalls[1].invalidInput)
            assertEquals(
                listOf(
                    ToolCall("call-3", "menu_price", JsonObject(emptyMap()), invalidInput = "not json"),
                    ToolCall("call-4", "menu_price", JsonObject(emptyMap()), invalidInput = "[]"),
                ),
                response.message.toolCalls.drop(2),
            )
            assertNull(response.message.providerContent)
            assertFalse(response.message.truncated)
            assertEquals(TokenUsage(inputTokens = 10, outputTokens = 7), response.usage)
        }

    @Test
    fun `an answer that reached the output limit is truncated`() =
        runTest {
            val response = LangChain4jChatModel(RecordingModel(reply(AiMessage.from("Once upon a"), FinishReason.LENGTH))).chat(hello)

            assertTrue(response.message.truncated)
            assertNull(response.usage)
        }

    @Test
    fun `a filtered answer is an error`() =
        runTest {
            val model = RecordingModel(reply(AiMessage.from(""), FinishReason.CONTENT_FILTER))

            assertFailsWith<ChatModelException> { LangChain4jChatModel(model).chat(hello) }
        }

    @Test
    fun `a model that throws is an error with the cause`() =
        runTest {
            val model =
                object : LangChain4jModel {
                    override fun doChat(chatRequest: LangChain4jRequest): LangChain4jResponse = throw IllegalStateException("No network")
                }

            val failure = assertFailsWith<ChatModelException> { LangChain4jChatModel(model).chat(hello) }

            assertEquals("The LangChain4j model failed: No network", failure.message)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun `an answer with thinking goes back to the model as it came`() =
        runTest {
            val thinking =
                AiMessage
                    .builder()
                    .text("Hi")
                    .thinking("A greeting.")
                    .attributes(mapOf("thinking_signature" to "abc"))
                    .build()
            val model = RecordingModel(reply(thinking), reply(AiMessage.from("Bye")))
            val chat = LangChain4jChatModel(model)

            val first = chat.chat(hello).message
            chat.chat(ChatRequest(listOf(ChatMessage.User("Hello"), first, ChatMessage.User("Bye"))))

            assertEquals("Hi", first.text)
            assertEquals(thinking, model.requests[1].messages()[1])
        }

    @Test
    fun `provider content of another model is ignored`() =
        runTest {
            val model = RecordingModel(reply(AiMessage.from("Bye")), reply(AiMessage.from("Bye")), reply(AiMessage.from("Bye")))
            val chat = LangChain4jChatModel(model)

            for (content in listOf(json("""{"type": "thinking"}"""), json("""{"type": "USER", "contents": []}"""), JsonPrimitive("raw"))) {
                chat.chat(ChatRequest(listOf(ChatMessage.User("Hello"), ChatMessage.Assistant("Hi", providerContent = content))))
            }

            assertEquals(List(3) { AiMessage.from("Hi") }, model.requests.map { it.messages()[1] })
        }

    @Test
    fun `an agent runs a tool for a LangChain4j model and sends the result back`() =
        runTest {
            val model =
                RecordingModel(
                    reply(
                        AiMessage.from(listOf(request("call-1", "order", """{"customer":"Ana","size":"LARGE","quantity":2}"""))),
                        FinishReason.TOOL_EXECUTION,
                    ),
                    reply(AiMessage.from("Ordered.")),
                )
            val order = Tool<Order>("order", "Places an order.") { "ok" }

            val state = toolAgent(LangChain4jChatModel(model), listOf(order)).invoke(AgentState("Two large pizzas for Ana")).state

            assertEquals("Ordered.", state.answer)
            val result = model.requests[1].messages().last() as ToolExecutionResultMessage
            assertEquals("call-1", result.id())
            assertTrue(result.isError())
            assertTrue("price" in result.text())
        }
}
