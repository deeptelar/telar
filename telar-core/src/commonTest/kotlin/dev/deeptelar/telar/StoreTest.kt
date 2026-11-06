package dev.deeptelar.telar

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A trip whose plan follows what the store remembers about the traveller. */
private data class Trip(
    val diet: String? = null,
    val notes: List<String> = emptyList(),
)

/** Stores a number, and fails to read anything else. */
private object TripCount : StateSerializer<Int> {
    override fun serialize(state: Int): String = state.toString()

    override fun deserialize(data: String): Int = data.toIntOrNull() ?: throw IllegalArgumentException("Not a number: $data")
}

/** A clock that stands still, so that items put one after the other have the same time. */
private class StillClock(
    var now: Instant = Instant.fromEpochSeconds(1_800_000_000),
) : Clock {
    override fun now(): Instant = now
}

private val preferences = listOf("preferences")

class StoreTest {
    @Test
    fun aValueIsReadUnderItsNamespaceAndKeyUntilItIsDeleted() =
        runTest {
            val clock = StillClock()
            val store = MemoryStore(clock)
            store.put(preferences, "diet", "piscivegetarian")

            assertEquals(StoreItem(preferences, "diet", "piscivegetarian", clock.now), store.get(preferences, "diet"))
            assertNull(store.get(listOf("trips"), "diet"))
            assertNull(store.get(preferences, "budget"))

            store.delete(preferences, "diet")
            store.delete(preferences, "diet")
            assertNull(store.get(preferences, "diet"))
        }

    @Test
    fun aPutReplacesTheValueAndItsTime() =
        runTest {
            val clock = StillClock()
            val store = MemoryStore(clock)
            store.put(preferences, "diet", "vegetarian")
            clock.now = Instant.fromEpochSeconds(1_900_000_000)
            store.put(preferences, "diet", "piscivegetarian")

            assertEquals(StoreItem(preferences, "diet", "piscivegetarian", clock.now), store.get(preferences, "diet"))
            assertEquals(1, store.list(emptyList()).size)
        }

    @Test
    fun aListHasTheNamespacesUnderItTheLastChangedFirst() =
        runTest {
            val store = MemoryStore(StillClock())
            store.put(listOf("users", "ana", "preferences"), "diet", "piscivegetarian")
            store.put(listOf("users", "ana"), "name", "Ana")
            store.put(listOf("users", "joan", "preferences"), "diet", "vegan")
            store.put(listOf("users", "ana", "preferences"), "lunch", "the big meal")
            // A namespace that starts with the same letters is another one.
            store.put(listOf("users", "anna"), "name", "Anna")

            val ana = store.list(listOf("users", "ana"))
            assertEquals(listOf("lunch", "name", "diet"), ana.map { it.key })
            assertEquals(listOf("lunch", "diet"), store.list(listOf("users", "ana", "preferences")).map { it.key })
            assertEquals(listOf("lunch", "name"), store.list(listOf("users", "ana"), limit = 2).map { it.key })
            assertEquals(5, store.list(emptyList()).size)
            assertTrue(store.list(listOf("trips")).isEmpty())

            // Putting a value again makes it the last changed.
            store.put(listOf("users", "ana", "preferences"), "diet", "pescatarian")
            assertEquals(listOf("diet", "lunch", "name"), store.list(listOf("users", "ana")).map { it.key })
        }

    @Test
    fun aKeyANamespaceAndALimitAreChecked() =
        runTest {
            val store = MemoryStore()
            assertFailsWith<GraphValidationException> { store.put(preferences, "", "x") }
            assertFailsWith<GraphValidationException> { store.get(listOf("users", ""), "diet") }
            assertFailsWith<GraphValidationException> { store.delete(listOf(""), "diet") }
            assertFailsWith<GraphValidationException> { store.list(listOf("")) }
            assertFailsWith<GraphValidationException> { store.list(preferences, limit = 0) }
            assertFailsWith<GraphValidationException> { store.within() }
            assertFailsWith<GraphValidationException> { store.within("users", "") }
        }

