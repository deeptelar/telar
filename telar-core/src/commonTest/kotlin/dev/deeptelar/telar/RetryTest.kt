package dev.deeptelar.telar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A service that is down for its first calls. */
private class Flaky(
    private val failures: Int,
    private val failure: () -> Throwable = { IllegalStateException("The service is down.") },
) {
    var calls = 0
        private set

    fun call() {
        if (++calls <= failures) throw failure()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {
    private fun graphWith(retry: Retry? = null, timeout: Duration? = null, action: NodeAction<TestState>) =
        StateGraph<TestState> {
            START then node("fetch", retry, timeout, action) then END
        }.compile()

    private fun counting(service: Flaky): NodeAction<TestState> =
        {
            service.call()
            it.copy(count = it.count + 1)
        }

    @Test
    fun `a node that fails is tried again until it succeeds`() =
        runTest {
            val service = Flaky(failures = 2)

            val result = graphWith(Retry(attempts = 3), action = counting(service)).invoke(TestState(5))

            assertEquals(GraphResult.Completed(TestState(6)), result)
            assertEquals(3, service.calls)
            // One second before the second try and two before the third.
            assertEquals(3000, currentTime)
        }

    @Test
    fun `a node that fails every try fails the run and says how often it ran`() =
        runTest {
            val failure = IllegalStateException("The service is down.")
            val service = Flaky(failures = 10) { failure }

            val exception =
                assertFailsWith<NodeExecutionException> {
                    graphWith(Retry(attempts = 3), action = counting(service)).invoke(TestState())
                }

            assertEquals(3, service.calls)
            assertEquals(3, exception.attempts)
            assertEquals("fetch", exception.nodeName)
            assertSame(failure, exception.cause)
            assertEquals("Node 'fetch' failed after 3 attempts: The service is down.", exception.message)
        }

    @Test
    fun `a node without a retry runs once`() =
        runTest {
            val service = Flaky(failures = 1)

            val exception = assertFailsWith<NodeExecutionException> { graphWith(action = counting(service)).invoke(TestState()) }

            assertEquals(1, service.calls)
            assertEquals(1, exception.attempts)
            assertEquals("Node 'fetch' failed: The service is down.", exception.message)
        }

    @Test
    fun `the pause grows by the factor up to the longest pause`() =
        runTest {
            val service = Flaky(failures = 4)
            val retry = Retry(attempts = 5, pause = 1.seconds, factor = 3.0, maxPause = 5.seconds)

            val retries =
                graphWith(
                    retry,
                    action = counting(service),
                ).stream(TestState()).filterIsInstance<GraphEvent.NodeRetry<TestState>>().toList()

            assertEquals(listOf(1.seconds, 3.seconds, 5.seconds, 5.seconds), retries.map { it.pause })
            assertEquals(listOf(1, 2, 3, 4), retries.map { it.attempt })
            assertEquals(14_000, currentTime)
        }

    @Test
    fun `a retry without a pause tries again at once`() =
        runTest {
            val service = Flaky(failures = 2)

            graphWith(Retry(attempts = 3, pause = Duration.ZERO), action = counting(service)).invoke(TestState())

            assertEquals(3, service.calls)
            assertEquals(0, currentTime)
        }

    @Test
    fun `every try gets the state the node received`() =
        runTest {
            val inputs = mutableListOf<TestState>()
            val service = Flaky(failures = 2)
            val app =
                graphWith(Retry(attempts = 3)) {
                    inputs += it
                    service.call()
                    it.copy(count = it.count + 1)
                }

            app.invoke(TestState(7))

            assertEquals(listOf(TestState(7), TestState(7), TestState(7)), inputs)
        }

    @Test
    fun `only the failures that the retry asks for are tried again`() =
        runTest {
            val service = Flaky(failures = 1)
            val retry = Retry(attempts = 3) { it is IllegalArgumentException }

            val exception =
                assertFailsWith<NodeExecutionException> { graphWith(retry, action = counting(service)).invoke(TestState()) }

            assertEquals(1, service.calls)
            assertEquals(1, exception.attempts)
            assertEquals(0, currentTime)

            // The same retry goes on for a failure it asks for.
            val other = Flaky(failures = 1) { IllegalArgumentException("Try again.") }
            assertEquals(GraphResult.Completed(TestState(1)), graphWith(retry, action = counting(other)).invoke(TestState()))
        }

    @Test
    fun `a failure that the retry does not ask for ends the tries`() =
        runTest {
            var calls = 0
            val retry = Retry(attempts = 5) { it is IllegalStateException }
            val app =
                graphWith(retry) {
                    if (++calls == 1) error("The service is down.") else throw IllegalArgumentException("No such item.")
                }

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(TestState()) }

            assertEquals(2, exception.attempts)
            assertEquals("Node 'fetch' failed after 2 attempts: No such item.", exception.message)
        }

    @Test
    fun `a mistake in the graph is never tried again`() =
        runTest {
            val service = Flaky(failures = 1) { GraphValidationException("The class has no schema.") }

            val exception =
                assertFailsWith<NodeExecutionException> {
                    graphWith(Retry(attempts = 3) { true }, action = counting(service)).invoke(TestState())
                }

            assertIs<GraphValidationException>(exception.cause)
            assertEquals(1, service.calls)
        }

    @Test
    fun `an error of the program is not tried again`() =
        runTest {
            val service = Flaky(failures = 1) { AssertionError("Expected 2, was 3") }

            assertFailsWith<AssertionError> { graphWith(Retry(attempts = 3), action = counting(service)).invoke(TestState()) }

            assertEquals(1, service.calls)
        }

    @Test
    fun `a pause of the node is not tried again`() =
        runTest {
            var calls = 0
            val app =
                graphWith(Retry(attempts = 3)) {
                    calls++
                    interrupt(it.copy(count = 42))
                }

            val result = app.invoke(TestState(), GraphConfig(checkpointer = MemoryCheckpointer()))

            assertEquals(GraphResult.Interrupted(TestState(42), listOf("fetch")), result)
            assertEquals(1, calls)
        }

    @Test
    fun `a cancelled run is not tried again`() =
        runTest {
            var calls = 0
            val started = CompletableDeferred<Unit>()
            val app =
                graphWith(Retry(attempts = 3)) {
                    calls++
                    started.complete(Unit)
                    awaitCancellation()
                }

            val run = async { app.invoke(TestState()) }
            started.await()
            run.cancel()

            assertFailsWith<CancellationException> { run.await() }
            assertEquals(1, calls)
        }

    @Test
    fun `a run that is cancelled between two tries stops`() =
        runTest {
            val service = Flaky(failures = 10)
            val run = async { graphWith(Retry(attempts = 3), action = counting(service)).invoke(TestState()) }

            delay(500)
            run.cancel()

            assertFailsWith<CancellationException> { run.await() }
            assertEquals(1, service.calls)
        }

    @Test
    fun `a try that takes longer than the time limit fails`() =
        runTest {
            val app = graphWith(timeout = 100.milliseconds) { awaitCancellation() }

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(TestState()) }

            assertEquals(100.milliseconds, assertIs<NodeTimeoutException>(exception.cause).timeout)
            assertEquals("Node 'fetch' failed: The node did not finish within 100ms.", exception.message)
            assertEquals(100, currentTime)
        }

    @Test
    fun `a node that is done within its time limit is not stopped`() =
        runTest {
            val app =
                graphWith(timeout = 100.milliseconds) {
                    delay(99)
                    it.copy(count = it.count + 1)
                }

            assertEquals(GraphResult.Completed(TestState(1)), app.invoke(TestState()))
        }

    @Test
    fun `a try that took too long is tried again`() =
        runTest {
            var calls = 0
            val app =
                graphWith(Retry(attempts = 2), timeout = 100.milliseconds) {
                    if (++calls == 1) awaitCancellation()
                    it.copy(count = it.count + 1)
                }

            val events = app.stream(TestState()).toList()

            val retry = events.filterIsInstance<GraphEvent.NodeRetry<TestState>>().single()
            assertIs<NodeTimeoutException>(retry.cause)
            assertEquals(GraphEvent.Completed(TestState(1)), events.last())
            // The limit of the first try, then the pause.
            assertEquals(1100, currentTime)
        }

    @Test
    fun `every try has the whole time limit`() =
        runTest {
            var calls = 0
            val app =
                graphWith(Retry(attempts = 3, pause = Duration.ZERO), timeout = 100.milliseconds) {
                    calls++
                    delay(80)
                    if (calls < 3) error("The service is down.")
                    it.copy(count = it.count + 1)
                }

            assertEquals(GraphResult.Completed(TestState(1)), app.invoke(TestState()))
            assertEquals(240, currentTime)
        }

    @Test
    fun `a time limit that the node sets for itself fails with its own exception`() =
        runTest {
            val app = graphWith(timeout = 1.seconds) { withTimeout(10) { awaitCancellation() } }

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(TestState()) }

            assertIs<TimeoutCancellationException>(exception.cause)
            assertEquals(10, currentTime)
        }

    @Test
    fun `code that cannot be cancelled goes on and the node fails when it returns`() =
        runTest {
            val app =
                graphWith(timeout = 100.milliseconds) {
                    withContext(NonCancellable) { delay(500) }
                    it.copy(count = it.count + 1)
                }

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(TestState()) }

            assertIs<NodeTimeoutException>(exception.cause)
            assertEquals(500, currentTime)
        }

    @Test
    fun `a stream reports each try that failed`() =
        runTest {
            val failure = IllegalStateException("The service is down.")
            val service = Flaky(failures = 2) { failure }

            val events = graphWith(Retry(attempts = 3), action = counting(service)).stream(TestState(5)).toList()

            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(1, "fetch", TestState(5)),
                    GraphEvent.NodeRetry(1, "fetch", attempt = 1, cause = failure, pause = 1.seconds, state = TestState(5)),
                    GraphEvent.NodeRetry(1, "fetch", attempt = 2, cause = failure, pause = 2.seconds, state = TestState(5)),
                    GraphEvent.NodeCompleted(1, "fetch", TestState(6)),
                    GraphEvent.StepCompleted(1, listOf("fetch"), TestState(6)),
                    GraphEvent.Completed(TestState(6)),
                ),
                events,
            )
        }

    @Test
    fun `the event of a failed try arrives before the pause`() =
        runTest {
            val service = Flaky(failures = 1)
            val arrival = mutableListOf<Long>()

            graphWith(Retry(attempts = 2, pause = 5.seconds), action = counting(service))
                .stream(TestState())
                .collect { if (it is GraphEvent.NodeRetry) arrival += currentTime }

            assertEquals(listOf(0L), arrival)
            assertEquals(5000, currentTime)
        }

    @Test
    fun `a node that is tried again reports its progress again`() =
        runTest {
            val service = Flaky(failures = 1)
            val app =
                graphWith(Retry(attempts = 2)) {
                    reportProgress("Asking the service")
                    service.call()
                    it.copy(count = it.count + 1)
                }

            val events = app.stream(TestState()).toList()

            assertEquals(
                listOf("NodeStarted", "NodeProgress", "NodeRetry", "NodeProgress", "NodeCompleted", "StepCompleted", "Completed"),
                events.map { it::class.simpleName },
            )
        }

    @Test
    fun `the last try that fails has no event`() =
        runTest {
            val service = Flaky(failures = 10)
            val events = mutableListOf<GraphEvent<TestState>>()

            assertFailsWith<NodeExecutionException> {
                graphWith(Retry(attempts = 2), action = counting(service)).stream(TestState()).collect { events += it }
            }

            assertEquals(listOf("NodeStarted", "NodeRetry"), events.map { it::class.simpleName })
        }

    /** Two nodes in one step: "stock" is down for its first calls, and "price" takes 100 ms. */
    private fun parallel(stock: Flaky, priceRuns: MutableList<Long> = mutableListOf(), retry: Retry? = Retry(attempts = 3)) =
        StateGraph<TestState> {
            val readStock =
                node("stock", work = {
                    stock.call()
                    10
                }, retry = retry) { state, found -> state.copy(count = state.count + found) }
            val readPrice =
                node("price", work = {
                    delay(100)
                    1
                }) { state, found -> state.copy(count = state.count + found) }
            START then readStock
            START then readPrice
        }.compile()

    @Test
    fun `the other nodes of a step go on while one waits for its next try`() =
        runTest {
            val stock = Flaky(failures = 1)
            val arrival = mutableMapOf<String, Long>()

            val events = mutableListOf<GraphEvent<TestState>>()
            parallel(stock).stream(TestState()).collect {
                events += it
                if (it is GraphEvent.NodeCompleted) arrival[it.node] = currentTime
            }

            assertEquals(mapOf("price" to 100L, "stock" to 1000L), arrival)
            assertEquals(1, events.count { it is GraphEvent.NodeRetry })
            assertEquals(GraphEvent.Completed(TestState(11)), events.last())
        }

    @Test
    fun `a run that is not collected tries a node of a parallel step again too`() =
        runTest {
            val stock = Flaky(failures = 2)

            assertEquals(GraphResult.Completed(TestState(11)), parallel(stock).invoke(TestState()))
            assertEquals(3, stock.calls)
        }

    @Test
    fun `a node that fails for good stops a node that waits for its next try`() =
        runTest {
            val stock = Flaky(failures = 10)
            val app =
                StateGraph<TestState> {
                    val readStock =
                        node("stock", work = { stock.call() }, retry = Retry(attempts = 3, pause = 10.seconds)) { state, _ -> state }
                    val readPrice =
                        node("price", work = {
                            delay(100)
                            error("No price list.")
                        }) { state, _: Unit -> state }
                    START then readStock
                    START then readPrice
                }.compile()

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(TestState()) }

            assertEquals("price", exception.nodeName)
            assertEquals(1, stock.calls)
            assertEquals(100, currentTime)
        }

    @Test
    fun `a step that failed gives its node all of its tries again on resume`() =
        runTest {
            val service = Flaky(failures = 4)
            val app = graphWith(Retry(attempts = 3), action = counting(service))
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertFailsWith<NodeExecutionException> { app.invoke(TestState(5), config) }
            // Nothing of the failed tries was saved.
            assertEquals(GraphResult.Interrupted(TestState(5), listOf("fetch")), app.lastResult(config))

            assertEquals(GraphResult.Completed(TestState(6)), app.resume(config))
            assertEquals(5, service.calls)
        }

    @Test
    fun `the tries of a node are one step`() =
        runTest {
            val service = Flaky(failures = 2)
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(checkpointer = checkpointer, maxIterations = 1)

            val app = graphWith(Retry(attempts = 3), action = counting(service))

            assertEquals(GraphResult.Completed(TestState(1)), app.invoke(TestState(), config))
            assertEquals(listOf(0, 1), app.history(config).map { it.step })
        }

    @Test
    fun `a try that failed inside a subgraph arrives as an event of the subgraph`() =
        runTest {
            val service = Flaky(failures = 1)
            val inner = graphWith(Retry(attempts = 2), action = counting(service))
            val outer =
                StateGraph<TestState> {
                    START then subgraph("inner", inner) then END
                }.compile()

            val events = outer.stream(TestState()).toList()

            val retry =
                events
                    .filterIsInstance<GraphEvent.SubgraphEvent<TestState>>()
                    .map { it.event }
                    .filterIsInstance<GraphEvent.NodeRetry<*>>()
            assertEquals(listOf("fetch"), retry.map { it.node })
            assertEquals(GraphEvent.Completed(TestState(1)), events.last())
        }

    @Test
    fun `a retry with a wrong setting is rejected`() {
        assertFailsWith<GraphValidationException> { Retry(attempts = 0) }
        assertFailsWith<GraphValidationException> { Retry(pause = (-1).seconds) }
        assertFailsWith<GraphValidationException> { Retry(maxPause = (-1).seconds) }
        assertFailsWith<GraphValidationException> { Retry(factor = 0.5) }
        assertFailsWith<GraphValidationException> { Retry(factor = Double.NaN) }
        // A factor that every platform prints the same way.
        assertEquals("Retry(attempts=3, pause=1s, factor=1.5, maxPause=30s)", Retry(factor = 1.5).toString())
    }

    @Test
    fun `a time limit that is not positive is rejected`() {
        val exception =
            assertFailsWith<GraphValidationException> { StateGraph<TestState> { node("fetch", timeout = Duration.ZERO) { it } } }

        assertEquals("The timeout of node 'fetch' must be positive, but was 0s.", exception.message)
        assertFailsWith<GraphValidationException> {
            StateGraph<TestState> { node("fetch", work = { 1 }, timeout = (-1).seconds) { state, _ -> state } }
        }
    }

    @Test
    fun `a node that is tried once may still have a retry`() =
        runTest {
            val service = Flaky(failures = 1)

            val exception =
                assertFailsWith<NodeExecutionException> { graphWith(Retry(attempts = 1), action = counting(service)).invoke(TestState()) }

            assertEquals(1, exception.attempts)
        }
}
