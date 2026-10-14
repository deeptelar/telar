package dev.deeptelar.telar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** An order that is paid with a card and then shipped. */
private data class Order(
    val amount: Int,
    val receipt: String = "",
    val shipped: Boolean = false,
    val approved: Boolean? = null,
)

/** Stores a result that is already a string. */
private object Text : StateSerializer<String> {
    override fun serialize(state: String): String = state

    override fun deserialize(data: String): String = data
}

/** Stores a number, and fails to read anything else. */
private object Number : StateSerializer<Int> {
    override fun serialize(state: Int): String = state.toString()

    override fun deserialize(data: String): Int = data.toIntOrNull() ?: throw IllegalArgumentException("Not a number: $data")
}

class EffectTest {
    /** The cards charged so far, as their receipts. */
    private val charges = mutableListOf<String>()

    private fun charge(amount: Int): String = "receipt-${charges.size + 1}-$amount".also { charges += it }

    private val checkpointer = MemoryCheckpointer<Order>()
    private val config = GraphConfig(threadId = "t", checkpointer = checkpointer)

    /** Fails the first time "ship" runs. */
    private var shipFails = true

    /** "pay" and "ship" run in parallel, and "ship" fails the first time. */
    private val app =
        StateGraph<Order> {
            val pay =
                node("pay", work = { order -> effect("charge", Text) { charge(order.amount) } }) { order, receipt ->
                    order.copy(receipt = receipt)
                }
            val ship =
                node("ship", work = { _ ->
                    // Let "pay" finish first, so that its effect is done when this node fails.
                    delay(10)
                    if (shipFails) error("The courier did not answer.")
                }) { order, _ -> order.copy(shipped = true) }

            START then pay then END
            START then ship then END
        }.compile()

    @Test
    fun `resume after a failed step does not repeat an effect that finished`() =
        runTest {
            assertFailsWith<NodeExecutionException> { app.invoke(Order(30), config) }
            shipFails = false

            val finished = app.resume(config)

            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-1-30", shipped = true)), finished)
            assertEquals(listOf("receipt-1-30"), charges)
        }

    @Test
    fun `the checkpoint of a failed step holds the effects that finished in it`() =
        runTest {
            assertFailsWith<NodeExecutionException> { app.invoke(Order(30), config) }

            assertEquals(
                Checkpoint(Order(30), listOf("pay", "ship"), step = 0, effects = mapOf("pay/charge" to "receipt-1-30")),
                checkpointer.load("t"),
            )
            // The effect is saved in the place of the checkpoint the step continues from, not as a step of its own.
            assertEquals(1, checkpointer.history("t").size)
        }

    @Test
    fun `the checkpoint of a finished step has no effects and the one before it keeps them`() =
        runTest {
            shipFails = false

            app.invoke(Order(30), config)

            assertEquals(
                listOf(mapOf("pay/charge" to "receipt-1-30"), emptyMap()),
                checkpointer.history("t").map { it.effects },
            )
        }

    @Test
    fun `an effect is saved even when the node is cancelled while it saves`() =
        runTest {
            val saving = CompletableDeferred<Unit>()
            // Saving an effect takes a while, and the other node fails in the meantime.
            val slow =
                object : Checkpointer<Order> by checkpointer {
                    override suspend fun save(threadId: String, checkpoint: Checkpoint<Order>) {
                        if (checkpoint.effects.isNotEmpty()) {
                            saving.complete(Unit)
                            delay(100)
                        }
                        checkpointer.save(threadId, checkpoint)
                    }
                }
            val graph =
                StateGraph<Order> {
                    val pay =
                        node("pay", work = { order ->
                            effect("charge", Text) { charge(order.amount) }
                            awaitCancellation()
                        }) { order, _ -> order }
                    val ship =
                        node("ship", work = { _ ->
                            saving.await()
                            error("The courier did not answer.")
                        }) { order, _ -> order }

                    START then pay then END
                    START then ship then END
                }.compile()

            assertFailsWith<NodeExecutionException> { graph.invoke(Order(30), config.copy(checkpointer = slow)) }

            assertEquals(mapOf("pay/charge" to "receipt-1-30"), checkpointer.load("t")?.effects)
        }