    @Test
    fun aViewAddsItsNamespaceAndLeavesItOutOfWhatItReturns() =
        runTest {
            val clock = StillClock()
            val store = MemoryStore(clock)
            val ana = store.within("users", "ana")
            ana.put(preferences, "diet", "piscivegetarian")

            assertEquals("piscivegetarian", store.get(listOf("users", "ana", "preferences"), "diet")?.value)
            assertEquals(StoreItem(preferences, "diet", "piscivegetarian", clock.now), ana.get(preferences, "diet"))
            assertEquals(listOf(StoreItem(preferences, "diet", "piscivegetarian", clock.now)), ana.list(emptyList()))
            assertNull(store.within("users", "joan").get(preferences, "diet"))

            // A view of a view adds both parts.
            val anasPreferences = ana.within("preferences")
            assertEquals(StoreItem(emptyList(), "diet", "piscivegetarian", clock.now), anasPreferences.get(emptyList(), "diet"))
            assertEquals(1, anasPreferences.list(emptyList()).size)

            anasPreferences.delete(emptyList(), "diet")
            assertNull(store.get(listOf("users", "ana", "preferences"), "diet"))
        }

    @Test
    fun aTypedValueIsWrittenAndReadWithItsSerializer() =
        runTest {
            val store = MemoryStore()
            store.put(listOf("trips"), "count", 3, TripCount)
            assertEquals(3, store.get(listOf("trips"), "count", TripCount))
            assertNull(store.get(listOf("trips"), "budget", TripCount))

            store.put(listOf("trips"), "count", "three")
            val failure = assertFailsWith<StoreCorruptedException> { store.get(listOf("trips"), "count", TripCount) }
            assertEquals(listOf("trips"), failure.namespace)
            assertEquals("count", failure.key)
            assertIs<IllegalArgumentException>(failure.cause)
        }

    @Test
    fun aNodeAndTheWorkOfANodeReachTheStoreOfTheRun() =
        runTest {
            val store = MemoryStore()
            store.within("users", "ana").put(preferences, "diet", "piscivegetarian")
            val graph =
                StateGraph<Trip> {
                    // A node reads with store(), even next to a variable of the same name.
                    val read = node("read") { trip -> trip.copy(diet = store().get(preferences, "diet")?.value) }
                    val write =
                        node("write", work = { _ -> store().put(listOf("trips"), "last", "Lisbon") }) { trip, _ ->
                            trip.copy(notes = trip.notes + "written")
                        }
                    START then read then write then END
                }.compile()

            val trip = graph.invoke(Trip(), GraphConfig(store = store.within("users", "ana"))).state

            assertEquals("piscivegetarian", trip.diet)
            assertEquals("Lisbon", store.get(listOf("users", "ana", "trips"), "last")?.value)
        }

    @Test
    fun theNodesOfASubgraphAndTheItemsOfAForEachReachTheStoreAroundThem() =
        runTest {
            val store = MemoryStore()
            val inner =
                StateGraph<Trip> {
                    val read = node("read") { trip -> trip.copy(diet = store().get(preferences, "diet")?.value) }
                    START then read then END
                }.compile()
            val graph =
                StateGraph<Trip> {
                    val plan = subgraph("plan", inner)
                    val towns =
                        node("towns", forEach = { listOf("Porto", "Lisbon") }, work = {
                            _,
                            town,
                            ->
                            store().put(listOf("towns"), town, "seen")
                        }) {
                            trip,
                            _,
                            ->
                            trip
                        }
                    START then plan then towns then END
                }.compile()
            store.put(preferences, "diet", "vegan")

            assertEquals("vegan", graph.invoke(Trip(), GraphConfig(store = store)).state.diet)
            assertEquals(setOf("Porto", "Lisbon"), store.list(listOf("towns")).map { it.key }.toSet())
        }

