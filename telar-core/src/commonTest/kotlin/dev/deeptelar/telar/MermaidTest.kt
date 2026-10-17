package dev.deeptelar.telar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MermaidTest {
    private val helpDesk =
        StateGraph<TestState> {
            val read = node("read") { it }
            val track = node("track") { it }
            val refund = node("refund") { it }
            START then read
            conditionalEdge(read, targets = setOf(track.name, refund.name)) { "track" }
            refund then END
        }.compile()

    @Test
    fun `draws a line of nodes from START to END`() {
        val graph =
            StateGraph<TestState> {
                val a = node("a") { it }
                val b = node("b") { it }
                START then a then b then END
            }.compile()

        assertEquals(
            """
            stateDiagram-v2
                state "a" as n0
                state "b" as n1
                [*] --> n0
                n0 --> n1
                n1 --> [*]
            """.trimIndent(),
            graph.toMermaid(),
        )
    }

    @Test
    fun `labels conditional edges with the given labels or a question mark`() {
        assertEquals(
            """
            stateDiagram-v2
                state "read" as n0
                state "track" as n1
                state "refund" as n2
                [*] --> n0
                n0 --> n1 : delivery
                n0 --> n2 : ?
                n1 --> [*]
                n2 --> [*]
            """.trimIndent(),
            helpDesk.toMermaid(edgeLabels = mapOf(("read" to "track") to "delivery")),
        )
    }

    @Test
    fun `labels conditional edges with the labels of the graph unless edgeLabels has others`() {
        val graph =
            StateGraph<TestState> {
                val read = node("read") { it }
                val track = node("track") { it }
                START then read
                conditionalEdge(read, targets = setOf(track, NodeRef.END), labels = mapOf(track to "delivery", NodeRef.END to "spam")) {
                    track
                }
                track then END
            }.compile()

        assertEquals(GraphEdge("read", "track", isConditional = true, label = "delivery"), graph.topology.edges[1])
        assertEquals(
            """
            stateDiagram-v2
                state "read" as n0
                state "track" as n1
                [*] --> n0
                n0 --> n1 : parcel
                n0 --> [*] : spam
                n1 --> [*]
            """.trimIndent(),
            graph.toMermaid(edgeLabels = mapOf(("read" to "track") to "parcel")),
        )
    }

    @Test
    fun `marks the nodes a run pauses at`() {
        val config =
            GraphConfig(checkpointer = MemoryCheckpointer<TestState>(), interruptBefore = setOf("refund"), interruptAfter = setOf("read"))

        assertEquals(
            """
            stateDiagram-v2
                state "read ⏸" as n0
                state "track" as n1
                state "⏸ refund" as n2
                [*] --> n0
                n0 --> n1 : ?
                n0 --> n2 : ?
                n1 --> [*]
                n2 --> [*]
                classDef interrupt fill:#fff4e5,stroke:#e8590c,stroke-width:2px,stroke-dasharray:4 3
                class n0,n2 interrupt
            """.trimIndent(),
            helpDesk.toMermaid(config),
        )
    }

    @Test
    fun `draws a subgraph as a composite state`() {
        val inner =
            StateGraph<TestState> {
                val draft = node("draft") { it }
                START then draft then END
            }.compile()
        val graph =
            StateGraph<TestState> {
                val write = subgraph("write", inner)
                START then write then END
            }.compile()

        assertEquals(
            """
            stateDiagram-v2
                state "write" as n0 {
                    state "draft" as n0_n0
                    [*] --> n0_n0
                    n0_n0 --> [*]
                }
                [*] --> n0
                n0 --> [*]
            """.trimIndent(),
            graph.toMermaid(),
        )
    }

    @Test
    fun `notes a dynamic route and escapes names`() {
        val graph =
            StateGraph<TestState> {
                val router = node("pick: \"one\"") { it }
                node("other") { it }
                START then router
                conditionalEdge(router) { "other" }
            }.compile()

        assertEquals(
            """
            stateDiagram-v2
                state "pick#58; #34;one#34;" as n0
                state "other" as n1
                [*] --> n0
                n1 --> [*]
                note right of n0 : routes to any node
            """.trimIndent(),
            graph.toMermaid(),
        )
    }

    @Test
    fun `rejects pauses and labels for nodes and edges that are not in the graph`() {
        assertFailsWith<GraphValidationException> { helpDesk.topology.toMermaid(interruptBefore = setOf("missing")) }
        assertFailsWith<GraphValidationException> { helpDesk.toMermaid(edgeLabels = mapOf(("track" to "refund") to "no")) }
    }
}
