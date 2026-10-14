package dev.deeptelar.telar

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * Watches the runs of a graph: when a run starts and how it ends, when each node starts and how it
 * ends, which nodes come next after a step, and when a checkpoint is saved. Use it to send traces
 * and metrics to the tools a team already has, such as OpenTelemetry with `telar-otel`. Give it to
 * a run with [GraphConfig.observers]:
 *
 * ```kotlin
 * val timer = object : GraphObserver {
 *     override fun onNodeCompleted(node: ObservedNode) = println("${node.name} done in step ${node.step}")
 * }
 * graph.invoke(order, GraphConfig(observers = listOf(timer)))
 * ```
 *
 * Every function does nothing unless it is overridden, so an observer overrides only what it needs.
 * An observer is told about every run of the config: [CompiledGraph.invoke] and
 * [CompiledGraph.stream], [CompiledGraph.resume], [CompiledGraph.fork], and the runs of the
 * subgraphs inside them.
 *
 * What an observer gets:
 * - **No state.** The state can hold personal data, so an observer only learns names, steps,
 *   timing and failures. [onNodeProgress] is the one exception: it gets what a node reports.
 * - **Every start has one end.** A run that started ends with exactly one of [onRunCompleted],
 *   [onRunInterrupted] and [onRunFailed], and a node that started ends with exactly one of
 *   [onNodeCompleted], [onNodeInterrupted] and [onNodeFailed], also when the run is cancelled.
 * - **Calls from several coroutines.** The nodes of a step run in parallel and call the observer at
 *   the same time, possibly on different threads. An observer that keeps data must be safe for that.
 *
 * The functions are not `suspend`: they are called in the middle of the engine and must return
 * quickly. Hand slow work, such as sending data over the network, to a queue of your own. What an
 * observer throws fails the run, so an observer must not throw.
 */
public interface GraphObserver {
    /** [run] starts, before anything is loaded or saved. */
    public fun onRunStarted(run: ObservedRun) {}

    /** [run] reached [END] on every branch. */
    public fun onRunCompleted(run: ObservedRun) {}

    /**
     * [run] paused: at [GraphConfig.interruptBefore] or [GraphConfig.interruptAfter], or because a
     * node called [interrupt]. [nextNodes] run when the thread is resumed.
     */
    public fun onRunInterrupted(run: ObservedRun, nextNodes: List<String>) {}

    /**
     * [run] failed with [cause], the exception that the caller of the run gets, or a
     * `CancellationException` when the run was cancelled.
     */
    public fun onRunFailed(run: ObservedRun, cause: Throwable) {}

    /** [node] starts. A node with a [Retry] starts once, however often it is tried. */
    public fun onNodeStarted(node: ObservedNode) {}

    /**
     * [node] reported [value] with [reportProgress], such as a piece of the text a model writes.
     * Only a run that is collected with [CompiledGraph.stream], [CompiledGraph.streamResume] or
     * [CompiledGraph.streamFork] has progress: an observer does not turn it on.
     */
    public fun onNodeProgress(node: ObservedNode, value: Any) {}

    /**
     * A try of [node] failed with [cause], and the node runs again after [pause]. See
     * [GraphEvent.NodeRetry].
     */
    public fun onNodeRetry(node: ObservedNode, attempt: Int, cause: Throwable, pause: Duration) {}

    /** [node] finished its work. Its result is combined with those of the other nodes of its step next. */
    public fun onNodeCompleted(node: ObservedNode) {}

    /**
     * [node] paused the run with [interrupt]. It runs again when the thread is resumed. Its own
     * run follows with [onRunInterrupted].
     */
    public fun onNodeInterrupted(node: ObservedNode) {}

    /**
     * [node] failed with [cause]: a [NodeExecutionException] when its last try failed, or a
     * `CancellationException` when it was cancelled, because another node of its step failed or
     * paused the run, or because the run was cancelled.
     */
    public fun onNodeFailed(node: ObservedNode, cause: Throwable) {}

    /**
     * Step [step] of [run] finished: [nodes] ran, their results were combined and the edges chose
     * [nextNodes]. An empty [nextNodes] means the run completes.
     */
    public fun onStepCompleted(run: ObservedRun, step: Int, nodes: List<String>, nextNodes: List<String>) {}

