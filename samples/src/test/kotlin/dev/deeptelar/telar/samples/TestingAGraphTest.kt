package dev.deeptelar.telar.samples

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.serialization.CheckpointCodec
import dev.deeptelar.telar.test.ScriptedChatModel
import dev.deeptelar.telar.test.replay
import dev.deeptelar.telar.test.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests of the refund graph of `HumanInTheLoop.kt` and the help desk agent of `ToolAgent.kt`,
 * written with `graph.test { }` and `graph.replay { }` of `telar-test`. The guide "Testing" of the
 * documentation shows these tests.
 */
class TestingAGraphTest {
    private val config = GraphConfig<RefundState>(checkpointer = MemoryCheckpointer(), interruptBefore = setOf(ISSUE_REFUND))

    @Test
    fun `a refund waits for a person and is issued on approval`() =
        runTest {
            refundGraph().test(RefundState(orderId = "1001", amount = 250), config) {
                expectNode("prepare")
                expectInterruptBefore(ISSUE_REFUND) { state ->
                    assertEquals("Prepared refund of 250 for order 1001", state.log.last())
                }
                resumeWith { it.copy(approved = true) }
                expectNode(ISSUE_REFUND)
                expectFinished { state ->
                    assertEquals("Refund issued", state.log.last())
                }
            }
        }

    @Test
    fun `a refund the person rejects is not issued`() =
        runTest {
            refundGraph().test(RefundState(orderId = "1002", amount = 900), config) {
                expectNode("prepare")
                expectInterruptBefore(ISSUE_REFUND)
                resumeWith { it.copy(approved = false) }
                expectNode(ISSUE_REFUND)
                expectFinished { assertEquals("Refund rejected by reviewer", it.log.last()) }
            }
        }

    /**
     * `checkpoints/ticket-42.json` is the file a `FileCheckpointer` wrote in production: the model
     * asked for `order_status`, the tool answered, and the second call to the model failed with
     * "API error 529: Overloaded". A step that fails is not saved, so the last checkpoint is the
     * one after step 2, before the model is asked again.
     */
    @Test
    fun `the ticket that failed in production is answered when it runs again`() =
        runTest {
            val file = checkNotNull(javaClass.getResource("/checkpoints/ticket-42.json")).readText()
            val history = CheckpointCodec<AgentState>().decodeHistory("ticket-42", file)
            val model = ScriptedChatModel { says("Your pizza is 5 minutes away.") }

            helpDeskAgent(model).replay(history.last()) {
                expectNode("model")
                expectFinished { state ->
                    assertEquals("Your pizza is 5 minutes away.", state.answer)
                }
            }

            // The model was asked with the conversation of the production run, tool result included.
            val result =
                model.requests
                    .single()
                    .messages
                    .last()
            assertIs<ChatMessage.ToolResult>(result)
            assertEquals("The pizza for Ana left the oven and the driver is 5 minutes away.", result.text)
        }

    @Test
    fun `the ticket runs again from the step the tool ran in`() =
        runTest {
            val file = checkNotNull(javaClass.getResource("/checkpoints/ticket-42.json")).readText()
            val afterFirstAnswer = CheckpointCodec<AgentState>().decodeHistory("ticket-42", file).first { it.step == 1 }
            val model = ScriptedChatModel { says("Your pizza is 5 minutes away.") }

            // The checkpoint after step 1 has the model's tool call; the real tool runs it again in step 2.
            helpDeskAgent(model).replay(afterFirstAnswer) {
                expectNodes("tools", "model")
                expectFinished()
            }
        }
}
