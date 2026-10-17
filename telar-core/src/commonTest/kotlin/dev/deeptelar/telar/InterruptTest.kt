package dev.deeptelar.telar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InterruptTest {
    private val app =
        StateGraph<TestState> {
            node("a") { it.copy(count = it.count + 1) }
            node("b") { it.copy(count = it.count + 10) }
            node("c") { it.copy(count = it.count + 100) }

            edge(START, "a")
            edge("a", "b")
            edge("b", "c")
            edge("c", END)
        }.compile()

    @Test
    fun `interruptBefore pauses before the node and resume continues`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(threadId = "t", checkpointer = checkpointer, interruptBefore = setOf("b"))

            val paused = app.invoke(TestState(0), config)

            assertEquals(GraphResult.Interrupted(TestState(1), listOf("b")), paused)
            assertEquals(Checkpoint(TestState(1), listOf("b"), step = 1, interruptedBefore = true), checkpointer.load("t"))

            val finished = app.resume(config)

            assertEquals(GraphResult.Completed(TestState(111)), finished)
            assertEquals(Checkpoint(TestState(111), emptyList(), step = 3), checkpointer.load("t"))
        }

    @Test
    fun `resume can edit the state before continuing`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("b"))
            app.invoke(TestState(0), config)

            val finished = app.resume(config) { it.copy(count = 5) }

            assertEquals(115, finished.state.count)
        }

    @Test
    fun `an edit of resume is saved before the step runs and a failure does not lose it`() =
        runTest {
            var fail = true
            val graph =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    node("b") { if (fail) error("offline") else it.copy(count = it.count + 10) }
                    edge(START, "a")
                    edge("a", "b")
                    edge("b", END)
                }.compile()
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(threadId = "t", checkpointer = checkpointer, interruptBefore = setOf("b"))
            graph.invoke(TestState(0), config)

            assertFailsWith<NodeExecutionException> { graph.resume(config) { it.copy(count = 5) } }

            // The edit is where the run stands, and it still continues past the pause.
            assertEquals(Checkpoint(TestState(5), listOf("b"), step = 1, interruptedBefore = true), checkpointer.load("t"))
            fail = false
            assertEquals(GraphResult.Completed(TestState(15)), graph.resume(config))
        }

    @Test
    fun `an edit of resume is kept when the run is cancelled in the step`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val graph =
                StateGraph<TestState> {
                    node("a") { it.copy(count = it.count + 1) }
                    node("b") {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    edge(START, "a")
                    edge("a", "b")
                    edge("b", END)
                }.compile()
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(threadId = "t", checkpointer = checkpointer, interruptBefore = setOf("b"))
            graph.invoke(TestState(0), config)

            val run = launch { graph.resume(config) { it.copy(count = 5) } }
            started.await()
            run.cancelAndJoin()

            assertEquals(TestState(5), checkpointer.load("t")?.state)
        }

    @Test
    fun `resume without an edit saves nothing before the step`() =
        runTest {
            val memory = MemoryCheckpointer<TestState>()
            val saves = mutableListOf<Int>()
            val recording =
                object : Checkpointer<TestState> by memory {
                    override suspend fun save(threadId: String, checkpoint: Checkpoint<TestState>) {
                        saves += checkpoint.step
                        memory.save(threadId, checkpoint)
                    }
                }
            val config = GraphConfig(threadId = "t", checkpointer = recording, interruptBefore = setOf("b"))
            app.invoke(TestState(0), config)
            saves.clear()

            app.resume(config) { it }

            // One save for each of the two steps that ran, none before them.
            assertEquals(listOf(2, 3), saves)
        }

    @Test
    fun `interruptAfter pauses after the node and resume continues`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptAfter = setOf("a"))

            assertEquals(GraphResult.Interrupted(TestState(1), listOf("b")), app.invoke(TestState(0), config))
            assertEquals(GraphResult.Completed(TestState(111)), app.resume(config))
        }

    @Test
    fun `interruptAfter on the last node completes the run`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptAfter = setOf("c"))

            assertEquals(GraphResult.Completed(TestState(111)), app.invoke(TestState(0), config))
        }

    @Test
    fun `a run can pause several times`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("b", "c"))

            assertEquals(GraphResult.Interrupted(TestState(1), listOf("b")), app.invoke(TestState(0), config))
            assertEquals(GraphResult.Interrupted(TestState(11), listOf("c")), app.resume(config))
            assertEquals(GraphResult.Completed(TestState(111)), app.resume(config))
        }

    @Test
    fun `interruptBefore still pauses after an interruptAfter pause`() =
        runTest {
            val config =
                GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptAfter = setOf("a"), interruptBefore = setOf("b"))

            assertEquals(GraphResult.Interrupted(TestState(1), listOf("b")), app.invoke(TestState(0), config))
            // The first pause came from interruptAfter, so the pause before "b" is still due.
            assertEquals(GraphResult.Interrupted(TestState(1), listOf("b")), app.resume(config))
            assertEquals(GraphResult.Completed(TestState(111)), app.resume(config))
        }

    @Test
    fun `resume after a crash between steps still pauses before the next node`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(checkpointer = checkpointer, interruptBefore = setOf("b"))
            // The checkpoint saved after step "a"; the process died before the run could pause before "b".
            checkpointer.save("default", Checkpoint(TestState(1), listOf("b"), step = 1))

            assertEquals(GraphResult.Interrupted(TestState(1), listOf("b")), app.resume(config))
            assertEquals(GraphResult.Completed(TestState(111)), app.resume(config))
        }

    @Test
    fun `interruptBefore on the first node pauses before anything runs`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(checkpointer = checkpointer, interruptBefore = setOf("a"))

            assertEquals(GraphResult.Interrupted(TestState(0), listOf("a")), app.invoke(TestState(0), config))
            assertEquals(0, checkpointer.load("default")?.step)
            assertEquals(111, app.resume(config).state.count)
        }

    @Test
    fun `invoke always starts a new run from the given input`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("b"))
            app.invoke(TestState(0), config)

            // A second invoke on a paused thread must not continue from the old checkpoint.
            assertEquals(GraphResult.Interrupted(TestState(1001), listOf("b")), app.invoke(TestState(1000), config))
        }

    @Test
    fun `a new run that fails in its first step resumes from its own input instead of the previous run`() =
        runTest {
            val failing =
                StateGraph<TestState> {
                    node("a") { if (it.count >= 1000) error("rejected") else it.copy(count = it.count + 1) }
                    node("b") { it.copy(count = it.count + 10) }

                    edge(START, "a")
                    edge("a", "b")
                    edge("b", END)
                }.compile()
            val checkpointer = MemoryCheckpointer<TestState>()
            val config = GraphConfig(checkpointer = checkpointer, interruptBefore = setOf("b"))
            failing.invoke(TestState(0), config)

            assertFailsWith<NodeExecutionException> { failing.invoke(TestState(1000), config) }

            assertEquals(Checkpoint(TestState(1000), listOf("a")), checkpointer.load("default"))
            assertEquals(GraphResult.Interrupted(TestState(11), listOf("b")), failing.resume(config) { it.copy(count = 10) })
        }

    @Test
    fun `a run that fails in its first step can be retried with resume`() =
        runTest {
            var attempts = 0
            val flaky =
                StateGraph<TestState> {
                    node("a") { if (++attempts == 1) error("busy") else it.copy(count = it.count + 1) }

                    edge(START, "a")
                }.compile()
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertFailsWith<NodeExecutionException> { flaky.invoke(TestState(5), config) }

            assertEquals(GraphResult.Interrupted(TestState(5), listOf("a")), flaky.lastResult(config))
            assertEquals(GraphResult.Completed(TestState(6)), flaky.resume(config))
        }

    @Test
    fun `a completed thread can be invoked again`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())

            assertEquals(111, app.invoke(TestState(0), config).state.count)
            assertEquals(611, app.invoke(TestState(500), config).state.count)
        }

    @Test
    fun `threads are isolated from each other`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            val first = GraphConfig(threadId = "first", checkpointer = checkpointer, interruptBefore = setOf("b"))
            val second = first.copy(threadId = "second")

            app.invoke(TestState(0), first)
            app.invoke(TestState(50), second)

            assertEquals(111, app.resume(first).state.count)
            assertEquals(161, app.resume(second).state.count)
        }

    @Test
    fun `a checkpoint is saved when the run starts and after every step`() =
        runTest {
            val saved = mutableListOf<Checkpoint<TestState>>()
            val recording =
                object : Checkpointer<TestState> {
                    override suspend fun save(threadId: String, checkpoint: Checkpoint<TestState>) {
                        saved += checkpoint
                    }

                    override suspend fun load(threadId: String): Checkpoint<TestState>? = saved.lastOrNull()

                    override suspend fun delete(threadId: String) = saved.clear()
                }

            app.invoke(TestState(0), GraphConfig(checkpointer = recording))

            assertEquals(
                listOf(
                    Checkpoint(TestState(0), listOf("a"), step = 0),
                    Checkpoint(TestState(1), listOf("b"), step = 1),
                    Checkpoint(TestState(11), listOf("c"), step = 2),
                    Checkpoint(TestState(111), emptyList(), step = 3),
                ),
                saved,
            )
        }

    @Test
    fun `resume without a checkpoint fails`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            val exception =
                assertFailsWith<CheckpointNotFoundException> {
                    app.resume(GraphConfig(threadId = "missing", checkpointer = checkpointer))
                }
            assertEquals("missing", exception.threadId)
            assertNull(checkpointer.load("missing"))
        }

    @Test
    fun `resume of a completed thread fails`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>())
            app.invoke(TestState(0), config)

            assertFailsWith<GraphAlreadyCompletedException> { app.resume(config) }
        }

    @Test
    fun `resume without a checkpointer fails`() =
        runTest {
            assertFailsWith<GraphValidationException> { app.resume(GraphConfig()) }
        }

    @Test
    fun `interrupts without a checkpointer are rejected`() {
        assertFailsWith<GraphValidationException> { GraphConfig<TestState>(interruptBefore = setOf("b")) }
        assertFailsWith<GraphValidationException> { GraphConfig<TestState>(interruptAfter = setOf("b")) }
    }

    @Test
    fun `interrupts on unknown nodes are rejected`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("typo"))

            assertFailsWith<GraphValidationException> { app.invoke(TestState(0), config) }
        }

    @Test
    fun `resume fails when the checkpoint refers to a node missing from the graph`() =
        runTest {
            val checkpointer = MemoryCheckpointer<TestState>()
            checkpointer.save("default", Checkpoint(TestState(0), listOf("removed"), step = 1))

            assertFailsWith<GraphValidationException> { app.resume(GraphConfig(checkpointer = checkpointer)) }
        }
}
