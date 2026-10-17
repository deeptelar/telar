package dev.deeptelar.telar

/** The label of a conditional edge that has no label of its own and none in `edgeLabels`. */
internal const val CONDITIONAL_LABEL: String = "?"

/** Writes [topology] as a Mermaid state diagram. See [GraphTopology.toMermaid]. */
internal fun mermaid(
    topology: GraphTopology,
    interruptBefore: Set<String>,
    interruptAfter: Set<String>,
    edgeLabels: Map<Pair<String, String>, String>,
): String {
    val unknown = (interruptBefore + interruptAfter).filter { it !in topology.nodes }
    if (unknown.isNotEmpty()) throw GraphValidationException("Interrupt nodes not in the graph: $unknown.")
    val edges = topology.edges.map { it.from to it.to }.toSet()
    val missing = edgeLabels.keys.filter { it !in edges }
    if (missing.isNotEmpty()) throw GraphValidationException("Labels for edges that are not in the graph: $missing.")

    return buildString {
        appendLine("stateDiagram-v2")
        writeGraph(topology, prefix = "", indent = "    ", interruptBefore, interruptAfter, edgeLabels)
        val paused = topology.nodes.withIndex().filter { (_, name) -> name in interruptBefore || name in interruptAfter }
        if (paused.isNotEmpty()) {
            appendLine("    classDef interrupt fill:#fff4e5,stroke:#e8590c,stroke-width:2px,stroke-dasharray:4 3")
            appendLine("    class ${paused.joinToString(",") { "n${it.index}" }} interrupt")
        }
    }.trimEnd()
}

/**
 * Writes the states and transitions of [topology]. Node names can hold any character, so each node
 * gets an id made of its position (and the positions of the subgraphs around it), and its name is
 * the description of the state. [interruptBefore], [interruptAfter] and [edgeLabels] name the nodes
 * of the outer graph, so a subgraph is written without them.
 */
private fun StringBuilder.writeGraph(
    topology: GraphTopology,
    prefix: String,
    indent: String,
    interruptBefore: Set<String>,
    interruptAfter: Set<String>,
    edgeLabels: Map<Pair<String, String>, String>,
) {
    val ids = topology.nodes.withIndex().associate { (index, name) -> name to "${prefix}n$index" }
    for (name in topology.nodes) {
        val id = ids.getValue(name)
        val label =
            buildString {
                if (name in interruptBefore) append("⏸ ")
                append(name)
                if (name in interruptAfter) append(" ⏸")
            }
        val inner = topology.subgraphs[name]
        if (inner == null) {
            appendLine("${indent}state \"${escape(label)}\" as $id")
        } else {
            // A subgraph is a composite state: its START and END are the [*] inside it.
            appendLine("${indent}state \"${escape(label)}\" as $id {")
            writeGraph(inner, "${id}_", "$indent    ", emptySet(), emptySet(), emptyMap())
            appendLine("$indent}")
        }
    }
    for (edge in topology.edges) {
        val from = if (edge.from == START) "[*]" else ids.getValue(edge.from)
        val to = if (edge.to == END) "[*]" else ids.getValue(edge.to)
        val label = edgeLabels[edge.from to edge.to] ?: edge.label ?: CONDITIONAL_LABEL.takeIf { edge.isConditional }
        appendLine(if (label == null) "$indent$from --> $to" else "$indent$from --> $to : ${escape(label)}")
    }
    for (name in topology.nodes.filter { it in topology.dynamicRoutes }) {
        appendLine("${indent}note right of ${ids.getValue(name)} : routes to any node")
    }
    for (name in topology.nodes.filter { it in topology.forEachNodes }) {
        appendLine("${indent}note right of ${ids.getValue(name)} : runs for each item")
    }
}

/** Keeps a name or a label on one line and out of Mermaid's syntax. */
private fun escape(text: String): String =
    buildString {
        for (char in text) {
            when {
                char.isWhitespace() -> if (!endsWith(' ')) append(' ')
                char in "#\";:{}" -> append("#${char.code};")
                else -> append(char)
            }
        }
    }
