package dev.deeptelar.telar

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs [block], a side effect such as a payment or an email, once for the step of the node that
 * calls it, and returns its result. When the step runs again, the call returns the result of the
 * first time and [block] does not run.
 *
 * ```kotlin
 * val pay = node("pay") { order ->
 *     val receipt = effect("charge", ReceiptSerializer) { payments.charge(order.id, order.amount) }
 *     order.copy(receipt = receipt, status = "paid")
 * }
 * ```
 *
 * A step runs again after a failure: a node with a [Retry] is tried again, and [CompiledGraph.resume]
 * runs the whole step of a run that failed, including the nodes that had already finished. It also
 * runs again after [interrupt]. Each time, the effects that finished before are not repeated. With
 * `telar-serialization`, `effect(key) { ... }` finds the [serializer] of a `@Serializable` result
 * itself.
 *
 * What to know:
 * - **The result is saved as soon as [block] returns**, in the checkpoint the step continues from,
 *   so a crash or a failure of another node later in the step does not lose it. The checkpoint of
 *   the finished step no longer has it: the next time the node runs, in a later step, the effect
 *   runs again.
 * - **[key] names the effect within the node.** Two nodes may use the same key. One run of a node
 *   may not: a second call with a key the node already used throws a [GraphValidationException].
 *   An effect that runs once for each item of a list needs a key for each item, such as
 *   `"email-${customer.id}"`.
 * - **A [block] that throws saves nothing**, and runs again the next time.
 * - **What [block] did between its start and the save may still happen twice**, when the process
 *   stops in that moment. Where a service takes an idempotency key, pass it one as well.
 * - **Without a checkpointer** the results are kept in memory, for the tries of a [Retry]. A
 *   [Checkpointer] stores them with [Checkpoint.effects].
 * - **[CompiledGraph.fork] runs them again.** The results belong to the thread the checkpoint comes
 *   from, and a fork starts a thread of its own.
 *
 * Call it from the action of a node, or from the `work` of a node with a `work` and an `update`.
 * Outside a running node it runs [block] every time.
 *
 * @param key names the effect, unique within one run of the node.
 * @param serializer stores the result of [block] as a string and reads it back.
 * @throws GraphValidationException when the node already called `effect` with [key] in this run.
 * @throws CheckpointCorruptedException when [serializer] cannot read the result that was saved, for
 * example because its type changed.
 */
public suspend fun <T> effect(key: String, serializer: StateSerializer<T>, block: suspend () -> T): T {
    val node = currentCoroutineContext()[RunningNode] ?: return block()
    val log = node.effects
    val path = node.effectPath + "/" + escapeEffectName(key)
    val saved = log.claim(node, key, path)
    if (saved != null) {
        return try {
            serializer.deserialize(saved)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, as are most parsing failures.
            throw CheckpointCorruptedException(
                node.threadId,
                "the saved result of effect '$key' of node '${node.name}' does not match its type",
                e,
            )
        }
    }
    val result =
        try {
            block()
        } catch (e: Throwable) {
            log.release(node, path)
            throw e
        }
    log.record(path, serializer.serialize(result))
    return result
}

/**
 * The effects that finished in one step of a run, by their path: the node, and for a node in a
 * subgraph the nodes and steps around it, and the key. Every node of the step, also inside a
 * subgraph, records into the log of the step of the outermost graph.
 *
 * @param save stores what was recorded, in the checkpoint the step continues from.
 */
internal class EffectLog(
    recorded: Map<String, String> = emptyMap(),
    private val save: suspend (Map<String, String>) -> Unit = {},
) {
    private val mutex = Mutex()
    private var recorded = recorded
    private var closed = false

    /** Marks [path] as used in this run of [node], and returns its saved result, if there is one. */
    suspend fun claim(node: RunningNode, key: String, path: String): String? =
        mutex.withLock {
            if (!node.effectsUsed.add(path)) {
                throw GraphValidationException(
                    "Node '${node.name}' called effect() with the key '$key' twice. Give each effect of a node its own key.",
                )
            }
            recorded[path]
        }

    /** Frees [path] after its effect failed, so that the node may try it again. */
    suspend fun release(node: RunningNode, path: String) {
        withContext(NonCancellable) { mutex.withLock { node.effectsUsed.remove(path) } }
    }

    /**
     * Records [value] as the result of [path] and saves it. A node that is cancelled right after its
     * effect, because another node of the step failed, still saves it.
     */
    suspend fun record(path: String, value: String) {
        withContext(NonCancellable) {
            mutex.withLock {
                // A coroutine that outlived its step must not write into the checkpoint of a later one.
                if (closed) return@withLock
                recorded = recorded + (path to value)
                save(recorded)
            }
        }
    }

    /** Ends the step: records nothing more, and returns what was recorded. */
    suspend fun close(): Map<String, String> =
        mutex.withLock {
            closed = true
            recorded
        }
}

/** Keeps a `/` in a node name or a key from making two paths the same. */
internal fun escapeEffectName(name: String): String = name.replace("%", "%25").replace("/", "%2F")
