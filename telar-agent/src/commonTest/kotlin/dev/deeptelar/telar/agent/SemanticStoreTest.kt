package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.MemoryStore
import dev.deeptelar.telar.within
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Makes a vector with one number for each topic: how many words of the text belong to it. Texts
 * about food point the same way without a word in common.
 */
private class TopicModel(
    override val id: String = "topics-1",
) : EmbeddingModel {
    val calls = mutableListOf<List<String>>()

    private val topics =
        listOf(
            setOf("eat", "eats", "food", "fish", "meat", "vegan", "piscivegetarian", "meal", "diet"),
            setOf("porto", "lisbon", "market", "city", "town", "visit", "trip"),
            setOf("lunch", "dinner", "day", "morning", "when"),
        )

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        calls += texts
        return texts.map { text ->
            val words = text.lowercase().split(Regex("[^a-z]+"))
            FloatArray(topics.size) { topic -> words.count { it in topics[topic] }.toFloat() }
        }
    }
}

private val memories = listOf("memories")

class SemanticStoreTest {
    private val base = MemoryStore()
    private val model = TopicModel()
    private val store = SemanticStore(base, model)

    private suspend fun fill() {
        store.put(memories, "diet", "Piscivegetarian: fish but no meat")
        store.put(memories, "trips", "Loved the market of Porto")
        store.put(memories, "schedule", "Lunch is the big one of the day")
    }

    @Test
    fun aSearchFindsWhatAQueryMeansWithoutItsWords() =
        runTest {
            fill()

            assertEquals(listOf("diet"), store.search(memories, "What does she eat?", limit = 1).map { it.key })
            assertEquals("trips", store.search(memories, "Which city should we visit?").first().key)
            assertEquals("schedule", store.search(memories, "When?").first().key)
            assertTrue(store.search(memories, "   ").isEmpty())
            assertTrue(store.search(listOf("trips"), "food").isEmpty())
        }

    @Test
    fun theVectorsAreMadeOnceAndKeptInTheStore() =
        runTest {
            fill()
            store.search(memories, "food")
            assertEquals(
                listOf(
                    listOf(
                        "schedule: Lunch is the big one of the day",
                        "trips: Loved the market of Porto",
                        "diet: Piscivegetarian: fish but no meat",
                    ),
                    listOf("food"),
                ),
                model.calls,
            )

            // A new store on the same items only makes the vector of the query.
            model.calls.clear()
            SemanticStore(base, model).search(memories, "a city")
            assertEquals(listOf(listOf("a city")), model.calls)

            // A changed value is made again, and so is everything for another model.
            model.calls.clear()
            store.put(memories, "diet", "Vegan")
            store.search(memories, "food")
            assertEquals(listOf(listOf("diet: Vegan"), listOf("food")), model.calls)
            val other = TopicModel(id = "topics-2")
            SemanticStore(base, other).search(memories, "food")
            assertEquals(4, other.calls.flatten().size)
        }

    @Test
    fun theVectorsAreLeftOutOfAListAndGoWithTheirItem() =
        runTest {
            fill()
            store.search(memories, "food")

            assertEquals(3, store.list(emptyList()).size)
            assertEquals(6, base.list(emptyList()).size)
            assertEquals(3, base.list(listOf(SemanticStore.VECTORS)).size)

            store.delete(memories, "diet")
            assertNull(store.get(memories, "diet"))
            assertNull(base.get(listOf(SemanticStore.VECTORS) + memories, "diet"))

            // An item deleted from the store underneath loses its vector at the next search.
            base.delete(memories, "trips")
            store.search(memories, "food")
            assertEquals(listOf("schedule"), base.list(listOf(SemanticStore.VECTORS)).map { it.key })
        }

    @Test
    fun aViewSearchesByMeaningInItsPart() =
        runTest {
            store.within("users", "ana").put(memories, "diet", "Piscivegetarian")
            store.within("users", "joan").put(memories, "diet", "Vegan")

            val found = store.within("users", "ana").search(memories, "What does she eat?")

            assertEquals(listOf(memories to "Piscivegetarian"), found.map { it.namespace to it.value })
            assertEquals(
                listOf("users", "ana"),
                base
                    .list(listOf(SemanticStore.VECTORS))
                    .single()
                    .namespace
                    .drop(1)
                    .take(2),
            )
        }

    @Test
    fun anItemBelowTheLowestScoreIsLeftOut() =
        runTest {
            fill()
            val strict = SemanticStore(base, model, minScore = 0.9)

            assertEquals(listOf("diet"), strict.search(memories, "fish and meat").map { it.key })
        }

    @Test
    fun recallFindsMemoriesByMeaning() =
        runTest {
            fill()
            val agent =
                ScriptedModel(
                    calls(call("1", "recall", "query" to "what does the traveller eat")),
                    says("Fish, then."),
                )
            toolAgent(agent, memoryTools(searchLimit = 1)).invoke(AgentState("Plan my dinner."), GraphConfig(store = store))

            val result =
                agent.requests[1]
                    .messages
                    .filterIsInstance<ChatMessage.ToolResult>()
                    .single()
            assertEquals("diet: Piscivegetarian: fish but no meat", result.text)
        }

    @Test
    fun aModelThatReturnsTooFewVectorsIsTold() =
        runTest {
            fill()
            val broken =
                object : EmbeddingModel {
                    override val id = "broken"

                    override suspend fun embed(texts: List<String>): List<FloatArray> = listOf(FloatArray(3))
                }

            assertFailsWith<EmbeddingModelException> { SemanticStore(base, broken).search(memories, "food") }
        }

    @Test
    fun theSettingsAreChecked() {
        assertFailsWith<GraphValidationException> { SemanticStore(base, model, minScore = 1.5) }
        assertFailsWith<GraphValidationException> { SemanticStore(base, model, scope = 0) }
    }
}
