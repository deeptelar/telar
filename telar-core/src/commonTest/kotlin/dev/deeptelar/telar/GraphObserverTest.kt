package dev.deeptelar.telar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/** Writes down what it is told, one line per call. */
private class Recorder : GraphObserver {
    val log = mutableListOf<String>()

    override fun onRunStarted(run: ObservedRun) {
        log += "${run.label} started"
    }

    override fun onRunCompleted(run: ObservedRun) {
        log += "${run.label} completed"
    }

    override fun onRunInterrupted(run: ObservedRun, nextNodes: List<String>) {
        log += "${run.label} interrupted before $nextNodes"
    }

    override fun onRunFailed(run: ObservedRun, cause: Throwable) {
        log += "${run.label} failed: ${describe(cause)}"
    }

    override fun onNodeStarted(node: ObservedNode) {
        log += "${node.label} started"
    }

    override fun onNodeProgress(node: ObservedNode, value: Any) {
        log += "${node.label} reported $value"
    }

    override fun onNodeRetry(node: ObservedNode, attempt: Int, cause: Throwable, pause: Duration) {
        log += "${node.label} try $attempt failed with '${cause.message}', again in $pause"
    }

    override fun onNodeCompleted(node: ObservedNode) {
        log += "${node.label} completed"
    }

    override fun onNodeInterrupted(node: ObservedNode) {
        log += "${node.label} interrupted"
    }

    override fun onNodeFailed(node: ObservedNode, cause: Throwable) {
        log += "${node.label} failed: ${describe(cause)}"
    }

    override fun onStepCompleted(run: ObservedRun, step: Int, nodes: List<String>, nextNodes: List<String>) {
        log += "${run.label} step $step: $nodes -> $nextNodes"
    }

    override fun onCheckpointSaved(run: ObservedRun, step: Int, nextNodes: List<String>) {
        log += "${run.label} saved step $step, next $nextNodes"
    }

    private fun describe(cause: Throwable): String =
        when (cause) {
            is CancellationException -> "cancelled"
            is NodeExecutionException -> "node '${cause.nodeName}' failed with '${cause.cause?.message}'"
            else -> cause.message.orEmpty()
        }
}

/** "run" for the run the test started, and the node around it for the run of a subgraph. */
private val ObservedRun.label: String
    get() = parent?.let { "${it.label} >" } ?: "run"

private val ObservedNode.label: String
    get() = if (run.parent == null) "$name@$step" else "${run.label} $name@$step"

class GraphObserverTest {
    private val recorder = Recorder()

    private fun config(checkpointer: Checkpointer<TestState>? = null, interruptBefore: Set<String> = emptySet()) =
        GraphConfig(checkpointer = checkpointer, interruptBefore = interruptBefore, observers = listOf(recorder))

    private val twoSteps =
        StateGraph<TestState> {
            START then node("a") { it.copy(count = it.count + 1) } then node("b") { it.copy(count = it.count * 10) } then END
        }.compile()

