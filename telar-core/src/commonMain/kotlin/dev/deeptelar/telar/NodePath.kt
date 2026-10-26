package dev.deeptelar.telar

/**
 * Joins the names in the path of a node of a subgraph: `"research/ask"` is the node `ask` of the
 * subgraph node `research`. A node name cannot hold it, so a path is never read two ways.
 */
internal const val PATH_SEPARATOR: Char = '/'

/** The names among [paths] that are neither a node of this graph nor the path of a node of one of its subgraphs. */
internal fun GraphTopology.unknownNodes(paths: Set<String>): List<String> = paths.filterNot { hasNode(it) }

private fun GraphTopology.hasNode(path: String): Boolean {
    val first = path.substringBefore(PATH_SEPARATOR)
    if (first == path) return path in nodes
    return subgraphs[first]?.hasNode(path.substringAfter(PATH_SEPARATOR)) ?: false
}

/** The paths among these that lead into the subgraph node [node], each without the name of [node]. */
internal fun Set<String>.inside(node: String): Set<String> {
    val prefix = "$node$PATH_SEPARATOR"
    return filter { it.startsWith(prefix) }.mapTo(mutableSetOf()) { it.removePrefix(prefix) }
}
