package dev.deeptelar.telar.test

import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.agent.ChatEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

/**
 * Runs the graph of this stream to its end and returns what happened, in a form a test can compare:
 *
 * ```kotlin
 * val run = agent.stream(AgentState("Where is my pizza?")).record()
 *
 * assertEquals(listOf("model", "tools", "model"), run.nodes)
 * assertEquals("Your pizza is 5 minutes away.", run.text)
 * assertEquals("Your pizza is 5 minutes away.", run.state.answer)
 * ```
 *
 * A run that fails throws here, as it does when the stream is collected.
 */
public suspend fun <State> Flow<GraphEvent<State>>.record(): RunRecord<State> = RunRecord(toList())

/**
 * What happened in one run of a graph, read from the events of its stream. Get one with [record].
 *
 * @property events every event of the run, in the order it arrived.
 * @throws IllegalArgumentException if [events] does not end with [GraphEvent.Completed] or
 * [GraphEvent.Interrupted], as every stream of a run does.
 */
public class RunRecord<State>(
    public val events: List<GraphEvent<State>>,
) {
    /** How the run ended: completed or paused, with its last state. */
    public val result: GraphResult<State> =
        when (val last = events.lastOrNull()) {
            is GraphEvent.Completed -> GraphResult.Completed(last.state)
            is GraphEvent.Interrupted -> GraphResult.Interrupted(last.state, last.nextNodes)
            else -> throw IllegalArgumentException("The events of a run end with Completed or Interrupted, but these end with $last.")
        }

    /** The state the run ended with. */
    public val state: State get() = result.state

    /**
     * The nodes that finished, in the order they finished: the path the run took. A node that ran
     * twice is there twice. The nodes inside a subgraph are not: read them from [events].
     *
     * Nodes that run at the same time finish in an order that can change from run to run. Compare
     * [steps] for a graph with parallel branches.
     */
    public val nodes: List<String> get() = events.filterIsInstance<GraphEvent.NodeCompleted<State>>().map { it.node }

    /** The nodes of each step that finished, in the order of the steps. The nodes of one step ran at the same time. */
    public val steps: List<List<String>> get() = events.filterIsInstance<GraphEvent.StepCompleted<State>>().map { it.nodes }

    /**
     * The text the models of the run wrote, as a screen shows it that prints each piece: the pieces
     * of every answer, joined in the order they arrived. The text of a try that failed and was
     * followed by another is left out, because a [GraphEvent.NodeRetry] tells a screen to clear it.
     */
    public val text: String
        get() {
            val pieces = mutableListOf<Pair<String, String>>()
            for (event in events) {
                var place = ""
                var inner: GraphEvent<*> = event
                while (inner is GraphEvent.SubgraphEvent<*>) {
                    place += "${inner.step}/${inner.node}/"
                    inner = inner.event
                }
                when (inner) {
                    is GraphEvent.NodeProgress<*> ->
                        (inner.value as? ChatEvent.TextDelta)?.let {
                            pieces += "$place${inner.step}/${inner.node}/${inner.item}" to it.text
                        }
                    is GraphEvent.NodeRetry<*> -> {
                        // A retry of one item of a node clears the text of that item.
                        val retried = "$place${inner.step}/${inner.node}/${inner.item}"
                        pieces.removeAll { (written, _) -> written == retried }
                    }
                    else -> Unit
                }
            }
            return pieces.joinToString("") { it.second }
        }

    override fun toString(): String = "RunRecord(steps=$steps, result=$result)"
}