    @Test
    fun `an observer is told about each node and step and checkpoint of a run`() =
        runTest {
            twoSteps.invoke(TestState(1), config(MemoryCheckpointer()))

            assertEquals(
                listOf(
                    "run started",
                    "run saved step 0, next [a]",
                    "a@1 started",
                    "a@1 completed",
                    "run step 1: [a] -> [b]",
                    "run saved step 1, next [b]",
                    "b@2 started",
                    "b@2 completed",
                    "run step 2: [b] -> []",
                    "run saved step 2, next []",
                    "run completed",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a run without a checkpointer saves nothing`() =
        runTest {
            twoSteps.invoke(TestState(1), config())

            assertEquals(
                listOf(
                    "run started",
                    "a@1 started",
                    "a@1 completed",
                    "run step 1: [a] -> [b]",
                    "b@2 started",
                    "b@2 completed",
                    "run step 2: [b] -> []",
                    "run completed",
                ),
                recorder.log,
            )
        }

    @Test
    fun `every observer of a config is told`() =
        runTest {
            val second = Recorder()

            twoSteps.invoke(TestState(1), GraphConfig(observers = listOf(recorder, second)))

            assertEquals(recorder.log, second.log)
            assertEquals("run completed", second.log.last())
        }

    @Test
    fun `a node that fails ends itself and the run and the nodes beside it are cancelled`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    val broken =
                        node("broken") {
                            delay(1)
                            throw IllegalStateException("The service is down.")
                        }
                    val slow = node("slow") { awaitCancellation() }
                    START then broken then END
                    START then slow then END
                }.compile(reducer = { _, states -> states.first() })

            assertFailsWith<NodeExecutionException> { graph.invoke(TestState(), config()) }

            assertEquals(
                listOf(
                    "run started",
                    "broken@1 started",
                    "slow@1 started",
                    "broken@1 failed: node 'broken' failed with 'The service is down.'",
                    "slow@1 failed: cancelled",
                    "run failed: node 'broken' failed with 'The service is down.'",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a try that fails is told and the node starts and completes once`() =
        runTest {
            var calls = 0
            val graph =
                StateGraph<TestState> {
                    START then
                        node("fetch", retry = Retry(attempts = 2)) {
                            if (++calls == 1) throw IllegalStateException("The service is down.")
                            it
                        } then END
                }.compile()

            graph.invoke(TestState(), config())

            assertEquals(
                listOf(
                    "run started",
                    "fetch@1 started",
                    "fetch@1 try 1 failed with 'The service is down.', again in 1s",
                    "fetch@1 completed",
                    "run step 1: [fetch] -> []",
                    "run completed",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a node that pauses the run ends as interrupted and resume is a run of its own`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    START then node("ask") { if (it.count == 0) interrupt(it) else it } then END
                }.compile()
            val checkpointer = MemoryCheckpointer<TestState>()

            graph.invoke(TestState(), config(checkpointer))
            graph.resume(config(checkpointer)) { it.copy(count = 1) }

            assertEquals(
                listOf(
                    "run started",
                    "run saved step 0, next [ask]",
                    "ask@1 started",
                    "ask@1 interrupted",
                    "run saved step 0, next [ask]",
                    "run interrupted before [ask]",
                    "run started",
                    // The edit of resume is saved before the node runs again.
                    "run saved step 0, next [ask]",
                    "ask@1 started",
                    "ask@1 completed",
                    "run step 1: [ask] -> []",
                    "run saved step 1, next []",
                    "run completed",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a pause before a node is told without the node`() =
        runTest {
            twoSteps.invoke(TestState(1), config(MemoryCheckpointer(), interruptBefore = setOf("b")))

            assertEquals(
                listOf(
                    "run started",
                    "run saved step 0, next [a]",
                    "a@1 started",
                    "a@1 completed",
                    "run step 1: [a] -> [b]",
                    "run saved step 1, next [b]",
                    "run saved step 1, next [b]",
                    "run interrupted before [b]",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a fork is a run that starts with the checkpoint it continues from`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            twoSteps.invoke(TestState(1), config(checkpointer))
            val afterA = twoSteps.history(config(checkpointer)).first { it.nextNodes == listOf("b") }
            recorder.log.clear()

            twoSteps.fork(afterA, GraphConfig(threadId = "fork", checkpointer = checkpointer, observers = listOf(recorder)))

            assertEquals(
                listOf(
                    "run started",
                    "run saved step 1, next [b]",
                    "b@2 started",
                    "b@2 completed",
                    "run step 2: [b] -> []",
                    "run saved step 2, next []",
                    "run completed",
                ),
                recorder.log,
            )
        }

    @Test
    fun `the run of a subgraph is told inside the node that runs it`() =
        runTest {
            val inner =
                StateGraph<TestState> {
                    START then node("double") { it.copy(count = it.count * 2) } then END
                }.compile()
            val graph =
                StateGraph<TestState> {
                    START then subgraph("inner", inner) then END
                }.compile()

            graph.invoke(TestState(1), config())

            assertEquals(
                listOf(
                    "run started",
                    "inner@1 started",
                    "inner@1 > started",
                    "inner@1 > double@1 started",
                    "inner@1 > double@1 completed",
                    "inner@1 > step 1: [double] -> []",
                    "inner@1 > completed",
                    "inner@1 completed",
                    "run step 1: [inner] -> []",
                    "run completed",
                ),
                recorder.log,
            )
        }

    @Test
    fun `a pause inside a subgraph ends the subgraph run and its node and the run as interrupted`() =
        runTest {
            val inner =
                StateGraph<TestState> {
                    START then node("ask") { interrupt(it) } then END
                }.compile()
            val graph =
                StateGraph<TestState> {
                    START then subgraph("inner", inner) then END
                }.compile()

            graph.invoke(TestState(), config(MemoryCheckpointer()))

            assertEquals(
                listOf(
                    "run started",
                    "run saved step 0, next [inner]",
                    "inner@1 started",
                    "inner@1 > started",
                    "inner@1 > ask@1 started",
                    "inner@1 > ask@1 interrupted",
                    "inner@1 > interrupted before [ask]",
                    "inner@1 interrupted",
                    "run saved step 0, next [inner]",
                    "run interrupted before [inner]",
                ),
                recorder.log,
            )
        }

    @Test
    fun `progress is told when the run is streamed and not for invoke`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    START then
                        node("write") {
                            reportProgress("half")
                            it
                        } then END
                }.compile()

            graph.invoke(TestState(), config())
            assertEquals(false, recorder.log.any { "reported" in it })

            recorder.log.clear()
            graph.stream(TestState(), config()).toList()
            assertEquals(listOf("run started", "write@1 started", "write@1 reported half", "write@1 completed"), recorder.log.take(4))
        }

    @Test
    fun `a node runs in the context its observer adds`() =
        runTest {
            val naming =
                object : GraphObserver {
                    override fun nodeContext(node: ObservedNode): CoroutineContext = CoroutineName("span of ${node.name}")
                }
            var seen: String? = null
            val graph =
                StateGraph<TestState> {
                    START then
                        node("traced") {
                            seen = currentCoroutineContext()[CoroutineName]?.name
                            it
                        } then END
                }.compile()

            graph.invoke(TestState(), GraphConfig(observers = listOf(recorder, naming)))

            assertEquals("span of traced", seen)
        }

    @Test
    fun `a run is told the context it was started in and a subgraph the context of its node`() =
        runTest {
            val contexts = mutableListOf<String?>()
            val naming =
                object : GraphObserver {
                    override fun onRunStarted(run: ObservedRun) {
                        contexts += run.context[CoroutineName]?.name
                    }

                    override fun nodeContext(node: ObservedNode): CoroutineContext = CoroutineName("span of ${node.name}")
                }
            val inner = StateGraph<TestState> { START then node("draft") { it } then END }.compile()
            val graph = StateGraph<TestState> { START then subgraph("write", inner) then END }.compile()

            withContext(CoroutineName("request")) {
                graph.invoke(TestState(), GraphConfig(observers = listOf(naming)))
                graph.stream(TestState(), GraphConfig(observers = listOf(naming))).toList()
            }

            assertEquals(listOf<String?>("request", "span of write", "request", "span of write"), contexts)
        }

    @Test
    fun `a run that fails before its first node is told as failed`() =
        runTest {
            assertFailsWith<GraphValidationException> {
                twoSteps.invoke(TestState(), config(MemoryCheckpointer(), interruptBefore = setOf("missing")))
            }

            assertEquals(
                listOf("run started", "run failed: GraphConfig interrupts refer to nodes that are not in the graph: [missing]"),
                recorder.log,
            )
        }

    @Test
    fun `a collector that stops after the last event does not make a completed run fail`() =
        runTest {
            twoSteps.stream(TestState(1), config()).first { it is GraphEvent.Completed }

            assertEquals("run completed", recorder.log.last())
        }

    @Test
    fun `a cancelled run ends its node and itself as cancelled`() =
        runTest {
            val graph =
                StateGraph<TestState> {
                    START then node("wait") { awaitCancellation() } then END
                }.compile()

            val job = launch { graph.invoke(TestState(), config()) }
            testScheduler.runCurrent()
            job.cancel()
            job.join()

            assertEquals(listOf("run started", "wait@1 started", "wait@1 failed: cancelled", "run failed: cancelled"), recorder.log)
        }

    @Test
    fun `a node reaches every observer of its run with itself and nothing outside a node`() =
        runTest {
            val second = Recorder()
            val reached = mutableListOf<String>()
            val inner =
                StateGraph<TestState> {
                    START then
                        node("deep") {
                            forEachNodeObserver { observer, node ->
                                reached +=
                                    "${if (observer === recorder) "first" else "second"} ${node.label}"
                            }
                            it
                        } then END
                }.compile()
            val graph =
                StateGraph<TestState> {
                    START then subgraph("inner", inner) then END
                }.compile()

            forEachNodeObserver { _, _ -> reached += "outside" }
            graph.invoke(TestState(1), GraphConfig(observers = listOf(recorder, second)))

            assertEquals(listOf("first inner@1 > deep@1", "second inner@1 > deep@1"), reached)
        }
}
