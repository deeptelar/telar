package dev.deeptelar.telar.agent

import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

data class Ticket(
    val messages: List<ChatMessage>,
    val reply: String = "",
    val log: List<String> = emptyList(),
    val question: String = "",
)

class ToolLoopTest {
    private val ran = mutableListOf<String>()

    private val orderStatus =
        Tool<Unit>("order_status", "Returns where the order is.") {
            ran += "order_status"
            "The driver is 5 minutes away."
        }
    private val menuPrice =
        Tool<Lookup>("menu_price", "Returns the price of an item.") {
            ran += "menu_price"
            "One ${it.item} costs 2 euros."
        }
    private val tools = listOf(orderStatus, menuPrice)

    private val status = ToolCall("call-1", "order_status", noInput)
    private val price = call("call-2", "menu_price", "item" to "cola")

    @Test
    fun `an agent whose model asks for no tools answers in one step`() =
        runTest {
            val model = ScriptedModel(says("Hello!"))

            val state = toolAgent(model, tools, system = "Be friendly").invoke(AgentState("Hi")).state

            assertEquals("Hello!", state.answer)
            assertEquals(listOf(ChatMessage.User("Hi"), ChatMessage.Assistant("Hello!")), state.messages)
            assertEquals(ChatRequest(listOf(ChatMessage.User("Hi")), "Be friendly", tools.map { it.spec }), model.requests.single())
            assertEquals(emptyList(), ran)
        }

    @Test
    fun `an agent runs the tools the model asks for and sends it the results`() =
        runTest {
            val model = ScriptedModel(calls(status, price), says("5 minutes, and a cola is 2 euros."))

            val state = toolAgent(model, tools).invoke(AgentState("Where is my pizza, and how much is a cola?")).state

            assertEquals("5 minutes, and a cola is 2 euros.", state.answer)
            assertEquals(setOf("order_status", "menu_price"), ran.toSet())
            assertEquals(
                listOf(
                    ChatMessage.User("Where is my pizza, and how much is a cola?"),
                    ChatMessage.Assistant(toolCalls = listOf(status, price)),
                    ChatMessage.ToolResult("call-1", "order_status", "The driver is 5 minutes away."),
                    ChatMessage.ToolResult("call-2", "menu_price", "One cola costs 2 euros."),
                ),
                model.requests[1].messages,
            )
        }

    @Test
    fun `an agent keeps asking the model until it asks for no more tools`() =
        runTest {
            val model = ScriptedModel(calls(status), calls(price), says("Done."))

            val state = toolAgent(model, tools).invoke(AgentState("Help")).state

            assertEquals("Done.", state.answer)
            assertEquals(3, model.requests.size)
            assertEquals(6, state.messages.size)
        }

    @Test
    fun `a conversation continues with the state of the last run`() =
        runTest {
            val model = ScriptedModel(says("Hello!"), says("I am fine."))
            val agent = toolAgent(model)

            val first = agent.invoke(AgentState("Hi")).state
            val second = agent.invoke(first.withUserMessage("How are you?")).state

            assertEquals("I am fine.", second.answer)
            assertEquals(4, second.messages.size)
            assertEquals(3, model.requests[1].messages.size)
        }

    @Test
    fun `the result of a failing tool goes back to the model`() =
        runTest {
            val model = ScriptedModel(calls(ToolCall("call-1", "refund", noInput)), says("I cannot do refunds."))

            val state = toolAgent(model, tools).invoke(AgentState("Refund me")).state

            assertEquals("I cannot do refunds.", state.answer)
            assertTrue((state.messages[2] as ChatMessage.ToolResult).isError)
        }

    @Test
    fun `a call whose input is not JSON goes back to the model and the model can send it again`() =
        runTest {
            val broken = ToolCall("call-1", "menu_price", noInput, invalidInput = "{\"item\": cola}")
            val model = ScriptedModel(calls(broken), calls(price), says("A cola is 2 euros."))

            val state = toolAgent(model, tools).invoke(AgentState("How much is a cola?")).state

            assertEquals("A cola is 2 euros.", state.answer)
            assertEquals(listOf("menu_price"), ran)
            assertEquals(
                ChatMessage.ToolResult("call-1", "menu_price", "The input is not a JSON object: {\"item\": cola}", isError = true),
                state.messages[2],
            )
        }

    @Test
    fun `two tools with the same name are rejected when the graph is built`() {
        val failure = assertFailsWith<GraphValidationException> { toolAgent(ScriptedModel(), listOf(orderStatus, orderStatus)) }

        assertTrue("order_status" in failure.message.orEmpty())
    }

    @Test
    fun `an answer that was cut off in a tool call fails the run`() =
        runTest {
            val model = ScriptedModel(calls(status).cutOff())

            val failure = assertFailsWith<NodeExecutionException> { toolAgent(model, tools).invoke(AgentState("Hi")) }

            assertEquals("model", failure.nodeName)
            assertIs<ChatModelException>(failure.cause)
            assertEquals(emptyList(), ran)
        }

    @Test
    fun `an answer that was cut off in its text is kept and marked`() =
        runTest {
            val model = ScriptedModel(says("Once upon a").cutOff())

            val state = toolAgent(model).invoke(AgentState("Tell me a story")).state

            assertEquals("Once upon a", state.answer)
            assertTrue(state.answerTruncated)
        }

