package dev.deeptelar.telar

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The state of a subgraph that the state of the graph around holds, or does not. */
private data class Job(
    val done: List<String> = emptyList(),
)

private data class Around(
    val job: Job = Job(),
    val result: String = "",
)

/** Stores a result that is already a string. */
private object ChargeResult : StateSerializer<String> {
    override fun serialize(state: String): String = state

    override fun deserialize(data: String): String = data
}

/** What a run after a failure runs again. The state alone cannot show it: both ways end the same. */
class SubgraphFailureTest {
    /** How often each node ran, by name. */
    private val runs = mutableMapOf<String, Int>()

    /** The nodes that fail on their next runs, with how often. */
    private val failing = mutableMapOf<String, Int>()

    private fun ran(name: String) {
        runs[name] = (runs[name] ?: 0) + 1
        val left = failing[name] ?: 0
        if (left > 0) {
            failing[name] = left - 1
            error("$name failed")
        }
    }

    private fun StateGraph<TestState>.counting(name: String, add: Int): NodeRef =
        node(name) {
            ran(name)
            it.copy(count = it.count + add)
        }

    private fun StateGraph<Job>.step(name: String): NodeRef =
        node(name) {
            ran(name)
            it.copy(done = it.done + name)
        }

    private val inner =
        StateGraph<TestState> {
            START then counting("a", 1) then counting("b", 10) then counting("c", 100) then END
        }.compile()

    private val outer =
        StateGraph<TestState> {
            START then counting("first", 1000) then subgraph("sub", inner) then counting("last", 10000) then END
        }.compile()

    private val jobGraph = StateGraph<Job> { START then step("a") then step("b") then END }.compile()

    private val checkpointer = MemoryCheckpointer<TestState>()
    private val config = GraphConfig(checkpointer = checkpointer)

    private val aroundCheckpointer = MemoryCheckpointer<Around>()
    private val aroundConfig = GraphConfig(checkpointer = aroundCheckpointer)

    @Test
    fun `resume continues at the node of a subgraph that failed`() =
        runTest {
            failing["b"] = 1

            val exception = assertFailsWith<NodeExecutionException> { outer.invoke(TestState(0), config) }

            assertEquals("sub", exception.nodeName)
            assertEquals("b", assertIs<NodeExecutionException>(exception.cause).nodeName)
            assertEquals(
                Checkpoint(
                    TestState(1001),
                    listOf("sub"),
                    step = 1,
                    interruptedBefore = true,
                    subgraphs = mapOf("sub" to SubgraphPosition(listOf("b"), step = 1)),
                ),
                checkpointer.load("default"),
            )
            assertEquals(GraphResult.Interrupted(TestState(1001), listOf("sub")), outer.lastResult(config))

            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
            assertEquals(mapOf("first" to 1, "a" to 1, "b" to 2, "c" to 1, "last" to 1), runs)
        }

    @Test
    fun `a second failure at the same node keeps the place`() =
        runTest {
            failing["b"] = 2

            assertFailsWith<NodeExecutionException> { outer.invoke(TestState(0), config) }
            assertFailsWith<NodeExecutionException> { outer.resume(config) }

            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
            assertEquals(1, runs["a"])
            assertEquals(3, runs["b"])
        }

    @Test
    fun `a failure further on moves the place forward`() =
        runTest {
            failing["b"] = 1
            failing["c"] = 1

            assertFailsWith<NodeExecutionException> { outer.invoke(TestState(0), config) }
            assertFailsWith<NodeExecutionException> { outer.resume(config) }

            assertEquals(mapOf("sub" to SubgraphPosition(listOf("c"), step = 2)), checkpointer.load("default")?.subgraphs)
            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
            assertEquals(mapOf("first" to 1, "a" to 1, "b" to 2, "c" to 2, "last" to 1), runs)
        }

