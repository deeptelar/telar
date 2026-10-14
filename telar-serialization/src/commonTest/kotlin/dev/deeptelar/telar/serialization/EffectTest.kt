package dev.deeptelar.telar.serialization

import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
private data class Receipt(
    val id: String,
    val amount: Int,
)

private data class Payment(
    val amount: Int,
    val receipt: Receipt? = null,
    val emailed: Boolean = false,
)

class EffectTest {
    @Test
    fun `saves a serializable result and an effect without one and repeats neither`() =
        runTest {
            val charges = mutableListOf<Receipt>()
            var emails = 0
            var shopOpen = false
            val graph =
                StateGraph<Payment> {
                    START then
                        node("pay") { payment ->
                            val receipt = effect("charge") { Receipt("r-${charges.size + 1}", payment.amount).also { charges += it } }
                            effect("email") { emails += 1 }
                            if (!shopOpen) error("The shop is closed.")
                            payment.copy(receipt = receipt, emailed = true)
                        } then END
                }.compile()
            val checkpointer = MemoryCheckpointer<Payment>()
            val config = GraphConfig(threadId = "t", checkpointer = checkpointer)

            assertFailsWith<NodeExecutionException> { graph.invoke(Payment(30), config) }
            assertEquals(mapOf("pay/charge" to """{"id":"r-1","amount":30}""", "pay/email" to "{}"), checkpointer.load("t")?.effects)
            shopOpen = true

            assertEquals(GraphResult.Completed(Payment(30, Receipt("r-1", 30), emailed = true)), graph.resume(config))
            assertEquals(1, charges.size)
            assertEquals(1, emails)
        }
}
