package dev.deeptelar.telar

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * A node that runs [work] for each item that [items] reads from the state, at most [concurrency] at
 * the same time, and writes the results into the state with [update], in the order of the items.
 * See the `node` of [StateGraph] with a `forEach`.
 *
 * Each item is tried on its own, with [retry] and [timeout], so the node itself has neither. With a
 * [serializer], the result of each item is recorded in the effect log of the step as soon as it is
 * there, and a step that runs again reads it instead of running the item.
 */
internal fun <State, Item, Result> forEachNode(
    name: String,
    items: suspend (State) -> List<Item>,
    work: suspend (State, Item) -> Result,
    serializer: StateSerializer<Result>?,
    concurrency: Int,
    retry: Retry?,
    timeout: Duration?,
    update: suspend (State, List<Result>) -> State,
): Node<State> =
    Node(name, returnsState = false, forEach = true) { state ->
        val node = checkNotNull(currentCoroutineContext()[RunningNode]) { "The engine runs every node with a RunningNode" }
        val list = wrapFailure({ NodeExecutionException(name, it) }) { items(state) }
        val limit = if (concurrency < list.size) Semaphore(concurrency) else null
        val each = ItemWork(node, state, work, serializer, retry, timeout, currentCoroutineContext()[ProgressReporter])
        val results =
            coroutineScope {
                list
                    .mapIndexed { index, item ->
                        async { if (limit == null) each.run(index, item) else limit.withPermit { each.run(index, item) } }
                    }.awaitAll()
            }
        // The engine wraps a failure of the update in a combined step itself, but not here: a node that runs for each item
        // names its own failures.
        NodeOutput(wrapFailure({ NodeExecutionException(name, it) }) { update(state, results) }) { update(it, results) }
    }

/**
 * Runs the work of the items of [node] on [state].
 *
 * @param reporter carries what an item reports, and the end of each item, to the collector of the
 * run. `null` when nobody collects it.
 */
private class ItemWork<State, Item, Result>(
    private val node: RunningNode,
    private val state: State,
    private val work: suspend (State, Item) -> Result,
    private val serializer: StateSerializer<Result>?,
    private val retry: Retry?,
    private val timeout: Duration?,
    private val reporter: ProgressReporter?,
) {
    /** Returns the result of [item], the one at [index]: the one saved before, or what its work returns. */
    suspend fun run(index: Int, item: Item): Result {
        // The `%` of a position cannot be in an escaped key, so no effect of the node has this path.
        val path = node.effectPath + "/%" + index
        val saved = serializer?.let { node.effects.recorded(path) }
        val result = if (saved != null) read(saved, index) else tries(index, item, path)
        if (saved == null) serializer?.let { node.effects.record(path, it.serialize(result)) }
        reporter?.report(ItemReport.Completed(index, result))
        return result
    }

    private suspend fun tries(index: Int, item: Item, path: String): Result {
        var attempt = 1
        while (true) {
            try {
                return once(index, item, path, attempt)
            } catch (failure: NodeExecutionException) {
                // Thrown by once and by nothing inside the work: a failure in there is its cause.
                val cause = checkNotNull(failure.cause) { "A NodeExecutionException always has a cause" }
                val pause = retry?.pauseAfter(attempt, cause) ?: throw failure
                node.observer.onNodeRetry(node.observed, attempt, cause, pause)
                reporter?.report(ItemReport.Failed(index, attempt, cause, pause))
                delay(pause)
                attempt++
            }
        }
    }

    /**
     * One try of [item], with a [RunningNode] of its own: the effects of its work are those of the
     * item, under [path], and what it reports says which item it comes from.
     */
    private suspend fun once(index: Int, item: Item, path: String, attempt: Int): Result {
        val running =
            RunningNode(
                node.name,
                node.threadId,
                node.canPause,
                node.maxIterations,
                position = null,
                node.effects,
                path,
                node.observed,
                node.observer,
            )
        val reports = reporter?.let { around -> ProgressReporter { value -> around.report(ItemReport.Progress(index, value)) } }
        return withContext(running + (reports ?: EmptyCoroutineContext)) {
            wrapFailure({ NodeExecutionException(node.name, it, attempt, index) }) {
                val limit = timeout
                if (limit == null) {
                    work(state, item)
                } else {
                    // Boxed, so that a work that returns null is not taken for the end of the limit.
                    (withTimeoutOrNull(limit) { Done(work(state, item)) } ?: throw NodeTimeoutException(limit)).value
                }
            }
        }
    }

    private fun read(saved: String, index: Int): Result =
        try {
            checkNotNull(serializer) { "Only a node with a serializer reads saved results" }.deserialize(saved)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, as are most parsing failures.
            throw CheckpointCorruptedException(
                node.threadId,
                "the saved result of item $index of node '${node.name}' does not match its type",
                e,
            )
        }

    private class Done<T>(
        val value: T,
    )
}

/** What the work of an item sends to the collector of the run, through the reporter of its node. */
internal sealed interface ItemReport {
    /** The position of the item in the list. */
    val item: Int

    /** The work of [item] reported [value]. */
    class Progress(
        override val item: Int,
        val value: Any,
    ) : ItemReport

    /** A try of [item] failed, and the next follows after [pause]. */
    class Failed(
        override val item: Int,
        val attempt: Int,
        val cause: Throwable,
        val pause: Duration,
    ) : ItemReport

    /** [item] has its [result]. */
    class Completed(
        override val item: Int,
        val result: Any?,
    ) : ItemReport
}
