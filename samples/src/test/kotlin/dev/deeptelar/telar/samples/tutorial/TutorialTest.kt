package dev.deeptelar.telar.samples.tutorial

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.MaxIterationsExceededException
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.textDelta
import dev.deeptelar.telar.samples.tutorial.level1.greeter
import dev.deeptelar.telar.samples.tutorial.level5.PAY
import dev.deeptelar.telar.samples.tutorial.level6.pretendModel
import dev.deeptelar.telar.samples.tutorial.level6.replyDesk
import dev.deeptelar.telar.samples.tutorial.level6.ticketDesk
import dev.deeptelar.telar.samples.tutorial.level7.endlessLoop
import dev.deeptelar.telar.samples.tutorial.level7.forgottenArrow
import dev.deeptelar.telar.samples.tutorial.level7.missingReducer
import dev.deeptelar.telar.samples.tutorial.level7.misspelledNode
import dev.deeptelar.telar.states
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import dev.deeptelar.telar.samples.tutorial.level1.Ticket as Ticket1
import dev.deeptelar.telar.samples.tutorial.level1.helpDesk as helpDesk1
import dev.deeptelar.telar.samples.tutorial.level2.Ticket as Ticket2
import dev.deeptelar.telar.samples.tutorial.level2.helpDesk as helpDesk2
import dev.deeptelar.telar.samples.tutorial.level3.Ticket as Ticket3
import dev.deeptelar.telar.samples.tutorial.level3.helpDesk as helpDesk3
import dev.deeptelar.telar.samples.tutorial.level4.Ticket as Ticket4
import dev.deeptelar.telar.samples.tutorial.level4.describe as describe4
import dev.deeptelar.telar.samples.tutorial.level4.helpDesk as helpDesk4
import dev.deeptelar.telar.samples.tutorial.level5.Ticket as Ticket5
import dev.deeptelar.telar.samples.tutorial.level5.helpDesk as helpDesk5
import dev.deeptelar.telar.samples.tutorial.level6.Ticket as Ticket6
import dev.deeptelar.telar.samples.tutorial.level6.describe as describe6
import dev.deeptelar.telar.samples.tutorial.level6.helpDesk as helpDesk6
import dev.deeptelar.telar.samples.tutorial.level7.Ticket as Ticket7
import dev.deeptelar.telar.samples.tutorial.level7.helpDesk as helpDesk7
import dev.deeptelar.telar.samples.tutorial.level8.PAY as PAY8
import dev.deeptelar.telar.samples.tutorial.level8.Ticket as Ticket8
import dev.deeptelar.telar.samples.tutorial.level8.helpDesk as helpDesk8

/** Keeps the code and the output shown in the tutorial in `docs/tutorial/` true. */
class TutorialTest {
    @Test
    fun `level 1 greets the customer`() =
        runTest {
            val state = greeter().invoke(Ticket1("Ana", "Where is my pizza?")).state

            assertEquals("Hi Ana, thanks for writing to Pixel Pizza!", state.reply)
        }

    @Test
    fun `level 1 passes the topic from one node to the next`() =
        runTest {
            val state = helpDesk1().invoke(Ticket1("Ana", "Where is my pizza?")).state

            assertEquals("delivery", state.topic)
            assertEquals("Hi Ana, we got your delivery question.", state.reply)
        }

    @Test
    fun `level 2 takes a different path for each topic`() =
        runTest {
            val graph = helpDesk2()
            val replies =
                listOf(
                    "Where is my pizza?",
                    "I want a refund",
                    "Do you sell salad?",
                ).map { graph.invoke(Ticket2("Ana", it)).state.reply }

            assertEquals(
                listOf(
                    "Your pizza left the oven and is on its way.",
                    "We are sorry. Your money is on its way back.",
                    "Thanks for your message. A human will reply soon.",
                ),
                replies,
            )
        }

    @Test
    fun `level 2 draws the diagram shown in the guide Inspecting a graph`() {
        val config = GraphConfig<Ticket2>(checkpointer = MemoryCheckpointer(), interruptBefore = setOf("refund"))
        val diagram =
            helpDesk2().toMermaid(
                config = config,
                edgeLabels = mapOf(("read" to "track") to "delivery", ("read" to "refund") to "refund"),
            )

        assertEquals(
            """
            stateDiagram-v2
                state "read" as n0
                state "track" as n1
                state "⏸ refund" as n2
                state "answer" as n3
                [*] --> n0
                n0 --> n1 : delivery
                n0 --> n2 : refund
                n0 --> n3 : ?
                n1 --> [*]
                n2 --> [*]
                n3 --> [*]
                classDef interrupt fill:#fff4e5,stroke:#e8590c,stroke-width:2px,stroke-dasharray:4 3
                class n2 interrupt
            """.trimIndent(),
            diagram,
        )
    }

