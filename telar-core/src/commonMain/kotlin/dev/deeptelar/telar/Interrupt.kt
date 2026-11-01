package dev.deeptelar.telar

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Pauses the run from inside a node and saves [state]. Use it when a node finds out in the middle of
 * its work that it needs a person, for example to ask a question it only knows at run time.
 *
 * The call never returns. The run ends with [GraphResult.Interrupted], which holds [state], and
 * [CompiledGraph.resume] runs the node again. Put the question into [state] and let `resume` write
 * the answer into the state, so that the node can tell the two runs apart:
 *
 * ```kotlin
 * val refund = node("refund") { order ->
 *     val amount = refundFor(order)
 *     // Nobody has decided yet: save the question and stop here.
 *     if (order.approved == null) interrupt(order.copy(question = "Refund $amount?"))
 *     if (order.approved) pay(order, amount)
 *     order.copy(question = null)
 * }
 *
 * val paused = graph.invoke(order, config)           // Interrupted, with the question in its state
 * graph.resume(config) { it.copy(approved = true) }  // runs "refund" again, now with the answer
 * ```
 *
 * What to know:
 * - **The node starts again from its first line.** What it did before the call happens a second
 *   time, so call `interrupt` before a side effect, or run the side effect with [effect], which
 *   saves its result and does not repeat it.
 * - **The step is not saved.** When other nodes run in the same step, they are cancelled, and
 *   `resume` runs all of them again with [state] as their input. If two nodes of a step call
 *   `interrupt`, the first one pauses the run and the other one asks after the next `resume`.
 * - **It needs a checkpointer.** Without one the run fails with a [GraphValidationException].
 * - A run that paused here does not pause again before the same nodes because of
 *   [GraphConfig.interruptBefore].
 *
 * Call it from the action of a node, or from the `work` of a node with a `work` and an `update`,
 * with a state of the graph's type. A `try` around the call must not catch `Throwable`, and
 * `runCatching` does: the node would then go on instead of pausing.
 *
 * @throws GraphValidationException when it is not called from a node of a running graph.
 */
public suspend fun <State> interrupt(state: State): Nothing {
    val node =
        currentCoroutineContext()[RunningNode]
            ?: throw GraphValidationException("interrupt() can only be called from a node of a running graph.")
    throw NodeInterrupt(node.name, state, position = null)
}

/**
 * Marks the coroutine of a running node, so that [interrupt] knows which node called it, and tells
 * the node of a subgraph what it needs from the run around it.
 *
 * @property canPause `true` when the run is saved by a checkpointer, so that it can pause.
 * @property position where the run stands inside the subgraph of this node, when it paused there.
 * @property effects where [effect] records what finished in this step.
 * @property effectPath names this node in [effects]: its name, and for a node of a subgraph the
 * nodes and steps around it.
 * @property observed this node for [observer], which a subgraph tells about its run as well.
 * @property interruptBefore the nodes of the subgraph of this node to pause before, named by their
 * paths inside it. Empty for a node without a subgraph.
 * @property interruptAfter the nodes of the subgraph of this node to pause after.
 * @property stopped is told where the subgraph of this node stands when it failed.
 */
internal class RunningNode(
    val name: String,
    val threadId: String,
    val canPause: Boolean,
    val maxIterations: Int,
    val position: SubgraphPosition?,
    val effects: EffectLog,
    val effectPath: String,
    val observed: ObservedNode,
    val observer: GraphObserver,
    val interruptBefore: Set<String>,
    val interruptAfter: Set<String>,
    val stopped: (SubgraphStop) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    /** The paths of the effects this try of the node has called. [effects] guards it. */
    val effectsUsed: MutableSet<String> = mutableSetOf()

    /** The last point that the subgraph of this node saved in this try, whether a checkpointer stored it or not. */
    var reached: Checkpoint<*>? = null

    companion object Key : CoroutineContext.Key<RunningNode>
}

/**
 * Carries an [interrupt] from the node that called it to the engine. It is not an `Exception`, so
 * that a `catch (e: Exception)` in the node does not stop it on the way. It is an `Error` of its
 * own class, which nothing between the node and the engine takes for a failure: see [isFailure].
 *
 * @property position where the run stands inside the subgraph of [node], when the pause comes from
 * there. `null` when the node called [interrupt] itself.
 */
internal class NodeInterrupt(
    val node: String,
    val state: Any?,
    val position: SubgraphPosition?,
) : Error("Node '$node' called interrupt(). The engine pauses the run with this; do not catch it.")
