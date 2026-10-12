package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.interrupt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

data class Basket(
    val messages: List<ChatMessage>,
    val lines: List<String> = emptyList(),
    val paid: Boolean = false,
    val question: String? = null,
    val approved: Boolean? = null,
    val log: List<String> = emptyList(),
)

@Serializable
data class Item(
    val item: String,
)

class StateToolTest {
    private val ran = mutableListOf<String>()

    private val addLine =
        StateTool<Basket, Item>("add_line", "Adds an item to the basket.") { basket, input ->
            ran += "add_line ${input.item}"
            val lines = basket.lines + input.item
            ToolOutcome(basket.copy(lines = lines), "Added ${input.item}. The basket has ${lines.size}.")
        }
    private val removeLine =
        StateTool<Basket, Item>("remove_line", "Takes an item out of the basket.") { basket, input ->
            ran += "remove_line ${input.item}"
            require(input.item in basket.lines) { "There is no ${input.item} in the basket." }
            ToolOutcome(basket.copy(lines = basket.lines - input.item), "Removed ${input.item}.")
        }
    private val pay =
        StateTool<Basket, Unit>("pay", "Pays for the basket. A person has to approve it.") { basket, _ ->
            ran += "pay"
            if (basket.approved == null) interrupt(basket.copy(question = "Pay for ${basket.lines}?"))
            if (basket.approved) {
                ToolOutcome(
                    basket.copy(paid = true, question = null),
                    "Paid.",
                )
            } else {
                throw IllegalStateException("The customer said no.")
            }
        }
    private val menuPrice =
        Tool<Item>("menu_price", "Returns the price of an item.") {
            ran += "menu_price ${it.item}"
            "One ${it.item} costs 2 euros."
        }

    private fun add(id: String, item: String) = call(id, "add_line", "item" to item)

    private fun remove(id: String, item: String) = call(id, "remove_line", "item" to item)

    private fun shop(
        model: ChatModel,
        stateTools: List<StateTool<Basket>> = listOf(addLine, removeLine, pay),
        tools: List<Tool> = listOf(menuPrice),
    ) = StateGraph<Basket> {
        START then
            toolLoop(
                model = model,
                tools = tools,
                stateTools = stateTools,
                messages = { it.messages },
                append = { basket, new -> basket.copy(messages = basket.messages + new, log = basket.log + "append ${new.size}") },
            )
    }.compile()

    private fun basket(text: String = "A cola, please.") = Basket(listOf(ChatMessage.User(text)))

    private fun Basket.results(): List<ChatMessage.ToolResult> = messages.filterIsInstance<ChatMessage.ToolResult>()

    @Test
    fun `a state tool changes the state and the model gets its text`() =
        runTest {
            val model = ScriptedModel(calls(add("call-1", "cola")), says("Done."))

            val state = shop(model).invoke(basket()).state

            assertEquals(listOf("cola"), state.lines)
            assertEquals(ChatMessage.ToolResult("call-1", "add_line", "Added cola. The basket has 1."), state.results().single())
            assertEquals(state.results(), model.requests[1].messages.filterIsInstance<ChatMessage.ToolResult>())
            assertEquals("Done.", state.messages.last().text)
        }

    @Test
    fun `the calls of an answer run in order and each gets the state the one before left`() =
        runTest {
            val model = ScriptedModel(calls(add("call-1", "cola"), add("call-2", "pizza"), remove("call-3", "cola")), says("Done."))

            val state = shop(model).invoke(basket()).state

            assertEquals(listOf("add_line cola", "add_line pizza", "remove_line cola"), ran)
            assertEquals(listOf("pizza"), state.lines)
            assertEquals(
                listOf("Added cola. The basket has 1.", "Added pizza. The basket has 2.", "Removed cola."),
                state.results().map { it.text },
            )
            assertEquals(listOf("call-1", "call-2", "call-3"), state.results().map { it.toolCallId })
        }

    @Test
    fun `a call that fails changes nothing and the calls after it still run`() =
        runTest {
            val model = ScriptedModel(calls(add("call-1", "cola"), remove("call-2", "water"), add("call-3", "pizza")), says("Done."))

            val state = shop(model).invoke(basket()).state

            assertEquals(listOf("cola", "pizza"), state.lines)
            val failed = state.results()[1]
            assertTrue(failed.isError)
            assertEquals("There is no water in the basket.", failed.text)
            assertEquals("Added pizza. The basket has 2.", state.results()[2].text)
        }

