package dev.deeptelar.telar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * An executable graph produced by [StateGraph.compile]. It is immutable and can be shared and run
 * concurrently; each run is isolated by [GraphConfig.threadId].
 *
 * ## Execution model
 *
 * A run advances in steps. In each step all currently active nodes run in parallel on the same
 * input state. When there is more than one, their results are combined: the [Reducer] merges the
 * nodes that returned a whole state, and the updates of the nodes that were added with a `work` and
 * an `update` are then applied one after another, in the order those nodes were added to the graph.
 * In a graph compiled with [MergeRules], given as `rules` or as `reducer`, each node instead gets
 * the state from before the step, its `update` too, and the rules merge all results in the order
 * the nodes were added.
 * The outgoing edges of the nodes then decide which nodes are active in the next step. A branch
 * that routes to [END] simply stops; the run completes when no nodes are active.
 *
 * A node reached by several branches in the same step runs once. If the branches have different
 * lengths, the node runs once for each step in which a branch reaches it.
 *
 * Nodes run in the coroutine context of the caller. If a node fails, the other nodes of that step
 * are cancelled and the failure is rethrown as [NodeExecutionException]. A node that was added with
 * a [Retry] is first tried again, inside the same step and on the same state. A failure of the
 * reducer or of the condition of an edge is rethrown as [ReducerException] or
 * [EdgeConditionException].
 *
 * ## Pauses
 *
 * A run pauses before or after the nodes that [GraphConfig.interruptBefore] and
 * [GraphConfig.interruptAfter] name, and wherever a node calls [interrupt]. It then ends with
 * [GraphResult.Interrupted], and [resume] continues it. A step in which a node calls [interrupt] is
 * not saved: the state the node passed is, and [resume] runs the nodes of that step again.
 *
 * ## Subgraphs
 *
 * A node added with [StateGraph.subgraph] runs another graph. That graph takes its own steps inside
 * one step of this graph. When one of its nodes calls [interrupt], this run pauses as well, and
 * [resume] continues inside the subgraph, at the node that paused. [GraphConfig.interruptBefore]
 * and [GraphConfig.interruptAfter] name a node of a subgraph by its path, such as `"research/ask"`.
 * A stream of this graph has the events of the subgraph too, each inside a [GraphEvent.SubgraphEvent].
 *
 * ## History
 *
 * A checkpointer keeps the checkpoint of every step of a thread. [history] reads them, and [fork]
 * continues from an earlier one on a new thread.
 *
 * ## Failures and retries
 *
 * With a checkpointer, a step is saved once its nodes have run, their results are merged and the
 * next nodes are chosen. A step that fails at any of these points is not saved, so [resume] runs the
 * whole step again, including the nodes of that step that had already finished. Run the side
 * effects of a node with [effect], which saves each one as soon as it finished, or make them safe to
 * repeat.
 *
 * The tries of a node with a [Retry] are not saved either. They do not count for
 * [GraphConfig.maxIterations], and a step that runs again after [resume] gives each of its nodes
 * all of its tries again.
 */
