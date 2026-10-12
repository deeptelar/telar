package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.Retry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** A model whose provider fails the calls with the numbers in [failing], counted from 1. */
private class UnsteadyModel(
    private val failing: Set<Int>,
    vararg replies: ChatResponse,
) : ChatModel {
    private val replies = ArrayDeque(replies.toList())
    var calls = 0
        private set

    private fun answer(): ChatResponse {
        if (++calls in failing) throw ChatModelException("API error 529: Overloaded")
        return replies.removeFirst()
    }

    override suspend fun chat(request: ChatRequest): ChatResponse = answer()

    /** Writes the half of an answer before it fails, as a connection that breaks does. */
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
        flow {
            emit(ChatEvent.TextDelta("Here "))
            val response = answer()
            emit(ChatEvent.TextDelta("it is."))
            emit(ChatEvent.Completed(response))
        }
}

class ModelRetryTest {
    private val lookup = Tool<Unit>("lookup", "Looks something up.") { "Found." }

    @Test
    fun `an agent with a retry asks the model again after a failed call`() =
        runTest {
            val model = UnsteadyModel(failing = setOf(1, 2), says("Here it is."))

            val state = toolAgent(model, retry = Retry(attempts = 3)).invoke(AgentState("Find it.")).state

            assertEquals("Here it is.", state.answer)
            assertEquals(3, model.calls)
        }

    @Test
    fun `an agent without a retry fails at the first failed call`() =
        runTest {
            val model = UnsteadyModel(failing = setOf(1), says("Here it is."))

            val exception = assertFailsWith<NodeExecutionException> { toolAgent(model).invoke(AgentState("Find it.")) }

            assertEquals("model", exception.nodeName)
            assertIs<ChatModelException>(exception.cause)
            assertEquals(1, model.calls)
        }

    @Test
    fun `a failed call leaves nothing in the conversation and nothing in the count`() =
        runTest {
            val call = ToolCall("call-1", "lookup", noInput)
            val model = UnsteadyModel(failing = setOf(2), calls(call).using(100, 20), says("Here it is.").using(150, 30))

            val state = toolAgent(model, listOf(lookup), retry = Retry(attempts = 2)).invoke(AgentState("Find it.")).state

            assertEquals(3, model.calls)
            assertEquals(
                listOf("User", "Assistant", "ToolResult", "Assistant"),
                state.messages.map { it::class.simpleName },
            )
            assertEquals(TokenUsage(250, 50), state.tokens)
        }

    @Test
    fun `an agent that used all of its tries fails with the number of attempts`() =
        runTest {
            val model = UnsteadyModel(failing = setOf(1, 2, 3))

            val exception =
                assertFailsWith<NodeExecutionException> { toolAgent(model, retry = Retry(attempts = 3)).invoke(AgentState("Find it.")) }

            assertEquals(3, exception.attempts)
            assertEquals("Node 'model' failed after 3 attempts: API error 529: Overloaded", exception.message)
        }

    @Test
    fun `a streamed run says when the text of the model starts again`() =
        runTest {
            val model = UnsteadyModel(failing = setOf(1), says("Here it is."))
            val shown = StringBuilder()

            val events = toolAgent(model, retry = Retry(attempts = 2)).stream(AgentState("Find it.")).toList()
            events.forEach { event ->
                if (event is GraphEvent.NodeRetry) shown.clear()
                event.textDelta?.let(shown::append)
            }

            assertEquals("Here it is.", shown.toString())
            assertEquals(listOf("Here ", "Here ", "it is."), events.mapNotNull { it.textDelta })
            assertIs<ChatModelException>(events.filterIsInstance<GraphEvent.NodeRetry<AgentState>>().single().cause)
        }

    @Test
    fun `a tool loop passes its retry to the model node only`() =
        runTest {
            var toolRuns = 0
            val broken =
                Tool<Unit>("lookup", "Looks something up.") {
                    toolRuns++
                    error("The index is gone.")
                }
            val model = UnsteadyModel(failing = setOf(1), calls(ToolCall("call-1", "lookup", noInput)), says("I could not find it."))

            val state = toolAgent(model, listOf(broken), retry = Retry(attempts = 2)).invoke(AgentState("Find it.")).state

            // The tool failed once and was not run again: its failure went to the model as a result.
            assertEquals(1, toolRuns)
            assertEquals("I could not find it.", state.answer)
        }
}
