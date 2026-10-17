package dev.deeptelar.telar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs one thread of a graph for a screen: the run is a [StateFlow] of [GraphSnapshot]s that a UI
 * draws, and the screen starts, resumes and cancels it with plain function calls. It is the
 * unidirectional data flow of an app (state down, events up) with a graph behind it.
 *
 * ```kotlin
 * class ApprovalViewModel(graph: CompiledGraph<Order>, checkpointer: Checkpointer<Order>) : ViewModel() {
 *     val session = GraphSession(graph, GraphConfig("order-42", checkpointer, interruptBefore = setOf("pay")), viewModelScope)
 *
 *     init {
 *         session.restore() // after the app was killed: back to where the run stopped
 *     }
 *
 *     fun approve() = session.resume { it.copy(approved = true) }
 * }
 *
 * // In a composable
 * when (val snapshot = viewModel.session.snapshot.collectAsState().value) {
 *     is GraphSnapshot.Interrupted -> ApproveButton(snapshot.state, onClick = viewModel::approve)
 *     ...
 * }
 * ```
 *
 * The session runs everything in [scope], so the run ends with the scope, for example when a
 * `ViewModel` is cleared. With a checkpointer in [config], nothing is lost: [restore] in the next
 * session finds the run where its last step left it, and [resume] continues it.
 *
 * One command runs at a time. [start], [resume], [restore] and [cancel] stop what the session is
 * doing before they begin, in the order they were called, and can be called from any thread. Each
 * returns a [Job] that completes when its work is over, which a test can wait for.
 *
 * A failure of the run does not reach [scope]: it becomes a [GraphSnapshot.Stopped] with the
 * exception. An `Error` that is no failure of the code, such as an `AssertionError` of a test,
 * fails the scope, as it fails [CompiledGraph.invoke].
 *
 * The nodes run in the coroutines of [scope]. A node that computes for long, rather than waiting
 * for a model or the network, moves that work off the main thread with `withContext`.
 *
 * @param graph the graph to run.
 * @param config the thread this session runs, and how. [resume] and [restore] need its
 * [GraphConfig.checkpointer].
 * @param scope where the session runs, such as `viewModelScope`.
 */
public class GraphSession<State>(
    private val graph: CompiledGraph<State>,
    private val config: GraphConfig<State>,
    scope: CoroutineScope,
) {
    private val mutableSnapshot = MutableStateFlow<GraphSnapshot<State>>(GraphSnapshot.Idle)
    private val mutableEvents = MutableSharedFlow<GraphEvent<State>>()
    private val commands = Channel<Command<State>>(Channel.UNLIMITED)

    /** Where the run stands, for a screen to draw. It starts as [GraphSnapshot.Idle]. */
    public val snapshot: StateFlow<GraphSnapshot<State>> = mutableSnapshot.asStateFlow()

    /**
     * Every [GraphEvent] of the runs of this session, as [CompiledGraph.stream] emits it: what nodes
     * report with [reportProgress], such as the text a model writes, retries and the events of
     * subgraphs. An event reaches the collectors that are there when it happens, after [snapshot]
     * has taken it in, and the run waits until they have it. Nothing is kept for a later collector.
     */
    public val events: SharedFlow<GraphEvent<State>> = mutableEvents.asSharedFlow()

    init {
        val worker =
            scope.launch {
                var running: Job? = null
                for (command in commands) {
                    // The run that is replaced writes nothing more, so its snapshot is set here.
                    running?.cancelAndJoin()
                    mutableSnapshot.value = command.begin(mutableSnapshot.value)
                    running =
                        launch {
                            try {
                                command.work()
                            } finally {
                                command.done.complete()
                            }
                        }
                }
            }
        worker.invokeOnCompletion {
            // The scope is over: the commands that are still waiting will never run.
            commands.close()
            while (true) {
                val waiting = commands.tryReceive().getOrNull() ?: break
                waiting.done.cancel()
            }
        }
    }

    /**
     * Starts a new run of the thread with [input], as [CompiledGraph.stream] does: an earlier run of
     * the thread is replaced. [snapshot] is [GraphSnapshot.Running] with [input] at once.
     */
    public fun start(input: State): Job = submit({ GraphSnapshot.Running(input, emptyList()) }) { follow(graph.stream(input, config)) }

    /**
     * Continues the run of the thread from its last checkpoint, as [CompiledGraph.streamResume] does:
     * after a [GraphSnapshot.Interrupted], or after a [GraphSnapshot.Stopped] to try the failed step
     * again.
     *
     * @param update edits the saved state before the run continues, for example to record what the
     * person decided.
     * @throws GraphValidationException if the config of this session has no checkpointer.
     */
    public fun resume(update: suspend (State) -> State = { it }): Job {
        requireCheckpointer("resume")
        return submit({ current -> current.state?.let { GraphSnapshot.Running(it, emptyList()) } ?: current }) {
            follow(graph.streamResume(config, update))
        }
    }

    /**
     * Reads where the run of the thread stopped, without running anything, as
     * [CompiledGraph.lastResult] does. Call it when a screen opens, for example after the operating
     * system ended the app: [snapshot] is [GraphSnapshot.Restoring] until the checkpoint is read,
     * and then [GraphSnapshot.Idle] when the thread has none, [GraphSnapshot.Completed], or
     * [GraphSnapshot.Interrupted] when [resume] can continue it. A run that the app lost in the
     * middle of a step counts as interrupted before that step.
     *
     * @throws GraphValidationException if the config of this session has no checkpointer.
     */
    public fun restore(): Job {
        requireCheckpointer("restore")
        return submit({ GraphSnapshot.Restoring }) {
            guarded {
                mutableSnapshot.value =
                    when (val result = graph.lastResult(config)) {
                        null -> GraphSnapshot.Idle
                        is GraphResult.Completed -> GraphSnapshot.Completed(result.state)
                        is GraphResult.Interrupted -> GraphSnapshot.Interrupted(result.state, result.nextNodes)
                    }
            }
        }
    }

    /**
     * Stops the run. [snapshot] becomes [GraphSnapshot.Stopped] without a cause when a run was
     * going on, and stays as it is otherwise. With a checkpointer, [resume] continues from the last
     * step that finished.
     */
    public fun cancel(): Job =
        submit({ current -> if (current is GraphSnapshot.Running) GraphSnapshot.Stopped(current.state, null) else current }) {
        }

    private fun requireCheckpointer(function: String) {
        if (config.checkpointer == null) throw GraphValidationException("$function() needs a GraphConfig with a checkpointer.")
    }

    private fun submit(begin: (GraphSnapshot<State>) -> GraphSnapshot<State>, work: suspend () -> Unit): Job {
        val command = Command(Job(), begin, work)
        // At once, so that the first frame after the call draws it. The worker sets it again when the
        // command begins, in case the run it replaces wrote in between.
        val before = mutableSnapshot.value
        val begun = begin(before)
        mutableSnapshot.value = begun
        if (commands.trySend(command).isFailure) {
            // The scope is over and the command will never run.
            mutableSnapshot.compareAndSet(begun, before)
            command.done.cancel()
        }
        return command.done
    }

    /** Takes in the events of a run. */
    private suspend fun follow(run: Flow<GraphEvent<State>>) {
        guarded {
            run.collect { event ->
                mutableSnapshot.value = mutableSnapshot.value.after(event)
                mutableEvents.emit(event)
            }
        }
    }

    /** Runs [block] and turns what it throws into a [GraphSnapshot.Stopped], except a cancellation and an `Error`. */
    private suspend inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            // Only a cancellation of the session stops here. A coroutine that cancelled itself failed.
            currentCoroutineContext().ensureActive()
            mutableSnapshot.value = GraphSnapshot.Stopped(mutableSnapshot.value.state, e)
        } catch (e: Throwable) {
            if (!e.isFailure()) throw e
            mutableSnapshot.value = GraphSnapshot.Stopped(mutableSnapshot.value.state, e)
        }
    }

    /**
     * Something the session was asked to do.
     *
     * @property done completes when [work] is over.
     * @property begin the snapshot when the command begins, from the one before.
     */
    private class Command<State>(
        val done: CompletableJob,
        val begin: (GraphSnapshot<State>) -> GraphSnapshot<State>,
        val work: suspend () -> Unit,
    )
}

