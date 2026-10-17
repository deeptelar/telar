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
private data class Summary(
    val title: String,
    val words: Int,
)

private data class Reading(
    val documents: List<String>,
    val summaries: List<Summary> = emptyList(),
)

class ForEachTest {
    @Test
    fun `a node for each item saves serializable results and does not run them again`() =
        runTest {
            val runs = mutableListOf<String>()
            var modelUp = false
            val graph =
                StateGraph<Reading> {
                    START then
                        node(
                            "summarize",
                            forEach = { it.documents },
                            work = { _, document ->
                                runs += document
                                if (document == "late" && !modelUp) error("The model is down.")
                                Summary(document, document.length)
                            },
                            serializer = KotlinxStateSerializer(),
                        ) { reading, summaries -> reading.copy(summaries = summaries) } then END
                }.compile()
            val checkpointer = MemoryCheckpointer<Reading>()
            val config = GraphConfig(threadId = "t", checkpointer = checkpointer)

            assertFailsWith<NodeExecutionException> { graph.invoke(Reading(listOf("early", "late")), config) }
            assertEquals(mapOf("summarize/%0" to """{"title":"early","words":5}"""), checkpointer.load("t")?.effects)
            modelUp = true

            val result = graph.resume(config)

            assertEquals(GraphResult.Completed(Reading(listOf("early", "late"), listOf(Summary("early", 5), Summary("late", 4)))), result)
            assertEquals(listOf("early", "late", "late"), runs)
        }
}