    @Test
    fun `append sees that an answer was cut off`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    START then
                        toolLoop(
                            model = ScriptedModel(says("Once upon a").cutOff()),
                            tools = tools,
                            messages = { it.messages },
                            append = { ticket, new ->
                                val cutOff = new.any { it is ChatMessage.Assistant && it.truncated }
                                ticket.copy(messages = ticket.messages + new, log = ticket.log + "cut off: $cutOff")
                            },
                        )
                }.compile()

            assertEquals(listOf("cut off: true"), graph.invoke(Ticket(listOf(ChatMessage.User("Tell me a story")))).state.log)
        }

    @Test
    fun `a run that pauses before the tools continues with them when it is resumed`() =
        runTest {
            val model = ScriptedModel(calls(status), says("5 minutes."))
            val agent = toolAgent(model, tools)
            val config = GraphConfig(checkpointer = MemoryCheckpointer<AgentState>(), interruptBefore = setOf("tools"))

            val paused = agent.invoke(AgentState("Where is my pizza?"), config)

            assertIs<GraphResult.Interrupted<AgentState>>(paused)
            assertEquals(listOf(status), paused.state.messages.pendingToolCalls())
            assertEquals(null, paused.state.answer)
            assertEquals(emptyList(), ran)

            val state = agent.resume(config).state

            assertEquals("5 minutes.", state.answer)
            assertEquals(listOf("order_status"), ran)
        }

    @Test
    fun `a tool call that got a result while the run was paused is not run`() =
        runTest {
            val model = ScriptedModel(calls(status, price), says("A cola is 2 euros."))
            val agent = toolAgent(model, tools)
            val config = GraphConfig(checkpointer = MemoryCheckpointer<AgentState>(), interruptBefore = setOf("tools"))
            agent.invoke(AgentState("Where is my pizza, and how much is a cola?"), config)

            val rejected = ChatMessage.ToolResult("call-1", "order_status", "The user did not allow this call.", isError = true)
            val state = agent.resume(config) { it.copy(messages = it.messages + rejected) }.state

            assertEquals(listOf("menu_price"), ran)
            assertEquals("A cola is 2 euros.", state.answer)
            assertEquals(
                listOf("order_status", "menu_price"),
                model.requests[1].messages.filterIsInstance<ChatMessage.ToolResult>().map {
                    it.toolName
                },
            )
        }

    @Test
    fun `a tool loop in a larger graph continues at the given node with its own node names`() =
        runTest {
            val model = ScriptedModel(calls(price), says("A cola is 2 euros."))
            val graph =
                StateGraph<Ticket> {
                    val greet = node("greet") { it.copy(log = it.log + "greet") }
                    val send = node("send") { it.copy(reply = it.messages.last().text, log = it.log + "send") }
                    val agent =
                        toolLoop(
                            model = model,
                            tools = tools,
                            messages = { it.messages },
                            append = { ticket, new -> ticket.copy(messages = ticket.messages + new, log = ticket.log + "append") },
                            then = send,
                            modelNode = "assistant",
                            toolsNode = "desk",
                        )
                    START then greet then agent
                    send then END
                }.compile()

            val state = graph.invoke(Ticket(listOf(ChatMessage.User("How much is a cola?")))).state

            assertEquals("A cola is 2 euros.", state.reply)
            assertEquals(listOf("greet", "append", "append", "append", "send"), state.log)
            assertEquals(setOf("greet", "send", "assistant", "desk"), graph.topology.nodes.toSet())
        }

    private fun ticketGraph(model: ChatModel) =
        StateGraph<Ticket> {
            START then
                toolLoop(
                    model = model,
                    tools = tools,
                    messages = { it.messages },
                    append = { ticket, new -> ticket.copy(messages = ticket.messages + new) },
                    firstMessage = { "Ana writes: ${it.question}" },
                )
        }.compile()

    @Test
    fun `the first message starts an empty conversation and is stored with the answer`() =
        runTest {
            val model = ScriptedModel(calls(price), says("A cola is 2 euros."))
            val first = ChatMessage.User("Ana writes: How much is a cola?")

            val state = ticketGraph(model).invoke(Ticket(emptyList(), question = "How much is a cola?")).state

            assertEquals(listOf(first), model.requests[0].messages)
            assertEquals(first, model.requests[1].messages.first())
            assertEquals(first, state.messages.first())
            assertEquals(4, state.messages.size)
        }

    @Test
    fun `the first message is not added to a conversation that has messages`() =
        runTest {
            val model = ScriptedModel(says("Hello!"))
            val stored = listOf(ChatMessage.User("Hi"))

            val state = ticketGraph(model).invoke(Ticket(stored, question = "How much is a cola?")).state

            assertEquals(stored, model.requests.single().messages)
            assertEquals(stored + ChatMessage.Assistant("Hello!"), state.messages)
        }

    @Test
    fun `a model call that is retried gets the first message once`() =
        runTest {
            var failed = false
            val graph =
                ticketGraph { request ->
                    if (!failed) {
                        failed = true
                        throw ChatModelException("overloaded")
                    }
                    says("Hello ${request.messages.size}!")
                }
            val config = GraphConfig(checkpointer = MemoryCheckpointer<Ticket>())

            assertFailsWith<NodeExecutionException> { graph.invoke(Ticket(emptyList(), question = "Hi"), config) }
            val state = graph.resume(config).state

            assertEquals(listOf(ChatMessage.User("Ana writes: Hi"), ChatMessage.Assistant("Hello 1!")), state.messages)
        }
}
