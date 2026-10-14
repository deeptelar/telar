package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.driver.native.inMemoryDriver
import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Serializable
data class Ticket(
    val text: String,
    val approved: Boolean = false,
)

/** The checkpointer with the driver of iOS and macOS, which runs on the SQLite of the system. */
class NativeDriverTest {
    private val serializer = KotlinxStateSerializer<Ticket>()

    @Test
    fun `saves loads and trims the checkpoints of a thread`() =
        runTest {
            val driver = inMemoryDriver(SqlDelightCheckpointer.Schema)
            val checkpointer = SqlDelightCheckpointer(driver, serializer, maxHistory = 2)

            (0..3).forEach { checkpointer.save("t", Checkpoint(Ticket("step $it"), listOf("next"), it)) }

            assertEquals((2..3).map { Checkpoint(Ticket("step $it"), listOf("next"), it) }, checkpointer.history("t"))
            checkpointer.delete("t")
            assertNull(checkpointer.load("t"))
            driver.close()
        }

    @Test
    fun `a run pauses for approval and resumes`() =
        runTest {
            val driver = inMemoryDriver(SqlDelightCheckpointer.Schema)
            val graph =
                StateGraph<Ticket> {
                    val send = node("send") { it.copy(text = it.text + if (it.approved) " sent" else " dropped") }
                    START then send then END
                }.compile()
            val config = GraphConfig("ticket", SqlDelightCheckpointer(driver, serializer), interruptBefore = setOf("send"))

            assertEquals(GraphResult.Interrupted(Ticket("hello"), listOf("send")), graph.invoke(Ticket("hello"), config))
            assertEquals(GraphResult.Completed(Ticket("hello sent", approved = true)), graph.resume(config) { it.copy(approved = true) })
            driver.close()
        }
}
