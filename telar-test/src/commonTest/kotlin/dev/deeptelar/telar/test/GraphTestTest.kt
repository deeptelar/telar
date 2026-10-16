package dev.deeptelar.telar.test

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.interrupt
import dev.deeptelar.telar.serialization.CheckpointCodec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphTestTest {
    @Serializable
    data class Refund(
        val id: String,
        val amount: Int,
        val status: String = "new",
        val approved: Boolean = false,
    )

    /** Checks a refund, and pays it. A refund of more than 1000 waits in `pay` until a person approves it. */
    private val refunds: CompiledGraph<Refund> =
        StateGraph<Refund> {
            val check = node("check") { it.copy(status = "checked") }
            val pay =
                node("pay") {
                    if (it.amount > 1000 && !it.approved) interrupt(it)
                    it.copy(status = "paid")
                }
            START then check then pay then END
        }.compile()

    @Test
    fun `a high value refund pauses before pay and completes on approval`() =
        runTest {
            refunds.test(Refund("1001", amount = 5000)) {
                expectNode("check")
                expectInterruptBefore("pay") { state ->
                    assertEquals("checked", state.status)
                }
                resumeWith { it.copy(approved = true) }
                expectNode("pay")
                expectFinished { state ->
                    assertEquals("paid", state.status)
                }
            }
        }

    @Test
    fun `a small refund is paid without a pause`() =
        runTest {
            refunds.test(Refund("1002", amount = 20)) {
                expectNodes("check", "pay")
                val state = expectFinished()
                assertEquals("paid", state.status)
            }
        }

    @Test
    fun `a pause that the config asks for is checked the same way`() =
        runTest {
            val config = GraphConfig<Refund>(checkpointer = MemoryCheckpointer(), interruptBefore = setOf("pay"))

            refunds.test(Refund("1002", amount = 20), config) {
                assertEquals("checked", expectNode("check").status)
                expectInterruptBefore("pay")
                resumeWith()
                expectNode("pay")
                expectFinished()
            }
        }

    @Test
    fun `a node that is not the next one fails with the path of the run`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    refunds.test(Refund("1002", amount = 20)) {
                        expectNode("pay")
                    }
                }

            assertEquals(
                "Expected node \"pay\" next, but the next was node \"check\" in step 1. " +
                    "The run so far: [node \"check\" in step 1], [node \"pay\" in step 2], [the end of the run]",
                error.message,
            )
        }

    @Test
    fun `a run that did more than the test checked fails`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    refunds.test(Refund("1002", amount = 20)) {
                        expectNode("check")
                    }
                }

            assertEquals(
                "The test did not check everything the run did. Not checked: node \"pay\" in step 2, the end of the run. " +
                    "The run so far: node \"check\" in step 1, [node \"pay\" in step 2], [the end of the run]",
                error.message,
            )
        }

    @Test
    fun `a pause is not the end of the run`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    refunds.test(Refund("1001", amount = 5000)) {
                        expectNode("check")
                        expectFinished()
                    }
                }

            assertTrue(error.message!!.startsWith("Expected the end of the run next, but the next was a pause before [pay]."))
        }

    @Test
    fun `a pause before another node fails`() =
        runTest {
            assertFailsWith<AssertionError> {
                refunds.test(Refund("1001", amount = 5000)) {
                    expectNode("check")
                    expectInterruptBefore("check")
                }
            }
        }

    @Test
    fun `nothing is next after the end`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    refunds.test(Refund("1002", amount = 20)) {
                        expectNodes("check", "pay")
                        expectFinished()
                        expectNode("pay")
                    }
                }

            assertTrue(error.message!!.startsWith("Expected node \"pay\" next, but the next was nothing: the run had stopped."))
        }

    @Test
    fun `resumeWith needs a checked pause`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    refunds.test(Refund("1001", amount = 5000)) {
                        expectNode("check")
                        resumeWith { it.copy(approved = true) }
                    }
                }

            assertTrue(
                error.message!!.startsWith("resumeWith continues a paused run after expectInterruptBefore, but the next was a pause"),
            )
        }

    @Test
    fun `the checks after a resume are about what the run did after it`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    refunds.test(Refund("1001", amount = 5000)) {
                        expectNode("check")
                        expectInterruptBefore("pay")
                        resumeWith()
                        expectNode("pay")
                    }
                }

            // Without the approval, pay pauses again.
            assertEquals(
                "Expected node \"pay\" next, but the next was a pause before [pay]. " +
                    "The run so far: node \"check\" in step 1, a pause before [pay], [a pause before [pay]]",
                error.message,
            )
        }

    @Serializable
    data class Research(
        val notes: List<String> = emptyList(),
    )

    private val research: CompiledGraph<Research> =
        StateGraph<Research> {
            val web = node("web", { "web" }) { state, note -> state.copy(notes = state.notes + note) }
            val papers = node("papers", { "papers" }) { state, note -> state.copy(notes = state.notes + note) }
            val summarize = node("summarize") { it.copy(notes = listOf(it.notes.sorted().joinToString(" + "))) }
            START then web then summarize
            START then papers then summarize
            summarize then END
        }.compile()

    @Test
    fun `a step of parallel nodes is checked in any order`() =
        runTest {
            research.test(Research()) {
                val state = expectStep("papers", "web")
                assertEquals(setOf("web", "papers"), state.notes.toSet())
                expectStep("summarize")
                expectFinished { assertEquals(listOf("papers + web"), it.notes) }
            }
        }

    @Test
    fun `a step of parallel nodes is checked after a node`() =
        runTest {
            val planned =
                StateGraph<Research> {
                    val plan = node("plan") { it.copy(notes = listOf("plan")) }
                    val web = node("web", { "web" }) { state, note -> state.copy(notes = state.notes + note) }
                    val papers = node("papers", { "papers" }) { state, note -> state.copy(notes = state.notes + note) }
                    START then plan
                    plan then web then END
                    plan then papers then END
                }.compile()

            planned.test(Research()) {
                expectNode("plan")
                expectStep("web", "papers") { assertEquals(listOf("plan", "web", "papers"), it.notes) }
                expectFinished()
            }
        }

    @Test
    fun `a step with other nodes fails`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    research.test(Research()) {
                        expectStep("web")
                    }
                }

            assertTrue(error.message!!.startsWith("Expected a step with [web] next, but the next was step 1 with [web, papers]."))
        }

    @Test
    fun `a step is not the end of the run`() =
        runTest {
            assertFailsWith<AssertionError> {
                research.test(Research()) {
                    expectStep("web", "papers")
                    expectStep("summarize")
                    expectStep("summarize")
                }
            }
        }

    private val broken: CompiledGraph<Refund> =
        StateGraph<Refund> {
            val check = node("check") { it.copy(status = "checked") }
            val pay = node("pay") { throw IllegalStateException("The payment service is down.") }
            START then check then pay then END
        }.compile()

    @Test
    fun `a failure is checked after the nodes before it`() =
        runTest {
            broken.test(Refund("1003", amount = 20)) {
                expectNode("check")
                val failure = expectFailure { assertIs<NodeExecutionException>(it) }
                assertEquals("The payment service is down.", failure.cause?.message)
            }
        }

    @Test
    fun `a failure that the test does not expect fails it with the failure as its cause`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    broken.test(Refund("1003", amount = 20)) {
                        expectNodes("check", "pay")
                    }
                }

            assertIs<NodeExecutionException>(error.cause)
            assertTrue(error.message!!.startsWith("Expected node \"pay\" next, but the next was a failure: "))
        }

    @Test
    fun `a failure that is not checked fails the test`() =
        runTest {
            val error =
                assertFailsWith<AssertionError> {
                    broken.test(Refund("1003", amount = 20)) {
                        expectNode("check")
                    }
                }

            assertIs<NodeExecutionException>(error.cause)
        }

    @Test
    fun `a run that does not fail is not a failure`() =
        runTest {
            assertFailsWith<AssertionError> {
                refunds.test(Refund("1002", amount = 20)) {
                    expectNode("check")
                    expectFailure()
                }
            }
        }

    @Test
    fun `an assertion of a scripted model fails the test as it is`() =
        runTest {
            val model = ScriptedChatModel { }

            val error =
                assertFailsWith<AssertionError> {
                    toolAgent(model).test(AgentState("Hi")) {
                        expectFailure()
                    }
                }

            assertTrue(error.message!!.startsWith("The script of this model has 0 answers"))
        }

    @Serializable
    data class OrderLookup(
        val customer: String,
    )

    @Test
    fun `a failed run is replayed from its last checkpoint with a scripted model`() =
        runTest {
            // A run in production: the model asked for a tool, the tool answered, and the second call failed.
            val production =
                ScriptedChatModel {
                    calls("order_status", OrderLookup("Ana"))
                    fails("API error 529: Overloaded")
                }
            val tool = Tool<OrderLookup>("order_status", "Where an order is.") { "5 minutes away." }
            val saved = MemoryCheckpointer<AgentState>()
            val codec = CheckpointCodec<AgentState>()
            toolAgent(
                production,
                listOf(tool),
            ).test(AgentState("Where is my pizza?"), GraphConfig(threadId = "ticket-42", checkpointer = saved)) {
                expectNodes("model", "tools")
                expectFailure()
            }
            // What a FileCheckpointer would have written, read back in a test.
            val file = saved.history("ticket-42").fold(null as String?) { stored, checkpoint -> codec.append(stored, checkpoint) }!!
            val history = codec.decodeHistory("ticket-42", file)
            assertEquals(listOf(0, 1, 2), history.map { it.step })

            val model = ScriptedChatModel { says("Your pizza is 5 minutes away.") }
            toolAgent(model, listOf(tool)).replay(history.last()) {
                expectNode("model")
                expectFinished { assertEquals("Your pizza is 5 minutes away.", it.answer) }
            }

            // The model was asked with the conversation of the production run.
            val result =
                model.requests
                    .single()
                    .messages
                    .last()
            assertIs<ChatMessage.ToolResult>(result)
            assertEquals("5 minutes away.", result.text)
        }

    @Test
    fun `a replay starts from an earlier step with a changed state`() =
        runTest {
            val config = GraphConfig<Refund>(threadId = "refund-1001", checkpointer = MemoryCheckpointer())
            refunds.test(Refund("1001", amount = 5000), config) {
                expectNode("check")
                expectInterruptBefore("pay")
            }
            val beforePay = refunds.history(config).last()

            refunds.replay(beforePay, update = { it.copy(approved = true) }) {
                expectNode("pay")
                expectFinished { assertEquals("paid", it.status) }
            }
            refunds.replay(beforePay.copy(state = beforePay.state.copy(amount = 10))) {
                expectNode("pay")
                expectFinished()
            }
        }

    @Test
    fun `a replay keeps the checkpointer of its config`() =
        runTest {
            val config = GraphConfig<Refund>(threadId = "refund-1002", checkpointer = MemoryCheckpointer())
            refunds.test(Refund("1002", amount = 20), config) {
                expectNodes("check", "pay")
                expectFinished()
            }
            val replayed = config.copy(threadId = "refund-1002-again")

            refunds.replay(refunds.history(config).first(), replayed) {
                expectNodes("check", "pay")
                val state = expectFinished()
                assertSame(state, refunds.lastResult(replayed)?.state)
            }
        }
}
