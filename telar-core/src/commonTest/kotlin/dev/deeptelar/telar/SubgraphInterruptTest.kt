package dev.deeptelar.telar

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** `interruptBefore` and `interruptAfter` for the nodes of a subgraph, named by their paths. */
class SubgraphInterruptTest {
    // Every node adds to its own digit, so the count shows how often each one ran.
    private val inner =
        StateGraph<TestState> {
            val a = node("a") { it.copy(count = it.count + 1) }
            val b = node("b") { it.copy(count = it.count + 10) }
            val c = node("c") { it.copy(count = it.count + 100) }

            START then a then b then c then END
        }.compile()

    private val outer =
        StateGraph<TestState> {
            val first = node("first") { it.copy(count = it.count + 1000) }
            val sub = subgraph("sub", inner)
            val last = node("last") { it.copy(count = it.count + 10000) }

            START then first then sub then last then END
        }.compile()

    private val checkpointer = MemoryCheckpointer<TestState>()

    private fun config(before: Set<String> = emptySet(), after: Set<String> = emptySet()): GraphConfig<TestState> =
        GraphConfig(checkpointer = checkpointer, interruptBefore = before, interruptAfter = after)

    @Test
    fun `a run pauses before a node of a subgraph and resume runs it once`() =
        runTest {
            val config = config(before = setOf("sub/b"))

            assertEquals(GraphResult.Interrupted(TestState(1001), listOf("sub")), outer.invoke(TestState(0), config))
            assertEquals(
                Checkpoint(
                    TestState(1001),
                    listOf("sub"),
                    step = 1,
                    interruptedBefore = true,
                    subgraphs = mapOf("sub" to SubgraphPosition(listOf("b"), step = 1, interruptedBefore = true)),
                ),
                checkpointer.load("default"),
            )

            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
        }

    @Test
    fun `a run pauses after a node of a subgraph and resume goes on with the next one`() =
        runTest {
            val config = config(after = setOf("sub/b"))

            assertEquals(GraphResult.Interrupted(TestState(1011), listOf("sub")), outer.invoke(TestState(0), config))
            assertEquals(
                mapOf("sub" to SubgraphPosition(listOf("c"), step = 2, interruptedBefore = false)),
                checkpointer.load("default")?.subgraphs,
            )

            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
        }

    @Test
    fun `a pause after the last node of a subgraph comes before the graph around goes on`() =
        runTest {
            val config = config(after = setOf("sub/c"))

            assertEquals(GraphResult.Interrupted(TestState(1111), listOf("sub")), outer.invoke(TestState(0), config))
            assertEquals(
                mapOf("sub" to SubgraphPosition(emptyList(), step = 3, interruptedBefore = false)),
                checkpointer.load("default")?.subgraphs,
            )

            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
        }

    @Test
    fun `the subgraph continues with the state that resume edited`() =
        runTest {
            val config = config(before = setOf("sub/b"))

            outer.invoke(TestState(0), config)

            assertEquals(GraphResult.Completed(TestState(111111)), outer.resume(config) { it.copy(count = it.count + 100000) })
        }

    @Test
    fun `a pause before the subgraph node comes before a pause before its first node`() =
        runTest {
            val config = config(before = setOf("sub", "sub/a"))

            assertEquals(GraphResult.Interrupted(TestState(1000), listOf("sub")), outer.invoke(TestState(0), config))
            assertEquals(emptyMap(), checkpointer.load("default")?.subgraphs)

            assertEquals(GraphResult.Interrupted(TestState(1000), listOf("sub")), outer.resume(config))
            assertEquals(
                mapOf("sub" to SubgraphPosition(listOf("a"), step = 0, interruptedBefore = true)),
                checkpointer.load("default")?.subgraphs,
            )

            assertEquals(GraphResult.Completed(TestState(11111)), outer.resume(config))
        }

    @Test
    fun `a path reaches a node of a subgraph inside a subgraph`() =
        runTest {
            val middle =
                StateGraph<TestState> {
                    START then node("m") { it.copy(count = it.count + 100000) } then subgraph("inner", inner) then END
                }.compile()
            val around = StateGraph<TestState> { START then subgraph("middle", middle) then END }.compile()
            val config = config(before = setOf("middle/inner/c"))

            assertEquals(GraphResult.Interrupted(TestState(100011), listOf("middle")), around.invoke(TestState(0), config))
            val inside = checkpointer.load("default")?.subgraphs?.get("middle")
            assertEquals(SubgraphPosition(listOf("c"), step = 2, interruptedBefore = true), inside?.subgraphs?.get("inner"))

            assertEquals(GraphResult.Completed(TestState(100111)), around.resume(config))
        }

    @Test
    fun `a stream that pauses inside a subgraph ends with the pause of the subgraph and then its own`() =
        runTest {
            val events = outer.stream(TestState(0), config(before = setOf("sub/b"))).toList()

            val inside = assertIs<GraphEvent.SubgraphEvent<TestState>>(events[events.size - 2])
            assertEquals("sub", inside.node)
            assertEquals(GraphEvent.Interrupted(TestState(1001), listOf("b")), inside.event)
            assertEquals(GraphEvent.Interrupted(TestState(1001), listOf("sub")), events.last())
        }

    @Test
    fun `a run rejects a path that leads to no node of a subgraph`() =
        runTest {
            // A node the subgraph does not have, a node that is not a subgraph, and a node that is not there.
            for (path in listOf("sub/x", "first/a", "x/a", "sub/a/b")) {
                assertFailsWith<GraphValidationException>(path) { outer.invoke(TestState(0), config(before = setOf(path))) }
                assertFailsWith<GraphValidationException>(path) { outer.resume(config(after = setOf(path))) }
            }
        }

    @Test
    fun `a node name cannot hold the slash of a path`() {
        val exception = assertFailsWith<GraphValidationException> { StateGraph<TestState> { node("sub/a") { it } } }

        assertEquals(
            "Node name 'sub/a' must not contain '/', which joins the names in the path to a node of a subgraph.",
            exception.message,
        )
        assertFailsWith<GraphValidationException> { StateGraph<TestState> { subgraph("a/b", inner) } }
    }
}