    @Test
    fun `a failure in the first step of a subgraph has nothing to keep`() =
        runTest {
            failing["a"] = 1

            assertFailsWith<NodeExecutionException> { outer.invoke(TestState(0), config) }

            assertEquals(Checkpoint(TestState(1000), listOf("sub"), step = 1), checkpointer.load("default"))
            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
        }

    @Test
    fun `a failure in a subgraph inside a subgraph continues in the innermost one`() =
        runTest {
            val middle = StateGraph<TestState> { START then counting("m", 100000) then subgraph("inner", inner) then END }.compile()
            val around = StateGraph<TestState> { START then subgraph("middle", middle) then END }.compile()
            failing["c"] = 1

            assertFailsWith<NodeExecutionException> { around.invoke(TestState(0), config) }

            val inside = checkpointer.load("default")?.subgraphs?.get("middle")
            assertEquals(mapOf("inner" to SubgraphPosition(listOf("c"), step = 2)), inside?.subgraphs)
            assertEquals(GraphResult.Completed(TestState(100111)), around.resume(config))
            assertEquals(mapOf("m" to 1, "a" to 1, "b" to 1, "c" to 2), runs)
        }

    @Test
    fun `a subgraph whose state the graph around holds continues`() =
        runTest {
            val around =
                StateGraph<Around> {
                    val job = subgraph("job", jobGraph, state = { it.job }, update = { state, job -> state.copy(job = job) })
                    START then job then END
                }.compile()
            failing["b"] = 1

            assertFailsWith<NodeExecutionException> { around.invoke(Around(), aroundConfig) }

            assertEquals(Around(Job(listOf("a"))), aroundCheckpointer.load("default")?.state)
            assertEquals(GraphResult.Completed(Around(Job(listOf("a", "b")))), around.resume(aroundConfig))
            assertEquals(mapOf("a" to 1, "b" to 2), runs)
        }

    @Test
    fun `a subgraph with a new state on every visit starts again and misses nothing`() =
        runTest {
            val around =
                StateGraph<Around> {
                    val job =
                        subgraph("job", jobGraph, state = { Job() }, update = {
                            state,
                            job,
                            ->
                            state.copy(result = job.done.joinToString())
                        })
                    START then job then END
                }.compile()
            failing["b"] = 1

            assertFailsWith<NodeExecutionException> { around.invoke(Around(), aroundConfig) }

            assertEquals(Checkpoint(Around(), listOf("job")), aroundCheckpointer.load("default"))
            assertEquals(GraphResult.Completed(Around(result = "a, b")), around.resume(aroundConfig))
            assertEquals(mapOf("a" to 2, "b" to 2), runs)
        }

    @Test
    fun `an update that cannot take an unfinished subgraph does not hide the failure`() =
        runTest {
            val around =
                StateGraph<Around> {
                    val job =
                        subgraph("job", jobGraph, state = { Job() }, update = { state, job ->
                            check(job.done.size == 2) { "unfinished" }
                            state.copy(result = job.done.last())
                        })
                    START then job then END
                }.compile()
            failing["b"] = 1

            val exception = assertFailsWith<NodeExecutionException> { around.invoke(Around(), aroundConfig) }

            assertEquals("b failed", assertIs<NodeExecutionException>(exception.cause).cause?.message)
            assertEquals(GraphResult.Completed(Around(result = "b")), around.resume(aroundConfig))
            assertEquals(mapOf("a" to 2, "b" to 2), runs)
        }

    @Test
    fun `resume with an edit reaches the subgraph that failed`() =
        runTest {
            failing["b"] = 1

            assertFailsWith<NodeExecutionException> { outer.invoke(TestState(0), config) }

            assertEquals(GraphResult.Completed(TestState(111111)), outer.resume(config) { it.copy(count = it.count + 100000) })
        }

    @Test
    fun `a pause the run already passed does not come again after a failure`() =
        runTest {
            val config = GraphConfig(checkpointer = checkpointer, interruptBefore = setOf("sub", "sub/c"))
            failing["c"] = 1

            outer.invoke(TestState(0), config)
            outer.resume(config)
            assertFailsWith<NodeExecutionException> { outer.resume(config) }

            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
        }

