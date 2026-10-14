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
