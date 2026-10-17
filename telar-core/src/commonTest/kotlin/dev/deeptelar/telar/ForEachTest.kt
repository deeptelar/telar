package dev.deeptelar.telar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A search whose documents are summarized one by one. */
private data class Search(
    val documents: List<String>,
    val summaries: List<String> = emptyList(),
    val approved: Boolean = false,
    val tagged: Boolean = false,
)

/** Stores a result that is already a string. */
private object Saved : StateSerializer<String> {
    override fun serialize(state: String): String = state

    override fun deserialize(data: String): String = data
}

/** Stores a number, and fails to read anything else. */
private object Count : StateSerializer<Int> {
    override fun serialize(state: Int): String = state.toString()

    override fun deserialize(data: String): Int = data.toIntOrNull() ?: throw IllegalArgumentException("Not a number: $data")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ForEachTest {
    /** How often the work of each document ran. */
    private val runs = mutableMapOf<String, Int>()

    /** The documents whose work fails, until it is taken out of here. */
    private val failing = mutableSetOf<String>()

    private val checkpointer = MemoryCheckpointer<Search>()
    private val config = GraphConfig(threadId = "t", checkpointer = checkpointer)

    private suspend fun summarize(document: String): String {
        runs[document] = (runs[document] ?: 0) + 1
        // A longer document takes longer, so the items finish in another order than the list.
        delay(document.length * 10L)
        if (document in failing) error("The model did not answer for $document.")
        return document.uppercase()
    }

    private fun graph(
        serializer: StateSerializer<String>? = null,
        concurrency: Int = Int.MAX_VALUE,
        retry: Retry? = null,
        timeout: Duration? = null,
    ): CompiledGraph<Search> =
        StateGraph<Search> {
            val each =
                node(
                    "summarize",
                    forEach = { search -> search.documents },
                    work = { _, document -> summarize(document) },
                    serializer = serializer,
                    concurrency = concurrency,
                    retry = retry,
                    timeout = timeout,
                ) { search, summaries -> search.copy(summaries = summaries) }
            START then each then END
        }.compile()

    @Test
    fun `the results come in the order of the items`() =
        runTest {
            val result = graph().invoke(Search(listOf("ccc", "a", "bb")))

            assertEquals(GraphResult.Completed(Search(listOf("ccc", "a", "bb"), listOf("CCC", "A", "BB"))), result)
        }

    @Test
    fun `all items run at the same time`() =
        runTest {
            graph().invoke(Search(listOf("aaaa", "bbbb", "cccc")))

            assertEquals(40, currentTime)
        }

    @Test
    fun `concurrency limits how many items run at the same time`() =
        runTest {
            graph(concurrency = 2).invoke(Search(listOf("aaaa", "bbbb", "cccc", "dddd", "eeee")))

            assertEquals(120, currentTime)
        }

    @Test
    fun `an empty list gives the update no results`() =
        runTest {
            val result = graph().invoke(Search(emptyList(), summaries = listOf("old")))

            assertEquals(GraphResult.Completed(Search(emptyList())), result)
        }

    @Test
    fun `an item that fails fails the node and names the item`() =
        runTest {
            failing += "bb"

            val failure = assertFailsWith<NodeExecutionException> { graph().invoke(Search(listOf("ccc", "bb", "a"))) }

            assertEquals("summarize", failure.nodeName)
            assertEquals(1, failure.item)
            assertEquals("Node 'summarize' failed for item 1: The model did not answer for bb.", failure.message)
        }

    @Test
    fun `the items still running are cancelled when one fails`() =
        runTest {
            failing += "a"
            var finished = false
            val app =
                StateGraph<Search> {
                    val each =
                        node("summarize", forEach = { it.documents }, work = { _, document ->
                            if (document == "slow") delay(1_000).also { finished = true }
                            summarize(document)
                        }) { search, summaries -> search.copy(summaries = summaries) }
                    START then each then END
                }.compile()

            assertFailsWith<NodeExecutionException> { app.invoke(Search(listOf("slow", "a"))) }

            assertEquals(false, finished)
        }

    @Test
    fun `resume runs only the items that have no saved result`() =
        runTest {
            // The slowest item fails, after the others have finished.
            failing += "ccc"
            val app = graph(Saved)
            assertFailsWith<NodeExecutionException> { app.invoke(Search(listOf("ccc", "bb", "a")), config) }
            failing.clear()

            val result = app.resume(config)

            assertEquals(GraphResult.Completed(Search(listOf("ccc", "bb", "a"), listOf("CCC", "BB", "A"))), result)
            assertEquals(mapOf("ccc" to 2, "bb" to 1, "a" to 1), runs)
        }

    @Test
    fun `without a serializer every item runs again`() =
        runTest {
            failing += "bb"
            val app = graph()
            assertFailsWith<NodeExecutionException> { app.invoke(Search(listOf("ccc", "bb", "a")), config) }
            failing.clear()

            app.resume(config)

            assertEquals(mapOf("ccc" to 2, "bb" to 2, "a" to 2), runs)
        }

    @Test
    fun `the checkpoint of a failed step holds the results of the items that finished`() =
        runTest {
            failing += "ccc"
            assertFailsWith<NodeExecutionException> { graph(Saved).invoke(Search(listOf("ccc", "bb", "a")), config) }

            assertEquals(mapOf("summarize/%1" to "BB", "summarize/%2" to "A"), checkpointer.load("t")?.effects)
        }

    @Test
    fun `an item is tried again on its own`() =
        runTest {
            val tried = mutableListOf<String>()
            val observer =
                object : GraphObserver {
                    override fun onNodeRetry(node: ObservedNode, attempt: Int, cause: Throwable, pause: Duration) {
                        tried += "${node.name} $attempt"
                    }
                }
            var answers = 0
            val app =
                StateGraph<Search> {
                    val each =
                        node(
                            "summarize",
                            forEach = { it.documents },
                            work = { _, document -> if (document == "bb" && answers++ == 0) error("No answer.") else summarize(document) },
                            retry = Retry(attempts = 2, pause = 5.milliseconds),
                        ) { search, summaries -> search.copy(summaries = summaries) }
                    START then each then END
                }.compile()

            val events = app.stream(Search(listOf("a", "bb")), GraphConfig(observers = listOf(observer))).toList()

            val retry = events.filterIsInstance<GraphEvent.NodeRetry<Search>>().single()
            assertEquals(1, retry.item)
            assertEquals(1, retry.attempt)
            assertEquals(listOf("summarize 1"), tried)
            assertEquals(mapOf("a" to 1, "bb" to 1), runs)
            assertEquals(listOf("A", "BB"), (events.last() as GraphEvent.Completed).state.summaries)
        }

    @Test
    fun `an item that fails its last try names how often it ran`() =
        runTest {
            failing += "a"

            val failure =
                assertFailsWith<NodeExecutionException> {
                    graph(retry = Retry(attempts = 3, pause = Duration.ZERO)).invoke(Search(listOf("a", "bb")))
                }

            assertEquals(3, failure.attempts)
            assertEquals(0, failure.item)
            assertEquals(mapOf("a" to 3, "bb" to 1), runs)
        }

    @Test
    fun `the timeout holds for one item`() =
        runTest {
            val failure = assertFailsWith<NodeExecutionException> { graph(timeout = 25.milliseconds).invoke(Search(listOf("a", "bbbbb"))) }

            assertIs<NodeTimeoutException>(failure.cause)
            assertEquals(1, failure.item)
        }

    @Test
    fun `an item may return null within its timeout`() =
        runTest {
            val app =
                StateGraph<Search> {
                    val each =
                        node("count", forEach = { it.documents }, work = {
                            _,
                            _,
                            ->
                            null as String?
                        }, timeout = 1.milliseconds) { search, found ->
                            search.copy(summaries = found.map { it ?: "none" })
                        }
                    START then each then END
                }.compile()

            assertEquals(listOf("none"), (app.invoke(Search(listOf("a"))) as GraphResult.Completed).state.summaries)
        }

    @Test
    fun `a stream has an event for each item between the start and the end of the node`() =
        runTest {
            val app =
                StateGraph<Search> {
                    val each =
                        node("summarize", forEach = { it.documents }, work = { _, document ->
                            reportProgress("reading $document")
                            summarize(document)
                        }) { search, summaries -> search.copy(summaries = summaries) }
                    START then each then END
                }.compile()
            val input = Search(listOf("bb", "a"))

            val events = app.stream(input).toList()

            assertEquals(
                listOf<GraphEvent<Search>>(
                    GraphEvent.NodeStarted(1, "summarize", input),
                    GraphEvent.NodeProgress(1, "summarize", "reading bb", input, item = 0),
                    GraphEvent.NodeProgress(1, "summarize", "reading a", input, item = 1),
                    GraphEvent.ItemCompleted(1, "summarize", 1, "A", input),
                    GraphEvent.ItemCompleted(1, "summarize", 0, "BB", input),
                ),
                events.take(5),
            )
            assertIs<GraphEvent.NodeCompleted<Search>>(events[5])
        }

    @Test
    fun `an item whose result was saved has its event too`() =
        runTest {
            failing += "bb"
            val app = graph(Saved)
            assertFailsWith<NodeExecutionException> { app.invoke(Search(listOf("bb", "a")), config) }
            failing.clear()

            val items = app.streamResume(config).toList().filterIsInstance<GraphEvent.ItemCompleted<Search>>()

            assertEquals(listOf(1 to "A", 0 to "BB"), items.map { it.item to it.result })
        }

    @Test
    fun `an effect in the work belongs to its item`() =
        runTest {
            val charged = mutableListOf<String>()
            var shipFails = true
            val app =
                StateGraph<Search> {
                    val each =
                        node("charge", forEach = { it.documents }, work = { _, customer ->
                            effect("card", Saved) { "receipt for $customer".also { charged += it } }
                        }) { search, receipts -> search.copy(summaries = receipts) }
                    val ship =
                        node("ship", work = { _ ->
                            delay(10)
                            if (shipFails) error("The courier did not answer.")
                        }) { search, _ -> search.copy(approved = true) }
                    START then each then END
                    START then ship then END
                }.compile()
            assertFailsWith<NodeExecutionException> { app.invoke(Search(listOf("ann", "bob")), config) }
            shipFails = false

            val result = app.resume(config)

            assertEquals(listOf("receipt for ann", "receipt for bob"), (result as GraphResult.Completed).state.summaries)
            assertEquals(listOf("receipt for ann", "receipt for bob"), charged)
        }

    @Test
    fun `an item that pauses the run keeps the results of the others`() =
        runTest {
            val app =
                StateGraph<Search> {
                    val each =
                        node("summarize", forEach = { it.documents }, serializer = Saved, work = { search, document ->
                            if (document == "bb") {
                                // Ask once "a" has its result.
                                delay(50)
                                if (!search.approved) interrupt(search)
                            }
                            summarize(document)
                        }) { search, summaries -> search.copy(summaries = summaries) }
                    START then each then END
                }.compile()

            val paused = app.invoke(Search(listOf("a", "bb")), config)
            val result = app.resume(config) { it.copy(approved = true) }

            assertEquals(GraphResult.Interrupted(Search(listOf("a", "bb")), listOf("summarize")), paused)
            assertEquals(listOf("A", "BB"), (result as GraphResult.Completed).state.summaries)
            assertEquals(mapOf("a" to 1, "bb" to 1), runs)
        }

    @Test
    fun `inside a subgraph the saved results are those of the step around it`() =
        runTest {
            failing += "ccc"
            val app =
                StateGraph<Search> {
                    val inner = subgraph("read", graph(Saved))
                    START then inner then END
                }.compile()
            assertFailsWith<NodeExecutionException> { app.invoke(Search(listOf("ccc", "bb", "a")), config) }
            failing.clear()

            val result = app.resume(config)

            assertEquals(listOf("CCC", "BB", "A"), (result as GraphResult.Completed).state.summaries)
            assertEquals(mapOf("ccc" to 2, "bb" to 1, "a" to 1), runs)
        }

    @Test
    fun `it runs next to another node without a reducer`() =
        runTest {
            val app =
                StateGraph<Search> {
                    val each =
                        node("summarize", forEach = { it.documents }, work = { _, document -> summarize(document) }) { search, summaries ->
                            search.copy(summaries = summaries)
                        }
                    val tag = node("tag", work = { }) { search, _ -> search.copy(tagged = true) }
                    START then each then END
                    START then tag then END
                }.compile()

            val result = app.invoke(Search(listOf("a")))

            assertEquals(GraphResult.Completed(Search(listOf("a"), listOf("A"), tagged = true)), result)
        }

    @Test
    fun `a failure of the update or of the list names the node`() =
        runTest {
            val badList =
                StateGraph<Search> {
                    val each = node("summarize", forEach = { error("No list.") }, work = { _, _: String -> "" }) { search, _ -> search }
                    START then each then END
                }.compile()
            val badUpdate =
                StateGraph<Search> {
                    val each =
                        node("summarize", forEach = { it.documents }, work = {
                            _,
                            document,
                            ->
                            document
                        }) { _, _ -> error("No room.") }
                    START then each then END
                }.compile()

            val list = assertFailsWith<NodeExecutionException> { badList.invoke(Search(listOf("a"))) }
            val update = assertFailsWith<NodeExecutionException> { badUpdate.invoke(Search(listOf("a"))) }

            assertNull(list.item)
            assertEquals("No list.", list.cause?.message)
            assertNull(update.item)
            assertEquals("No room.", update.cause?.message)
        }

    @Test
    fun `a saved result that cannot be read is a corrupted checkpoint`() =
        runTest {
            val numbers = MemoryCheckpointer<Search>()
            val app =
                StateGraph<Search> {
                    val each =
                        node("count", forEach = { it.documents }, work = {
                            _,
                            document,
                            ->
                            document.length
                        }, serializer = Count) { search, counts ->
                            search.copy(summaries = counts.map { it.toString() })
                        }
                    START then each then END
                }.compile()
            numbers.save("t", Checkpoint(Search(listOf("a")), listOf("count"), effects = mapOf("count/%0" to "many")))

            val failure = assertFailsWith<CheckpointCorruptedException> { app.resume(GraphConfig(threadId = "t", checkpointer = numbers)) }

            assertEquals(
                "Checkpoint of thread 't' cannot be read: the saved result of item 0 of node 'count' does not match its type",
                failure.message,
            )
        }

    @Test
    fun `the topology names the nodes that run for each item`() {
        val topology = graph().topology

        assertEquals(setOf("summarize"), topology.forEachNodes)
        assertEquals(
            """
            stateDiagram-v2
                state "summarize" as n0
                [*] --> n0
                n0 --> [*]
                note right of n0 : runs for each item
            """.trimIndent(),
            topology.toMermaid(),
        )
    }

    @Test
    fun `the concurrency must be positive`() {
        val failure = assertFailsWith<GraphValidationException> { graph(concurrency = 0) }

        assertEquals("The concurrency of node 'summarize' must be positive, but was 0.", failure.message)
    }

    @Test
    fun `the timeout must be positive`() {
        assertFailsWith<GraphValidationException> { graph(timeout = Duration.ZERO) }
    }
}