    @Test
    fun aRunWithoutAStoreAndCodeOutsideANodeAreTold() =
        runTest {
            val graph =
                StateGraph<Trip> {
                    val read = node("read") { trip -> trip.copy(diet = store().get(preferences, "diet")?.value) }
                    START then read then END
                }.compile()

            val failure = assertFailsWith<NodeExecutionException> { graph.invoke(Trip()) }
            assertIs<GraphValidationException>(failure.cause)
            assertTrue("GraphConfig(store = ...)" in failure.cause!!.message!!)
            assertFailsWith<GraphValidationException> { store() }
        }

    @Test
    fun aStepThatRunsAgainLeavesTheValueOfItsLastTry() =
        runTest {
            val store = MemoryStore()
            var tries = 0
            val graph =
                StateGraph<Trip> {
                    val note =
                        node("note", retry = Retry(attempts = 2, pause = kotlin.time.Duration.ZERO)) { trip ->
                            tries++
                            store().put(preferences, "diet", "try $tries")
                            if (tries == 1) throw IllegalStateException("The model did not answer.")
                            trip.copy(diet = "try $tries")
                        }
                    START then note then END
                }.compile()

            val trip = graph.invoke(Trip(), GraphConfig(store = store)).state

            assertEquals("try 2", trip.diet)
            assertEquals("try 2", store.get(preferences, "diet")?.value)
        }

    @Test
    fun deletingAThreadLeavesTheStore() =
        runTest {
            val store = MemoryStore()
            val checkpointer = MemoryCheckpointer<Trip>()
            val graph =
                StateGraph<Trip> {
                    val note = node("note") { trip -> trip.also { store().put(preferences, "diet", "vegan") } }
                    START then note then END
                }.compile()

            graph.invoke(Trip(), GraphConfig("plan-1", checkpointer, store = store))
            checkpointer.delete("plan-1")

            assertEquals("vegan", store.get(preferences, "diet")?.value)
        }

    @Test
    fun aSearchFindsTheItemsWithTheWordsOfTheQuery() =
        runTest {
            val store = MemoryStore(StillClock())
            store.put(listOf("users", "ana"), "diet", "Piscivegetarian: fish, but no meat.")
            store.put(listOf("users", "ana"), "lunch", "Lunch is the big meal of the day.")
            store.put(listOf("users", "ana", "trips"), "porto", "Loved the fish market in Porto.")
            store.put(listOf("users", "joan"), "diet", "Vegan, no fish.")

            // Two words of the query in "porto", one in "diet": the namespaces under it are searched too.
            assertEquals(listOf("porto", "diet"), store.search(listOf("users", "ana"), "Fish MARKET?").map { it.key })
            // A key counts as well, and of two items with as many words the last changed comes first.
            assertEquals(
                listOf(listOf("users", "joan") to "diet", listOf("users", "ana") to "diet", listOf("users", "ana", "trips") to "porto"),
                store.search(emptyList(), "diet fish").map { it.namespace to it.key },
            )
            assertEquals(listOf("porto"), store.search(listOf("users", "ana"), "fish", limit = 1).map { it.key })
            assertTrue(store.search(listOf("users", "ana"), "pizza").isEmpty())
            // Words of one letter and signs are no words.
            assertTrue(store.search(listOf("users", "ana"), "a ? !").isEmpty())
            assertFailsWith<GraphValidationException> { store.search(listOf(""), "fish") }
            assertFailsWith<GraphValidationException> { store.search(emptyList(), "fish", limit = 0) }
        }

    @Test
    fun aViewSearchesItsPartOfTheStoreWithTheSearchOfTheStore() =
        runTest {
            val searched = mutableListOf<List<String>>()
            val store =
                object : Store by MemoryStore() {
                    override suspend fun search(namespace: List<String>, query: String, limit: Int): List<StoreItem> {
                        searched += namespace
                        return list(namespace, limit)
                    }
                }
            store.put(listOf("users", "ana", "memories"), "diet", "fish")

            val found = store.within("users", "ana").search(listOf("memories"), "anything", limit = 3)

            assertEquals(listOf(listOf("users", "ana", "memories")), searched)
            assertEquals(listOf(StoreItem(listOf("memories"), "diet", "fish", found.single().updatedAt)), found)
        }
}
