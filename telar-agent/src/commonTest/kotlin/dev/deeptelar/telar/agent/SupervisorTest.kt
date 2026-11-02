package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupervisorTest {
    private fun ask(id: String, agent: String, task: String) = call(id, "ask_$agent", "task" to task)

    private fun agent(name: String, model: ChatModel, tools: List<Tool> = emptyList()) = TeamAgent(name, "Knows about $name.", model, tools)

    @Test
    fun `an agent gets only its task and the supervisor gets only its answer`() =
        runTest {
            val lead = ScriptedModel(calls(ask("1", "menu", "What does a calzone cost?")), says("A calzone is 9 euros."))
            val menu = ScriptedModel(says("9 euros."))

            val state = supervisor(lead, listOf(agent("menu", menu)), system = "Lead.").invoke(TeamState("Calzone?")).state

            assertEquals("A calzone is 9 euros.", state.answer)
            assertEquals(listOf<ChatMessage>(ChatMessage.User("What does a calzone cost?")), menu.requests.single().messages)
            assertEquals(ChatMessage.ToolResult("1", "ask_menu", "9 euros."), lead.requests[1].messages.last())
            assertEquals("Lead.", lead.requests[0].system)
            assertEquals(listOf("ask_menu"), lead.requests[0].tools.map { it.name })
            assertEquals(
                "9 euros.",
                state.assignments
                    .single()
                    .conversation.answer,
            )
            assertTrue(state.assignments.single().finished)
        }

    @Test
    fun `an agent works with its own tools and instructions`() =
        runTest {
            val price = Tool<Unit>("price", "Gives the price.") { "9 euros" }
            val menu = ScriptedModel(calls(call("a", "price")), says("It is 9 euros."))
            val lead = ScriptedModel(calls(ask("1", "menu", "Price?")), says("Done."))
            val team = supervisor(lead, listOf(TeamAgent("menu", "Prices.", menu, listOf(price), system = "Be short.")))

            val state = team.invoke(TeamState("Price?")).state

            assertEquals(listOf("price"), menu.requests[0].tools.map { it.name })
            assertEquals("Be short.", menu.requests[0].system)
            assertEquals(
                "It is 9 euros.",
                state.assignments
                    .single()
                    .conversation.answer,
            )
        }

    @Test
    fun `tasks for different agents run at the same time`() =
        runTest {
            val bothStarted = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val waits =
                { name: String ->
                    object : ChatModel {
                        override suspend fun chat(request: ChatRequest): ChatResponse {
                            started += name
                            if (started.size == 2) bothStarted.complete(Unit)
                            bothStarted.await()
                            return says("$name done")
                        }
                    }
                }
            val lead = ScriptedModel(calls(ask("1", "orders", "One."), ask("2", "menu", "Two.")), says("Both done."))

            val state =
                supervisor(
                    lead,
                    listOf(agent("orders", waits("orders")), agent("menu", waits("menu"))),
                ).invoke(TeamState("Go.")).state

            assertEquals("Both done.", state.answer)
            assertEquals(
                setOf("1", "2"),
                lead.requests[1]
                    .messages
                    .filterIsInstance<ChatMessage.ToolResult>()
                    .map { it.toolCallId }
                    .toSet(),
            )
        }

    @Test
    fun `two tasks for one agent run one after the other in new conversations`() =
        runTest {
            val menu = ScriptedModel(says("First."), says("Second."))
            val lead = ScriptedModel(calls(ask("1", "menu", "One."), ask("2", "menu", "Two.")), says("Done."))

            val state = supervisor(lead, listOf(agent("menu", menu))).invoke(TeamState("Go.")).state

            assertEquals(
                listOf<List<ChatMessage>>(listOf(ChatMessage.User("One.")), listOf(ChatMessage.User("Two."))),
                menu.requests.map {
                    it.messages
                },
            )
            assertEquals(
                listOf("First.", "Second."),
                lead.requests[1]
                    .messages
                    .filterIsInstance<ChatMessage.ToolResult>()
                    .map { it.text },
            )
            assertEquals("Done.", state.answer)
        }

    @Test
    fun `a supervisor can ask again with what it learned`() =
        runTest {
            val menu = ScriptedModel(says("Calzone."), says("9 euros."))
            val lead =
                ScriptedModel(calls(ask("1", "menu", "Dish?")), calls(ask("2", "menu", "Price of calzone?")), says("A calzone, 9 euros."))

            val state = supervisor(lead, listOf(agent("menu", menu))).invoke(TeamState("Go.")).state

            assertEquals("A calzone, 9 euros.", state.answer)
            assertEquals(listOf(0, 1), state.assignments.map { it.index })
        }

    @Test
    fun `a conversation continues with another question`() =
        runTest {
            val lead = ScriptedModel(says("Hello."), says("Bye."))
            val team = supervisor(lead, listOf(agent("menu", ScriptedModel())))

            val first = team.invoke(TeamState("Hi.")).state
            val second = team.invoke(first.withUserMessage("Thanks.")).state

            assertEquals("Bye.", second.answer)
            assertEquals(ChatMessage.User("Thanks."), lead.requests[1].messages.last())
        }

    @Test
    fun `a call of an unknown agent is an error the model reads`() =
        runTest {
            val lead = ScriptedModel(calls(ask("1", "kitchen", "Cook.")), says("Sorry."))

            val state = supervisor(lead, listOf(agent("menu", ScriptedModel()))).invoke(TeamState("Go.")).state

            val result = assertIs<ChatMessage.ToolResult>(lead.requests[1].messages.last())
            assertTrue(result.isError)
            assertEquals("Sorry.", state.answer)
            assertTrue(state.assignments.isEmpty())
        }

    @Test
    fun `a call without a task is an error the model reads`() =
        runTest {
            val lead = ScriptedModel(calls(call("1", "ask_menu"), ask("2", "menu", "Price?")), says("Done."))
            val menu = ScriptedModel(says("9 euros."))

            supervisor(lead, listOf(agent("menu", menu))).invoke(TeamState("Go."))

            val results = lead.requests[1].messages.filterIsInstance<ChatMessage.ToolResult>()
            assertEquals(listOf(true, false), results.map { it.isError })
            assertEquals(1, menu.requests.size)
        }

    @Test
    fun `the tokens of the agents count for the team`() =
        runTest {
            val lead = ScriptedModel(calls(ask("1", "menu", "Price?")).using(10, 1), says("Done.").using(20, 1))
            val menu = ScriptedModel(says("9 euros.").using(100, 10))

            val state = supervisor(lead, listOf(agent("menu", menu))).invoke(TeamState("Go.")).state

            assertEquals(142, state.tokens.total)
            assertEquals(
                110,
                state.assignments
                    .single()
                    .conversation.tokens.total,
            )
        }

    @Test
    fun `a team that used its budget stops without an answer`() =
        runTest {
            val lead = ScriptedModel(calls(ask("1", "menu", "Price?")).using(60, 0))
            val menu = ScriptedModel(says("9 euros.").using(50, 0))

            val state = supervisor(lead, listOf(agent("menu", menu)), tokenBudget = 100).invoke(TeamState("Go.")).state

            assertEquals(1, lead.requests.size)
            assertNull(state.answer)
            assertEquals(40, state.assignments.single().tokenBudget)
        }

    @Test
    fun `an agent that reaches the budget gives the supervisor an error`() =
        runTest {
            val lookup = Tool<Unit>("lookup", "Looks up.") { "Found." }
            val lead = ScriptedModel(calls(ask("1", "menu", "Price?")).using(10, 0))
            val menu = ScriptedModel(calls(call("a", "lookup")).using(95, 0))

            val state = supervisor(lead, listOf(agent("menu", menu, listOf(lookup))), tokenBudget = 100).invoke(TeamState("Go.")).state

            assertEquals(1, menu.requests.size)
            assertTrue(assertIs<ChatMessage.ToolResult>(state.messages.last()).isError)
            assertEquals(105, state.tokens.total)
        }

    @Test
    fun `an answer cut off at the output limit shows in the state`() =
        runTest {
            val lead = ScriptedModel(says("A long").cutOff())

            val state = supervisor(lead, listOf(agent("menu", ScriptedModel()))).invoke(TeamState("Go.")).state

            assertTrue(state.answerTruncated)
        }

    @Test
    fun `a run paused before the tools of an agent continues inside it`() =
        runTest {
            var ran = 0
            val charge =
                Tool<Unit>("charge", "Charges.") {
                    ran++
                    "Charged."
                }
            val pay = ScriptedModel(calls(call("a", "charge")), says("Paid."))
            val lead = ScriptedModel(calls(ask("1", "pay", "Charge it.")), says("Done."))
            val team = supervisor(lead, listOf(agent("pay", pay, listOf(charge))))
            val config = GraphConfig<TeamState>("t", MemoryCheckpointer(), interruptBefore = setOf("pay/tools"))

            val paused = team.invoke(TeamState("Go."), config)

            assertIs<GraphResult.Interrupted<TeamState>>(paused)
            assertEquals(0, ran)
            assertEquals(
                1,
                paused.state.assignments
                    .single()
                    .conversation.messages
                    .pendingToolCalls()
                    .size,
            )
            assertNull(paused.state.answer)

            val done = team.resume(config) { it }

            assertEquals("Done.", done.state.answer)
            assertEquals(1, ran)
            assertEquals(2, pay.requests.size)
            assertEquals(2, lead.requests.size)
        }

    @Test
    fun `a pause after the last node of an agent reports its answer once`() =
        runTest {
            val menu = ScriptedModel(says("9 euros.").using(10, 0))
            val lead = ScriptedModel(calls(ask("1", "menu", "Price?")), says("Done."))
            val team = supervisor(lead, listOf(agent("menu", menu)))
            val config = GraphConfig<TeamState>("t", MemoryCheckpointer(), interruptAfter = setOf("menu/finish"))

            assertIs<GraphResult.Interrupted<TeamState>>(team.invoke(TeamState("Go."), config))
            val done = team.resume(config) { it }

            assertEquals("Done.", done.state.answer)
            assertEquals(
                1,
                done.state.messages
                    .filterIsInstance<ChatMessage.ToolResult>()
                    .size,
            )
            assertEquals(10, done.state.tokens.total)
        }

    @Test
    fun `a failure of an agent's model continues inside the agent on resume`() =
        runTest {
            var fail = true
            val lookup = Tool<Unit>("lookup", "Looks up.") { "Found." }
            val menu =
                object : ChatModel {
                    val script = ScriptedModel(calls(call("a", "lookup")), says("9 euros."))

                    override suspend fun chat(request: ChatRequest): ChatResponse {
                        if (request.messages.size > 1 && fail) throw ChatModelException("Down.")
                        return script.chat(request)
                    }
                }
            val lead = ScriptedModel(calls(ask("1", "menu", "Price?")), says("Done."))
            val team = supervisor(lead, listOf(agent("menu", menu, listOf(lookup))))
            val config = GraphConfig<TeamState>("t", MemoryCheckpointer())

            assertFailsWith<NodeExecutionException> { team.invoke(TeamState("Go."), config) }
            fail = false
            val done = team.resume(config) { it }

            assertEquals("Done.", done.state.answer)
            assertEquals(2, menu.script.requests.size)
        }

    @Test
    fun `a stream names the agent that writes`() =
        runTest {
            val lead = ScriptedModel(calls(ask("1", "menu", "Price?")), says("Done."))
            val menu = ScriptedModel(says("9 euros."))

            val events = supervisor(lead, listOf(agent("menu", menu))).stream(TeamState("Go.")).toList()

            assertEquals(setOf("menu"), events.filterIsInstance<GraphEvent.SubgraphEvent<TeamState>>().map { it.node }.toSet())
        }

    @Test
    fun `an observer can tell which agent made a call`() =
        runTest {
            val calledBy = mutableListOf<String?>()
            val observer =
                object : AgentObserver {
                    override fun onModelCallStarted(call: ObservedModelCall) {
                        calledBy +=
                            call.node.run.parent
                                ?.name
                    }
                }
            val lead = ScriptedModel(calls(ask("1", "menu", "Price?")), says("Done."))
            val config = GraphConfig<TeamState>("t", observers = listOf(observer))

            supervisor(lead, listOf(agent("menu", ScriptedModel(says("9 euros."))))).invoke(TeamState("Go."), config)

            assertEquals(listOf(null, "menu", null), calledBy)
        }

    @Test
    fun `the graph shows the agents`() {
        val team = supervisor(ScriptedModel(), listOf(agent("orders", ScriptedModel()), agent("menu", ScriptedModel())))

        assertEquals(setOf("supervisor", "orders", "menu"), team.topology.nodes.toSet())
    }

    @Test
    fun `a team needs agents with names that work`() {
        val model = ScriptedModel()

        assertFailsWith<GraphValidationException> { supervisor(model, emptyList()) }
        assertFailsWith<GraphValidationException> { supervisor(model, listOf(agent("a", model)), tokenBudget = 0) }
        assertFailsWith<GraphValidationException> { supervisor(model, listOf(agent("a", model), agent("a", model))) }
        assertFailsWith<GraphValidationException> { supervisor(model, listOf(agent("supervisor", model))) }
        assertFailsWith<GraphValidationException> { supervisor(model, listOf(agent("a b", model))) }
    }

    @Test
    fun `no answer before the run`() {
        assertNull(TeamState("Hi.").answer)
        assertFalse(TeamState("Hi.").answerTruncated)
    }
}