    @Test
    fun `the tries of a node with a retry do not repeat an effect`() =
        runTest {
            var tries = 0
            val graph =
                StateGraph<Order> {
                    START then
                        node("pay", retry = Retry(attempts = 2)) { order ->
                            val receipt = effect("charge", Text) { charge(order.amount) }
                            if (++tries == 1) error("The order service did not answer.")
                            order.copy(receipt = receipt)
                        } then END
                }.compile()

            // Without a checkpointer: the result is kept in memory for the next try.
            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-1-30")), graph.invoke(Order(30)))
            assertEquals(2, tries)
            assertEquals(listOf("receipt-1-30"), charges)
        }

    @Test
    fun `resume after interrupt does not repeat an effect that ran before the pause`() =
        runTest {
            var starts = 0
            val graph =
                StateGraph<Order> {
                    START then
                        node("pay") { order ->
                            starts++
                            val receipt = effect("charge", Text) { charge(order.amount) }
                            if (order.approved == null) interrupt(order.copy(receipt = receipt))
                            order.copy(receipt = receipt, shipped = order.approved == true)
                        } then END
                }.compile()

            val paused = graph.invoke(Order(30), config)
            assertEquals(GraphResult.Interrupted(Order(30, receipt = "receipt-1-30"), listOf("pay")), paused)
            assertEquals(mapOf("pay/charge" to "receipt-1-30"), checkpointer.load("t")?.effects)

            val finished = graph.resume(config) { it.copy(approved = true) }

            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-1-30", shipped = true, approved = true)), finished)
            assertEquals(2, starts)
            assertEquals(listOf("receipt-1-30"), charges)
        }

    @Test
    fun `an effect that throws is not saved and runs again`() =
        runTest {
            var cardDeclined = true
            val graph =
                StateGraph<Order> {
                    START then
                        node("pay") { order ->
                            val receipt =
                                effect("charge", Text) {
                                    if (cardDeclined) error("Card declined.")
                                    charge(order.amount)
                                }
                            order.copy(receipt = receipt)
                        } then END
                }.compile()

            assertFailsWith<NodeExecutionException> { graph.invoke(Order(30), config) }
            assertEquals(emptyMap(), checkpointer.load("t")?.effects)
            cardDeclined = false

            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-1-30")), graph.resume(config))
        }

    @Test
    fun `a node may try an effect again after it threw`() =
        runTest {
            var declines = 1
            val graph =
                StateGraph<Order> {
                    START then
                        node("pay") { order ->
                            val receipt =
                                try {
                                    effect("charge", Text) { if (declines-- > 0) error("Card declined.") else charge(order.amount) }
                                } catch (_: IllegalStateException) {
                                    effect("charge", Text) { charge(order.amount) }
                                }
                            order.copy(receipt = receipt)
                        } then END
                }.compile()

            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-1-30")), graph.invoke(Order(30)))
        }

    @Test
    fun `an effect runs again when its node runs in a later step`() =
        runTest {
            val graph =
                StateGraph<Order> {
                    val pay = node("pay") { order -> order.copy(receipt = order.receipt + effect("charge", Text) { charge(order.amount) }) }
                    START then pay
                    conditionalEdge(pay) { if (charges.size < 2) "pay" else END }
                }.compile()

            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-1-30receipt-2-30")), graph.invoke(Order(30), config))
        }

    @Test
    fun `two nodes may use the same key`() =
        runTest {
            val graph =
                StateGraph<Order> {
                    val first = node("first", work = { order -> effect("charge", Text) { charge(order.amount) } }) { order, _ -> order }
                    val second = node("second", work = { order -> effect("charge", Text) { charge(order.amount) } }) { order, _ -> order }
                    START then first then END
                    START then second then END
                }.compile()

            graph.invoke(Order(30), config)

            assertEquals(2, charges.size)
        }

