package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CheckpointCorruptedException
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphSession
import dev.deeptelar.telar.GraphSnapshot
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.SubgraphPosition
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class Order(
    val item: String,
    val approved: Boolean = false,
    val log: List<String> = emptyList(),
)

class SqlDelightCheckpointerTest {
    private val serializer = KotlinxStateSerializer<Order>()
    private val directory = File.createTempFile("telar-sqldelight", "").apply { delete() }.also { it.mkdirs() }
    private val drivers = mutableListOf<SqlDriver>()

    private fun inMemory(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }

    /** A driver for the database file [name], as an app opens it on every launch. */
    private fun onDisk(name: String = "runs.db"): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:${File(directory, name)}").also { drivers += it }

    @AfterTest
    fun cleanUp() {
        drivers.forEach { it.close() }
        directory.deleteRecursively()
    }

    private fun checkpoint(item: String, step: Int, nextNodes: List<String> = listOf("next")) = Checkpoint(Order(item), nextNodes, step)

    @Test
    fun `saves and loads a checkpoint with everything it holds`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(inMemory(), serializer)
            val checkpoint =
                Checkpoint(
                    Order("pizza", log = listOf("asked")),
                    listOf("pay"),
                    step = 3,
                    interruptedBefore = true,
                    subgraphs = mapOf("pay" to SubgraphPosition(listOf("card"), step = 1, interruptedBefore = true)),
                )

            checkpointer.save("order-42", checkpoint)

