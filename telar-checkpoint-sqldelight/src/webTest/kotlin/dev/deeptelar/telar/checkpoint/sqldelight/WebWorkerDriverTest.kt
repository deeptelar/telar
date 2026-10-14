package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CheckpointCorruptedException
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphSession
import dev.deeptelar.telar.GraphSnapshot
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.SubgraphPosition
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The `WebWorkerDriver` of SQLDelight, with its sql.js worker, in a new database. */
expect fun workerDriver(): SqlDriver

@Serializable
data class Ticket(
    val text: String,
    val approved: Boolean = false,
    val log: List<String> = emptyList(),
)

/**
 * Runs in a real browser: the checkpointer talks to SQLite in a web worker, through the driver and
 * the worker that SQLDelight ships for the browser.
 */
class WebWorkerDriverTest {
    private val serializer = KotlinxStateSerializer<Ticket>()
    private val drivers = mutableListOf<SqlDriver>()

    private fun driver(): SqlDriver = workerDriver().also { drivers += it }

    @AfterTest
    fun closeDrivers() {
        drivers.forEach { it.close() }
    }

    private fun checkpoint(text: String, step: Int) = Checkpoint(Ticket(text), listOf("next"), step)

    @Test
    fun `saves and loads a checkpoint with everything it holds`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(driver(), serializer)
            val checkpoint =
                Checkpoint(
                    Ticket("hello", log = listOf("asked")),
                    listOf("send"),
                    step = 3,
                    interruptedBefore = true,
                    subgraphs = mapOf("send" to SubgraphPosition(listOf("mail"), step = 1)),
                    effects = mapOf("send/charge" to "\"receipt-7\""),
                )

            checkpointer.save("t", checkpoint)

            assertEquals(checkpoint, checkpointer.load("t"))
        }

    @Test
    fun `the table is created in the database of the worker`() =
        runTest {
            val driver = driver()
            SqlDelightCheckpointer(driver, serializer).save("t", checkpoint("hello", 0))

            val tables =
                driver
                    .executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table'", { cursor ->
                        QueryResult.AsyncValue { buildList { while (cursor.next().await()) add(cursor.getString(0)) } }
                    }, 0)
                    .await()

            assertEquals(listOf<String?>("telar_checkpoints"), tables)
        }

    @Test
    fun `a thread keeps one checkpoint per step and a save replaces its step and the later ones`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(driver(), serializer)
            (0..3).forEach { checkpointer.save("t", checkpoint("step $it", it)) }

            checkpointer.save("t", checkpoint("step 2 again", 2))

            assertEquals(listOf("step 0", "step 1", "step 2 again"), checkpointer.history("t").map { it.state.text })
        }

    @Test
    fun `maxHistory keeps the latest checkpoints of a thread`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(driver(), serializer, maxHistory = 2)

            (0..4).forEach { checkpointer.save("t", checkpoint("step $it", it)) }

            assertEquals(listOf(checkpoint("step 3", 3), checkpoint("step 4", 4)), checkpointer.history("t"))
        }

    @Test
    fun `threads are separate and delete removes only its own`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(driver(), serializer)
            checkpointer.save("a", checkpoint("a", 1))
            checkpointer.save("b", checkpoint("b", 1))

            checkpointer.delete("a")

            assertNull(checkpointer.load("a"))
            assertEquals(emptyList(), checkpointer.history("a"))
            assertEquals(checkpoint("b", 1), checkpointer.load("b"))
        }

    @Test
    fun `a run paused for approval is resumed`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    val draft = node("draft") { it.copy(log = it.log + "drafted") }
                    val send = node("send") { it.copy(log = it.log + if (it.approved) "sent" else "dropped") }
                    START then draft then send then END
                }.compile()
            val config = GraphConfig("ticket-7", SqlDelightCheckpointer(driver(), serializer), interruptBefore = setOf("send"))

            assertEquals(
                GraphResult.Interrupted(Ticket("hello", log = listOf("drafted")), listOf("send")),
                graph.invoke(Ticket("hello"), config),
            )

            val finished = graph.resume(config) { it.copy(approved = true) }

            assertEquals(GraphResult.Completed(Ticket("hello", approved = true, log = listOf("drafted", "sent"))), finished)
            assertEquals(3, config.checkpointer?.history("ticket-7")?.size)
        }

    @Test
    fun `a new session restores the screen of a run that an ended one left`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    val draft = node("draft") { it.copy(log = it.log + "drafted") }
                    val send = node("send") { it.copy(log = it.log + "sent") }
                    START then draft then send then END
                }.compile()
            val driver = driver()

            fun config() = GraphConfig("ticket-7", SqlDelightCheckpointer(driver, serializer), interruptBefore = setOf("send"))
            val closedPage = CoroutineScope(backgroundScope.coroutineContext + Job())
            GraphSession(graph, config(), closedPage).start(Ticket("hello")).join()
            closedPage.cancel()

            val session = GraphSession(graph, config(), backgroundScope)
            session.restore().join()

            assertEquals(GraphSnapshot.Interrupted(Ticket("hello", log = listOf("drafted")), listOf("send")), session.snapshot.value)
            session.resume().join()
            assertEquals(GraphSnapshot.Completed(Ticket("hello", log = listOf("drafted", "sent"))), session.snapshot.value)
        }

    @Test
    fun `a checkpoint that cannot be read is reported as corrupted`() =
        runTest {
            val driver = driver()
            val checkpointer = SqlDelightCheckpointer(driver, serializer)
            checkpointer.save("t", checkpoint("hello", 0))
            driver.execute(null, "UPDATE telar_checkpoints SET checkpoint = 'not json'", 0).await()

            assertFailsWith<CheckpointCorruptedException> { checkpointer.load("t") }
        }
}
