package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphObserver
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.ObservedNode
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.interrupt
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Writes down the calls it is told about, one line per call. */
private class CallRecorder : AgentObserver {
    val log = mutableListOf<String>()
    val modelCalls = mutableListOf<ObservedModelCall>()

    override fun onNodeStarted(node: ObservedNode) {
        log += "${node.name}@${node.step} started"
    }

    override fun onModelCallStarted(call: ObservedModelCall) {
        modelCalls += call
        log += "${call.node.name}@${call.node.step} asks the model"
    }

    override fun onModelCallCompleted(call: ObservedModelCall, usage: TokenUsage?) {
        log += "${call.node.name}@${call.node.step} got an answer, ${usage?.total ?: "no"} tokens"
    }

    override fun onModelCallFailed(call: ObservedModelCall, cause: Throwable) {
        log += "${call.node.name}@${call.node.step} model failed: ${cause.message}"
    }

    override fun onToolCallStarted(call: ObservedToolCall) {
        log += "${call.node.name}@${call.node.step} runs ${call.name} (${call.id})"
    }

    override fun onToolCallCompleted(call: ObservedToolCall, isError: Boolean) {
        log += "${call.node.name}@${call.node.step} ${call.name} ${if (isError) "returned an error" else "returned"}"
    }

    override fun onToolCallFailed(call: ObservedToolCall, cause: Throwable) {
        log += "${call.node.name}@${call.node.step} ${call.name} ended without a result"
    }
}

class AgentObserverTest {
    private val recorder = CallRecorder()
    private val config = GraphConfig<AgentState>(observers = listOf(recorder))

    private val orderStatus = Tool<Unit>("order_status", "Returns where the order is.") { "The driver is 5 minutes away." }
    private val broken = Tool<Unit>("broken", "Always fails.") { error("The service is down.") }

    @Test
    fun `an agent observer is told about each call to the model and each tool`() =
        runTest {
            val model =
                ScriptedModel(
                    calls(ToolCall("call-1", "order_status", noInput), ToolCall("call-2", "broken", noInput)).using(10, 5),
                    says("5 minutes.").using(20, 3),
                )

            toolAgent(model, listOf(orderStatus, broken), inOrder = true).invoke(AgentState("Where is my pizza?"), config)

            assertEquals(
                listOf(
                    "model@1 started",
                    "model@1 asks the model",
                    "model@1 got an answer, 15 tokens",
                    "tools@2 started",
                    "tools@2 runs order_status (call-1)",
                    "tools@2 order_status returned",
                    "tools@2 runs broken (call-2)",
                    "tools@2 broken returned an error",
                    "model@3 started",
                    "model@3 asks the model",
                    "model@3 got an answer, 23 tokens",
                ),
                recorder.log,
            )
            assertSame(model, recorder.modelCalls.first().model)
        }

    @Test
    fun `a streamed run reports the call once and a call to an unknown tool as an error`() =
        runTest {
            val model = ScriptedModel(calls(ToolCall("call-1", "missing", noInput)), says("Sorry."))

            toolAgent(model, listOf(orderStatus)).stream(AgentState("Hi"), config).toList()

            assertEquals(
                listOf(
                    "model@1 started",
                    "model@1 asks the model",
                    "model@1 got an answer, no tokens",
                    "tools@2 started",
                    "tools@2 runs missing (call-1)",
                    "tools@2 missing returned an error",
                    "model@3 started",
                    "model@3 asks the model",
                    "model@3 got an answer, no tokens",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a model that fails ends its call as failed`() =
        runTest {
            val model = ChatModel { throw ChatModelException("Overloaded") }

            assertFailsWith<NodeExecutionException> { toolAgent(model).invoke(AgentState("Hi"), config) }

            assertEquals(listOf("model@1 started", "model@1 asks the model", "model@1 model failed: Overloaded"), recorder.log)
        }

    @Test
    fun `a state tool that pauses the run ends its call without a result`() =
        runTest {
            val ask = StateTool<AgentState, Unit>("ask", "Asks a person.") { state, _ -> interrupt(state) }
            val graph =
                StateGraph<AgentState> {
                    START then
                        toolLoop(
                            model = ScriptedModel(calls(ToolCall("call-1", "ask", noInput))),
                            stateTools = listOf(ask),
                            messages = { it.messages },
                            append = { state, new -> state.copy(messages = state.messages + new) },
                        )
                }.compile()

            graph.invoke(AgentState("Hi"), GraphConfig(observers = listOf(recorder), threadId = "t", checkpointer = MemoryCheckpointer()))

            assertEquals("tools@2 ask ended without a result", recorder.log.last())
        }

    @Test
    fun `calls outside a node and observers of the graph alone are not told`() =
        runTest {
            val plain = object : GraphObserver {}
            val model = ScriptedModel(says("Hello!"), says("Hello again!"))

            model.chatWithProgress("Hi")
            listOf(orderStatus).execute(listOf(ToolCall("call-1", "order_status", noInput)))
            toolAgent(model).invoke(AgentState("Hi"), GraphConfig(observers = listOf(plain)))

            assertEquals(emptyList(), recorder.log)
            assertEquals(2, model.requests.size)
        }
}
