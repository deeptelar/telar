@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.deeptelar.telar.checkpoint.browser

import dev.deeptelar.telar.Checkpoint
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
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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
    var now: Instant = Instant.fromEpochSeconds(1_800_000_000),
) : Clock {
    override fun now(): Instant = now
}

private val preferences = listOf("preferences")

/** Runs in a real browser, against its `localStorage`. */
class LocalStorageStoreTest {
    private val clock = StillClock()

    private fun newStore(keyPrefix: String = "test.store.") = LocalStorageStore(keyPrefix, clock)

    @BeforeTest
    @AfterTest
    fun emptyTheStorage() = clearStorage()

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
            assertEquals(1, storageSize())

            reopened.delete(preferences, "diet")
            reopened.delete(preferences, "diet")
            assertNull(newStore().get(preferences, "diet"))
            assertEquals(0, storageSize())
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
            store.put(emptyList(), "version", "1")

            // The clock stood still. A new store goes on after the last write it finds.
            val reopened = newStore()
            reopened.put(listOf("users", "ana", "preferences"), "diet", "pescatarian")

            assertEquals(listOf("diet", "lunch", "name"), reopened.list(listOf("users", "ana")).map { it.key })
            assertEquals(listOf("diet", "lunch"), reopened.list(listOf("users", "ana"), limit = 2).map { it.key })
            assertEquals(5, reopened.list(listOf("users")).size)
            assertEquals(6, reopened.list(emptyList()).size)
            assertTrue(reopened.list(listOf("trips")).isEmpty())
            assertEquals(clock.now + 6.nanoseconds, reopened.list(emptyList()).first().updatedAt)
        }

    @Test
    fun anyCharacterInANamespaceOrAKeyKeepsItsItemsApart() =
        runTest {
            val store = newStore()
            val parts = listOf("ana", "Ana", "a/b", "a%2Fb", "a%b", "/", "%", "日本", "a b")
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
    fun theEntriesOfOtherPrefixesAreLeftOut() =
        runTest {
            newStore().put(preferences, "diet", "vegan")
            newStore("other.").put(preferences, "diet", "vegetarian")
            setStorageItem("test.store.not%ours", "x")
            LocalStorageCheckpointer(KotlinxStateSerializer<String>()).save(
                "thread",
                Checkpoint("Ana", nextNodes = listOf("next")),
            )

            assertEquals(listOf("vegan"), newStore().list(emptyList()).map { it.value })
            assertEquals(listOf("vegetarian"), newStore("other.").list(emptyList()).map { it.value })
        }

    @Test
    fun aDamagedEntryIsTold() =
        runTest {
            setStorageItem("test.store.users/diet", "{not json")

            val failure = assertFailsWith<StoreCorruptedException> { newStore().get(listOf("users"), "diet") }
            assertEquals(listOf("users"), failure.namespace)
            assertEquals("diet", failure.key)
            assertFailsWith<StoreCorruptedException> { newStore().list(emptyList()) }
        }

    @Test
    fun aFullStorageIsToldAndKeepsWhatItHad() =
        runTest {
            val store = newStore()
            store.put(preferences, "diet", "vegan")
            fillStorage()

            val failure = assertFailsWith<LocalStorageStoreException> { store.put(preferences, "notes", "x".repeat(8_192)) }
            assertEquals("notes", failure.key)
            assertEquals("vegan", store.get(preferences, "diet")?.value)
            assertNull(store.get(preferences, "notes"))
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
    fun aGraphRemembersAcrossRuns() =
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

            assertEquals(listOf("none"), graph.invoke(emptyList(), GraphConfig("plan-1", store = newStore().within("traveller"))).state)
            assertEquals(listOf("Porto"), graph.invoke(emptyList(), GraphConfig("plan-2", store = newStore().within("traveller"))).state)
        }
}

private fun clearStorage(): Unit = js("globalThis.localStorage.clear()")

private fun storageSize(): Int = js("globalThis.localStorage.length")

private fun setStorageItem(key: String, value: String): Unit = js("globalThis.localStorage.setItem(key, value)")

/** Fills the storage of the page until less than 4,096 characters are free, as the test of the checkpointer does. */
private fun fillStorage(): Unit =
    js(
        """
        (function () {
            var count = 0;
            [1048576, 65536, 4096].forEach(function (size) {
                try {
                    while (true) globalThis.localStorage.setItem('filler.' + count++, 'x'.repeat(size));
                } catch (full) {
                }
            });
        })()
        """,
    )
