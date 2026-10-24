package dev.deeptelar.telar.test

import dev.deeptelar.telar.Retry
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.ask
import dev.deeptelar.telar.agent.chat
import dev.deeptelar.telar.agent.toolAgent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Serializable
data class OrderLookup(
    val customer: String,
    val express: Boolean = false,
)

@Serializable
data class Verdict(
    val refund: Boolean,
    val reason: String,
)

class ScriptedChatModelTest {
    private val hello = ChatRequest(listOf(ChatMessage.User("Hello")))
    private val orderStatus =
        Tool<OrderLookup>("order_status", "Returns where an order is.") { "The pizza for ${it.customer} is 5 minutes away." }

    @Test
    fun `a scripted model gives its answers in order and keeps what it was asked`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls("order_status", OrderLookup("Ana"))
                    says("Your pizza is 5 minutes away.")
                }

            val state = toolAgent(model, listOf(orderStatus)).invoke(AgentState("Where is my pizza?")).state

            assertEquals("Your pizza is 5 minutes away.", state.answer)
            assertEquals(2, model.requests.size)
            assertEquals(ChatMessage.User("Where is my pizza?"), model.requests[0].messages.single())
            // The second request has the result of the tool, so the tool ran with the input of the script.
            assertEquals(
                "The pizza for Ana is 5 minutes away.",
                model.requests[1]
                    .messages
                    .last()
                    .text,
            )
            assertEquals(listOf("order_status"), model.requests[0].tools.map { it.name })
        }

    @Test
    fun `the calls of a script have ids that count up`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls(call("order_status", OrderLookup("Ana")), call("opening_hours"), text = "Let me check.")
                    calls("order_status", OrderLookup("Ben", express = true))
                }

            val first = model.chat(hello).message
            val second = model.chat(hello).message

            assertEquals("Let me check.", first.text)
            assertEquals(listOf("call-1", "call-2"), first.toolCalls.map { it.id })
            assertEquals(buildJsonObject { put("customer", "Ana") }, first.toolCalls[0].input)
            assertEquals(JsonObject(emptyMap()), first.toolCalls[1].input)
            assertEquals(
                ToolCall(
                    "call-3",
                    "order_status",
                    buildJsonObject {
                        put("customer", "Ben")
                        put("express", true)
                    },
                ),
                second.toolCalls.single(),
            )
        }

    @Test
    fun `the input of a call can be a map or a JSON object for an input that does not fit`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls("order_status", mapOf("client" to "Ana"))
                    calls("order_status", buildJsonObject { put("express", true) })
                    says("Sorry.")
                }

            val state = toolAgent(model, listOf(orderStatus)).invoke(AgentState("Where is my pizza?")).state

            assertEquals(
                JsonPrimitive("Ana"),
                model.requests[1]
                    .messages
                    .filterIsInstance<ChatMessage.Assistant>()
                    .single()
                    .toolCalls
                    .single()
                    .input["client"],
            )
            // The tool could not read either input, and the model got an error for each.
            assertEquals(listOf(true, true), state.messages.filterIsInstance<ChatMessage.ToolResult>().map { it.isError })
        }

    @Test
    fun `an input that is not written as a JSON object is rejected`() {
        val exception = assertFailsWith<IllegalArgumentException> { ScriptedChatModel { calls("order_status", "Ana") } }

        assertTrue("JSON object" in exception.message.orEmpty())
    }

    @Test
    fun `a value of the script is read by ask`() =
        runTest {
            val model = ScriptedChatModel { answers(Verdict(refund = true, reason = "The pizza was cold."), usage = TokenUsage(40, 9)) }

            val response = model.ask<Verdict>(ChatRequest(listOf(ChatMessage.User("Refund?"))))

            assertEquals(Verdict(refund = true, reason = "The pizza was cold."), response.value)
            assertEquals(TokenUsage(40, 9), response.usage)
        }

    @Test
    fun `an answer has the tokens and the cut the script gave it`() =
        runTest {
            val model =
                ScriptedChatModel {
                    says("Hello!", usage = TokenUsage(10, 2))
                    says("Once upon a", truncated = true)
                    calls(call("opening_hours"), usage = TokenUsage(30, 4))
                }

            assertEquals(ChatResponse(ChatMessage.Assistant("Hello!"), TokenUsage(10, 2)), model.chat(hello))
            assertTrue(model.chat(hello).message.truncated)
            assertEquals(TokenUsage(30, 4), model.chat(hello).usage)
        }

    @Test
    fun `an agent counts the tokens of a script`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls("order_status", OrderLookup("Ana"), usage = TokenUsage(100, 20))
                    says("5 minutes.", usage = TokenUsage(150, 30))
                }

            val state = toolAgent(model, listOf(orderStatus)).invoke(AgentState("Where is my pizza?")).state

            assertEquals(TokenUsage(250, 50), state.tokens)
        }

    @Test
    fun `a call that the script fails throws and is still kept`() =
        runTest {
            val broken = IllegalStateException("No connection.")
            val model =
                ScriptedChatModel {
                    fails("API error 529: Overloaded")
                    fails(broken)
                    says("Hello!")
                }

            assertEquals("API error 529: Overloaded", assertFailsWith<ChatModelException> { model.chat(hello) }.message)
            assertSame(broken, assertFailsWith<IllegalStateException> { model.chat(hello) })
            assertEquals("Hello!", model.chat("Hello"))
            assertEquals(3, model.requests.size)
        }

    @Test
    fun `an agent with a retry gets past the failures of a script`() =
        runTest {
            val model =
                ScriptedChatModel {
                    fails("API error 529: Overloaded")
                    says("Hello!")
                }

            val state = toolAgent(model, retry = Retry(attempts = 2)).invoke(AgentState("Hi")).state

            assertEquals("Hello!", state.answer)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `an answer of the script can depend on the request`() =
        runTest {
            val model =
                ScriptedChatModel {
                    responds { request ->
                        ChatResponse(ChatMessage.Assistant("You said: ${request.messages.last().text}"))
                    }
                }

            assertEquals("You said: Hello", model.chat("Hello"))
        }

    @Test
    fun `a model that is asked for more than its script fails the test`() =
        runTest {
            val model = ScriptedChatModel { says("Hello!") }
            model.chat(hello)

            val error = assertFailsWith<AssertionError> { model.chat(ChatRequest(listOf(ChatMessage.User("And now?")))) }

            assertEquals(
                "The script of this model has 1 answers, but the model was asked 2 times. " +
                    "The last message it was asked with: User(text=And now?, attachments=[])",
                error.message,
            )
        }

    @Test
    fun `a script that ends inside a graph fails the test with its own message`() =
        runTest {
            val model = ScriptedChatModel { calls("order_status", OrderLookup("Ana")) }

            // Not a NodeExecutionException: the engine passes an AssertionError on as it is.
            val error =
                assertFailsWith<AssertionError> {
                    toolAgent(
                        model,
                        listOf(orderStatus),
                        retry = Retry(attempts = 3),
                    ).invoke(AgentState("Where?"))
                }

            assertTrue("was asked 2 times" in error.message.orEmpty())
            assertEquals(2, model.requests.size)
        }

    @Test
    fun `a model says how much of its script is left`() =
        runTest {
            val model =
                ScriptedChatModel {
                    says("One")
                    says("Two")
                }

            assertEquals(2, model.remaining)
            model.chat(hello)
            assertEquals(1, model.remaining)
            model.chat(hello)
            assertFailsWith<AssertionError> { model.chat(hello) }
            assertEquals(0, model.remaining)
        }

    @Test
    fun `a scripted model writes a streamed answer word by word`() =
        runTest {
            val model =
                ScriptedChatModel {
                    says("Your pizza is on its way.")
                    calls(call("opening_hours"))
                }

            val events = model.stream(hello).toList()

            assertEquals(
                listOf("Your ", "pizza ", "is ", "on ", "its ", "way."),
                events.filterIsInstance<ChatEvent.TextDelta>().map { it.text },
            )
            assertEquals("Your pizza is on its way.", assertIs<ChatEvent.Completed>(events.last()).response.message.text)
            // An answer without text has no pieces.
            assertEquals(1, model.stream(hello).toList().size)
        }

    @Test
    fun `a streamed call that fails writes its partial text first`() =
        runTest {
            val model = ScriptedChatModel { fails("The connection broke.", partialText = "Your pizza") }
            val pieces = mutableListOf<String>()

            assertFailsWith<ChatModelException> { model.stream(hello).collect { if (it is ChatEvent.TextDelta) pieces += it.text } }

            assertEquals(listOf("Your ", "pizza"), pieces)
            // A call that is not streamed has no partial text.
            assertFailsWith<ChatModelException> {
                ScriptedChatModel {
                    fails(
                        "The connection broke.",
                        partialText = "Your pizza",
                    )
                }.chat(hello)
            }
        }

    @Test
    fun `the pieces of a text joined are the text`() {
        assertEquals(emptyList(), words(""))
        assertEquals(listOf("  "), words("  "))
        assertEquals(listOf("Hello!"), words("Hello!"))
        assertEquals(listOf("  One\n", "two  ", "three "), words("  One\ntwo  three "))
        val text = " A line.\n\nAnother\tline. "
        assertEquals(text, words(text).joinToString(""))
    }
}
