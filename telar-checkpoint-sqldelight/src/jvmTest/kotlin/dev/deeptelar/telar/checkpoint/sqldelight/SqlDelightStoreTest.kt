package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.StoreCorruptedException
import dev.deeptelar.telar.StoreItem
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import dev.deeptelar.telar.store
import dev.deeptelar.telar.within
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/** A clock that stands still unless it is moved, so that writes one after the other have the same time. */
private class StillClock(
    var now: Instant = Instant.fromEpochSeconds(1_800_000_000, 123_456_789),
) : Clock {
    override fun now(): Instant = now
}

private val preferences = listOf("preferences")

class SqlDelightStoreTest {
    private val directory = File.createTempFile("telar-sqldelight-store", "").apply { delete() }.also { it.mkdirs() }
    private val drivers = mutableListOf<SqlDriver>()
    private val clock = StillClock()

    /** A driver for the database file, as an app opens it on every launch. */
    private fun onDisk(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${File(directory, "app.db")}").also { drivers += it }

    @AfterTest
    fun cleanUp() {
        drivers.forEach { it.close() }
        directory.deleteRecursively()
    }

    @Test
    fun `keeps replaces and deletes a value, also after a restart`() =
        runTest {
            for (async in listOf(false, true)) {
                directory.listFiles()?.forEach { it.delete() }
                clock.now = Instant.fromEpochSeconds(1_800_000_000, 123_456_789)
                val driver = { if (async) AsyncDriver(onDisk()) else onDisk() }
                val store = SqlDelightStore(driver(), clock = clock)
                store.put(preferences, "diet", "vegetarian")
                clock.now = Instant.fromEpochSeconds(1_900_000_000, 5)
                store.put(preferences, "diet", "piscivegetarian")

                val reopened = SqlDelightStore(driver(), clock = clock)
                assertEquals(
                    StoreItem(preferences, "diet", "piscivegetarian", clock.now),
                    reopened.get(preferences, "diet"),
                    "async = $async",
                )
                assertNull(reopened.get(preferences, "budget"))
                assertNull(reopened.get(listOf("trips"), "diet"))
                assertEquals(1, reopened.list(emptyList()).size)

                reopened.delete(preferences, "diet")
                reopened.delete(preferences, "diet")
                assertNull(SqlDelightStore(driver(), clock = clock).get(preferences, "diet"))
            }
        }

    @Test
    fun `a list has the namespaces under it, the last changed first`() =
        runTest {
            for (async in listOf(false, true)) {
                directory.listFiles()?.forEach { it.delete() }
                clock.now = Instant.fromEpochSeconds(1_800_000_000, 123_456_789)
                val driver = { if (async) AsyncDriver(onDisk()) else onDisk() }
                val store = SqlDelightStore(driver(), clock = clock)
                store.put(listOf("users", "ana", "preferences"), "diet", "piscivegetarian")
                store.put(listOf("users", "ana"), "name", "Ana")
                store.put(listOf("users", "joan", "preferences"), "diet", "vegan")
                store.put(listOf("users", "ana", "preferences"), "lunch", "the big meal")
                // Namespaces that start with the same letters are others.
                store.put(listOf("users", "anna"), "name", "Anna")
                store.put(listOf("users", "ana0"), "name", "Ana zero")
                store.put(listOf("users", "ana.x"), "name", "Ana x")
                store.put(listOf("users"), "count", "5")
                store.put(emptyList(), "version", "1")

                // The clock stood still. A new store goes on after the last write it finds.
                val reopened = SqlDelightStore(driver(), clock = clock)
                reopened.put(listOf("users", "ana", "preferences"), "diet", "pescatarian")

                assertEquals(listOf("diet", "lunch", "name"), reopened.list(listOf("users", "ana")).map { it.key }, "async = $async")
                assertEquals(listOf("diet", "lunch"), reopened.list(listOf("users", "ana"), limit = 2).map { it.key })
                assertEquals(8, reopened.list(listOf("users")).size)
                assertEquals(9, reopened.list(emptyList()).size)
                assertTrue(reopened.list(listOf("trips")).isEmpty())
                val times = reopened.list(emptyList()).map { it.updatedAt }
                assertEquals(times.sortedDescending(), times)
                assertEquals(clock.now + 9.nanoseconds, times.first())
            }
        }

    @Test
    fun `any character in a namespace keeps its items apart`() =
        runTest {
            val store = SqlDelightStore(onDisk())
            val parts = listOf("ana", "Ana", "a/b", "a%2Fb", "a%b", "/", "%", "日本", "a b", "'; DROP TABLE telar_store; --")
            parts.forEach { part -> store.put(listOf("users", part), part, "value of $part") }
            store.put(listOf("users", "a", "b"), "nested", "not a/b")

            parts.forEach { part ->
                assertEquals("value of $part", store.get(listOf("users", part), part)?.value, part)
                assertEquals(listOf(listOf("users", part)), store.list(listOf("users", part)).map { it.namespace }, part)
            }
            assertEquals(listOf("nested"), store.list(listOf("users", "a")).map { it.key })
            assertEquals(parts.size + 1, store.list(listOf("users")).size)
        }

    @Test
    fun `the checkpointer and the store share one database`() =
        runTest {
            val driver = onDisk()
            val graph =
                StateGraph<List<String>> {
                    val visit =
                        node("visit") { visits ->
                            val last = store().get(listOf("trips"), "last")?.value
                            store().put(listOf("trips"), "last", "Porto")
                            visits + (last ?: "none")
                        }
                    START then visit then END
                }.compile()
            val checkpointer = SqlDelightCheckpointer(driver, KotlinxStateSerializer<List<String>>())
            val store = SqlDelightStore(driver).within("users", "ana")

            assertEquals(listOf("none"), graph.invoke(emptyList(), GraphConfig("plan-1", checkpointer, store = store)).state)
            assertEquals(listOf("Porto"), graph.invoke(emptyList(), GraphConfig("plan-2", checkpointer, store = store)).state)
            assertEquals(listOf("Porto"), checkpointer.load("plan-2")?.state)
        }

    @Test
    fun `a namespace the store did not write is told`() =
        runTest {
            val driver = onDisk()
            val store = SqlDelightStore(driver)
            store.put(listOf("users"), "count", "1")
            driver.execute(null, "INSERT INTO telar_store VALUES ('users', 'broken', 'x', 1)", 0)

            val failure = assertFailsWith<StoreCorruptedException> { store.list(listOf("users").dropLast(1)) }
            assertNull(failure.key)
        }

    @Test
    fun `a table name, a key, a namespace and a limit are checked`() =
        runTest {
            assertFailsWith<GraphValidationException> { SqlDelightStore(onDisk(), table = "store; DROP") }
            val store = SqlDelightStore(onDisk(), table = "memories")
            store.put(preferences, "diet", "vegan")
            assertEquals("vegan", store.get(preferences, "diet")?.value)
            assertFailsWith<GraphValidationException> { store.put(preferences, "", "x") }
            assertFailsWith<GraphValidationException> { store.get(listOf(""), "diet") }
            assertFailsWith<GraphValidationException> { store.delete(listOf("users", ""), "diet") }
            assertFailsWith<GraphValidationException> { store.list(preferences, limit = 0) }
        }
}
