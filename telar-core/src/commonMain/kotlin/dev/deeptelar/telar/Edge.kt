package dev.deeptelar.telar

/**
 * A routing function for a conditional edge: it inspects the current state and returns the name of
 * the node to run next, or [END] to finish.
 */
public typealias EdgeCondition<State> = suspend (State) -> String

/** A static transition from one node to another. */
internal data class Edge(
    val from: String,
    val to: String,
)

/**
 * A transition whose targets are chosen at run time by [condition], optionally restricted to
 * [targets]. [condition] picks one target, or several when [fansOut]: those run in the same step.
 * [labels] describe some of the targets, by their names, for [GraphTopology.toMermaid].
 */
internal data class ConditionalEdge<State>(
    val from: String,
    val targets: Set<String>?,
    val condition: suspend (State) -> Set<String>,
    val labels: Map<String, String> = emptyMap(),
    val fansOut: Boolean = false,
)
