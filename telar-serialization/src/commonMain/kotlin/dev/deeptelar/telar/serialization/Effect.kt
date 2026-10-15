package dev.deeptelar.telar.serialization

import dev.deeptelar.telar.CompiledGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Runs [block], a side effect such as a payment or an email, once for the step of the node that
 * calls it, and returns its result, which a [Json] of [json] saves. [T] is a `@Serializable` class,
 * a basic type such as `String`, or `Unit` for an effect without a result.
 *
 * ```kotlin
 * val pay = node("pay") { order ->
 *     val receipt = effect("charge") { payments.charge(order.id, order.amount) }
 *     effect("email") { mailer.send(order.customer, "Paid: ${receipt.id}") }
 *     order.copy(receipt = receipt, status = "paid")
 * }
 * ```
 *
 * When the step runs again, after a failure or a pause, the call returns the result that was saved
 * and [block] does not run. See [dev.deeptelar.telar.effect], which this calls, for the details,
 * and [CompiledGraph] for when a step runs again.
 *
 * @param key names the effect, unique within one run of the node.
 */
public suspend inline fun <reified T> effect(key: String, json: Json = Json, noinline block: suspend () -> T): T =
    dev.deeptelar.telar.effect(key, KotlinxStateSerializer(serializer<T>(), json), block)

/**
 * Runs [block] once for the step of the node that calls it, with an id to pass to the service as
 * its idempotency key, and asks [reconcile] when the step runs again after [block] started but
 * before its result was saved. [T] is saved by a [Json] of [json], as for the `effect` without
 * [reconcile].
 *
 * ```kotlin
 * val pay = node("pay") { order ->
 *     val receipt = effect("charge", reconcile = { id -> payments.findByIdempotencyKey(id) }) { id ->
 *         payments.charge(order.id, order.amount, idempotencyKey = id)
 *     }
 *     order.copy(receipt = receipt)
 * }
 * ```
 *
 * See [dev.deeptelar.telar.effect] with a `reconcile`, which this calls, for the details.
 *
 * @param key names the effect, unique within one run of the node.
 * @param reconcile tells, from the id, whether a [block] that started before did its work: its
 * result if it did, `null` if it did not.
 * @param block does the work, with the id as the idempotency key.
 */
public suspend inline fun <reified T> effect(
    key: String,
    json: Json = Json,
    noinline reconcile: suspend (id: String) -> T?,
    noinline block: suspend (id: String) -> T,
): T = dev.deeptelar.telar.effect(key, KotlinxStateSerializer(serializer<T>(), json), reconcile, block)