    /**
     * The checkpointer of [run] saved a checkpoint after step [step] (0 when the run starts), with
     * [nextNodes] to run next. A subgraph has no checkpointer of its own: the graph around it saves.
     */
    public fun onCheckpointSaved(run: ObservedRun, step: Int, nextNodes: List<String>) {}

    /**
     * Returns what to add to the coroutine context in which [node] runs, for example the trace span
     * of the node, so that what the node calls is recorded inside it. Called once for each node in
     * each step, after [onNodeStarted].
     */
    public fun nodeContext(node: ObservedNode): CoroutineContext = EmptyCoroutineContext
}

/**
 * A run that a [GraphObserver] is told about. It is the same object from the start of the run to its
 * end, so an observer can keep what belongs to a run, such as a trace span, in a map with the run as
 * its key.
 *
 * @property threadId the thread of the run, from [GraphConfig.threadId].
 * @property parent the node that runs this run as a subgraph. `null` for the run that the app started.
 */
public class ObservedRun internal constructor(
    public val threadId: String,
    public val parent: ObservedNode?,
) {
    override fun toString(): String = if (parent == null) "run of thread '$threadId'" else "subgraph run of $parent"
}

/**
 * A node in one step of a run, which a [GraphObserver] is told about. It is the same object from the
 * start of the node to its end.
 *
 * @property run the run the node belongs to.
 * @property step the step of [run] in which the node runs.
 * @property name the name of the node.
 */
public class ObservedNode internal constructor(
    public val run: ObservedRun,
    public val step: Int,
    public val name: String,
) {
    override fun toString(): String = "node '$name' in step $step"
}

/** The observers of a config, told about everything as one. */
internal fun GraphConfig<*>.observer(): GraphObserver = observers.singleOrNull() ?: ObserverGroup(observers)

/** Tells each of [observers] in turn. */
private class ObserverGroup(
    private val observers: List<GraphObserver>,
) : GraphObserver {
    override fun onRunStarted(run: ObservedRun) = observers.forEach { it.onRunStarted(run) }

    override fun onRunCompleted(run: ObservedRun) = observers.forEach { it.onRunCompleted(run) }

    override fun onRunInterrupted(run: ObservedRun, nextNodes: List<String>) = observers.forEach { it.onRunInterrupted(run, nextNodes) }

    override fun onRunFailed(run: ObservedRun, cause: Throwable) = observers.forEach { it.onRunFailed(run, cause) }

    override fun onNodeStarted(node: ObservedNode) = observers.forEach { it.onNodeStarted(node) }

    override fun onNodeProgress(node: ObservedNode, value: Any) = observers.forEach { it.onNodeProgress(node, value) }

    override fun onNodeRetry(node: ObservedNode, attempt: Int, cause: Throwable, pause: Duration) =
        observers.forEach { it.onNodeRetry(node, attempt, cause, pause) }

    override fun onNodeCompleted(node: ObservedNode) = observers.forEach { it.onNodeCompleted(node) }

    override fun onNodeInterrupted(node: ObservedNode) = observers.forEach { it.onNodeInterrupted(node) }

    override fun onNodeFailed(node: ObservedNode, cause: Throwable) = observers.forEach { it.onNodeFailed(node, cause) }

    override fun onStepCompleted(run: ObservedRun, step: Int, nodes: List<String>, nextNodes: List<String>) =
        observers.forEach { it.onStepCompleted(run, step, nodes, nextNodes) }

    override fun onCheckpointSaved(run: ObservedRun, step: Int, nextNodes: List<String>) =
        observers.forEach { it.onCheckpointSaved(run, step, nextNodes) }

    override fun nodeContext(node: ObservedNode): CoroutineContext =
        observers.fold<GraphObserver, CoroutineContext>(EmptyCoroutineContext) { context, observer -> context + observer.nodeContext(node) }
}

/**
 * Tells an observer how [run] goes. A run ends once for the observer, also when the collector of its
 * stream throws after the last event.
 */
internal class RunWatch(
    val observer: GraphObserver,
    val run: ObservedRun,
) {
    private var ended = false

    fun started() = observer.onRunStarted(run)

    fun completed() {
        ended = true
        observer.onRunCompleted(run)
    }

    fun interrupted(nextNodes: List<String>) {
        ended = true
        observer.onRunInterrupted(run, nextNodes)
    }

    fun failed(cause: Throwable) {
        if (ended) return
        ended = true
        observer.onRunFailed(run, cause)
    }
}
