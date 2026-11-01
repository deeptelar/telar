package dev.deeptelar.telar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

data class ParallelState(
    val messages: List<String> = emptyList(),
)

class ReducerTest {
    private val appendMessages =
        Reducer<ParallelState> { current, updates ->
            current.copy(messages = current.messages + updates.flatMap { it.messages - current.messages.toSet() })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `fan-out runs branches in parallel and fan-in runs the join once`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    node("branchA") {
                        delay(100)
                        it.copy(messages = it.messages + "A")
                    }
                    node("branchB") {
                        delay(100)
                        it.copy(messages = it.messages + "B")
                    }
                    node("aggregator") { it.copy(messages = it.messages + "Aggregated") }

                    edge(START, "branchA")
                    edge(START, "branchB")
                    edge("branchA", "aggregator")
                    edge("branchB", "aggregator")
                    edge("aggregator", END)
                }.compile(reducer = appendMessages)

            val events = app.stream(ParallelState()).toList()

            assertEquals(listOf("A", "B", "Aggregated"), events.last().state.messages)
            assertEquals(
                listOf(listOf("branchA", "branchB"), listOf("aggregator")),
                events.filterIsInstance<GraphEvent.StepCompleted<ParallelState>>().map { it.nodes },
            )
            // Both 100 ms branches overlap, so virtual time advances by 100 ms, not 200 ms.
            assertEquals(100, currentTime)
        }

    @Test
    fun `reducer can suspend`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    node("a") { it.copy(messages = listOf("A")) }
                    node("b") { it.copy(messages = listOf("B")) }
                    edge(START, "a")
                    edge(START, "b")
                }.compile { current, updates ->
                    delay(10)
                    current.copy(messages = updates.flatMap { it.messages })
                }

            assertEquals(listOf("A", "B"), app.invoke(ParallelState()).state.messages)
        }

    @Test
    fun `a reducer gets the states in the order the nodes were added and not the order the edges reach them`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    val a = node("a") { it.copy(messages = listOf("A")) }
                    val b = node("b") { it.copy(messages = listOf("B")) }

                    // "a" was added first, but the edge to "b" was written first.
                    START then b then END
                    START then a then END
                }.compile { current, updates -> current.copy(messages = updates.flatMap { it.messages }) }

            assertEquals(listOf("A", "B"), app.invoke(ParallelState()).state.messages)
        }

    @Test
    fun `a reducer gets the states in the order the nodes were added whatever a fan-out chose first`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    val a = node("a") { it.copy(messages = listOf("A")) }
                    val b = node("b") { it.copy(messages = listOf("B")) }
                    val pick = node("pick") { it }

                    START then pick
                    conditionalFanOut(pick, targets = setOf(a, b)) { setOf(b, a) }
                }.compile { current, updates -> current.copy(messages = updates.flatMap { it.messages }) }

            assertEquals(listOf("A", "B"), app.invoke(ParallelState()).state.messages)
        }

    @Test
    fun `a reducer that keeps the last state lets the node added last win`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    val first = node("first") { it.copy(messages = listOf("first")) }
                    val last = node("last") { it.copy(messages = listOf("last")) }

                    START then last then END
                    START then first then END
                }.compile { _, updates -> updates.last() }

            assertEquals(listOf("last"), app.invoke(ParallelState()).state.messages)
        }

    @Test
    fun `a failing branch cancels its siblings`() =
        runTest {
            var siblingCancelled = false
            val app =
                StateGraph<ParallelState> {
                    node("fails") {
                        delay(10)
                        error("boom")
                    }
                    node("waits") {
                        try {
                            awaitCancellation()
                        } finally {
                            siblingCancelled = true
                        }
                    }
                    edge(START, "fails")
                    edge(START, "waits")
                }.compile(reducer = appendMessages)

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(ParallelState()) }

            assertEquals("fails", exception.nodeName)
            assertTrue(siblingCancelled)
        }

    @Test
    fun `a failing reducer names its nodes in the order they were added`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    val a = node("a") { it }
                    val b = node("b") { it }

                    START then b then END
                    START then a then END
                }.compile(reducer = { _, _ -> error("cannot merge") })

            assertEquals(listOf("a", "b"), assertFailsWith<ReducerException> { app.invoke(ParallelState()) }.nodes)
        }

    @Test
    fun `a failing reducer is reported with the nodes it was merging`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    node("a") { it }
                    node("b") { it }
                    edge(START, "a")
                    edge(START, "b")
                }.compile(reducer = { _, _ -> error("cannot merge") })

            val exception = assertFailsWith<ReducerException> { app.invoke(ParallelState()) }

            assertEquals(listOf("a", "b"), exception.nodes)
            assertEquals("cannot merge", exception.cause?.message)
            assertEquals("Reducer failed to merge the results of [a, b]: cannot merge", exception.message)
        }

    @Test
    fun `parallel interrupt lists every pending node`() =
        runTest {
            val app =
                StateGraph<ParallelState> {
                    node("a") { it.copy(messages = it.messages + "A") }
                    node("b") { it.copy(messages = it.messages + "B") }
                    edge(START, "a")
                    edge(START, "b")
                }.compile(reducer = appendMessages)
            val config = GraphConfig(checkpointer = MemoryCheckpointer<ParallelState>(), interruptBefore = setOf("b"))

            assertEquals(GraphResult.Interrupted(ParallelState(), listOf("a", "b")), app.invoke(ParallelState(), config))
            assertEquals(listOf("A", "B"), app.resume(config).state.messages)
        }
}