public class CompiledGraph<State> internal constructor(
    internal val nodes: Map<String, Node<State>>,
    internal val edges: Map<String, List<String>>,
    internal val conditionalEdges: Map<String, ConditionalEdge<State>>,
    internal val reducer: Reducer<State>? = null,
    internal val rules: MergeRules<State>? = null,
) {
    /** The position of each node in the order the nodes were added to the graph. */
    private val nodeOrder: Map<String, Int> = nodes.keys.withIndex().associate { it.value to it.index }

    /** The nodes and edges of this graph, for drawing or inspecting it. */
    public val topology: GraphTopology =
        GraphTopology(
            nodes = nodes.keys.toList(),
            edges =
                (listOf(START) + nodes.keys).flatMap { source ->
                    val conditional = conditionalEdges[source]
                    val targets = edges[source].orEmpty()
                    when {
                        conditional != null -> conditional.targets.orEmpty().map { GraphEdge(source, it, true, conditional.labels[it]) }
                        targets.isNotEmpty() -> targets.map { GraphEdge(source, it) }
                        // A node without an outgoing edge ends its branch.
                        else -> listOf(GraphEdge(source, END))
                    }
                },
            dynamicRoutes =
                conditionalEdges.values
                    .filter { it.targets == null }
                    .map { it.from }
                    .toSet(),
            subgraphs = nodes.values.mapNotNull { node -> node.subgraph?.let { node.name to it.topology } }.toMap(),
            forEachNodes = nodes.values.filter { it.forEach }.mapTo(mutableSetOf()) { it.name },
        )

    /**
     * Draws this graph as a Mermaid state diagram, to paste into a README or a pull request. See
     * [GraphTopology.toMermaid].
     *
     * @param config marks the nodes of its [GraphConfig.interruptBefore] and
     * [GraphConfig.interruptAfter] as the nodes where a run pauses. Defaults to no pauses. A
     * [GraphConfig] with pauses needs a checkpointer; to draw them without one, such as in a test,
     * pass the nodes to [GraphTopology.toMermaid] of [topology].
     * @param edgeLabels a label for an edge, by the names of its two ends, such as
     * `("read" to "refund") to "refund request"`, which takes the place of the `labels` given to
     * [StateGraph.conditionalEdge]. A conditional edge with neither is labeled `?`.
     * @throws GraphValidationException if [config] or [edgeLabels] name nodes or edges that are not
     * in the graph.
     */
    public fun toMermaid(config: GraphConfig<State> = GraphConfig(), edgeLabels: Map<Pair<String, String>, String> = emptyMap()): String =
        topology.toMermaid(config.interruptBefore, config.interruptAfter, edgeLabels)

    /**
     * Runs the graph from [START] with [input] until it completes or reaches an interrupt.
     *
     * A call always starts a new run: if [config] has a checkpointer, any earlier checkpoint of the
     * thread is replaced by one that holds [input], before the first node runs. Use [resume] to
     * continue a paused run, or to retry one that failed.
     *
     * @throws GraphValidationException if [config] names interrupt nodes that are not in the graph, or
     * if a node calls [interrupt] and [config] has no checkpointer.
     * @throws MaxIterationsExceededException if the run needs more than [GraphConfig.maxIterations] steps.
     * @throws NodeExecutionException if a node throws, or takes longer than its time limit, and has
     * no try left.
     * @throws ReducerException if the reducer throws.
     * @throws EdgeConditionException if the condition of a conditional edge throws.
     * @throws InvalidRouteException if a conditional edge returns an invalid target.
     */
    public suspend fun invoke(input: State, config: GraphConfig<State> = GraphConfig()): GraphResult<State> =
        events(input, config, progress = false).last().toResult()

    /**
     * Continues the run of [GraphConfig.threadId] from its last checkpoint, typically after an
     * interrupt and a human decision.
     *
     * @param update edits the checkpointed state before execution continues, for example to record
     * an approval. Defaults to continuing with the state unchanged. A state that [update] changed is
     * saved before anything runs, in place of the checkpoint it was made from, so that a step that
     * fails or is cancelled does not lose it: a `resume` without an update then continues with it.
     * @throws GraphValidationException if [config] has no checkpointer.
     * @throws CheckpointNotFoundException if the thread has no checkpoint.
     * @throws GraphAlreadyCompletedException if the thread's last run already completed.
     */
    public suspend fun resume(config: GraphConfig<State>, update: suspend (State) -> State = { it }): GraphResult<State> =
        resumeEvents(config, update, progress = false).last().toResult()

    /**
     * Returns where the run of [GraphConfig.threadId] stopped, read from its last checkpoint, without
     * running anything. Use it to restore a screen after a restart, or to build the next input of a
     * conversation from the state the last run ended with.
     *
     * The result is [GraphResult.Completed] when the run finished, and [GraphResult.Interrupted] when
     * [resume] can continue it: the run paused at an interrupt, or stopped before or between two
     * steps because a node failed or the process ended. It is `null` when the thread has no checkpoint.
     *
     * @throws GraphValidationException if [config] has no checkpointer.
     */
    public suspend fun lastResult(config: GraphConfig<State>): GraphResult<State>? {
        val checkpointer =
            config.checkpointer ?: throw GraphValidationException("lastResult() needs a GraphConfig with a checkpointer.")
        val checkpoint = checkpointer.load(config.threadId) ?: return null
        return if (checkpoint.isComplete) {
            GraphResult.Completed(checkpoint.state)
        } else {
            GraphResult.Interrupted(checkpoint.state, checkpoint.nextNodes)
        }
    }

    /**
     * Returns the checkpoints of the run of [GraphConfig.threadId], oldest first: the one that was
     * saved when the run started, and one for each step that finished since. The last one is where
     * the thread stands now. Empty when the thread has no checkpoint.
     *
     * ```kotlin
     * val history = graph.history(config)
     * history.forEach { println("after step ${it.step}: ${it.state}, next ${it.nextNodes}") }
     * ```
     *
     * A run that paused has the state it paused with in the checkpoint of its last step. [invoke]
     * starts the history of a thread again. How far back the history goes is up to the
     * [Checkpointer]: one that keeps only the latest checkpoint returns that one.
     *
     * @throws GraphValidationException if [config] has no checkpointer.
     */
    public suspend fun history(config: GraphConfig<State>): List<Checkpoint<State>> {
        val checkpointer =
            config.checkpointer ?: throw GraphValidationException("history() needs a GraphConfig with a checkpointer.")
        return checkpointer.history(config.threadId)
    }

    /**
     * Continues from [checkpoint] on the new thread [GraphConfig.threadId], and leaves the thread
     * that [checkpoint] comes from as it is. Use it to go back to an earlier step of a run and try
     * something else from there: another answer of a person, a corrected state, a changed node.
     *
     * ```kotlin
     * val beforeReview = graph.history(config).first { it.nextNodes == listOf("review") }
     * val retry = graph.fork(beforeReview, config.copy(threadId = "ticket-42-retry")) { it.copy(approved = false) }
     * ```
     *
     * The new thread starts with [checkpoint], with the state that [update] returns, and runs its
     * next nodes like [resume] does. It keeps the step numbers of the run it comes from. The
     * [effect]s of its first step run again, also those that [Checkpoint.effects] holds.
     *
     * @param checkpoint where to continue from, usually one of [history].
     * @param config the settings of the new run. Its thread must not have a checkpoint yet.
     * @param update edits the state of [checkpoint] before execution continues.
     * @throws GraphValidationException if [config] has no checkpointer, [checkpoint] is the end of
     * a run, or it names nodes that are not in this graph.
     * @throws ThreadAlreadyExistsException if the thread of [config] already has a checkpoint.
     */
    public suspend fun fork(
        checkpoint: Checkpoint<State>,
        config: GraphConfig<State>,
        update: suspend (State) -> State = { it },
    ): GraphResult<State> = forkEvents(checkpoint, config, update, progress = false).last().toResult()

    /** Like [fork], but returns a cold [Flow] of [GraphEvent]s. See [stream]. */
    public fun streamFork(
        checkpoint: Checkpoint<State>,
        config: GraphConfig<State>,
        update: suspend (State) -> State = { it },
    ): Flow<GraphEvent<State>> = forkEvents(checkpoint, config, update, progress = true)

    private fun forkEvents(
        checkpoint: Checkpoint<State>,
        config: GraphConfig<State>,
        update: suspend (State) -> State,
        progress: Boolean,
    ): Flow<GraphEvent<State>> =
        observed(config) { watch ->
            validateInterrupts(config)
            val checkpointer =
                config.checkpointer ?: throw GraphValidationException("fork() needs a GraphConfig with a checkpointer.")
            if (checkpoint.isComplete) {
                throw GraphValidationException("fork() needs a checkpoint with nodes to run next. This one is the end of a run.")
            }
            validatePosition(config.threadId, checkpoint.nextNodes, checkpoint.subgraphs)
            if (checkpointer.load(config.threadId) != null) throw ThreadAlreadyExistsException(config.threadId)
            // Save the start of the new thread before anything runs, so that a failure in its first step can be resumed.
            // The effects of the checkpoint belong to the thread it comes from: the new one runs its own.
            val start = checkpoint.copy(state = update(checkpoint.state), effects = emptyMap())
            checkpointer.save(config.threadId, start)
            watch.observer.onCheckpointSaved(watch.run, start.step, start.nextNodes)
            run(
                config,
                RunStart(start.state, start.nextNodes, start.step, start.interruptedBefore, start.subgraphs, saved = start),
                progress,
                watch,
            )
        }

    /**
     * Like [invoke], but returns a cold [Flow] that emits a [GraphEvent] as each node starts and
     * finishes and after every step, and ends with [GraphEvent.Completed] or
     * [GraphEvent.Interrupted]. What a node passes to [reportProgress] while it runs arrives as a
     * [GraphEvent.NodeProgress], what happens inside a subgraph as a [GraphEvent.SubgraphEvent], and
     * a try of a node that failed and is followed by another as a [GraphEvent.NodeRetry].
     * Nothing runs until the flow is collected, and each collection is a new run.
     */
    public fun stream(input: State, config: GraphConfig<State> = GraphConfig()): Flow<GraphEvent<State>> =
        events(input, config, progress = true)

    /** The run of [invoke] and [stream]. Only a stream has a collector for the [progress] of its nodes. */
    private fun events(input: State, config: GraphConfig<State>, progress: Boolean): Flow<GraphEvent<State>> =
        observed(config) { watch ->
            validateInterrupts(config)
            // Drop the previous run now, so that a failure before the first save cannot be resumed into it.
            config.checkpointer?.delete(config.threadId)
            val firstNodes = resolveNextNodes(listOf(START), input)
            // Save the input before anything runs, so that a failure in the first step can be resumed.
            val saved = Checkpoint(input, firstNodes)
            config.checkpointer?.let { checkpointer ->
                checkpointer.save(config.threadId, saved)
                watch.observer.onCheckpointSaved(watch.run, 0, firstNodes)
            }
            run(config, RunStart(input, firstNodes, step = 0, skipInterruptBefore = false, saved = saved), progress, watch)
        }

    /**
     * A run that the observers of [config] are told about, from its start to its end. [block] runs
     * the graph and tells [RunWatch] how the run ended; a failure is told here.
     */
    private fun observed(
        config: GraphConfig<State>,
        block: suspend FlowCollector<GraphEvent<State>>.(RunWatch) -> Unit,
    ): Flow<GraphEvent<State>> =
        flow {
            val watch = RunWatch(config.observer(), ObservedRun(config.threadId, parent = null, currentCoroutineContext()))
            watch.started()
            try {
                block(watch)
            } catch (failure: Throwable) {
                watch.failed(failure)
                throw failure
            }
        }

    /** Like [resume], but returns a cold [Flow] of [GraphEvent]s. See [stream]. */
    public fun streamResume(config: GraphConfig<State>, update: suspend (State) -> State = { it }): Flow<GraphEvent<State>> =
        resumeEvents(config, update, progress = true)

    private fun resumeEvents(config: GraphConfig<State>, update: suspend (State) -> State, progress: Boolean): Flow<GraphEvent<State>> =
        observed(config) { watch ->
            validateInterrupts(config)
            val checkpointer =
                config.checkpointer ?: throw GraphValidationException("resume() needs a GraphConfig with a checkpointer.")
            val checkpoint = checkpointer.load(config.threadId) ?: throw CheckpointNotFoundException(config.threadId)
            if (checkpoint.isComplete) throw GraphAlreadyCompletedException(config.threadId)
            validatePosition(config.threadId, checkpoint.nextNodes, checkpoint.subgraphs)
            // Save an edited state before anything runs, as fork does: the answer of a person is not lost
            // when the first step fails or is cancelled. It keeps the step's effects and its pause.
            val updated = update(checkpoint.state)
            val saved =
                if (updated == checkpoint.state) {
                    checkpoint
                } else {
                    checkpoint.copy(state = updated).also {
                        checkpointer.save(config.threadId, it)
                        watch.observer.onCheckpointSaved(watch.run, it.step, it.nextNodes)
                    }
                }
            // Only a run that paused before its next nodes continues past that pause. After any other
            // checkpoint (an interruptAfter pause, or a crash between steps) the pause is still due.
            val start =
                RunStart(
                    saved.state,
                    saved.nextNodes,
                    saved.step,
                    saved.interruptedBefore,
                    saved.subgraphs,
                    saved = saved,
                )
            run(config, start, progress, watch)
        }

    /**
     * Runs this graph as the subgraph of the node [parent], from [START] or from where the run
     * paused inside it. Nothing is saved here: a pause is returned, and the graph around saves it.
     */
    internal suspend fun runAsSubgraph(state: State, parent: RunningNode): RunEnd<State> {
        val config = GraphConfig<State>(threadId = parent.threadId, maxIterations = parent.maxIterations)
        // The observers of the run around are told about this run too, as a run inside the node.
        val watch = RunWatch(parent.observer, ObservedRun(parent.threadId, parent.observed, currentCoroutineContext()))
        watch.started()
        try {
            val position = parent.position
            val start =
                if (position == null) {
                    RunStart(state, resolveNextNodes(listOf(START), state), step = 0, skipInterruptBefore = false)
                } else {
                    RunStart(state, position.nextNodes, position.step, position.interruptedBefore, position.subgraphs)
                }
            // The node of this subgraph has a reporter when the run around it is collected. The events of
            // this graph go to that collector through it, like what a node reports.
            val reporter = currentCoroutineContext()[ProgressReporter]
            val events = FlowCollector<GraphEvent<State>> { event -> reporter?.report(SubgraphReport(event)) }
            return events.run(
                config,
                start,
                progress = reporter != null,
                watch,
                canPause = parent.canPause,
                parent = parent,
                // A subgraph has no checkpointer, so its config cannot hold the pauses: they come with the node.
                interruptBefore = parent.interruptBefore,
                interruptAfter = parent.interruptAfter,
            )
        } catch (failure: Throwable) {
            watch.failed(failure)
            throw failure
        }
    }

    /**
     * Where a run starts.
     *
     * @property subgraphs where the run stands inside the subgraphs among [activeNodes], for a run
     * that paused in one of them.
     * @property saved the checkpoint the run continues from, as it is stored. Its effects are those
     * that the first step already ran. `null` for a subgraph, which saves nothing.
     */
    private class RunStart<State>(
        val state: State,
        val activeNodes: List<String>,
        val step: Int,
        val skipInterruptBefore: Boolean,
        val subgraphs: Map<String, SubgraphPosition> = emptyMap(),
        val saved: Checkpoint<State>? = null,
    )

    /**
     * Runs the graph from [start] and emits its events, and tells [watch] how the run ends unless it fails.
     *
     * @param canPause `true` when the run is saved, so that a node may pause it. A subgraph has no
     * checkpointer of its own and can pause when the graph around it can.
     * @param parent the node that runs this graph as its subgraph. The effects of this graph go
     * into the step of the graph around it.
     * @param interruptBefore the nodes to pause before: their names, and the paths of nodes of subgraphs.
     * @param interruptAfter the nodes to pause after, named the same way.
     */
    private suspend fun FlowCollector<GraphEvent<State>>.run(
        config: GraphConfig<State>,
        start: RunStart<State>,
        progress: Boolean,
        watch: RunWatch,
        canPause: Boolean = config.checkpointer != null,
        parent: RunningNode? = null,
        interruptBefore: Set<String> = config.interruptBefore,
        interruptAfter: Set<String> = config.interruptAfter,
    ): RunEnd<State> {
        var state = start.state
        var activeNodes = start.activeNodes
        var step = start.step
        var skipInterruptBefore = start.skipInterruptBefore
        var subgraphs = start.subgraphs
        var effects = start.saved?.effects.orEmpty()
        var saved = start.saved
        var executedSteps = 0

        suspend fun save(checkpoint: Checkpoint<State>): Checkpoint<State> =
            checkpoint.also {
                config.checkpointer?.let { checkpointer ->
                    checkpointer.save(config.threadId, it)
                    watch.observer.onCheckpointSaved(watch.run, it.step, it.nextNodes)
                }
                saved = it
            }

        // An effect is saved into the checkpoint its step continues from, the one saved last.
        suspend fun saveEffects(recorded: Map<String, String>) {
            saved?.let { config.checkpointer?.save(config.threadId, it.copy(effects = recorded)) }
        }

        while (activeNodes.isNotEmpty()) {
            if (!skipInterruptBefore && activeNodes.any { it in interruptBefore }) {
                val paused = save(Checkpoint(state, activeNodes, step, interruptedBefore = true, effects = effects))
                watch.interrupted(activeNodes)
                emit(GraphEvent.Interrupted(state, activeNodes))
                return RunEnd.Paused(paused)
            }
            skipInterruptBefore = false

            if (executedSteps >= config.maxIterations) throw MaxIterationsExceededException(config.maxIterations)
            executedSteps++
            step++

            val log = parent?.effects ?: EffectLog(effects) { saveEffects(it) }
            val effectPrefix = parent?.let { "${it.effectPath}/$step/" }.orEmpty()
            val run =
                StepRun(
                    step,
                    config.threadId,
                    canPause,
                    config.maxIterations,
                    subgraphs,
                    progress,
                    log,
                    effectPrefix,
                    watch,
                    interruptBefore,
                    interruptAfter,
                )
            state =
                try {
                    runStep(run, activeNodes, state)
                } catch (pause: NodeInterrupt) {
                    if (!canPause) {
                        throw GraphValidationException(
                            "Node '${pause.node}' called interrupt(), which needs a GraphConfig with a checkpointer to save the paused run.",
                        )
                    }

                    @Suppress("UNCHECKED_CAST")
                    val asked = pause.state as State
                    // The step did not finish, so it keeps its number, and resume runs its nodes again
                    // without pausing before them a second time. A subgraph continues where it paused.
                    val inside = pause.position?.let { mapOf(pause.node to it) }.orEmpty()
                    // The effects of a subgraph stay in the log of the graph around it, which saves them there.
                    val recorded = if (parent == null) log.close() else emptyMap()
                    val paused =
                        save(Checkpoint(asked, activeNodes, step - 1, interruptedBefore = true, subgraphs = inside, effects = recorded))
                    watch.interrupted(activeNodes)
                    emit(GraphEvent.Interrupted(asked, activeNodes))
                    return RunEnd.Paused(paused)
                }
            if (parent == null) log.close()
            // Only the first step of a resumed run continues inside a subgraph, and has effects that ran before.
            subgraphs = emptyMap()
            effects = emptyMap()
            val nextNodes = resolveNextNodes(activeNodes, state)
            watch.observer.onStepCompleted(watch.run, step, activeNodes, nextNodes)
            val saved = save(Checkpoint(state, nextNodes, step))
            emit(GraphEvent.StepCompleted(step, activeNodes, state))

            // A finished run has nothing to pause for, but a subgraph that finished has the graph around it.
            if ((nextNodes.isNotEmpty() || parent != null) && activeNodes.any { it in interruptAfter }) {
                watch.interrupted(nextNodes)
                emit(GraphEvent.Interrupted(state, nextNodes))
                return RunEnd.Paused(saved)
            }
            activeNodes = nextNodes
        }

        watch.completed()
        emit(GraphEvent.Completed(state))
        return RunEnd.Completed(state)
    }

    /**
     * What the nodes of one step need to know about their run.
     *
     * @property effects where the nodes record their effects.
     * @property effectPrefix what the path of a node in [effects] starts with: empty in the outermost
     * graph, and the node and step of the graph around it in a subgraph.
     * @property watch tells the observer of the run about the nodes.
     * @property interruptBefore the pauses of the run, from which a subgraph node takes those inside it.
     */
    private class StepRun(
        val step: Int,
        val threadId: String,
        val canPause: Boolean,
        val maxIterations: Int,
        val subgraphs: Map<String, SubgraphPosition>,
        val progress: Boolean,
        val effects: EffectLog,
        val effectPrefix: String,
        val watch: RunWatch,
        val interruptBefore: Set<String>,
        val interruptAfter: Set<String>,
    )

    private suspend fun FlowCollector<GraphEvent<State>>.runStep(run: StepRun, activeNodes: List<String>, state: State): State {
        val step = run.step
        val progress = run.progress
        val observed = activeNodes.associateWith { ObservedNode(run.watch.run, step, it) }
        activeNodes.forEach { emit(GraphEvent.NodeStarted(step, it, state)) }
        if (activeNodes.size == 1 && !progress) {
            val name = activeNodes.single()
            val output =
                runNode(run, nodes.getValue(name), observed.getValue(name), state) { attempt, cause, pause ->
                    emit(GraphEvent.NodeRetry(step, name, attempt, cause, pause, state))
                }
            return output.state.also { emit(GraphEvent.NodeCompleted(step, name, it)) }
        }

        // A flow may only emit from the coroutine that collects it, so the nodes hand what they
        // report and their results over a channel, and this coroutine emits them as they arrive.
        // A node waits until its report is emitted, so it cannot run ahead of the collector, and a
        // node that fails right after a report does not take the report with it.
        val signals = Channel<NodeSignal<State>>(Channel.BUFFERED)
        val outputs =
            try {
                coroutineScope {
                    val results =
                        activeNodes.map { name ->
                            async(if (progress) reporter(name, signals) else EmptyCoroutineContext) {
                                val output =
                                    runNode(run, nodes.getValue(name), observed.getValue(name), state) { attempt, cause, pause ->
                                        signals.send(NodeSignal.Retry(name, attempt, cause, pause))
                                    }
                                output.also { signals.send(NodeSignal.Finished(name, it.state)) }
                            }
                        }
                    var running = activeNodes.size
                    while (running > 0) {
                        when (val signal = signals.receive()) {
                            is NodeSignal.Progress -> {
                                emit(reported(run, observed.getValue(signal.node), signal.value, state))
                                signal.emitted.complete(Unit)
                            }
                            is NodeSignal.Retry ->
                                emit(GraphEvent.NodeRetry(step, signal.node, signal.attempt, signal.cause, signal.pause, state))
                            is NodeSignal.Finished -> {
                                emit(GraphEvent.NodeCompleted(step, signal.node, signal.state))
                                running--
                            }
                        }
                    }
                    results.awaitAll()
                }
            } finally {
                // A coroutine that outlives its node must not wait for a reader that is gone.
                signals.close()
                while (true) {
                    val unread = signals.tryReceive().getOrNull() ?: break
                    (unread as? NodeSignal.Progress)?.emitted?.complete(Unit)
                }
            }
        return combine(state, activeNodes.zip(outputs))
    }

    /**
     * The event of what [node] reported: [value] of [reportProgress], or what the engine sends the
     * same way, the event of a subgraph or the news of an item.
     */
    private fun reported(run: StepRun, node: ObservedNode, value: Any, state: State): GraphEvent<State> {
        val step = run.step
        val name = node.name
        return when (value) {
            is SubgraphReport -> GraphEvent.SubgraphEvent(step, name, value.event, state)
            is ItemReport.Progress -> {
                run.watch.observer.onNodeProgress(node, value.value)
                GraphEvent.NodeProgress(step, name, value.value, state, value.item)
            }
            is ItemReport.Failed -> GraphEvent.NodeRetry(step, name, value.attempt, value.cause, value.pause, state, value.item)
            is ItemReport.Completed -> GraphEvent.ItemCompleted(step, name, value.item, value.result, state)
            else -> {
                run.watch.observer.onNodeProgress(node, value)
                GraphEvent.NodeProgress(step, name, value, state)
            }
        }
    }

    /** Sends what the node named [name] reports to [signals], and makes the node wait until it is emitted. */
    private fun reporter(name: String, signals: SendChannel<NodeSignal<State>>): ProgressReporter =
        ProgressReporter { value ->
            val emitted = CompletableDeferred<Unit>()
            signals.send(NodeSignal.Progress(name, value, emitted))
            emitted.await()
        }

    /** What a running node sends to the coroutine that emits the events of its step. */
    private sealed interface NodeSignal<out State> {
        class Progress(
            val node: String,
            val value: Any,
            val emitted: CompletableDeferred<Unit>,
        ) : NodeSignal<Nothing>

        class Retry(
            val node: String,
            val attempt: Int,
            val cause: Throwable,
            val pause: Duration,
        ) : NodeSignal<Nothing>

        class Finished<State>(
            val node: String,
            val state: State,
        ) : NodeSignal<State>
    }

    /**
     * Combines what the nodes of a parallel step produced, each given with the name of its node.
     *
     * The nodes that returned a whole state decide the state to start from: [state] when there is
     * none, the state of the only one, or what the reducer makes of several. The updates of the
     * other nodes are then applied to it one after another, in the order those nodes were added to
     * the graph. In a graph with [rules], they merge the results of all nodes instead.
     * [StateGraph.compile] passes a [MergeRules] reducer as [rules].
     */
    private suspend fun combine(state: State, outputs: List<Pair<String, NodeOutput<State>>>): State {
        if (rules != null) return merge(rules, state, outputs.sortedBy { nodeOrder.getValue(it.first) })
        val states = outputs.filter { (_, output) -> output.update == null }
        var combined =
            when (states.size) {
                0 -> state
                1 -> states.single().second.state
                else -> {
                    val reducer =
                        checkNotNull(reducer) { "compile() guarantees a reducer when two nodes that return a state can run together" }
                    val names = states.map { it.first }
                    wrapFailure({ cause -> ReducerException(names, cause) }) { reducer.reduce(state, states.map { it.second.state }) }
                }
            }
        for ((name, output) in outputs.sortedBy { nodeOrder.getValue(it.first) }) {
            val update = output.update ?: continue
            combined = wrapFailure({ cause -> NodeExecutionException(name, cause) }) { update(combined) }
        }
        return combined
    }

    /**
     * Merges what [outputs] produced by [rules], each output being the state after its node alone,
     * so that an error names the node.
     */
    private suspend fun merge(rules: MergeRules<State>, state: State, outputs: List<Pair<String, NodeOutput<State>>>): State {
        if (outputs.size <= 1) return outputs.singleOrNull()?.second?.state ?: state
        val names = outputs.map { it.first }
        return wrapFailure({ cause -> ReducerException(names, cause) }) {
            rules.merge(state, outputs.map { it.second.state }) { "Node '${names[it]}'" }
        }
    }

    /**
     * Runs [node] on [state], and again after a failure for as long as its [Retry] allows, and tells
     * the observer of the run about it as [observed].
     *
     * @param retrying is told about each try that failed and is followed by another, before the
     * pause between the two. It is called outside the context of the node, so that the collector of
     * the run may emit from it.
     */
    private suspend inline fun runNode(
        run: StepRun,
        node: Node<State>,
        observed: ObservedNode,
        state: State,
        retrying: (attempt: Int, cause: Throwable, pause: Duration) -> Unit,
    ): NodeOutput<State> {
        val observer = run.watch.observer
        observer.onNodeStarted(observed)
        val output =
            try {
                runTries(run, node, observed, state, retrying)
            } catch (pause: NodeInterrupt) {
                observer.onNodeInterrupted(observed)
                throw pause
            } catch (failure: Throwable) {
                observer.onNodeFailed(observed, failure)
                throw failure
            }
        observer.onNodeCompleted(observed)
        return output
    }

    /** The tries of [runNode]. */
    private suspend inline fun runTries(
        run: StepRun,
        node: Node<State>,
        observed: ObservedNode,
        state: State,
        retrying: (attempt: Int, cause: Throwable, pause: Duration) -> Unit,
    ): NodeOutput<State> {
        val observer = run.watch.observer
        val context = observer.nodeContext(observed)
        var attempt = 1
        while (true) {
            try {
                return runOnce(run, node, observed, context, state, attempt)
            } catch (failure: NodeExecutionException) {
                // Thrown by runOnce and by nothing inside the node: a failure in there is its cause.
                val cause = checkNotNull(failure.cause) { "A NodeExecutionException always has a cause" }
                val pause = node.retry?.pauseAfter(attempt, cause) ?: throw failure
                observer.onNodeRetry(observed, attempt, cause, pause)
                retrying(attempt, cause, pause)
                delay(pause)
                attempt++
            }
        }
    }

    /** One try of [node], the one with the number [attempt], in [context] and the context of a running node. */
    private suspend fun runOnce(
        run: StepRun,
        node: Node<State>,
        observed: ObservedNode,
        context: CoroutineContext,
        state: State,
        attempt: Int,
    ): NodeOutput<State> =
        withContext(
            context +
                RunningNode(
                    node.name,
                    run.threadId,
                    run.canPause,
                    run.maxIterations,
                    run.subgraphs[node.name],
                    run.effects,
                    run.effectPrefix + escapeEffectName(node.name),
                    observed,
                    run.watch.observer,
                    run.interruptBefore.inside(node.name),
                    run.interruptAfter.inside(node.name),
                ),
        ) {
            if (node.forEach) {
                // It tries each item on its own, and its failures name the item.
                node.run(state)
            } else {
                wrapFailure({ NodeExecutionException(node.name, it, attempt) }) {
                    val timeout = node.timeout
                    if (timeout == null) {
                        node.run(state)
                    } else {
                        // Null only when this limit ran out: one that the node set for itself throws.
                        withTimeoutOrNull(timeout) { node.run(state) } ?: throw NodeTimeoutException(timeout)
                    }
                }
            }
        }

    /** Returns the nodes to run after [currentNodes], without [END]. */
    private suspend fun resolveNextNodes(currentNodes: List<String>, state: State): List<String> {
        val nextNodes = mutableListOf<String>()
        for (node in currentNodes) {
            val conditionalEdge = conditionalEdges[node]
            if (conditionalEdge != null) {
                nextNodes.addAll(route(conditionalEdge, state))
            } else {
                nextNodes.addAll(edges[node].orEmpty())
            }
        }
        return nextNodes.distinct() - END
    }

    private suspend fun route(edge: ConditionalEdge<State>, state: State): Set<String> {
        val targets = wrapFailure({ EdgeConditionException(edge.from, it) }) { edge.condition(state) }
        targets
            .firstOrNull { target -> !(edge.targets?.contains(target) ?: (target == END || target in nodes)) }
            ?.let { throw InvalidRouteException(edge.from, it) }
        return targets
    }

    /** Checks that a checkpoint, or the position inside a subgraph, only names what this graph has. */
    private fun validatePosition(threadId: String, nextNodes: List<String>, subgraphs: Map<String, SubgraphPosition>) {
        (nextNodes - nodes.keys).firstOrNull()?.let {
            throw GraphValidationException("Checkpoint of thread '$threadId' refers to node '$it', which is not in this graph.")
        }
        for ((name, position) in subgraphs) {
            val subgraph =
                nodes[name]?.subgraph?.takeIf { name in nextNodes }
                    ?: throw GraphValidationException(
                        "Checkpoint of thread '$threadId' refers to a subgraph '$name', which is not one of the nodes it continues with.",
                    )
            subgraph.validatePosition(threadId, position.nextNodes, position.subgraphs)
        }
    }

    private fun validateInterrupts(config: GraphConfig<State>) {
        val unknown = topology.unknownNodes(config.interruptBefore + config.interruptAfter)
        if (unknown.isNotEmpty()) {
            throw GraphValidationException("GraphConfig interrupts refer to nodes that are not in the graph: $unknown")
        }
    }

    private fun GraphEvent<State>.toResult(): GraphResult<State> =
        when (this) {
            is GraphEvent.Completed -> GraphResult.Completed(state)
            is GraphEvent.Interrupted -> GraphResult.Interrupted(state, nextNodes)
            is GraphEvent.NodeStarted,
            is GraphEvent.NodeProgress,
            is GraphEvent.SubgraphEvent,
            is GraphEvent.NodeRetry,
            is GraphEvent.ItemCompleted,
            is GraphEvent.NodeCompleted,
            is GraphEvent.StepCompleted,
            -> error("A graph stream always ends with Completed or Interrupted")
        }
}

