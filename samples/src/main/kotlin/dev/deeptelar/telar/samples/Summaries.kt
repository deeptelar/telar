package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class Summary(
    val document: String,
    val text: String,
)

data class Reading(
    val topic: String,
    val documents: List<String> = emptyList(),
    val summaries: List<Summary> = emptyList(),
)

/**
 * A search finds documents, and "summarize" runs once for each of them, all at the same time.
 * [summarize] stands in for a call to a model. Each summary is saved as soon as it is there, so a
 * resume after a failure only summarizes the documents that have none.
 */
fun summariesGraph(summarize: suspend (String) -> String): CompiledGraph<Reading> =
    StateGraph<Reading> {
        val search = node("search") { it.copy(documents = listOf("intro", "guide", "faq", "changelog")) }
        val each =
            node(
                "summarize",
                forEach = { reading -> reading.documents },
                work = { _, document -> Summary(document, summarize(document)) },
                serializer = KotlinxStateSerializer(),
            ) { reading, summaries -> reading.copy(summaries = summaries) }

        START then search then each then END
    }.compile()

suspend fun main() {
    // The model does not answer for "faq" the first time.
    var faqAnswered = false
    val graph =
        summariesGraph { document ->
            // "faq" takes longest, so the others have their summaries when it fails.
            delay(if (document == "faq") 400 else 200)
            if (document == "faq" && !faqAnswered) error("The model did not answer.")
            "a short summary of $document"
        }
    val config = GraphConfig(threadId = "reading", checkpointer = MemoryCheckpointer<Reading>())

    try {
        graph.stream(Reading("coroutines"), config).collect { event ->
            if (event is GraphEvent.ItemCompleted) println("summarized item ${event.item}: ${(event.result as Summary).document}")
        }
    } catch (failure: NodeExecutionException) {
        println(failure.message)
    }

    faqAnswered = true
    println("resuming: only the documents without a summary run again")
    graph.streamResume(config).collect { event ->
        if (event is GraphEvent.ItemCompleted) println("item ${event.item}: ${(event.result as Summary).text}")
        if (event is GraphEvent.Completed) println("done: ${event.state.summaries.size} summaries")
    }
}
