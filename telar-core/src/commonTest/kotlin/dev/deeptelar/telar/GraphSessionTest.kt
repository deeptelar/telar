package dev.deeptelar.telar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GraphSessionTest {
    private val line =
        StateGraph<TestState> {
            val a = node("a") { it.copy(count = it.count + 1) }
            val b = node("b") { it.copy(count = it.count + 10) }
            val c = node("c") { it.copy(count = it.count + 100) }
            START then a then b then c then END
        }.compile()

    /** Every snapshot of [session] from now on, as a screen that draws each one sees them. */
    private fun TestScope.recordSnapshots(session: GraphSession<TestState>): List<GraphSnapshot<TestState>> {
        val seen = mutableListOf<GraphSnapshot<TestState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.snapshot.toList(seen) }
        return seen
    }

    @Test
    fun `a session starts idle and a run goes from running to completed`() =
        runTest {
            val session = GraphSession(line, GraphConfig(), backgroundScope)
            val seen = recordSnapshots(session)

            session.start(TestState(0)).join()

            assertEquals(
                listOf(
                    GraphSnapshot.Idle,
                    GraphSnapshot.Running(TestState(0), emptyList()),
                    GraphSnapshot.Running(TestState(0), listOf("a")),
                    GraphSnapshot.Running(TestState(0), emptyList()),
                    GraphSnapshot.Running(TestState(1), emptyList()),
                    GraphSnapshot.Running(TestState(1), listOf("b")),
                    GraphSnapshot.Running(TestState(1), emptyList()),
                    GraphSnapshot.Running(TestState(11), emptyList()),
                    GraphSnapshot.Running(TestState(11), listOf("c")),
                    GraphSnapshot.Running(TestState(11), emptyList()),
                    GraphSnapshot.Running(TestState(111), emptyList()),
                    GraphSnapshot.Completed(TestState(111)),
                ),
                seen,
            )
        }

    @Test
    fun `a run pauses at an interrupt and resume continues it with the decision of a person`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("b"))
            val session = GraphSession(line, config, backgroundScope)

            session.start(TestState(0)).join()
            assertEquals(GraphSnapshot.Interrupted(TestState(1), listOf("b")), session.snapshot.value)

            session.resume { it.copy(count = 5) }.join()
            assertEquals(GraphSnapshot.Completed(TestState(115)), session.snapshot.value)
        }

    @Test
    fun `a new session restores the run that an ended one left at an interrupt`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(threadId = "order-42", checkpointer = checkpointer, interruptBefore = setOf("b"))
            // The first screen, and its scope, end while the run waits for a person.
            val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            GraphSession(line, config, firstScope).start(TestState(0)).join()
            firstScope.cancel()

            val session = GraphSession(line, config, backgroundScope)
            val restoring = session.restore()
            assertEquals(GraphSnapshot.Restoring, session.snapshot.value)
            restoring.join()
            assertEquals(GraphSnapshot.Interrupted(TestState(1), listOf("b")), session.snapshot.value)

            session.resume().join()
            assertEquals(GraphSnapshot.Completed(TestState(111)), session.snapshot.value)
        }

    @Test
    fun `restore finds a run that ended in the middle as interrupted and resume finishes it`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(checkpointer = checkpointer)
            val inB = CompletableDeferred<Unit>()
            val graph =
                StateGraph<TestState> {
                    val a = node("a") { it.copy(count = it.count + 1) }
                    val b =
                        node("b") {
                            if (!inB.isCompleted) {
                                inB.complete(Unit)
                                CompletableDeferred<Unit>().await() // the app is killed here
                            }
                            it.copy(count = it.count + 10)
                        }
                    START then a then b then END
                }.compile()
            val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            GraphSession(graph, config, firstScope).start(TestState(0))
            inB.await()
            firstScope.cancel()

            val session = GraphSession(graph, config, backgroundScope)
            session.restore().join()
            assertEquals(GraphSnapshot.Interrupted(TestState(1), listOf("b")), session.snapshot.value)

            session.resume().join()
            assertEquals(GraphSnapshot.Completed(TestState(11)), session.snapshot.value)
        }

    @Test
    fun `restore of a thread without a checkpoint is idle and of a finished one completed`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())
            val session = GraphSession(line, config, backgroundScope)

            session.restore().join()
            assertEquals(GraphSnapshot.Idle, session.snapshot.value)

            line.invoke(TestState(0), config)
            session.restore().join()
            assertEquals(GraphSnapshot.Completed(TestState(111)), session.snapshot.value)
        }

    @Test
    fun `a failure stops the run with its cause and resume tries the step again`() =
        runTest {
            var fail = true
            val graph =
                StateGraph<TestState> {
                    val a = node("a") { it.copy(count = it.count + 1) }
                    val flaky =
                        node("flaky") {
                            if (fail) throw IllegalStateException("network down")
                            it.copy(count = it.count + 10)
                        }
                    START then a then flaky then END
                }.compile()
            val session = GraphSession(graph, GraphConfig(checkpointer = MemoryCheckpointer()), backgroundScope)

            session.start(TestState(0)).join()
            val stopped = assertIs<GraphSnapshot.Stopped<TestState>>(session.snapshot.value)
            assertEquals(TestState(1), stopped.state)
            val cause = assertIs<NodeExecutionException>(stopped.cause)
            assertEquals("flaky", cause.nodeName)

            fail = false
            session.resume().join()
            assertEquals(GraphSnapshot.Completed(TestState(11)), session.snapshot.value)
        }

    @Test
    fun `resume of a thread without a checkpoint stops with the reason`() =
        runTest {
            val session = GraphSession(line, GraphConfig(checkpointer = MemoryCheckpointer()), backgroundScope)

            session.resume().join()

            val stopped = assertIs<GraphSnapshot.Stopped<TestState>>(session.snapshot.value)
            assertNull(stopped.state)
            assertIs<CheckpointNotFoundException>(stopped.cause)
        }

    @Test
    fun `cancel stops a running node and resume runs its step again`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var block = true
            val graph =
                StateGraph<TestState> {
                    val a = node("a") { it.copy(count = it.count + 1) }
                    val slow =
                        node("slow") {
                            entered.complete(Unit)
                            if (block) CompletableDeferred<Unit>().await()
                            it.copy(count = it.count + 10)
                        }
                    START then a then slow then END
                }.compile()
            val session = GraphSession(graph, GraphConfig(checkpointer = MemoryCheckpointer()), backgroundScope)
            val seen = recordSnapshots(session)

            val run = session.start(TestState(0))
            entered.await()
            assertEquals(GraphSnapshot.Running(TestState(1), listOf("slow")), session.snapshot.value)
            session.cancel().join()

            assertTrue(run.isCompleted)
            assertEquals(GraphSnapshot.Stopped(TestState(1), null), session.snapshot.value)
            assertEquals(1, seen.count { it is GraphSnapshot.Stopped })

            block = false
            session.resume().join()
            assertEquals(GraphSnapshot.Completed(TestState(11)), session.snapshot.value)
        }

    @Test
    fun `cancel without a run changes nothing`() =
        runTest {
            val session = GraphSession(line, GraphConfig(), backgroundScope)
            session.start(TestState(0)).join()

            session.cancel().join()

            assertEquals(GraphSnapshot.Completed(TestState(111)), session.snapshot.value)
        }

    @Test
    fun `a new start replaces the run that is going on without stopping in between`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val graph =
                StateGraph<TestState> {
                    val wait =
                        node("wait") {
                            if (it.count == 0) {
                                entered.complete(Unit)
                                CompletableDeferred<Unit>().await()
                            }
                            it.copy(count = it.count * 2)
                        }
                    START then wait then END
                }.compile()
            val session = GraphSession(graph, GraphConfig(), backgroundScope)
            val seen = recordSnapshots(session)

            val first = session.start(TestState(0))
            entered.await()
            session.start(TestState(21)).join()

            assertTrue(first.isCompleted)
            assertEquals(GraphSnapshot.Completed(TestState(42)), session.snapshot.value)
            assertTrue(seen.none { it is GraphSnapshot.Stopped })
        }

    @Test
    fun `running lists the parallel nodes that have not finished`() =
        runTest {
            val docsDone = CompletableDeferred<Unit>()
            val webMayFinish = CompletableDeferred<Unit>()
            val graph =
                StateGraph<TestState> {
                    val web = node("web", work = { webMayFinish.await() }) { state, _ -> state.copy(count = state.count + 1) }
                    val docs = node("docs", work = { docsDone.complete(Unit) }) { state, _ -> state.copy(count = state.count + 10) }
                    START then web then END
                    START then docs then END
                }.compile()
            val session = GraphSession(graph, GraphConfig(), backgroundScope)
            val seen = recordSnapshots(session)

            val run = session.start(TestState(0))
            docsDone.await()
            webMayFinish.complete(Unit)
            run.join()

            assertTrue(GraphSnapshot.Running(TestState(0), listOf("web", "docs")) in seen)
            assertTrue(GraphSnapshot.Running(TestState(0), listOf("web")) in seen)
            assertEquals(GraphSnapshot.Completed(TestState(11)), session.snapshot.value)
        }

    @Test
    fun `events carry what the nodes report`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    val write =
                        node("write") {
                            reportProgress("Hel")
                            reportProgress("lo")
                            it
                        }
                    START then write then END
                }.compile()
            val session = GraphSession(graph, GraphConfig(), backgroundScope)
            val text = mutableListOf<Any>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                session.events.filterIsInstance<GraphEvent.NodeProgress<TestState>>().collect { text += it.value }
            }

            session.start(TestState(0)).join()

            assertEquals(listOf<Any>("Hel", "lo"), text)
        }

    @Test
    fun `resume and restore need a checkpointer`() =
        runTest {
            val session = GraphSession(line, GraphConfig(), backgroundScope)

            assertFailsWith<GraphValidationException> { session.resume() }
            assertFailsWith<GraphValidationException> { session.restore() }
        }

    @Test
    fun `an Error that is no failure fails the scope as it fails invoke`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    val broken = node("broken") { throw AssertionError("the script of the test is too short") }
                    START then broken then END
                }.compile()
            val failure = CompletableDeferred<Throwable>()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job() + CoroutineExceptionHandler { _, e -> failure.complete(e) })
            val session = GraphSession(graph, GraphConfig(), scope)

            session.start(TestState(0)).join()

            assertIs<AssertionError>(failure.await())
        }

    @Test
    fun `a command after the scope ended returns a cancelled job`() =
        runTest {
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val session = GraphSession(line, GraphConfig(), scope)
            scope.coroutineContext.job.cancelAndJoin()

            val job = session.start(TestState(0))
            job.join()

            assertTrue(job.isCancelled)
            assertSame(GraphSnapshot.Idle, session.snapshot.value)
        }
}
