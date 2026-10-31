package dev.deeptelar.telar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A saved point in a graph run.
 *
 * @property state the graph state after the last executed step, or the input of the run when no
 * step has finished yet.
 * @property nextNodes the nodes that run when the thread is resumed. Empty when the run completed.
 * @property step the number of steps the thread has executed so far. `0` for the checkpoint that is
 * saved when a run starts.
 * @property interruptedBefore `true` when the run paused before [nextNodes] because of
 * [GraphConfig.interruptBefore], or because one of them called [interrupt] when it ran, or when a
 * subgraph among them failed after some of its steps.
 * [CompiledGraph.resume] then runs those nodes without pausing before them again. A checkpoint saved
 * for any other reason is `false`, so resuming it still pauses before a node listed in
 * [GraphConfig.interruptBefore].
 * @property subgraphs where the run stands inside the subgraphs among [nextNodes], by the name of
 * their node. It has an entry for a subgraph that the run paused in or that failed after some of
 * its steps, so that [CompiledGraph.resume] continues inside it. A [Checkpointer] has to store it
 * with the rest.
 * @property effects the results of the [effect]s that finished in the step of [nextNodes], so that
 * they do not run again when [CompiledGraph.resume] runs that step again. The keys name the node and
 * the effect; the values are the results as the serializer of each effect wrote them. Empty until
 * an effect of that step finishes. In a history, the checkpoint before a step that finished keeps
 * the effects of that step. A [Checkpointer] has to store it with the rest.
 */
public data class Checkpoint<State>(
    val state: State,
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
    val subgraphs: Map<String, SubgraphPosition> = emptyMap(),
    val effects: Map<String, String> = emptyMap(),
) {
    /** `true` when the run reached [END] and there is nothing left to resume. */
    public val isComplete: Boolean get() = nextNodes.isEmpty()
}

/**
 * Where a run stands inside a subgraph that was added with [StateGraph.subgraph]. It is a
 * [Checkpoint] without a state: the state of a subgraph is kept in the state of the graph around it.
 *
 * @property nextNodes the nodes of the subgraph that run when the thread is resumed.
 * @property step the number of steps the subgraph has executed in this visit.
 * @property interruptedBefore see [Checkpoint.interruptedBefore].
 * @property subgraphs the same for the subgraphs among [nextNodes], when subgraphs are nested.
 */
public data class SubgraphPosition(
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
    val subgraphs: Map<String, SubgraphPosition> = emptyMap(),
)

/**
 * Persists the checkpoints of each thread so a run can pause and resume later, even in a different
 * process.
 *
 * A checkpointer has to keep the latest checkpoint of a thread. One that also keeps the earlier
 * ones implements [history], and [CompiledGraph.history] and [CompiledGraph.fork] can then go back
 * to an earlier step. The checkpointers of this library do; [withCheckpoint] has the rule for a
 * checkpointer of your own.
 */
public interface Checkpointer<State> {
    /**
     * Stores [checkpoint] as the latest checkpoint of [threadId].
     *
     * A checkpointer that keeps a history adds it to the history of the thread, where it replaces
     * the checkpoints with its step or a later one: a thread has one checkpoint for each step.
     */
    public suspend fun save(threadId: String, checkpoint: Checkpoint<State>)

    /** Returns the latest checkpoint of [threadId], or `null` if the thread has none. */
    public suspend fun load(threadId: String): Checkpoint<State>?

    /**
     * Returns the checkpoints that [threadId] still has, oldest first, ending with the one that
     * [load] returns. Empty if the thread has none.
     *
     * The default is for a checkpointer that keeps only the latest checkpoint.
     */
    public suspend fun history(threadId: String): List<Checkpoint<State>> = listOfNotNull(load(threadId))

    /** Removes every checkpoint of [threadId]. Does nothing if the thread has none. */
    public suspend fun delete(threadId: String)
}

/**
 * Returns this history of a thread after [checkpoint] is saved, for a [Checkpointer] that keeps a
 * history: the checkpoints with an earlier step, then [checkpoint], and of those the last
 * [maxHistory].
 *
 * A checkpoint replaces the one with the same step, because both stand at the same place of the
 * run: a run that pauses before its next nodes, or in one of them, saves that place again.
 */
public fun <State> List<Checkpoint<State>>.withCheckpoint(
    checkpoint: Checkpoint<State>,
    maxHistory: Int = Int.MAX_VALUE,
): List<Checkpoint<State>> = (filter { it.step < checkpoint.step } + checkpoint).takeLast(maxHistory)

/**
 * A [Checkpointer] that keeps checkpoints in memory. Intended for tests and short-lived processes.
 * Safe to share between coroutines and threads.
 *
 * @param maxHistory how many checkpoints a thread keeps, counted from the latest. The default keeps
 * all of them; `1` keeps only the latest.
 * @throws GraphValidationException if [maxHistory] is not positive.
 */
public class MemoryCheckpointer<State>(
    private val maxHistory: Int = Int.MAX_VALUE,
) : Checkpointer<State> {
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, List<Checkpoint<State>>>()

    init {
        if (maxHistory <= 0) throw GraphValidationException("maxHistory must be positive, was $maxHistory.")
    }

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        mutex.withLock { memory[threadId] = memory[threadId].orEmpty().withCheckpoint(checkpoint, maxHistory) }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? = mutex.withLock { memory[threadId]?.lastOrNull() }

    override suspend fun history(threadId: String): List<Checkpoint<State>> = mutex.withLock { memory[threadId].orEmpty() }

    override suspend fun delete(threadId: String) {
        mutex.withLock { memory.remove(threadId) }
    }
}