/** How a run ended. */
internal sealed interface RunEnd<out State> {
    class Completed<State>(
        val state: State,
    ) : RunEnd<State>

    /** The run paused. [checkpoint] is what it continues from, whether a checkpointer saved it or not. */
    class Paused<State>(
        val checkpoint: Checkpoint<State>,
    ) : RunEnd<State>
}

/** An event of a subgraph on its way to the collector of the run around it, sent like the progress of a node. */
internal class SubgraphReport(
    val event: GraphEvent<*>,
)

/**
 * Runs [block], which calls code of the application (a node, the condition of an edge or the
 * reducer), and rethrows what it throws as the exception that [failure] builds. That includes a
 * [TelarException], such as the failure of a graph that a node runs, so the caller always
 * learns which part of the graph failed.
 *
 * Not everything a browser throws is an `Exception`, so every `Throwable` is looked at. What is
 * no failure of the code passes as it is: see [isFailure].
 */
internal suspend inline fun <T> wrapFailure(failure: (Throwable) -> TelarException, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        // Propagate a real cancellation of the run. If the run is still active, the code cancelled
        // only itself (for example its own withTimeout expired), which is a failure of that code.
        currentCoroutineContext().ensureActive()
        throw failure(e)
    } catch (e: Throwable) {
        throw if (e.isFailure()) failure(e) else e
    }
