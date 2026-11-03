package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.MaxIterationsExceededException
import dev.deeptelar.telar.MemoryCheckpointer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffTeamTest {
    private fun agent(name: String, model: ChatModel, tools: List<Tool> = emptyList()) =
        TeamAgent(name, "Knows about $name.", model, tools, system = "You are $name.")

    private fun transfer(id: String, agent: String) = call(id, "transfer_to_$agent")

    @Test
    fun `the first agent answers when it hands nothing over`() =
        runTest {
            val reception = ScriptedModel(says("Hello."))

            val state = handoffTeam(listOf(agent("reception", reception), agent("billing", ScriptedModel()))).invoke(TeamState("Hi.")).state

            assertEquals("Hello.", state.answer)
            assertNull(state.agent)
            assertEquals(
                listOf("transfer_to_billing"),
                reception.requests
                    .single()
                    .tools
                    .map { it.name },
            )
        }

    @Test
    fun `the agent that takes over reads the conversation under its own instructions`() =
        runTest {
            val reception = ScriptedModel(calls(transfer("1", "billing")))
            val billing = ScriptedModel(says("Refunded."))
            val refund = Tool<Unit>("refund", "Refunds.") { "Done." }

            val state =
                handoffTeam(
                    listOf(agent("reception", reception), agent("billing", billing, listOf(refund))),
                ).invoke(TeamState("Refund?")).state

            assertEquals("Refunded.", state.answer)
            assertEquals("billing", state.agent)
            val request = billing.requests.single()
            assertEquals("You are billing.", request.system)
            assertEquals(setOf("refund", "transfer_to_reception"), request.tools.map { it.name }.toSet())
            assertEquals(ChatMessage.User("Refund?"), request.messages.first())
        }

    @Test
    fun `the agent that takes over reads the work of the other agent as notes`() =
        runTest {
            val lookup = Tool<Unit>("lookup", "Looks up.") { "Paid." }
            val reception =
                ScriptedModel(
                    ChatResponse(ChatMessage.Assistant("Let me see.", listOf(call("1", "lookup", "order" to "7")))),
                    calls(transfer("2", "billing")),
                )
            val billing = ScriptedModel(says("Refunded."))

            handoffTeam(listOf(agent("reception", reception, listOf(lookup)), agent("billing", billing))).invoke(TeamState("Refund?"))

            val read = billing.requests.single().messages
            assertEquals(ChatMessage.User("Refund?"), read[0])
            assertEquals(ChatMessage.Assistant("Let me see."), read[1])
            assertEquals(ChatMessage.User("[reception called the tool lookup with {\"order\":\"7\"}, which returned: Paid.]"), read[2])
            assertTrue("reception handed this conversation to you, billing." in read[3].text, read[3].text)
            assertEquals(4, read.size)
        }

    @Test
    fun `a tool that failed shows in the notes`() =
        runTest {
            val lookup = Tool<Unit>("lookup", "Looks up.") { error("No such order.") }
            val reception = ScriptedModel(calls(call("1", "lookup")), calls(transfer("2", "billing")))
            val billing = ScriptedModel(says("Refunded."))

            handoffTeam(listOf(agent("reception", reception, listOf(lookup)), agent("billing", billing))).invoke(TeamState("Refund?"))

            assertEquals(
                ChatMessage.User("[reception called the tool lookup with {}, which failed: No such order.]"),
                billing.requests.single().messages[1],
            )
        }

    @Test
    fun `the notes of an earlier hand-off stay when the conversation goes on`() =
        runTest {
            val lookup = Tool<Unit>("lookup", "Looks up.") { "Paid." }
            val reception = ScriptedModel(calls(call("1", "lookup")), calls(transfer("2", "billing")), says("Good day."))
            val billing = ScriptedModel(calls(call("3", "lookup")), calls(transfer("4", "reception")))

            val state =
                handoffTeam(
                    listOf(agent("reception", reception, listOf(lookup)), agent("billing", billing, listOf(lookup))),
                ).invoke(TeamState("Refund?")).state

            assertEquals(
                listOf("reception", "billing"),
                state.messages
                    .filter {
                        "called the tool" in it.text
                    }.map { it.text.drop(1).substringBefore(" ") },
            )
            assertTrue(
                reception.requests
                    .last()
                    .messages
                    .none { it is ChatMessage.ToolResult },
            )
        }

    @Test
    fun `the next message goes to the agent that has the conversation`() =
        runTest {
            val reception = ScriptedModel(calls(transfer("1", "billing")))
            val billing = ScriptedModel(says("Refunded."), says("Two days."))
            val team = handoffTeam(listOf(agent("reception", reception), agent("billing", billing)))

            val first = team.invoke(TeamState("Refund?")).state
            val second = team.invoke(first.withUserMessage("How long?")).state

            assertEquals("Two days.", second.answer)
            assertEquals(1, reception.requests.size)
        }

    @Test
    fun `a conversation can go back to an agent`() =
        runTest {
            val reception = ScriptedModel(calls(transfer("1", "billing")), says("Anything else?"))
            val billing = ScriptedModel(calls(transfer("2", "reception")))

            val state = handoffTeam(listOf(agent("reception", reception), agent("billing", billing))).invoke(TeamState("Refund?")).state

            assertEquals("Anything else?", state.answer)
            assertEquals("reception", state.agent)
        }

    @Test
    fun `a hand-off removes what only the provider of the last agent can read`() =
        runTest {
            val thinking = JsonPrimitive("thinking of reception")
            val lookup = Tool<Unit>("lookup", "Looks up.") { "Found." }
            val answer = {
                text: String,
                calls: List<ToolCall>,
                ->
                ChatResponse(ChatMessage.Assistant(text, calls, providerContent = thinking))
            }
            val reception = ScriptedModel(answer("One.", listOf(call("1", "lookup"))), answer("Two.", listOf(transfer("2", "billing"))))
            val billing = ScriptedModel(answer("Refunded.", emptyList()))

            val state =
                handoffTeam(
                    listOf(agent("reception", reception, listOf(lookup)), agent("billing", billing)),
                ).invoke(TeamState("Refund?")).state

            assertEquals(thinking, (reception.requests[1].messages[1] as ChatMessage.Assistant).providerContent)
            val read =
                billing.requests
                    .single()
                    .messages
                    .filterIsInstance<ChatMessage.Assistant>()
            assertEquals(listOf("One.", "Two."), read.map { it.text })
            assertTrue(read.all { it.providerContent == null && it.toolCalls.isEmpty() })
            assertEquals(thinking, (state.messages.last() as ChatMessage.Assistant).providerContent)
        }

    @Test
    fun `a tool called next to a transfer still runs`() =
        runTest {
            var ran = false
            val note =
                Tool<Unit>("note", "Notes.") {
                    ran = true
                    "Noted."
                }
            val reception = ScriptedModel(calls(transfer("1", "billing"), call("2", "note")))
            val billing = ScriptedModel(says("Done."))

            handoffTeam(listOf(agent("reception", reception, listOf(note)), agent("billing", billing))).invoke(TeamState("Go."))

            assertTrue(ran)
            assertTrue(
                billing.requests
                    .single()
                    .messages
                    .any { "called the tool note" in it.text },
            )
        }

    @Test
    fun `the tokens of all agents count`() =
        runTest {
            val reception = ScriptedModel(calls(transfer("1", "billing")).using(10, 1))
            val billing = ScriptedModel(says("Done.").using(20, 1))

            val state = handoffTeam(listOf(agent("reception", reception), agent("billing", billing))).invoke(TeamState("Go.")).state

            assertEquals(32, state.tokens.total)
        }

    @Test
    fun `a budget stops the team at the next agent`() =
        runTest {
            val reception = ScriptedModel(calls(transfer("1", "billing")).using(100, 0))
            val billing = ScriptedModel()

            val state =
                handoffTeam(
                    listOf(agent("reception", reception), agent("billing", billing)),
                    tokenBudget = 50,
                ).invoke(TeamState("Go.")).state

            assertTrue(billing.requests.isEmpty())
            assertNull(state.answer)
        }

    @Test
    fun `two agents that only hand over end at the limit of steps`() =
        runTest {
            val endless =
                object : ChatModel {
                    var count = 0

                    override suspend fun chat(request: ChatRequest): ChatResponse {
                        count++
                        val target = if (request.system == "You are a.") "b" else "a"
                        return calls(transfer("$count", target))
                    }
                }
            val config = GraphConfig<TeamState>("t", maxIterations = 6)

            assertFailsWith<MaxIterationsExceededException> {
                handoffTeam(listOf(agent("a", endless), agent("b", endless))).invoke(TeamState("Go."), config)
            }
        }

    @Test
    fun `a run paused before the tools of an agent continues inside it`() =
        runTest {
            var ran = 0
            val refund =
                Tool<Unit>("refund", "Refunds.") {
                    ran++
                    "Done."
                }
            val reception = ScriptedModel(calls(transfer("1", "billing")))
            val billing = ScriptedModel(calls(call("2", "refund")), says("Refunded."))
            val team = handoffTeam(listOf(agent("reception", reception), agent("billing", billing, listOf(refund))))
            val config = GraphConfig<TeamState>("t", MemoryCheckpointer(), interruptBefore = setOf("billing/tools"))

            val paused = team.invoke(TeamState("Refund?"), config)

            assertIs<GraphResult.Interrupted<TeamState>>(paused)
            assertEquals(0, ran)
            assertEquals("Refunded.", team.resume(config) { it }.state.answer)
            assertEquals(1, ran)
        }

    @Test
    fun `the graph shows the agents`() {
        val team = handoffTeam(listOf(agent("a", ScriptedModel()), agent("b", ScriptedModel())))

        assertEquals(setOf("a", "b"), team.topology.nodes.toSet())
    }

    @Test
    fun `a team needs two agents with names that work`() {
        val model = ScriptedModel()

        assertFailsWith<GraphValidationException> { handoffTeam(listOf(agent("a", model))) }
        assertFailsWith<GraphValidationException> { handoffTeam(listOf(agent("a", model), agent("a", model))) }
        assertFailsWith<GraphValidationException> { handoffTeam(listOf(agent("a", model), agent("b c", model))) }
        assertFailsWith<GraphValidationException> { handoffTeam(listOf(agent("a", model), agent("b", model)), tokenBudget = 0) }
    }
}