    @Test
    fun `a node that uses a key twice fails`() =
        runTest {
            val graph =
                StateGraph<Order> {
                    START then
                        node("pay") { order ->
                            listOf(10, 20).forEach { effect("charge", Text) { charge(it) } }
                            order
                        } then END
                }.compile()

            val failure = assertFailsWith<NodeExecutionException> { graph.invoke(Order(30), config) }

            val cause = assertIs<GraphValidationException>(failure.cause)
            assertEquals("Node 'pay' called effect() with the key 'charge' twice. Give each effect of a node its own key.", cause.message)
            assertEquals(listOf("receipt-1-10"), charges)
        }

    @Test
    fun `a saved result that the serializer cannot read fails the node`() =
        runTest {
            checkpointer.save("t", Checkpoint(Order(30), listOf("count"), effects = mapOf("count/clicks" to "many")))
            val graph =
                StateGraph<Order> {
                    START then node("count") { order -> order.copy(amount = effect("clicks", Number) { 1 }) } then END
                }.compile()

            val failure = assertFailsWith<NodeExecutionException> { graph.resume(config) }

            val cause = assertIs<CheckpointCorruptedException>(failure.cause)
            assertEquals("t", cause.threadId)
        }

    @Test
    fun `a slash in a name does not mix up two effects`() =
        runTest {
            val graph =
                StateGraph<Order> {
                    val a = node("a", work = { _ -> effect("b/c", Text) { "first" } }) { order, _ -> order }
                    val ab = node("a/b", work = { _ -> effect("c", Text) { "second" } }) { order, _ -> order }
                    val fail =
                        node("fail", work = { _ ->
                            delay(10)
                            error("Stop")
                        }) { order, _ -> order }
                    START then a then END
                    START then ab then END
                    START then fail then END
                }.compile()

            assertFailsWith<NodeExecutionException> { graph.invoke(Order(30), config) }

            assertEquals(mapOf("a/b%2Fc" to "first", "a%2Fb/c" to "second"), checkpointer.load("t")?.effects)
        }

    @Test
    fun `outside a node an effect runs every time`() =
        runTest {
            effect("charge", Text) { charge(10) }
            effect("charge", Text) { charge(10) }

            assertEquals(2, charges.size)
        }

    @Test
    fun `an effect in a subgraph is not repeated when the graph around it resumes`() =
        runTest {
            val payment =
                StateGraph<Order> {
                    START then node("charge") { order -> order.copy(receipt = effect("card", Text) { charge(order.amount) }) } then END
                }.compile()
            val graph =
                StateGraph<Order> {
                    val pay = subgraph("pay", payment)
                    val ship =
                        node("ship", work = { _ ->
                            delay(10)
                            if (shipFails) error("The courier did not answer.")
                        }) { order, _ -> order.copy(shipped = true) }
                    START then pay then END
                    START then ship then END
                }.compile()

            assertFailsWith<NodeExecutionException> { graph.invoke(Order(30), config) }
            // The path names the node of the subgraph, the step of the subgraph and the node inside it.
            assertEquals(mapOf("pay/1/charge/card" to "receipt-1-30"), checkpointer.load("t")?.effects)
            shipFails = false

            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-1-30", shipped = true)), graph.resume(config))
            assertEquals(1, charges.size)
        }

    @Test
    fun `a node of a subgraph that runs in two of its steps runs its effect in each`() =
        runTest {
            val payment =
                StateGraph<Order> {
                    val charge =
                        node("charge") { order ->
                            order.copy(
                                receipt =
                                    order.receipt + effect("card", Text) { charge(order.amount) },
                            )
                        }
                    START then charge
                    conditionalEdge(charge) { if (charges.size < 2) "charge" else END }
                }.compile()
            val graph = StateGraph<Order> { START then subgraph("pay", payment) then END }.compile()

            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-1-30receipt-2-30")), graph.invoke(Order(30), config))
        }

    @Test
    fun `a fork runs the effects again`() =
        runTest {
            assertFailsWith<NodeExecutionException> { app.invoke(Order(30), config) }
            shipFails = false
            val failed = app.history(config).last()

            val forked = app.fork(failed, config.copy(threadId = "other"))

            assertEquals(GraphResult.Completed(Order(30, receipt = "receipt-2-30", shipped = true)), forked)
            assertEquals(listOf("receipt-1-30", "receipt-2-30"), charges)
        }
}
