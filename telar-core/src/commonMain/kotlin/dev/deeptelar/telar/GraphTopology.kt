package dev.deeptelar.telar

/**
 * The structure of a compiled graph: its nodes and the edges between them. Read it from
 * [CompiledGraph.topology] to draw the graph or to check it in a test.
 *
 * @property nodes the node names in the order they were added, without [START] and [END].
 * @property edges every edge that is known before the graph runs: the static edges, the declared
 * targets of conditional edges, and an edge to [END] for each node that has no outgoing edge.
 * @property dynamicRoutes the nodes whose conditional edge declares no targets. Such a node can
 * route to any node or to [END], so it has no entries in [edges].
 * @property subgraphs the structure of the graph that a node added with [StateGraph.subgraph] runs,
 * by the name of that node. The node is in [nodes] and [edges] like any other.
 * @property forEachNodes the nodes that run their work for each item of a list, added with the
 * `forEach` of [StateGraph.node]. They are in [nodes] and [edges] like any other.
 */
public data class GraphTopology(
    val nodes: List<String>,
    val edges: List<GraphEdge>,
    val dynamicRoutes: Set<String>,
    val subgraphs: Map<String, GraphTopology> = emptyMap(),
    val forEachNodes: Set<String> = emptySet(),
) {
    /** The nodes (or [END]) that [node] can lead to according to [edges]. */
    public fun successors(node: String): List<String> = edges.filter { it.from == node }.map { it.to }

    /**
     * Draws this graph as a [Mermaid](https://mermaid.js.org) state diagram, which GitHub, GitLab and
     * most documentation sites show as a picture in a code block of the language `mermaid`.
     *
     * [START] and [END] are the `[*]` of the diagram, and a subgraph is a state that holds the
     * diagram of its graph. A conditional edge is labeled with its entry in [edgeLabels], its
     * [GraphEdge.label], or `?`. A node in [dynamicRoutes] gets a note that it routes to any node,
     * and a node in [forEachNodes] one that it runs for each item.
     *
     * @param interruptBefore nodes the run pauses before, usually [GraphConfig.interruptBefore]. Their
     * names start with ⏸ and their states are highlighted.
     * @param interruptAfter nodes the run pauses after, usually [GraphConfig.interruptAfter]. Their
     * names end with ⏸ and their states are highlighted.
     * @param edgeLabels a label for an edge, by the names of its two ends, such as
     * `("read" to "refund") to "refund request"`. Use [START] and [END] for those ends. It takes
     * the place of the [GraphEdge.label] of the edge.
     * @throws GraphValidationException if [interruptBefore] or [interruptAfter] name a node that is
     * not in [nodes], or [edgeLabels] an edge that is not in [edges].
     */
    public fun toMermaid(
        interruptBefore: Set<String> = emptySet(),
        interruptAfter: Set<String> = emptySet(),
        edgeLabels: Map<Pair<String, String>, String> = emptyMap(),
    ): String = mermaid(this, interruptBefore, interruptAfter, edgeLabels)
}

/**
 * A transition from [from] to [to]. Either end can be [START] or [END].
 *
 * @property isConditional `true` when [to] is one of the declared targets of a conditional edge, so
 * the transition only happens when the edge's condition picks it.
 * @property label says when a conditional edge goes to [to], from the `labels` of
 * [StateGraph.conditionalEdge]. `null` when it has none, and for a static edge.
 */
public data class GraphEdge(
    val from: String,
    val to: String,
    val isConditional: Boolean = false,
    val label: String? = null,
)
