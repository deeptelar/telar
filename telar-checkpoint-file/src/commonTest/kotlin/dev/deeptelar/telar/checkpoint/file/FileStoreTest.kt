package dev.deeptelar.telar.checkpoint.file

import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.StoreCorruptedException
import dev.deeptelar.telar.StoreItem
import dev.deeptelar.telar.store
import dev.deeptelar.telar.within
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/** A clock that stands still unless it is moved, so that writes one after the other have the same time. */
private class StillClock(
    var now: Instant = Instant.fromEpochSeconds(1_800_000_000),
) : Clock {
    override fun now(): Instant = now
}

private val preferences = listOf("preferences")

class FileStoreTest {
    private val directory = Path(SystemTemporaryDirectory, "telar-store-test-${Random.nextLong().toULong()}")
    private val clock = StillClock()

    private fun newStore() = FileStore(directory, clock = clock)

    @AfterTest
    fun cleanUp() {
        deleteAll(directory)
    }

    private fun deleteAll(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach { deleteAll(it) }
        SystemFileSystem.delete(path, mustExist = false)
    }

    @Test
    fun aValueIsKeptReplacedAndDeletedAndOutlivesTheStore() =
        runTest {
            val store = newStore()
            store.put(preferences, "diet", "vegetarian")
            clock.now = Instant.fromEpochSeconds(1_900_000_000)
            store.put(preferences, "diet", "piscivegetarian")

            val reopened = newStore()
            assertEquals(StoreItem(preferences, "diet", "piscivegetarian", clock.now), reopened.get(preferences, "diet"))
            assertNull(reopened.get(preferences, "budget"))
            assertNull(reopened.get(listOf("trips"), "diet"))
            assertEquals(1, reopened.list(emptyList()).size)

            reopened.delete(preferences, "diet")
            reopened.delete(preferences, "diet")
            assertNull(newStore().get(preferences, "diet"))
        }

    @Test
    fun aListHasTheNamespacesUnderItTheLastChangedFirst() =
        runTest {
            val store = newStore()
            store.put(listOf("users", "ana", "preferences"), "diet", "piscivegetarian")
            store.put(listOf("users", "ana"), "name", "Ana")
            store.put(listOf("users", "joan", "preferences"), "diet", "vegan")
            store.put(listOf("users", "ana", "preferences"), "lunch", "the big meal")
            // A namespace that starts with the same letters is another one.
            store.put(listOf("users", "anna"), "name", "Anna")

            val reopened = newStore()
            assertEquals(listOf("lunch", "name", "diet"), reopened.list(listOf("users", "ana")).map { it.key })
            assertEquals(listOf("lunch", "name"), reopened.list(listOf("users", "ana"), limit = 2).map { it.key })
            assertEquals(5, reopened.list(emptyList()).size)
            assertTrue(reopened.list(listOf("trips")).isEmpty())

            // The clock stood still, and each write still came after the one before.
            val times = reopened.list(emptyList()).map { it.updatedAt }
            assertEquals(times.sortedDescending(), times)
            assertEquals(times.size, times.toSet().size)
            assertEquals(clock.now + 4.nanoseconds, times.first())

            // A new store starts from its clock, which has moved on since.
            clock.now += 1_000.nanoseconds
            reopened.put(listOf("users", "ana", "preferences"), "diet", "pescatarian")
            assertEquals(listOf("diet", "lunch", "name"), reopened.list(listOf("users", "ana")).map { it.key })
        }

    @Test
    fun theItemsOfANamespaceAreInOneFileOfItsFolder() =
        runTest {
            val store = newStore()
            store.put(listOf("users", "ana"), "diet", "vegan")
            store.put(listOf("users", "ana"), "lunch", "the big meal")

            val file = Path(directory, "users", "ana", "items.json")
            assertTrue(SystemFileSystem.exists(file))
            assertEquals(listOf("items.json"), SystemFileSystem.list(Path(directory, "users", "ana")).map { it.name })

            store.delete(listOf("users", "ana"), "diet")
            assertTrue(SystemFileSystem.exists(file))
            store.delete(listOf("users", "ana"), "lunch")
            assertFalse(SystemFileSystem.exists(file))
            assertTrue(store.list(emptyList()).isEmpty())
        }

    @Test
    fun anyCharacterInANamespaceOrAKeyKeepsItsItemsApart() =
        runTest {
            val store = newStore()
            val parts = listOf("ana", "Ana", "a/b", "a%2Fb", "items.json", "..", "日本", "a b")
            parts.forEach { part -> store.put(listOf("users", part), part, "value of $part") }

            val reopened = newStore()
            parts.forEach { part ->
                assertEquals("value of $part", reopened.get(listOf("users", part), part)?.value, part)
                assertEquals(listOf(listOf("users", part)), reopened.list(listOf("users", part)).map { it.namespace }, part)
            }
            assertEquals(parts.toSet(), reopened.list(listOf("users")).map { it.key }.toSet())
        }

    @Test
    fun aFolderOrAFileThatIsNotOfTheStoreIsLeftOut() =
        runTest {
            val store = newStore()
            store.put(listOf("users"), "count", "2")
            SystemFileSystem.createDirectories(Path(directory, "users", "Not Ours"))
            SystemFileSystem.sink(Path(directory, "users", "notes.txt")).buffered().use { it.writeString("hello") }

            assertEquals(listOf("count"), store.list(emptyList()).map { it.key })
        }

    @Test
    fun aDamagedFileIsTold() =
        runTest {
            val store = newStore()
            store.put(listOf("users", "ana"), "diet", "vegan")
            SystemFileSystem.sink(Path(directory, "users", "ana", "items.json")).buffered().use { it.writeString("{not json") }

            val failure = assertFailsWith<StoreCorruptedException> { store.get(listOf("users", "ana"), "diet") }
            assertEquals(listOf("users", "ana"), failure.namespace)
            assertNull(failure.key)
            assertFailsWith<StoreCorruptedException> { store.list(listOf("users")) }
        }

    @Test
    fun aKeyANamespaceAndALimitAreChecked() =
        runTest {
            val store = newStore()
            assertFailsWith<GraphValidationException> { store.put(preferences, "", "x") }
            assertFailsWith<GraphValidationException> { store.get(listOf(""), "diet") }
            assertFailsWith<GraphValidationException> { store.delete(listOf("users", ""), "diet") }
            assertFailsWith<GraphValidationException> { store.list(preferences, limit = 0) }
        }

    @Test
    fun aGraphRemembersAcrossRunsAndRestarts() =
        runTest {
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

            assertEquals(listOf("none"), graph.invoke(emptyList(), GraphConfig("plan-1", store = newStore().within("users", "ana"))).state)
            assertEquals(listOf("Porto"), graph.invoke(emptyList(), GraphConfig("plan-2", store = newStore().within("users", "ana"))).state)
            assertEquals(listOf("none"), graph.invoke(emptyList(), GraphConfig("plan-3", store = newStore().within("users", "joan"))).state)
        }
}