    @Test
    fun `input that does not fit the tool comes back as an error and changes nothing`() =
        runTest {
            val wrong = ToolCall("call-1", "add_line", buildJsonObject { putJsonObject("item") { put("name", "cola") } })
            val model = ScriptedModel(calls(wrong), says("Sorry."))

            val state = shop(model).invoke(basket()).state

            assertEquals(emptyList(), state.lines)
            assertTrue(state.results().single().isError)
            assertEquals(emptyList(), ran)
        }

    @Test
    fun `a call of a tool that does not exist comes back as an error`() =
        runTest {
            val model = ScriptedModel(calls(ToolCall("call-1", "empty_basket", noInput)), says("Sorry."))

            val result =
                shop(model)
                    .invoke(basket())
                    .state
                    .results()
                    .single()

            assertTrue(result.isError)
            assertContains(result.text, "empty_basket")
        }

    @Test
    fun `tools and state tools of one answer run in the order of the calls`() =
        runTest {
            val model = ScriptedModel(calls(call("call-1", "menu_price", "item" to "cola"), add("call-2", "cola")), says("Done."))

            val state = shop(model).invoke(basket()).state

            assertEquals(listOf("menu_price cola", "add_line cola"), ran)
            assertEquals(listOf("One cola costs 2 euros.", "Added cola. The basket has 1."), state.results().map { it.text })
            assertEquals(listOf("cola"), state.lines)
        }

    @Test
    fun `the model is told about the tools and the state tools`() =
        runTest {
            val model = ScriptedModel(says("Hello!"))

            shop(model).invoke(basket())

            assertEquals(
                listOf("menu_price", "add_line", "remove_line", "pay"),
                model.requests
                    .single()
                    .tools
                    .map { it.name },
            )
        }

    @Test
    fun `a loop needs no tools next to its state tools`() =
        runTest {
            val model = ScriptedModel(calls(add("call-1", "cola")), says("Done."))
            val graph =
                StateGraph<Basket> {
                    START then
                        toolLoop(
                            model = model,
                            stateTools = listOf(addLine),
                            messages = { it.messages },
                            append = { basket, new -> basket.copy(messages = basket.messages + new) },
                        )
                }.compile()

            assertEquals(listOf("cola"), graph.invoke(basket()).state.lines)
        }

    @Test
    fun `a tool and a state tool with the same name are rejected when the graph is built`() {
        val clash = Tool<Unit>("add_line", "Adds a line.") { "Added." }

        val failure = assertFailsWith<GraphValidationException> { shop(ScriptedModel(), tools = listOf(clash)) }

        assertContains(failure.message!!, "add_line")
    }

    @Test
    fun `append gets each result on its own before the next call runs`() =
        runTest {
            val model = ScriptedModel(calls(add("call-1", "cola"), add("call-2", "pizza")), says("Done."))

            val state = shop(model).invoke(basket()).state

            // The answer with the calls, the two results, and the last answer.
            assertEquals(listOf("append 1", "append 1", "append 1", "append 1"), state.log)
        }

    @Test
    fun `a state tool that pauses the run saves what the calls before it did`() =
        runTest {
            val model =
                ScriptedModel(calls(add("call-1", "cola"), ToolCall("call-2", "pay", noInput), add("call-3", "napkins")), says("Paid."))
            val graph = shop(model)
            val config = GraphConfig(checkpointer = MemoryCheckpointer<Basket>())

            val paused = graph.invoke(basket(), config)

            assertIs<GraphResult.Interrupted<Basket>>(paused)
            assertEquals("Pay for [cola]?", paused.state.question)
            assertEquals(listOf("cola"), paused.state.lines)
            assertEquals(listOf("call-1"), paused.state.results().map { it.toolCallId })
            assertEquals(
                listOf("pay", "add_line"),
                paused.state.messages
                    .pendingToolCalls()
                    .map { it.name },
            )

            val state = graph.resume(config) { it.copy(approved = true) }.state

            // The first call is not run again: the run continues with the call that paused.
            assertEquals(listOf("add_line cola", "pay", "pay", "add_line napkins"), ran)
            assertEquals(listOf("cola", "napkins"), state.lines)
            assertTrue(state.paid)
            assertNull(state.question)
            assertEquals(listOf("call-1", "call-2", "call-3"), state.results().map { it.toolCallId })
            assertEquals("Paid.", state.messages.last().text)
        }

