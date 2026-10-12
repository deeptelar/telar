package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

data class Research(
    val web: List<ChatMessage> = emptyList(),
    val docs: List<ChatMessage> = emptyList(),
    val tokens: TokenUsage = TokenUsage(),
    val rounds: Int = 0,
    val report: String = "",
)

class TokenCountTest {
    private val lookup = Tool<Unit>("lookup", "Looks something up.") { "Found." }

    private fun lookUp(id: String) = ToolCall(id, "lookup", noInput)

    @Test
    fun `token usages add up and no usage adds nothing`() {
        assertEquals(TokenUsage(0, 0), TokenUsage())
        assertEquals(TokenUsage(50, 7), TokenUsage(40, 5) + TokenUsage(10, 2))
        assertEquals(TokenUsage(40, 5), TokenUsage(40, 5) + null)
        assertEquals(45, TokenUsage(40, 5).total)
    }

    @Test
    fun `an agent counts the tokens of every answer of the model`() =
        runTest {
            val model = ScriptedModel(calls(lookUp("call-1")).using(100, 20), says("Here it is.").using(150, 30))

            val state = toolAgent(model, listOf(lookup)).invoke(AgentState("Find it.")).state

            assertEquals(TokenUsage(inputTokens = 250, outputTokens = 50), state.tokens)
            assertEquals("Here it is.", state.answer)
        }

    @Test
    fun `an answer without a usage adds nothing to the count`() =
        runTest {
            val model = ScriptedModel(calls(lookUp("call-1")).using(100, 20), says("Here it is."))

            assertEquals(TokenUsage(100, 20), toolAgent(model, listOf(lookup)).invoke(AgentState("Find it.")).state.tokens)
        }

    @Test
    fun `a conversation that continues keeps counting`() =
        runTest {
            val agent = toolAgent(ScriptedModel(says("Hello!").using(10, 2), says("Fine.").using(20, 3)))

            val first = agent.invoke(AgentState("Hi")).state
            val second = agent.invoke(first.withUserMessage("How are you?")).state

            assertEquals(TokenUsage(10, 2), first.tokens)
            assertEquals(TokenUsage(30, 5), second.tokens)
        }

    @Test
    fun `an agent that used its budget is not asked again and ends without an answer`() =
        runTest {
            val model = ScriptedModel(calls(lookUp("call-1")).using(100, 20), calls(lookUp("call-2")).using(150, 30), says("Never said."))

            val result = toolAgent(model, listOf(lookup), tokenBudget = 300).invoke(AgentState("Find it."))

            // 120 tokens are below the budget, 300 are not: the model is asked twice.
            assertEquals(2, model.requests.size)
            assertEquals(TokenUsage(250, 50), result.state.tokens)
            assertNull(result.state.answer)
            // The conversation is whole: the tools of the last answer ran.
            val last = assertIs<ChatMessage.ToolResult>(result.state.messages.last())
            assertEquals("call-2", last.toolCallId)
            assertEquals(emptyList(), result.state.messages.pendingToolCalls())
        }

    @Test
    fun `an agent that stays below its budget answers`() =
        runTest {
            val model = ScriptedModel(calls(lookUp("call-1")).using(100, 20), says("Here it is.").using(150, 30))

            val state = toolAgent(model, listOf(lookup), tokenBudget = 1000).invoke(AgentState("Find it.")).state

            assertEquals("Here it is.", state.answer)
        }

    @Test
    fun `the last answer of an agent is kept when it reaches the budget`() =
        runTest {
            val model = ScriptedModel(says("Here it is.").using(500, 100))

            val state = toolAgent(model, tokenBudget = 300).invoke(AgentState("Find it.")).state

            assertEquals("Here it is.", state.answer)
            assertEquals(600, state.tokens.total)
        }

    @Test
    fun `a conversation that starts above its budget does not ask the model`() =
        runTest {
            val model = ScriptedModel(says("Hello!").using(200, 150), says("Never said."))
            val agent = toolAgent(model, tokenBudget = 300)
            val first = agent.invoke(AgentState("Hi")).state

            val second = agent.invoke(first.withUserMessage("How are you?")).state

            assertEquals(1, model.requests.size)
            assertNull(second.answer)
            assertEquals(ChatMessage.User("How are you?"), second.messages.last())

            // With the count set back, the next question has the whole budget again.
            val third = agent.invoke(second.copy(tokens = TokenUsage())).state
            assertEquals("Never said.", third.answer)
        }

    @Test
    fun `a budget below one token is rejected`() {
        assertFailsWith<GraphValidationException> { toolAgent(ScriptedModel(), tokenBudget = 0) }
    }

