package dev.deeptelar.telar

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StateGraphTest {
    private fun validationError(block: StateGraph<TestState>.() -> Unit): String =
        assertFailsWith<GraphValidationException> { StateGraph(block).compile() }.message.orEmpty()

    @Test
    fun `build valid graph successfully`() {
        val compiled =
            StateGraph<TestState> {
                node("a") { it.copy(count = it.count + 1) }
                node("b") { it.copy(count = it.count + 1) }

                edge(START, "a")
                conditionalEdge("a", targets = setOf("b", END)) { state ->
                    if (state.count > 0) "b" else END
                }
                edge("b", END)
            }.compile()

        assertEquals(setOf("a", "b"), compiled.nodes.keys)
        assertEquals(setOf(START, "b"), compiled.edges.keys)
        assertEquals(setOf("a"), compiled.conditionalEdges.keys)
    }

    @Test
    fun `missing START edge is rejected`() {
        val message =
            validationError {
                node("a") { it }
                edge("a", END)
            }
        assertEquals("Graph must have at least one edge originating from START", message)
    }

    @Test
    fun `dangling edge is rejected`() {
        val message =
            validationError {
                node("a") { it }
                edge(START, "a")
                edge("a", "unknown")
            }
        assertEquals("Edge references unknown to-node: unknown", message)
    }

    @Test
    fun `edges into START or out of END are rejected`() {
        val message =
            validationError {
                node("a") { it }
                edge(START, "a")
                edge("a", START)
                edge(END, "a")
            }
        assertContains(message, "unknown to-node: $START")
        assertContains(message, "unknown from-node: $END")
    }

    @Test
    fun `blank and reserved node names are rejected`() {
        assertFailsWith<GraphValidationException> { StateGraph<TestState> { node(" ") { it } } }
        assertFailsWith<GraphValidationException> { StateGraph<TestState> { node(START) { it } } }
        assertFailsWith<GraphValidationException> { StateGraph<TestState> { node(END) { it } } }
    }

    @Test
    fun `duplicate edge is rejected`() {
        val message =
            validationError {
                node("a") { it }
                edge(START, "a")
                edge(START, "a")
            }
        assertContains(message, "Duplicate edge from '$START' to 'a'")
    }

    @Test
    fun `unknown conditional target is rejected`() {
        val message =
            validationError {
                node("a") { it }
                edge(START, "a")
                conditionalEdge("a", targets = setOf("missing", END)) { END }
            }
        assertEquals("Conditional edge from 'a' references unknown target: missing", message)
    }

    @Test
    fun `a label for a node that is not a conditional target is rejected`() {
        val message =
            validationError {
                val a = node("a") { it }
                val b = node("b") { it }
                START then a
                conditionalEdge(a, targets = setOf(b, NodeRef.END), labels = mapOf(a to "again")) { NodeRef.END }
                b then END
            }
        assertEquals("Conditional edge from 'a' has a label for 'a', which is not one of its targets", message)

        val withoutTargets =
            validationError {
                node("a") { it }
                edge(START, "a")
                conditionalEdge("a", labels = mapOf(END to "done")) { END }
            }
        assertEquals("Conditional edge from 'a' has a label for '$END', which is not one of its targets", withoutTargets)
    }

    @Test
    fun `two conditional edges from one node are rejected`() {
        val message =
            validationError {
                node("a") { it }
                edge(START, "a")
                conditionalEdge("a", targets = setOf(END)) { END }
                conditionalEdge("a", targets = setOf(END)) { END }
            }
        assertEquals("Node 'a' has more than one conditional edge", message)
    }

    @Test
    fun `mixing conditional and static edges on one node is rejected`() {
        val message =
            validationError {
                node("a") { it }
                edge(START, "a")
                edge("a", END)
                conditionalEdge("a", targets = setOf(END)) { END }
            }
        assertEquals("Node 'a' has both a conditional edge and static edges", message)
    }

    @Test
    fun `fan-out without a reducer is rejected at compile time`() {
        val graph =
            StateGraph<TestState> {
                node("a") { it }
                node("b") { it }
                edge(START, "a")
                edge(START, "b")
            }

        val message = assertFailsWith<GraphValidationException> { graph.compile() }.message.orEmpty()
        assertContains(message, "needs a Reducer")

        graph.compile(reducer = { current, _ -> current })
    }

    @Test
    fun `unreachable node is rejected when all routes are declared`() {
        val message =
            validationError {
                node("a") { it }
                node("island") { it }
                edge(START, "a")
                conditionalEdge("a", targets = setOf(END)) { END }
                edge("island", END)
            }
        assertEquals("Node 'island' is not reachable from START", message)
    }

    @Test
    fun `reachability is not checked when a conditional edge has undeclared targets`() {
        StateGraph<TestState> {
            node("a") { it }
            node("maybe") { it }
            edge(START, "a")
            conditionalEdge("a") { "maybe" }
        }.compile()
    }

    @Test
    fun `all problems are reported together`() {
        val message =
            validationError {
                node("a") { it }
                edge(START, "a")
                edge("a", "x")
                edge("y", END)
            }
        assertTrue(message.startsWith("Invalid graph:"))
        assertContains(message, "unknown to-node: x")
        assertContains(message, "unknown from-node: y")
    }

    @Test
    fun `routing to a node outside the declared targets fails at run time`() =
        runTest {
            val runnable =
                StateGraph<TestState> {
                    node("a") { it }
                    node("b") { it }
                    edge(START, "a")
                    conditionalEdge("a", targets = setOf("b")) { END }
                }.compile()

            val exception = assertFailsWith<InvalidRouteException> { runnable.invoke(TestState()) }
            assertEquals("a", exception.from)
            assertEquals(END, exception.target)
        }

    @Test
    fun `routing to an unknown node fails at run time when targets are undeclared`() =
        runTest {
            val app =
                StateGraph<TestState> {
                    node("a") { it }
                    edge(START, "a")
                    conditionalEdge("a") { "nowhere" }
                }.compile()

            assertEquals("nowhere", assertFailsWith<InvalidRouteException> { app.invoke(TestState()) }.target)
        }
}
