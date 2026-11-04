package dev.deeptelar.telar.serialization

import dev.deeptelar.telar.MemoryStore
import dev.deeptelar.telar.StoreCorruptedException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Serializable
private data class Diet(
    val name: String,
    val avoids: List<String> = emptyList(),
)

private val preferences = listOf("preferences")

class StoreTest {
    @Test
    fun `keeps a serializable value as JSON and reads it back`() =
        runTest {
            val store = MemoryStore()
            store.put(preferences, "diet", Diet("piscivegetarian", listOf("meat")))

            assertEquals(Diet("piscivegetarian", listOf("meat")), store.get<Diet>(preferences, "diet"))
            assertEquals("""{"name":"piscivegetarian","avoids":["meat"]}""", store.get(preferences, "diet")?.value)
            assertNull(store.get<Diet>(preferences, "budget"))

            store.put(preferences, "trips", 3)
            assertEquals(3, store.get<Int>(preferences, "trips"))
        }

    @Test
    fun `keeps a string as it is`() =
        runTest {
            val store = MemoryStore()
            store.put(preferences, "lunch", "the big meal")
            assertEquals("the big meal", store.get<String>(preferences, "lunch"))
            assertEquals("the big meal", store.get(preferences, "lunch")?.value)
            assertEquals("the big meal", putThenGet(store, "the big meal"))
        }

    @Test
    fun `tells when a value does not match its type`() =
        runTest {
            val store = MemoryStore()
            store.put(preferences, "diet", "vegan")
            assertFailsWith<StoreCorruptedException> { store.get<Diet>(preferences, "diet") }
        }

    /** Puts and gets a value whose type reaches the overloads only as a type parameter. */
    private suspend inline fun <reified T> putThenGet(store: MemoryStore, value: T): T? {
        store.put(preferences, "value", value)
        return store.get<T>(preferences, "value")
    }
}
