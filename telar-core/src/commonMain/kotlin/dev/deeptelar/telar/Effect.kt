package dev.deeptelar.telar

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
 *   stops in that moment. For an effect that must never happen twice, use the `effect` with a
 *   `reconcile`, which gives [block] an idempotency key and asks what happened.
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
    val saved = log.claim(node, key, path).result
    if (saved != null) return node.read(saved, serializer, key)
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
 * Runs [block], a side effect such as a payment, once for the step of the node that calls it, as
 * the `effect` without [reconcile] does, and also closes the moment in which that one can repeat
 * it: between the end of [block] and the save of its result.
 *
 * Before [block] runs, the effect saves that it started, with a new id. [block] gets the id: pass
 * it to the service as its idempotency key. When the step runs again and finds the effect started
 * but without a result, the process stopped or [block] failed while the service may have done the
 * work. Then [reconcile] is asked first, with the same id:
 * - it returns the result when the work was done, for example after it found the payment with
 *   that id at the provider. The result is saved, and [block] does not run.
 * - it returns `null` when the work was not done. [block] runs again, with the same id, so a
 *   service that takes idempotency keys still does the work only once.
 * - it throws, or calls [interrupt] to let a person check, when it cannot tell.
 *
 * ```kotlin
 * val pay = node("pay") { order ->
 *     val receipt = effect(
 *         "charge",
 *         ReceiptSerializer,
 *         reconcile = { id -> payments.findByIdempotencyKey(id) },
 *     ) { id -> payments.charge(order.id, order.amount, idempotencyKey = id) }
 *     order.copy(receipt = receipt)
 * }
 * ```
 *
 * For a service with idempotency keys, `reconcile = { null }` is enough: [block] runs again with
 * the same key, and the service answers with what it did the first time.
 *
 * What to know:
 * - **It saves twice**: once before [block] and once after. The first save is what makes the id
 *   the same when the step runs again.
 * - **The id belongs to this effect of this run of the step.** Another key, another step, another
 *   run of the thread or a [CompiledGraph.fork] gets a new one. Ids of tool calls and step numbers
 *   repeat across runs, so do not build a key from them.
 * - **A [block] that throws leaves the effect started**: a timeout can come after the service did
 *   the work. The next call with [key], in this run of the node or when the step runs again, asks
 *   [reconcile].
 * - [effectId] returns the id as well, to code that [block] or [reconcile] calls.
 * - Outside a running node, [block] runs every time, with a new id, and [reconcile] is never asked.
 *
 * Everything else is as for the `effect` without [reconcile].
 *
 * @param key names the effect, unique within one run of the node.
 * @param serializer stores the result of [block] as a string and reads it back.
 * @param reconcile tells, from the id, whether a [block] that started before did its work: its
 * result if it did, `null` if it did not.
 * @param block does the work, with the id as the idempotency key.
 * @throws GraphValidationException when the node already called `effect` with [key] in this run.
 * @throws CheckpointCorruptedException when [serializer] cannot read the result that was saved.
 */
public suspend fun <T> effect(
    key: String,
    serializer: StateSerializer<T>,
    reconcile: suspend (id: String) -> T?,
    block: suspend (id: String) -> T,
): T {
    val node = currentCoroutineContext()[RunningNode] ?: return newEffectId().let { id -> withContext(EffectId(id)) { block(id) } }
    val log = node.effects
    val path = node.effectPath + "/" + escapeEffectName(key)
    val marker = path + STARTED
    val claimed = log.claim(node, key, path, marker)
    if (claimed.result != null) return node.read(claimed.result, serializer, key)
    try {
        val started = claimed.started
        val id =
            if (started != null) {
                val found = withContext(EffectId(started)) { reconcile(started) }
                if (found != null) {
                    log.record(path, serializer.serialize(found), remove = marker)
                    return found
                }
                started
            } else {
                newEffectId().also { log.record(marker, it) }
            }
        val result = withContext(EffectId(id)) { block(id) }
        log.record(path, serializer.serialize(result), remove = marker)
        return result
    } catch (e: Throwable) {
        log.release(node, path)
        throw e
    }
}

/**
 * Returns the id of the `effect` with a `reconcile` whose block or `reconcile` is running, and
 * `null` elsewhere. It is the same each time the step of the effect runs again, so it is the
 * idempotency key to pass to a service from code that does not get the id itself, such as a tool
 * of `telar-agent`.
 */
public suspend fun effectId(): String? = currentCoroutineContext()[EffectId]?.id

/** Carries the id of the running `effect` to [effectId]. */
private class EffectId(
    val id: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<EffectId>
}

@OptIn(ExperimentalUuidApi::class)
private fun newEffectId(): String = Uuid.random().toString()

/**
 * Marks an effect that started. `%` is always escaped in a key, so no key ends with it.
 */
private const val STARTED = "%started"

private fun <T> RunningNode.read(saved: String, serializer: StateSerializer<T>, key: String): T =
    try {
        serializer.deserialize(saved)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException, as are most parsing failures.
        throw CheckpointCorruptedException(
            threadId,
            "the saved result of effect '$key' of node '$name' does not match its type",
            e,
        )
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

    /**
     * Marks [path] as used in this run of [node], and returns its saved result and the id under
     * [marker], if they are there.
     */
    suspend fun claim(node: RunningNode, key: String, path: String, marker: String? = null): Claimed =
        mutex.withLock {
            if (!node.effectsUsed.add(path)) {
                throw GraphValidationException(
                    "Node '${node.name}' called effect() with the key '$key' twice. Give each effect of a node its own key.",
                )
            }
            Claimed(recorded[path], marker?.let { recorded[it] })
        }

    /** What [claim] found: the saved result, and the id of an effect that started. */
    class Claimed(
        val result: String?,
        val started: String?,
    )

    /** Frees [path] after its effect failed, so that the node may try it again. */
    suspend fun release(node: RunningNode, path: String) {
        withContext(NonCancellable) { mutex.withLock { node.effectsUsed.remove(path) } }
    }

    /**
     * Records [value] as the result of [path], without the entry [remove], and saves it. A node that is cancelled right after its
     * effect, because another node of the step failed, still saves it.
     */
    suspend fun record(path: String, value: String, remove: String? = null) {
        withContext(NonCancellable) {
            mutex.withLock {
                // A coroutine that outlived its step must not write into the checkpoint of a later one.
                if (closed) return@withLock
                recorded = recorded + (path to value) - listOfNotNull(remove).toSet()
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