    @Test
    fun `level 3 rewrites the reply until it passes the check`() =
        runTest {
            val state = helpDesk3().invoke(Ticket3("Ana", "My pizza is late!")).state

            assertEquals(3, state.attempts)
            assertEquals("Sorry Ana, your pizza is late. It arrives in 10 minutes.", state.reply)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `level 4 runs both lookups at the same time`() =
        runTest {
            val state = helpDesk4().invoke(Ticket4("Ana", "Where is my pizza?")).state

            assertEquals("Hi Ana, your pizza left the oven and the driver is 5 minutes away.", state.reply)
            assertEquals(1_000, currentTime)
        }

    @Test
    fun `level 4 reports every node and step`() =
        runTest {
            val events = helpDesk4().stream(Ticket4("Ana", "Where is my pizza?")).map { describe4(it) }.toList()

            assertEquals(
                listOf(
                    "step 1: kitchen started",
                    "step 1: driver started",
                    "step 1: kitchen finished",
                    "step 1: driver finished",
                    "step 1 done, facts so far: 2",
                    "step 2: answer started",
                    "step 2: answer finished",
                    "step 2 done, facts so far: 2",
                    "finished: Hi Ana, your pizza left the oven and the driver is 5 minutes away.",
                ),
                events,
            )
            assertEquals(
                2,
                helpDesk4()
                    .stream(Ticket4("Ana", "Where is my pizza?"))
                    .states()
                    .toList()
                    .size,
            )
        }

    @Test
    fun `level 5 pauses before paying and continues with the decision`() =
        runTest {
            val graph = helpDesk5()
            val config = GraphConfig(threadId = "ticket-42", checkpointer = MemoryCheckpointer<Ticket5>(), interruptBefore = setOf(PAY))

            val paused = graph.invoke(Ticket5("Ana", "My pizza arrived cold. I want a refund."), config)
            assertIs<GraphResult.Interrupted<Ticket5>>(paused)
            assertEquals(listOf(PAY), paused.nextNodes)
            assertEquals(12, paused.state.refund)
            assertEquals(paused, graph.lastResult(config))

            val finished = graph.resume(config) { it.copy(approved = true) }
            assertIs<GraphResult.Completed<Ticket5>>(finished)
            assertEquals("Sorry Ana! We sent you 12 euros.", finished.state.reply)
        }

    @Test
    fun `level 5 keeps tickets apart by thread id`() =
        runTest {
            val graph = helpDesk5()
            val checkpointer = MemoryCheckpointer<Ticket5>()
            val ana = GraphConfig(threadId = "ticket-42", checkpointer = checkpointer, interruptBefore = setOf(PAY))
            val ben = ana.copy(threadId = "ticket-43")

            graph.invoke(Ticket5("Ana", "Cold pizza"), ana)
            graph.invoke(Ticket5("Ben", "Wrong pizza"), ben)

            assertEquals("Sorry Ben, we cannot refund this order.", graph.resume(ben).state.reply)
            assertEquals("Sorry Ana! We sent you 12 euros.", graph.resume(ana) { it.copy(approved = true) }.state.reply)
        }

    @Test
    fun `level 6 stores the reply of the model`() =
        runTest {
            val state = replyDesk(pretendModel).invoke(Ticket6("Ana", "Where is my pizza?")).state

            assertEquals("Thanks for your message! We are looking into it.", state.reply)
        }

    @Test
    fun `level 6 lets the model look up what the customer asks for`() =
        runTest {
            val state = helpDesk6(pretendModel).invoke(AgentState("I'm Ana. Where is my pizza, and how much is a cola?")).state

            assertEquals(
                listOf(
                    "customer: I'm Ana. Where is my pizza, and how much is a cola?",
                    "model asks for: order_status {\"customer\":\"Ana\"}, menu_price {\"item\":\"cola\"}",
                    "order_status: The pizza for Ana left the oven and the driver is 5 minutes away.",
                    "menu_price: One cola costs 2 euros.",
                    "model: The pizza for Ana left the oven and the driver is 5 minutes away. One cola costs 2 euros.",
                ),
                state.messages.map { describe6(it) },
            )
            assertEquals("The pizza for Ana left the oven and the driver is 5 minutes away. One cola costs 2 euros.", state.answer)
        }

    @Test
    fun `level 6 tells the model when a tool fails`() =
        runTest {
            val state = helpDesk6(pretendModel).invoke(AgentState("Do you sell tiramisu?")).state

            val result = state.messages.filterIsInstance<ChatMessage.ToolResult>().single()
            assertEquals(true, result.isError)
            assertEquals("We do not sell tiramisu.", state.answer)
        }

    @Test
    fun `level 6 runs the agent inside a graph with its own state`() =
        runTest {
            val state = ticketDesk(pretendModel).invoke(Ticket6(customer = "Ben", message = "Do you sell salad?")).state

            assertEquals("Hi Ben! One salad costs 6 euros.", state.reply)
            assertEquals("I'm Ben. Do you sell salad?", state.conversation.first().text)
            assertEquals(4, state.conversation.size)
        }

    @Test
    fun `level 6 delivers the answer of the model to a stream`() =
        runTest {
            val pieces = helpDesk6(pretendModel).stream(AgentState("How much is a cola?")).mapNotNull { it.textDelta }.toList()

            assertEquals("One cola costs 2 euros.", pieces.joinToString(""))
        }

    @Test
    fun `level 7 mistakes are reported with the messages shown in the tutorial`() =
        runTest {
            assertEquals(
                "Edge references unknown to-node: anwser",
                assertFailsWith<GraphValidationException> { misspelledNode() }.message,
            )
            assertEquals(
                "Node 'check' is not reachable from START",
                assertFailsWith<GraphValidationException> { forgottenArrow() }.message,
            )
            assertEquals(
                "Node 'read' fans out to several nodes that return a whole state, so compile() needs a Reducer, or those nodes need a work and an update",
                assertFailsWith<GraphValidationException> { missingReducer() }.message,
            )
            assertEquals(
                "Graph execution exceeded max iterations (25). Possible infinite loop.",
                assertFailsWith<MaxIterationsExceededException> { endlessLoop().invoke(Ticket7("Ana", "Hello")) }.message,
            )
        }

    @Test
    fun `level 7 retries a failed node with resume`() =
        runTest {
            var calls = 0
            val graph = helpDesk7 { if (++calls == 1) error("the kitchen phone is busy") else "Your pizza is in the oven." }
            val config = GraphConfig(threadId = "ticket-42", checkpointer = MemoryCheckpointer<Ticket7>())

            val failure = assertFailsWith<NodeExecutionException> { graph.invoke(Ticket7("Ana", "Where is my pizza?"), config) }
            assertEquals("Node 'kitchen' failed: the kitchen phone is busy", failure.message)
            assertEquals("kitchen", failure.nodeName)

            assertEquals("Hi Ana! Your pizza is in the oven.", graph.resume(config).state.reply)
        }

    @Test
    fun `level 8 handles a delivery question, a refund, a menu question and anything else`() =
        runTest {
            val graph = helpDesk8(pretendModel)
            val checkpointer = MemoryCheckpointer<Ticket8>()

            fun config(threadId: String) = GraphConfig(threadId = threadId, checkpointer = checkpointer, interruptBefore = setOf(PAY8))

            val delivery = graph.invoke(Ticket8("Ana", "Where is my pizza?"), config("ticket-1"))
            assertEquals("Hi Ana, your pizza left the oven and the driver is 5 minutes away.", delivery.state.reply)
            assertEquals(2, delivery.state.attempts)

            val refund = graph.invoke(Ticket8("Ana", "My pizza arrived cold. I want a refund."), config("ticket-2"))
            assertIs<GraphResult.Interrupted<Ticket8>>(refund)
            assertEquals("Sorry Ana! We sent you 12 euros.", graph.resume(config("ticket-2")) { it.copy(approved = true) }.state.reply)

            val menu = graph.invoke(Ticket8("Ana", "Do you sell salad?"), config("ticket-3"))
            assertEquals("Hi Ana! One salad costs 6 euros.", menu.state.reply)
            assertEquals(4, menu.state.conversation.size)

            val other = graph.invoke(Ticket8("Ana", "Thanks for the pizza!"), config("ticket-4"))
            assertEquals("Hi Ana, a colleague will reply soon.", other.state.reply)
        }
}