    @Test
    fun `an agent state is saved with its count and read without one`() {
        val state = AgentState(listOf(ChatMessage.User("Hi")), TokenUsage(inputTokens = 12, outputTokens = 3))

        assertEquals(state, Json.decodeFromString(AgentState.serializer(), Json.encodeToString(AgentState.serializer(), state)))
        // A state saved before the count existed.
        val saved = """{"messages":[{"type":"user","text":"Hi"}]}"""
        assertEquals(TokenUsage(), Json.decodeFromString(AgentState.serializer(), saved).tokens)
    }

    @Test
    fun `a tool loop counts into a state of its own and stops where the state says`() =
        runTest {
            val model = ScriptedModel(calls(lookUp("call-1")).using(100, 20), calls(lookUp("call-2")).using(150, 30), says("Never said."))
            val graph =
                StateGraph<Research> {
                    val write = node("write") { it.copy(report = "Stopped after ${it.tokens.total} tokens.") }
                    val agent =
                        toolLoop(
                            model = model,
                            tools = listOf(lookup),
                            messages = { it.web },
                            append = { research, new -> research.copy(web = research.web + new) },
                            firstMessage = { "Find it." },
                            then = write,
                            countTokens = { research, used -> research.copy(tokens = research.tokens + used) },
                            stopWhen = { research -> research.tokens.total >= 300 },
                        )
                    START then agent
                }.compile()

            val state = graph.invoke(Research()).state

            assertEquals(2, model.requests.size)
            assertEquals(TokenUsage(250, 50), state.tokens)
            assertEquals("Stopped after 300 tokens.", state.report)
        }

    @Test
    fun `a tool loop stops for any reason the state gives`() =
        runTest {
            val model = ScriptedModel(calls(lookUp("call-1")), calls(lookUp("call-2")), calls(lookUp("call-3")), says("Never said."))
            val graph =
                StateGraph<Research> {
                    START then
                        toolLoop(
                            model = model,
                            tools = listOf(lookup),
                            messages = { it.web },
                            append = { research, new ->
                                research.copy(
                                    web = research.web + new,
                                    rounds = research.rounds + new.count { it is ChatMessage.Assistant },
                                )
                            },
                            stopWhen = { research -> research.rounds >= 2 },
                        )
                }.compile()

            val state = graph.invoke(Research(web = listOf(ChatMessage.User("Find it.")))).state

            assertEquals(2, model.requests.size)
            assertEquals(2, state.rounds)
            assertEquals(TokenUsage(), state.tokens)
        }

    @Test
    fun `a loop that is stopped before its first call stores nothing`() =
        runTest {
            val model = ScriptedModel(says("Never said."))
            val graph =
                StateGraph<Research> {
                    START then
                        toolLoop(
                            model = model,
                            messages = { it.web },
                            append = { research, new -> research.copy(web = research.web + new, rounds = research.rounds + 1) },
                            firstMessage = { "Find it." },
                            stopWhen = { true },
                        )
                }.compile()

            assertEquals(Research(), graph.invoke(Research()).state)
            assertEquals(emptyList(), model.requests)
        }

    @Test
    fun `two loops in parallel branches add to the same count`() =
        runTest {
            val web = ScriptedModel(calls(lookUp("call-1")).using(100, 20), says("From the web.").using(150, 30))
            val docs = ScriptedModel(says("From the docs.").using(40, 8))
            val graph =
                StateGraph<Research> {
                    val searchWeb =
                        toolLoop(
                            model = web,
                            tools = listOf(lookup),
                            messages = { it.web },
                            append = { research, new -> research.copy(web = research.web + new) },
                            firstMessage = { "Search the web." },
                            countTokens = { research, used -> research.copy(tokens = research.tokens + used) },
                            modelNode = "web",
                            toolsNode = "web-tools",
                        )
                    val searchDocs =
                        toolLoop(
                            model = docs,
                            messages = { it.docs },
                            append = { research, new -> research.copy(docs = research.docs + new) },
                            firstMessage = { "Search the documents." },
                            countTokens = { research, used -> research.copy(tokens = research.tokens + used) },
                            modelNode = "docs",
                            toolsNode = "docs-tools",
                        )
                    START then searchWeb
                    START then searchDocs
                }.compile()

            val state = graph.invoke(Research()).state

            assertEquals(TokenUsage(inputTokens = 290, outputTokens = 58), state.tokens)
            assertEquals("From the web.", state.web.last().text)
            assertEquals("From the docs.", state.docs.last().text)
        }
}