    @Test
    fun `a call that was answered while the run was paused before the tools is not run`() =
        runTest {
            val model = ScriptedModel(calls(add("call-1", "cola"), add("call-2", "pizza")), says("Done."))
            val graph = shop(model)
            val config = GraphConfig(checkpointer = MemoryCheckpointer<Basket>(), interruptBefore = setOf("tools"))
            graph.invoke(basket(), config)

            val rejected = ChatMessage.ToolResult("call-1", "add_line", "The reviewer said no.", isError = true)
            val state = graph.resume(config) { it.copy(messages = it.messages + rejected) }.state

            assertEquals(listOf("add_line pizza"), ran)
            assertEquals(listOf("pizza"), state.lines)
        }

    @Test
    fun `a state tool with a schema written by hand gets the input as the model sent it`() =
        runTest {
            val spec = ToolSpec("add_line", "Adds an item.", buildJsonObject { put("type", "object") })
            val byHand =
                StateTool<Basket>(spec) { basket, input ->
                    ToolOutcome(basket.copy(lines = basket.lines + input.getValue("item").jsonPrimitive.content), "Added.")
                }
            val model = ScriptedModel(calls(add("call-1", "cola")), says("Done."))

            assertEquals(listOf("cola"), shop(model, stateTools = listOf(byHand)).invoke(basket()).state.lines)
        }

    @Test
    fun `a loop with state tools runs next to a node with a work and an update`() =
        runTest {
            val model = ScriptedModel(calls(add("call-1", "cola")), says("Done."))
            val graph =
                StateGraph<Basket> {
                    val agent =
                        toolLoop(
                            model = model,
                            stateTools = listOf(addLine),
                            messages = { it.messages },
                            append = { basket, new -> basket.copy(messages = basket.messages + new) },
                        )
                    // Three steps long, so that its last node runs in the step of the tools node.
                    val wait = node("wait", work = { }) { basket, _ -> basket }
                    val note = node("note", work = { "noted" }) { basket, text -> basket.copy(log = basket.log + text) }
                    START then agent
                    START then wait then note
                }.compile()

            val state = graph.invoke(basket()).state

            assertEquals(listOf("cola"), state.lines)
            assertEquals(listOf("noted"), state.log)
            assertEquals("Done.", state.messages.last().text)
        }

    @Test
    fun `a loop with state tools next to a node that returns a whole state needs a reducer`() {
        val failure =
            assertFailsWith<GraphValidationException> {
                StateGraph<Basket> {
                    val agent =
                        toolLoop(
                            model = ScriptedModel(),
                            stateTools = listOf(addLine),
                            messages = { it.messages },
                            append = { basket, new -> basket.copy(messages = basket.messages + new) },
                        )
                    val wait = node("wait", work = { }) { basket, _ -> basket }
                    val note = node("note") { basket -> basket.copy(log = basket.log + "noted") }
                    START then agent
                    START then wait then note
                }.compile()
            }

        assertContains(failure.message!!, "'tools'")
        assertContains(failure.message!!, "Reducer")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `tools run one after the other when the loop is told to run them in order`() =
        runTest {
            val slow =
                Tool<Item>("cook", "Cooks an item.") {
                    ran += "start ${it.item}"
                    delay(100)
                    ran += "end ${it.item}"
                    "Cooked ${it.item}."
                }
            val cook = calls(call("call-1", "cook", "item" to "pizza"), call("call-2", "cook", "item" to "pasta"))

            val together = toolAgent(ScriptedModel(cook, says("Done.")), listOf(slow))
            together.invoke(AgentState("Cook."))
            assertEquals(100, currentTime)
            assertEquals(listOf("start pizza", "start pasta", "end pizza", "end pasta"), ran)

            ran.clear()
            val inOrder = toolAgent(ScriptedModel(cook, says("Done.")), listOf(slow), inOrder = true)
            val state = inOrder.invoke(AgentState("Cook.")).state
            assertEquals(300, currentTime)
            assertEquals(listOf("start pizza", "end pizza", "start pasta", "end pasta"), ran)
            assertEquals(
                listOf("Cooked pizza.", "Cooked pasta."),
                state.messages.filterIsInstance<ChatMessage.ToolResult>().map { it.text },
            )
        }
}