    @Test
    fun `the other nodes of the step run again and the subgraph continues`() =
        runTest {
            val around =
                StateGraph<TestState> {
                    val other = node("other", work = { _ -> ran("other") }) { state, _ -> state }
                    val sub = subgraph("sub", inner)
                    START then other then END
                    START then sub then END
                }.compile()
            failing["b"] = 1

            assertFailsWith<NodeExecutionException> { around.invoke(TestState(0), config) }

            around.resume(config)
            assertEquals(1, runs["a"])
            assertEquals(2, runs["other"])
        }

    @Test
    fun `a subgraph that was cancelled by another node of the step starts again`() =
        runTest {
            val slow =
                StateGraph<TestState> {
                    val a = counting("a", 1)
                    val wait = node("wait", work = { _ -> delay(100) }) { state, _ -> state }
                    START then a then wait then END
                }.compile()
            val around =
                StateGraph<TestState> {
                    val sub = subgraph("sub", slow)
                    val other =
                        node("other", work = { _ ->
                            delay(10)
                            ran("other")
                        }) { state, _ -> state }
                    START then sub then END
                    START then other then END
                }.compile()
            failing["other"] = 1

            val exception = assertFailsWith<NodeExecutionException> { around.invoke(TestState(0), config) }

            assertEquals("other", exception.nodeName)
            assertEquals(emptyMap(), checkpointer.load("default")?.subgraphs)
            around.resume(config)
            assertEquals(2, runs["a"])
        }

    @Test
    fun `an effect of the node that failed is not repeated`() =
        runTest {
            val charged =
                StateGraph<TestState> {
                    START then counting("a", 1) then
                        node("pay", work = { _ ->
                            effect("charge", ChargeResult) {
                                ran("charge")
                                "paid"
                            }
                            ran("pay")
                        }) { state, _ -> state } then END
                }.compile()
            val around = StateGraph<TestState> { START then subgraph("sub", charged) then END }.compile()
            failing["pay"] = 1

            assertFailsWith<NodeExecutionException> { around.invoke(TestState(0), config) }

            assertEquals(mapOf("sub/2/pay/charge" to "paid"), checkpointer.load("default")?.effects)
            around.resume(config)
            assertEquals(mapOf("a" to 1, "charge" to 1, "pay" to 2), runs)
        }

    @Test
    fun `a failed edge of a subgraph runs its step again`() =
        runTest {
            val routed =
                StateGraph<TestState> {
                    val a = counting("a", 1)
                    val b = counting("b", 10)
                    START then a then b
                    conditionalEdge(b, targets = setOf(NodeRef.END)) {
                        ran("edge")
                        NodeRef.END
                    }
                }.compile()
            val around = StateGraph<TestState> { START then subgraph("sub", routed) then END }.compile()
            failing["edge"] = 1

            val exception = assertFailsWith<NodeExecutionException> { around.invoke(TestState(0), config) }

            assertIs<EdgeConditionException>(exception.cause)
            assertEquals(GraphResult.Completed(TestState(11)), around.resume(config))
            assertEquals(mapOf("a" to 1, "b" to 2, "edge" to 2), runs)
        }

    @Test
    fun `the step limit of a subgraph keeps what it finished`() =
        runTest {
            val limited = GraphConfig(checkpointer = checkpointer, maxIterations = 2)
            val around = StateGraph<TestState> { START then subgraph("sub", inner) then END }.compile()

            val exception = assertFailsWith<NodeExecutionException> { around.invoke(TestState(0), limited) }

            assertIs<MaxIterationsExceededException>(exception.cause)
            assertEquals(GraphResult.Completed(TestState(111)), around.resume(limited))
            assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), runs)
        }

    @Test
    fun `a run without a checkpointer fails as it always did`() =
        runTest {
            failing["b"] = 1

            val exception = assertFailsWith<NodeExecutionException> { outer.invoke(TestState(0)) }

            assertEquals("sub", exception.nodeName)
        }
}
