package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** What happens to the tool calls of a step that runs again. */
class ToolReplayTest {
    private var charges = 0
    private var receipts = 0

    /** Fails like a crash the first time: an `Error` is not a failure of the tool, so it fails the step. */
    private var receiptCrashes = true

    private val charged = CompletableDeferred<Unit>()

    private val charge =
        Tool<Unit>("charge_card", "Charges the card.") {
            charges++
            charged.complete(Unit)
            "Charged 20 euros."
        }
    private val sendReceipt =
        Tool<Unit>("send_receipt", "Sends the receipt.") {
            charged.await()
            receipts++
            if (receiptCrashes) throw AssertionError("The process stopped.")
            "Receipt sent."
        }
    private val tools = listOf(charge, sendReceipt)

    private val chargeCall = ToolCall("call-1", "charge_card", noInput)
    private val receiptCall = ToolCall("call-2", "send_receipt", noInput)

    private fun config() = GraphConfig(checkpointer = MemoryCheckpointer<AgentState>())

    @Test
    fun `a tool that finished is not run again when its step runs again`() =
        runTest {
            for (inOrder in listOf(false, true)) {
                charges = 0
                receipts = 0
                receiptCrashes = true
                val model = ScriptedModel(calls(chargeCall, receiptCall), says("Paid, and the receipt is on its way."))
                val agent = toolAgent(model, tools, inOrder = inOrder)
                val config = config()

                assertFails { agent.invoke(AgentState("Pay my order"), config) }
                receiptCrashes = false
                val state = agent.resume(config).state

                assertEquals(1, charges, "inOrder = $inOrder")
                assertEquals(2, receipts, "inOrder = $inOrder")
                assertEquals("Paid, and the receipt is on its way.", state.answer)
                assertEquals(
                    listOf(
                        ChatMessage.ToolResult("call-1", "charge_card", "Charged 20 euros."),
                        ChatMessage.ToolResult("call-2", "send_receipt", "Receipt sent."),
                    ),
                    model.requests[1].messages.filterIsInstance<ChatMessage.ToolResult>(),
                )
            }
        }

    @Test
    fun `a tool that finished is not run again in a loop with state tools`() =
        runTest {
            receiptCrashes = true
            val crashOnce =
                StateTool<Ticket, Unit>("send_receipt", "Sends the receipt.") { ticket, _ ->
                    receipts++
                    if (receiptCrashes) throw AssertionError("The process stopped.")
                    ToolOutcome(ticket.copy(log = ticket.log + "receipt"), "Receipt sent.")
                }
            val model = ScriptedModel(calls(chargeCall, receiptCall), says("Done."))
            val graph =
                StateGraph<Ticket> {
                    START then
                        toolLoop(
                            model = model,
                            tools = listOf(charge),
                            stateTools = listOf(crashOnce),
                            messages = { it.messages },
                            append = { ticket, new -> ticket.copy(messages = ticket.messages + new) },
                        )
                }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<Ticket>())

            assertFails { graph.invoke(Ticket(listOf(ChatMessage.User("Pay my order"))), config) }
            receiptCrashes = false
            val state = graph.resume(config).state

            assertEquals(1, charges)
            assertEquals(2, receipts)
            assertEquals(listOf("receipt"), state.log)
        }

    @Test
    fun `a tool call with an error result runs again when its step runs again`() =
        runTest {
            var lookups = 0
            val flaky =
                Tool<Unit>("lookup", "Looks something up.") {
                    lookups++
                    if (lookups == 1) error("Timed out.")
                    "Found it."
                }
            val lookupCall = ToolCall("call-3", "lookup", noInput)
            val model = ScriptedModel(calls(chargeCall, lookupCall, receiptCall), says("Done."))
            val agent = toolAgent(model, listOf(charge, flaky, sendReceipt), inOrder = true)
            val config = config()

            // The lookup gets an error result, and then the receipt stops the step.
            assertFails { agent.invoke(AgentState("Pay my order"), config) }
            receiptCrashes = false
            val state = agent.resume(config).state

            assertEquals(1, charges)
            assertEquals(2, lookups)
            assertEquals(
                listOf("Charged 20 euros.", "Found it.", "Receipt sent."),
                state.messages.filterIsInstance<ChatMessage.ToolResult>().map { it.text },
            )
        }

    @Test
    fun `two calls with the same id both run`() =
        runTest {
            val model =
                ScriptedModel(calls(ToolCall("call_0", "charge_card", noInput), ToolCall("call_0", "charge_card", noInput)), says("Done."))

            val state = toolAgent(model, listOf(charge)).invoke(AgentState("Pay twice")).state

            assertEquals(2, charges)
            assertEquals("Done.", state.answer)
        }

    @Test
    fun `effect keys are the call ids, with a number for an id that repeats`() {
        val calls = listOf(chargeCall, receiptCall, chargeCall, chargeCall)

        assertEquals(
            listOf("tool-call:call-1", "tool-call:call-2", "tool-call:call-1#2", "tool-call:call-1#3"),
            effectKeys(calls),
        )
    }
}