/**
 * Where the run of a [GraphSession] stands. A screen draws one of these with a `when`, and each
 * holds what it needs to.
 */
public sealed interface GraphSnapshot<out State> {
    /** The state of the run as far as the session knows it, or `null` when it knows none. */
    public val state: State?

    /** Nothing has run in this session, and [GraphSession.restore] found no run of the thread. */
    public data object Idle : GraphSnapshot<Nothing> {
        override val state: Nothing? get() = null
    }

    /** [GraphSession.restore] is reading the last checkpoint of the thread. */
    public data object Restoring : GraphSnapshot<Nothing> {
        override val state: Nothing? get() = null
    }

    /**
     * The run is going on.
     *
     * @property state the state the nodes of the current step received, or the state after the last
     * step. It changes once per step.
     * @property nodes the nodes of the current step that have not finished yet, in the order they
     * started. Empty between two steps.
     */
    public data class Running<out State>(
        override val state: State,
        val nodes: List<String>,
    ) : GraphSnapshot<State>

    /**
     * The run paused, usually for a person to decide, and [GraphSession.resume] continues it.
     *
     * @property state the state the run paused with.
     * @property nextNodes the nodes that run when the run continues.
     */
    public data class Interrupted<out State>(
        override val state: State,
        val nextNodes: List<String>,
    ) : GraphSnapshot<State>

    /** The run reached [END] with [state]. */
    public data class Completed<out State>(
        override val state: State,
    ) : GraphSnapshot<State>

    /**
     * The run ended before [END] and before an interrupt. With a checkpointer,
     * [GraphSession.resume] continues from the last step that finished.
     *
     * @property state the last state the session knew, or `null` when it knew none.
     * @property cause what the run threw, for example a [NodeExecutionException] when a node failed
     * after its last try. `null` when [GraphSession.cancel] stopped it.
     */
    public data class Stopped<out State>(
        override val state: State?,
        val cause: Throwable?,
    ) : GraphSnapshot<State>
}

/** The snapshot after [event] of the run. */
private fun <State> GraphSnapshot<State>.after(event: GraphEvent<State>): GraphSnapshot<State> =
    when (event) {
        is GraphEvent.NodeStarted -> GraphSnapshot.Running(event.state, (this as? GraphSnapshot.Running)?.nodes.orEmpty() + event.node)
        is GraphEvent.NodeCompleted -> if (this is GraphSnapshot.Running) copy(nodes = nodes - event.node) else this
        is GraphEvent.StepCompleted -> GraphSnapshot.Running(event.state, emptyList())
        is GraphEvent.Interrupted -> GraphSnapshot.Interrupted(event.state, event.nextNodes)
        is GraphEvent.Completed -> GraphSnapshot.Completed(event.state)
        is GraphEvent.NodeProgress, is GraphEvent.NodeRetry, is GraphEvent.ItemCompleted, is GraphEvent.SubgraphEvent -> this
    }
