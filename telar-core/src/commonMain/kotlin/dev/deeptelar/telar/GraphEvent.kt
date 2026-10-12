package dev.deeptelar.telar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlin.time.Duration

/**
 * A progress event emitted by [CompiledGraph.stream] and [CompiledGraph.streamResume].
 *
 * For every executed step a stream emits a [NodeStarted] for each node of the step, a
 * [NodeCompleted] as each of them finishes, and then one [StepCompleted]. While a node runs, it can
 * add [NodeProgress] events of its own, and a node that runs a subgraph adds a [SubgraphEvent] for
 * every event of that graph. A node with a [Retry] adds a [NodeRetry] for each try that failed and
 * is followed by another. A stream always ends with exactly one [Completed] or [Interrupted]. When
 * a node calls [interrupt], [Interrupted] comes right after the events of the nodes that had
 * started, without a [StepCompleted].
 */
public sealed interface GraphEvent<out State> {
    /** The graph state at the time of the event. */
    public val state: State

    /**
     * [node] is about to run in [step]. [state] is the state the node receives. When a step runs
     * several nodes in parallel, all of their [NodeStarted] events come before the first
     * [NodeCompleted].
     */
    public data class NodeStarted<out State>(
        val step: Int,
        val node: String,
        override val state: State,
    ) : GraphEvent<State>

    /**
     * [node] reported [value] with [reportProgress] while it ran in [step]. The events of a node
     * arrive in the order the node reported them, after its [NodeStarted] and before its
     * [NodeCompleted].
     *
     * @property value what the node reported. Its type is up to the node: check it with `is` or `as?`.
     * @property state the state the node received.
     */
    public data class NodeProgress<out State>(
        val step: Int,
        val node: String,
        val value: Any,
        override val state: State,
    ) : GraphEvent<State>

    /**
     * [event] happened inside the subgraph that [node] runs in [step]. A subgraph sends every event
     * that a stream of its own would have, from the [NodeStarted] of its first node to its
     * [Completed] or [Interrupted], and they arrive after the [NodeStarted] of [node] and before
     * its [NodeCompleted]. The steps inside [event] are those of the subgraph, which start at 1 on
     * every visit.
     *
     * ```kotlin
     * graph.stream(case, config).collect { event ->
     *     if (event is GraphEvent.SubgraphEvent) {
     *         val inside = event.event
     *         if (inside is GraphEvent.NodeStarted) println("${event.node} > ${inside.node} started")
     *     }
     * }
     * ```
     *
     * When the subgraph has a subgraph of its own, [event] is a [SubgraphEvent] again. [innermost]
     * and [path] read through the layers.
     *
     * @property event what happened in the subgraph. Its state is the state of the subgraph.
     * @property state the state the node received, in the type of this graph.
     */
    public data class SubgraphEvent<out State>(
        val step: Int,
        val node: String,
        val event: GraphEvent<*>,
        override val state: State,
    ) : GraphEvent<State> {
        /** The nodes that lead to [innermost], from [node] inwards: one name for each subgraph. */
        public val path: List<String>
            get() = generateSequence<SubgraphEvent<*>>(this) { it.event as? SubgraphEvent<*> }.map { it.node }.toList()

        /** The event that is not a [SubgraphEvent]: [event], or the one inside it when subgraphs are nested. */
        public val innermost: GraphEvent<*>
            get() = generateSequence<SubgraphEvent<*>>(this) { it.event as? SubgraphEvent<*> }.last().event
    }

    /**
     * A try of [node] failed in [step], and the node will run again because it has a [Retry]. The
     * event arrives before the engine waits for [pause]. The node has one [NodeStarted] and one
     * [NodeCompleted], however often it is tried.
     *
     * The next try reports its progress from the start. A screen that shows what [NodeProgress]
     * brought, such as the text a model writes, clears it for [node] when this event arrives.
     *
     * The last try of a node has no event: when it fails, the run fails with a
     * [NodeExecutionException].
     *
     * @property attempt the number of the try that failed, starting at 1.
     * @property cause what the node threw, or a [NodeTimeoutException] when the try took too long.
     * @property pause how long the engine waits before the next try.
     * @property state the state the node received, and receives again.
     */
    public data class NodeRetry<out State>(
        val step: Int,
        val node: String,
        val attempt: Int,
        val cause: Throwable,
        val pause: Duration,
        override val state: State,
    ) : GraphEvent<State>

    /**
     * [node] finished in [step]. [state] is what the node returned, or for a node with a `work` and
     * an `update`, its update applied to the state the node received. In a step with several nodes
     * this is the node's own result, before it is combined with the others; the combined state
     * arrives with [StepCompleted].
     */
    public data class NodeCompleted<out State>(
        val step: Int,
        val node: String,
        override val state: State,
    ) : GraphEvent<State>

    /**
     * A step finished: [nodes] ran (in parallel when there are several) and produced [state].
     *
     * @property step the thread's step number, starting at 1 and continuing across resumes.
     */
    public data class StepCompleted<out State>(
        val step: Int,
        val nodes: List<String>,
        override val state: State,
    ) : GraphEvent<State>

    /**
     * The run paused at an interrupt. [nextNodes] run when the thread is resumed. After an [interrupt]
     * from a node, [state] is the state that node passed.
     */
    public data class Interrupted<out State>(
        override val state: State,
        val nextNodes: List<String>,
    ) : GraphEvent<State>

    /** Every branch reached [END]. */
    public data class Completed<out State>(
        override val state: State,
    ) : GraphEvent<State>
}

/**
 * Maps a stream of events to the state after each step, which is convenient for UI code that only
 * renders the latest state (for example `collectAsState` in Compose).
 */
public fun <State> Flow<GraphEvent<State>>.states(): Flow<State> = filterIsInstance<GraphEvent.StepCompleted<State>>().map { it.state }
