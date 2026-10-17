package dev.deeptelar.telar.test

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.Retry
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.reportProgress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration

data class Desk(
    val chat: AgentState = AgentState("Where is my pizza?"),
    val facts: List<String> = emptyList(),
)

class RunRecordTest {
    private val orderStatus = Tool<OrderLookup>("order_status", "Returns where an order is.") { "5 minutes away." }

    private fun helpDesk(retry: Retry? = null) =
        ScriptedChatModel {
            calls("order_status", OrderLookup("Ana"), text = "Let me check.")
            says("Your pizza is 5 minutes away.")
        }.let { toolAgent(it, listOf(orderStatus), retry = retry) }

    @Test
    fun `a record has the path of a run and what its model wrote`() =
        runTest {
            val run = helpDesk().stream(AgentState("Where is my pizza?")).record()

            assertEquals(listOf("model", "tools", "model"), run.nodes)
            assertEquals(listOf(listOf("model"), listOf("tools"), listOf("model")), run.steps)
            assertEquals("Let me check.Your pizza is 5 minutes away.", run.text)
            assertEquals("Your pizza is 5 minutes away.", run.state.answer)
            assertIs<GraphResult.Completed<AgentState>>(run.result)
            assertIs<GraphEvent.NodeStarted<AgentState>>(run.events.first())
            assertEquals("RunRecord(steps=[[model], [tools], [model]], result=${run.result})", run.toString())
        }

    @Test
    fun `a record of a run that paused says where it paused`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<AgentState>(), interruptBefore = setOf("tools"))

            val run = helpDesk().stream(AgentState("Where is my pizza?"), config).record()

            assertEquals(listOf("tools"), assertIs<GraphResult.Interrupted<AgentState>>(run.result).nextNodes)
            assertEquals(listOf("model"), run.nodes)
            assertEquals("Let me check.", run.text)
        }

    @Test
    fun `a record has the nodes of a parallel step together`() =
        runTest {
            val graph =
                StateGraph<Desk> {
                    val kitchen = node("kitchen", work = { "In the oven." }) { desk, fact -> desk.copy(facts = desk.facts + fact) }
                    val driver = node("driver", work = { "Not out yet." }) { desk, fact -> desk.copy(facts = desk.facts + fact) }
                    val answer = node("answer") { it }
                    START then kitchen then answer
                    START then driver then answer
                }.compile()

            val run = graph.stream(Desk()).record()

            assertEquals(listOf(listOf("kitchen", "driver"), listOf("answer")), run.steps)
            assertEquals(setOf("kitchen", "driver", "answer"), run.nodes.toSet())
            assertEquals("", run.text)
        }

    @Test
    fun `the text of a try that failed is left out`() =
        runTest {
            val model =
                ScriptedChatModel {
                    fails("The connection broke.", partialText = "Your pizza")
                    says("Your pizza is 5 minutes away.")
                }

            val run = toolAgent(model, retry = Retry(attempts = 2)).stream(AgentState("Where is my pizza?")).record()

            assertEquals("Your pizza is 5 minutes away.", run.text)
            assertEquals(1, run.events.count { it is GraphEvent.NodeRetry })
            assertEquals(listOf("model"), run.nodes)
        }

    @Test
    fun `a retry of one item leaves out the text of that item only`() =
        runTest {
            var tries = 0
            val graph =
                StateGraph<Desk> {
                    val each =
                        node(
                            "answer",
                            forEach = { listOf("kitchen", "driver") },
                            work = { _, who ->
                                if (who == "kitchen") {
                                    reportProgress(ChatEvent.TextDelta("Baking. "))
                                } else {
                                    // The first try breaks off in the middle of its answer.
                                    if (tries++ == 0) {
                                        reportProgress(ChatEvent.TextDelta("On the w"))
                                        error("The connection broke.")
                                    }
                                    reportProgress(ChatEvent.TextDelta("On the way."))
                                }
                            },
                            retry = Retry(attempts = 2, pause = Duration.ZERO),
                        ) { desk, _ -> desk }
                    START then each
                }.compile()

            val run = graph.stream(Desk()).record()

            assertEquals("Baking. On the way.", run.text)
        }

    @Test
    fun `a record has the text of a model inside a subgraph`() =
        runTest {
            val model =
                ScriptedChatModel {
                    fails("The connection broke.", partialText = "Let me")
                    calls("order_status", OrderLookup("Ana"), text = "Let me check.")
                    says("5 minutes.")
                }
            val graph =
                StateGraph<Desk> {
                    val greet = node("greet") { it }
                    val chat =
                        subgraph(
                            "chat",
                            toolAgent(model, listOf(orderStatus), retry = Retry(attempts = 2)),
                            state = { it.chat },
                            update = { desk, chat -> desk.copy(chat = chat) },
                        )
                    START then greet then chat
                }.compile()

            val run = graph.stream(Desk()).record()

            assertEquals("Let me check.5 minutes.", run.text)
            // The nodes of the subgraph are in the events, not in the path of this graph.
            assertEquals(listOf("greet", "chat"), run.nodes)
            assertEquals("5 minutes.", run.state.chat.answer)
        }

    @Test
    fun `a run that fails throws from the record too`() =
        runTest {
            val model = ScriptedChatModel { fails("API error 529: Overloaded") }

            assertFailsWith<NodeExecutionException> { toolAgent(model).stream(AgentState("Hi")).record() }
        }

    @Test
    fun `events that are not a whole run are rejected`() {
        assertFailsWith<IllegalArgumentException> { RunRecord<AgentState>(emptyList()) }
        assertFailsWith<IllegalArgumentException> { RunRecord(listOf(GraphEvent.NodeStarted(1, "model", AgentState("Hi")))) }
    }
}
