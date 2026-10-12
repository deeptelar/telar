package dev.deeptelar.telar.samples

import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.test.ScriptedChatModel
import dev.deeptelar.telar.test.ScriptedDecisionModel
import dev.deeptelar.telar.test.record
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of the help desk agent of `ToolAgent.kt` and of the router of `DecisionRouter.kt`, written
 * with `telar-test`: the test writes down what the model answers, and checks what the graph does
 * with it. The guide "Testing" of the documentation shows these tests.
 */
class TestingAnAgentTest {
    @Test
    fun `the agent looks up the order and answers with what it found`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls("order_status", OrderLookup(customer = "Ana"))
                    says("Your pizza is 5 minutes away.")
                }

            val run = helpDeskAgent(model).stream(AgentState("Where is my pizza?")).record()

            assertEquals(listOf("model", "tools", "model"), run.nodes)
            assertEquals("Your pizza is 5 minutes away.", run.state.answer)
            // What the model was asked the second time: the result of the tool is the last message.
            val result = model.requests[1].messages.last() as ChatMessage.ToolResult
            assertEquals("The pizza for Ana left the oven and the driver is 5 minutes away.", result.text)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `a tool that fails tells the model and the run goes on`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls("menu_price", MenuLookup(item = "tiramisu"))
                    says("Sorry, we do not sell tiramisu.")
                }

            val state = helpDeskAgent(model).invoke(AgentState("How much is a tiramisu?")).state

            val result = state.messages.filterIsInstance<ChatMessage.ToolResult>().single()
            assertTrue(result.isError)
            assertEquals("Sorry, we do not sell tiramisu.", state.answer)
        }

    @Test
    fun `an agent that used its budget stops before the next answer`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls("order_status", OrderLookup(customer = "Ana"), usage = TokenUsage(inputTokens = 900, outputTokens = 200))
                    says("Never said.")
                }
            val agent = toolAgent(model, tools = listOf(orderStatus), tokenBudget = 1000)

            val state = agent.invoke(AgentState("Where is my pizza?")).state

            assertNull(state.answer)
            assertEquals(1100, state.tokens.total)
            assertEquals(1, model.remaining)
        }

    @Test
    fun `the screen shows the text of the model while it is written`() =
        runTest {
            val model = ScriptedChatModel { says("Thanks for writing to us.") }

            val run = helpDeskAgent(model).stream(AgentState("Hello")).record()

            assertEquals("Thanks for writing to us.", run.text)
        }

    @Test
    fun `an email the model is not sure about goes to a person`() =
        runTest {
            val model =
                ScriptedDecisionModel {
                    chooses("refund")
                    chooses("technical", confidence = 0.3)
                }
            val support = routedSupport(model)

            val sure = support.invoke(SupportEmail(sender = "Ana", body = "I want my money back.")).state
            val unsure = support.invoke(SupportEmail(sender = "Ben", body = "Hm.")).state

            assertEquals(Category.REFUND, sure.category)
            assertEquals(Category.ESCALATION, unsure.category)
            assertEquals(
                "Hm.",
                model.requests[1]
                    .state
                    .toString()
                    .trim('"'),
            )
        }
}
