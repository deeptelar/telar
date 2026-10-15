package dev.deeptelar.telar

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A payment provider that takes idempotency keys. */
private class Payments {
    /** The charges made, by their idempotency key. */
    val charges = mutableMapOf<String, String>()
    val keys = mutableListOf<String>()

    fun charge(key: String, amount: Int): String {
        keys += key
        return charges.getOrPut(key) { "receipt-${charges.size + 1}-$amount" }
    }
}

private object Receipt : StateSerializer<String> {
    override fun serialize(state: String): String = state

    override fun deserialize(data: String): String = data
}

private data class Bill(
    val amount: Int,
    val receipt: String = "",
)

/** The `effect` with a `reconcile`: it saves that it started, so that a stop after the work is not repeated. */
class ReconciledEffectTest {
    private val payments = Payments()
    private val checkpointer = MemoryCheckpointer<Bill>()
    private val config = GraphConfig(threadId = "t", checkpointer = checkpointer)
    private val reconciled = mutableListOf<String>()

    /** Stops the process right after the provider charged the card, before the result is saved. */
    private var stopsAfterCharge = true

    private fun graph(reconcile: suspend (String) -> String?) =
        StateGraph<Bill> {
            START then
                node("pay") { bill ->
                    val receipt =
                        effect("charge", Receipt, reconcile = { id ->
                            reconciled += id
                            reconcile(id)
                        }) { id ->
                            val receipt = payments.charge(id, bill.amount)
                            if (stopsAfterCharge) error("The process stopped.")
                            receipt
                        }
                    bill.copy(receipt = receipt)
                } then END
        }.compile()

    @Test
    fun `a reconcile that finds the work saves its result and the block does not run again`() =
        runTest {
            val app = graph { id -> payments.charges[id] }

            assertFailsWith<NodeExecutionException> { app.invoke(Bill(30), config) }
            stopsAfterCharge = false
            val finished = app.resume(config).state

            assertEquals("receipt-1-30", finished.receipt)
            assertEquals(1, payments.keys.size)
            assertEquals(payments.keys, reconciled)
        }

    @Test
    fun `a reconcile that returns null runs the block again with the same id`() =
        runTest {
            val app = graph { null }

            assertFailsWith<NodeExecutionException> { app.invoke(Bill(30), config) }
            stopsAfterCharge = false
            val finished = app.resume(config).state

            assertEquals("receipt-1-30", finished.receipt)
            assertEquals(1, payments.charges.size)
            assertEquals(2, payments.keys.size)
            assertEquals(payments.keys[0], payments.keys[1])
            assertEquals(listOf(payments.keys[0]), reconciled)
        }

    @Test
    fun `the checkpoint of the failed step holds the effect that started`() =
        runTest {
            assertFailsWith<NodeExecutionException> { graph { null }.invoke(Bill(30), config) }

            val effects = checkpointer.load("t")!!.effects

            assertEquals(mapOf("pay/charge%started" to payments.keys.single()), effects)
        }

    @Test
    fun `a reconcile that throws stops the run and asks again on the next resume`() =
        runTest {
            var canTell = false
            val app = graph { id -> if (canTell) payments.charges[id] else error("The provider is down.") }

            assertFailsWith<NodeExecutionException> { app.invoke(Bill(30), config) }
            stopsAfterCharge = false
            assertFailsWith<NodeExecutionException> { app.resume(config) }
            canTell = true
            val finished = app.resume(config).state

            assertEquals("receipt-1-30", finished.receipt)
            assertEquals(1, payments.keys.size)
            assertEquals(2, reconciled.size)
        }

    @Test
    fun `a try of a retry asks the reconcile about the try before it`() =
        runTest {
            stopsAfterCharge = false
            var tries = 0
            val app =
                StateGraph<Bill> {
                    START then
                        node("pay", retry = Retry(attempts = 2)) { bill ->
                            val receipt =
                                effect("charge", Receipt, reconcile = { id ->
                                    reconciled += id
                                    payments.charges[id]
                                }) { id ->
                                    payments.charge(id, bill.amount).also { if (++tries == 1) error("Timed out.") }
                                }
                            bill.copy(receipt = receipt)
                        } then END
                }.compile()

            val finished = app.invoke(Bill(30)).state

            assertEquals("receipt-1-30", finished.receipt)
            assertEquals(1, tries)
            assertEquals(payments.keys, reconciled)
        }

    @Test
    fun `each run of a thread gets new ids`() =
        runTest {
            stopsAfterCharge = false
            val app = graph { null }

            app.invoke(Bill(30), config)
            app.invoke(Bill(30), config)

            assertEquals(2, payments.charges.size)
            assertNotEquals(payments.keys[0], payments.keys[1])
            assertEquals(emptyList(), reconciled)
        }

    @Test
    fun `effectId returns the id inside the block and the reconcile and null elsewhere`() =
        runTest {
            val seen = mutableListOf<String?>()
            val app =
                StateGraph<Bill> {
                    START then
                        node("pay") { bill ->
                            seen += effectId()
                            val receipt =
                                effect("charge", Receipt, reconcile = {
                                    seen += effectId()
                                    null
                                }) { id ->
                                    seen += effectId()
                                    assertEquals(id, effectId())
                                    payments.charge(id, bill.amount).also { if (stopsAfterCharge) error("Stopped.") }
                                }
                            bill.copy(receipt = receipt)
                        } then END
                }.compile()

            assertFailsWith<NodeExecutionException> { app.invoke(Bill(30), config) }
            stopsAfterCharge = false
            app.resume(config)

            assertNull(seen[0])
            assertEquals(List(3) { payments.keys[0] }, seen.drop(1).filterNotNull())
            assertNull(effectId())
        }

    @Test
    fun `outside a node the block runs with a new id each time`() =
        runTest {
            stopsAfterCharge = false
            val first = effect("charge", Receipt, reconcile = { error("Not asked.") }) { id -> payments.charge(id, 30) }
            val second = effect("charge", Receipt, reconcile = { error("Not asked.") }) { id -> payments.charge(id, 30) }

            assertNotEquals(first, second)
            assertTrue(payments.keys.all { it.isNotBlank() })
        }
}