            assertEquals(checkpoint, checkpointer.load("order-42"))
            assertEquals(listOf(checkpoint), checkpointer.history("order-42"))
        }

    @Test
    fun `a thread without checkpoints loads null and has no history`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(inMemory(), serializer)

            assertNull(checkpointer.load("nobody"))
            assertEquals(emptyList(), checkpointer.history("nobody"))
            checkpointer.delete("nobody")
        }

    @Test
    fun `a thread keeps one checkpoint per step and a save replaces its step and the later ones`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(inMemory(), serializer)
            (0..3).forEach { checkpointer.save("t", checkpoint("step $it", it)) }

            val again = checkpoint("step 2 again", 2)
            checkpointer.save("t", again)

            assertEquals(listOf(checkpoint("step 0", 0), checkpoint("step 1", 1), again), checkpointer.history("t"))
            assertEquals(again, checkpointer.load("t"))
        }

    @Test
    fun `maxHistory keeps the latest checkpoints of a thread`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(inMemory(), serializer, maxHistory = 2)

            (0..4).forEach { checkpointer.save("t", checkpoint("step $it", it)) }

            assertEquals(listOf(checkpoint("step 3", 3), checkpoint("step 4", 4)), checkpointer.history("t"))
        }

    @Test
    fun `threads are separate and delete removes only its own`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(inMemory(), serializer)
            checkpointer.save("a", checkpoint("a", 1))
            checkpointer.save("b", checkpoint("b", 1))

            checkpointer.delete("a")

            assertNull(checkpointer.load("a"))
            assertEquals(checkpoint("b", 1), checkpointer.load("b"))
        }

    @Test
    fun `checkpoints are still there after the app is started again`() =
        runTest {
            SqlDelightCheckpointer(onDisk(), serializer).save("order-42", checkpoint("pizza", 1, listOf("pay")))
            drivers.removeLast().close()

            val restarted = SqlDelightCheckpointer(onDisk(), serializer)

            assertEquals(checkpoint("pizza", 1, listOf("pay")), restarted.load("order-42"))
        }

    @Test
    fun `a run paused for approval is resumed by a new process`() =
        runTest {
            val graph =
                StateGraph<Order> {
                    val prepare = node("prepare") { it.copy(log = it.log + "prepared") }
                    val pay = node("pay") { it.copy(log = it.log + if (it.approved) "paid" else "cancelled") }
                    START then prepare then pay then END
                }.compile()

            fun config(driver: SqlDriver) =
                GraphConfig("order-42", SqlDelightCheckpointer(driver, serializer), interruptBefore = setOf("pay"))

            assertEquals(
                GraphResult.Interrupted(Order("pizza", log = listOf("prepared")), listOf("pay")),
                graph.invoke(Order("pizza"), config(onDisk())),
            )
            drivers.removeLast().close()

            val finished = graph.resume(config(onDisk())) { it.copy(approved = true) }

            assertEquals(GraphResult.Completed(Order("pizza", approved = true, log = listOf("prepared", "paid"))), finished)
        }

    @Test
    fun `a session restores the screen of a run after the app was killed`() =
        runTest {
            val graph =
                StateGraph<Order> {
                    val prepare = node("prepare") { it.copy(log = it.log + "prepared") }
                    val pay = node("pay") { it.copy(log = it.log + "paid") }
                    START then prepare then pay then END
                }.compile()

            fun config(driver: SqlDriver) =
                GraphConfig("order-42", SqlDelightCheckpointer(driver, serializer), interruptBefore = setOf("pay"))
            val killed = CoroutineScope(backgroundScope.coroutineContext + Job())
            GraphSession(graph, config(onDisk()), killed).start(Order("pizza")).join()
            killed.cancel()
            drivers.removeLast().close()

            val session = GraphSession(graph, config(onDisk()), backgroundScope)
            session.restore().join()

            assertEquals(GraphSnapshot.Interrupted(Order("pizza", log = listOf("prepared")), listOf("pay")), session.snapshot.value)
            session.resume().join()
            assertEquals(GraphSnapshot.Completed(Order("pizza", log = listOf("prepared", "paid"))), session.snapshot.value)
        }

    @Test
    fun `works with an asynchronous driver like the one of a browser`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(AsyncDriver(inMemory()), serializer, maxHistory = 2)

            (0..3).forEach { checkpointer.save("t", checkpoint("step $it", it)) }

            assertEquals(listOf(checkpoint("step 2", 2), checkpoint("step 3", 3)), checkpointer.history("t"))
            assertEquals(checkpoint("step 3", 3), checkpointer.load("t"))
            checkpointer.delete("t")
            assertNull(checkpointer.load("t"))
        }

    @Test
    fun `Schema creates the table for a driver that asks for a schema`() =
        runTest {
            val driver = inMemory()
            SqlDelightCheckpointer.Schema.create(driver)
            assertEquals(1, SqlDelightCheckpointer.Schema.version)
            SqlDelightCheckpointer.Schema.migrate(driver, 1, 1)

            assertEquals(listOf("telar_checkpoints"), tables(driver))
            SqlDelightCheckpointer(driver, serializer).save("t", checkpoint("pizza", 0))
            assertEquals(checkpoint("pizza", 0), SqlDelightCheckpointer(driver, serializer).load("t"))
        }

    @Test
    fun `the table can have another name and sits next to the tables of the app`() =
        runTest {
            val driver = inMemory()
            driver.execute(null, "CREATE TABLE notes (text TEXT)", 0)
            val checkpointer = SqlDelightCheckpointer(driver, serializer, table = "agent_runs")

            checkpointer.save("t", checkpoint("pizza", 0))

            assertEquals(listOf("agent_runs", "notes"), tables(driver))
            assertEquals(checkpoint("pizza", 0), checkpointer.load("t"))
        }

    @Test
    fun `saves from many coroutines at once all arrive`() =
        runTest {
            val checkpointer = SqlDelightCheckpointer(onDisk(), serializer)

            withContext(Dispatchers.Default) {
                (1..20).map { thread -> async { (0..4).forEach { checkpointer.save("t$thread", checkpoint("t$thread", it)) } } }.awaitAll()
            }

            (1..20).forEach { thread -> assertEquals(5, checkpointer.history("t$thread").size) }
        }

    @Test
    fun `a checkpoint that cannot be read is reported as corrupted`() =
        runTest {
            val driver = inMemory()
            val checkpointer = SqlDelightCheckpointer(driver, serializer)
            checkpointer.save("t", checkpoint("pizza", 0))
            driver.execute(null, "UPDATE telar_checkpoints SET checkpoint = 'not json'", 0)

            assertFailsWith<CheckpointCorruptedException> { checkpointer.load("t") }
        }

    @Test
    fun `a table name that is not plain and a maxHistory below one are refused`() {
        val driver = inMemory()

        assertFailsWith<GraphValidationException> { SqlDelightCheckpointer(driver, serializer, table = "runs; DROP TABLE notes") }
        assertFailsWith<GraphValidationException> { SqlDelightCheckpointer(driver, serializer, table = "1runs") }
        assertFailsWith<GraphValidationException> { SqlDelightCheckpointer(driver, serializer, maxHistory = 0) }
        assertTrue(tables(driver).isEmpty())
    }

    private fun tables(driver: SqlDriver): List<String> =
        driver
            .executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)!!) })
            }, 0)
            .value
}
