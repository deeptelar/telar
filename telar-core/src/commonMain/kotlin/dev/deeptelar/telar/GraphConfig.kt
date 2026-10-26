package dev.deeptelar.telar

/**
 * Per-run settings for a [CompiledGraph].
 *
 * @property threadId identifies the conversation or workflow instance whose checkpoints are stored
 * by [checkpointer]. Use one id per independent run.
 * @property checkpointer where checkpoints are stored. When set, a checkpoint is saved when a run
 * starts and after every step. Required for [interruptBefore], [interruptAfter], [interrupt] and
 * [CompiledGraph.resume].
 * @property interruptBefore node names to pause before, for human-in-the-loop review. A node of a
 * subgraph is named by its path: the name of the subgraph node, a `/` and the name of the node in
 * the subgraph, such as `"research/ask"`, or `"research/sources/ask"` for a subgraph inside it. A
 * node that decides while it runs whether to pause calls [interrupt] instead.
 * @property interruptAfter node names to pause after, and paths to nodes of subgraphs. A pause after
 * the last node of a subgraph happens before the graph around it goes on.
 * @property maxIterations the maximum number of steps a single [CompiledGraph.invoke] or
 * [CompiledGraph.resume] call may execute before [MaxIterationsExceededException] is thrown.
 * @property observers are told how each run of this config goes, node by node, for example to
 * send traces. See [GraphObserver].
 * @throws GraphValidationException if [threadId] is blank, [maxIterations] is not positive, or
 * interrupts are configured without a [checkpointer].
 */
public data class GraphConfig<State>(
    val threadId: String = "default",
    val checkpointer: Checkpointer<State>? = null,
    val interruptBefore: Set<String> = emptySet(),
    val interruptAfter: Set<String> = emptySet(),
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    val observers: List<GraphObserver> = emptyList(),
) {
    init {
        if (threadId.isBlank()) throw GraphValidationException("threadId must not be blank.")
        if (maxIterations <= 0) throw GraphValidationException("maxIterations must be positive, was $maxIterations.")
        if (checkpointer == null && (interruptBefore.isNotEmpty() || interruptAfter.isNotEmpty())) {
            throw GraphValidationException("interruptBefore/interruptAfter need a checkpointer to save the paused run.")
        }
    }

    public companion object {
        /** The default for [maxIterations]. */
        public const val DEFAULT_MAX_ITERATIONS: Int = 25
    }
}
