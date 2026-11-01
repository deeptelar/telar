package dev.deeptelar.telar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Duration

/**
 * The work a node performs: a suspending function that takes the current state and returns the
 * updated state.
 *
 * The action must not mutate the state it receives. Return a new instance instead, typically with
 * `state.copy(...)`.
 */
public typealias NodeAction<State> = suspend (State) -> State

/**
 * A reference to a node added with [StateGraph.node]. Use it with [StateGraph.then] and
 * [StateGraph.conditionalEdge] to connect nodes without repeating their names, and read [name]
 * where a node name is needed (for example in [GraphConfig.interruptBefore]).
 */
public class NodeRef internal constructor(
    public val name: String,
) {
    override fun toString(): String = name

    public companion object {
        /**
         * [dev.deeptelar.telar.END] as a reference, for a conditional edge that works with references:
         * list it in the edge's targets and return it to finish the branch.
         */
        public val END: NodeRef = NodeRef(dev.deeptelar.telar.END)
    }
}

/**
 * A node of a graph.
 *
 * @property returnsState `true` for a node that returns a whole state. Two such nodes in one step
 * need a [Reducer]. `false` for a node with a `work` and an `update`.
 * @property subgraph the graph that this node runs, for a node added with [StateGraph.subgraph].
 * @property retry how the node is tried again after a failure. `null` for a node that runs once.
 * @property timeout how long one try of the node may take. `null` for no limit.
 * @property forEach `true` for a node that runs its work for each item of a list. It tries each
 * item and wraps the failures of its items itself, so that they name the item: see [forEachNode].
 */
internal class Node<State>(
    val name: String,
    val returnsState: Boolean,
    val subgraph: CompiledGraph<*>? = null,
    val retry: Retry? = null,
    val timeout: Duration? = null,
    val forEach: Boolean = false,
    val run: suspend (State) -> NodeOutput<State>,
)

/**
 * What a node produced in one step.
 *
 * @property state the state after this node alone. A step with a single node continues with it, and
 * [GraphEvent.NodeCompleted] reports it.
 * @property update writes the result of a node with a `work` and an `update` into the state it is
 * given. `null` for a node that returns a whole state.
 */
internal class NodeOutput<State>(
    val state: State,
    val update: (suspend (State) -> State)?,
)

/** A node that returns a whole state. */
internal fun <State> stateNode(name: String, retry: Retry? = null, timeout: Duration? = null, action: NodeAction<State>): Node<State> =
    Node(name, returnsState = true, retry = retry, timeout = timeout) { NodeOutput(action(it), update = null) }

/** A node that does its [work] first and then writes the result into the state with [update]. */
internal fun <State, Result> workNode(
    name: String,
    work: suspend (State) -> Result,
    retry: Retry? = null,
    timeout: Duration? = null,
    update: suspend (State, Result) -> State,
): Node<State> =
    Node(name, returnsState = false, retry = retry, timeout = timeout) { state ->
        val result = work(state)
        NodeOutput(update(state, result)) { update(it, result) }
    }

/**
 * A node that runs [graph] on the state that [state] reads from the state of this graph, and writes
 * the state of [graph] back with [update]: when [graph] finishes, and when it pauses.
 *
 * @param returnsState `true` when [graph] has the state type of this graph, so that its result is a
 * whole state.
 */
internal fun <State, Child> subgraphNode(
    name: String,
    graph: CompiledGraph<Child>,
    returnsState: Boolean,
    state: suspend (State) -> Child,
    update: suspend (State, Child) -> State,
): Node<State> =
    Node(name, returnsState, subgraph = graph) { parent ->
        val running = checkNotNull(currentCoroutineContext()[RunningNode]) { "The engine runs every node with a RunningNode" }
        val end =
            try {
                graph.runAsSubgraph(state(parent), running)
            } catch (failure: Throwable) {
                if (failure !is CancellationException && failure.isFailure()) tellWhereItStopped(name, parent, running, state, update)
                throw failure
            }
        when (end) {
            is RunEnd.Completed -> {
                val result = end.state
                NodeOutput(update(parent, result), update = if (returnsState) null else { combined -> update(combined, result) })
            }
            // The pause goes on to the graph around this node, with the state of the subgraph in its own.
            is RunEnd.Paused -> throw NodeInterrupt(name, update(parent, end.checkpoint.state), end.checkpoint.position())
        }
    }

/**
 * Tells the run around [running] where the failed subgraph of the node [name] stands, so that a
 * resume continues there.
 *
 * It does so only when [state] reads back what [update] writes. A subgraph that gets a new state on
 * every visit and gives back only its result has no such state: it would continue without the work
 * of its finished nodes, so it starts again. So does one whose [update] cannot take the state of a
 * subgraph that did not finish. The failure of the node stays what the caller gets.
 */
private suspend fun <State, Child> tellWhereItStopped(
    name: String,
    parent: State,
    running: RunningNode,
    state: suspend (State) -> Child,
    update: suspend (State, Child) -> State,
) {
    @Suppress("UNCHECKED_CAST")
    val reached = running.reached as Checkpoint<Child>? ?: return
    val saved =
        try {
            update(parent, reached.state).takeIf { state(it) == reached.state }
        } catch (cause: Throwable) {
            if (cause is CancellationException || !cause.isFailure()) throw cause
            null
        }
    if (saved != null) running.stopped(SubgraphStop(name, saved, reached.position()))
}

private fun Checkpoint<*>.position(): SubgraphPosition = SubgraphPosition(nextNodes, step, interruptedBefore, subgraphs)

/**
 * Where a subgraph stands that failed.
 *
 * @property node the subgraph node.
 * @property state the state of the graph around it, with the state of the subgraph in it.
 * @property position what the failed subgraph had finished.
 */
internal class SubgraphStop(
    val node: String,
    val state: Any?,
    val position: SubgraphPosition,
)
