package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.effectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * What a model is told about a tool.
 *
 * @property name how the model refers to the tool: 1 to 64 letters, digits, `_` or `-`.
 * @property description tells the model what the tool does and when to use it. The model chooses
 * its tools from this text, so say what the tool returns and when it applies.
 * @property inputSchema a JSON Schema object that describes the tool's input.
 * @throws GraphValidationException if [name] has other characters or the wrong length.
 */
public data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
) {
    init {
        if (!toolName.matches(name)) {
            throw GraphValidationException("Tool name '$name' must be 1 to 64 letters, digits, '_' or '-'.")
        }
    }

    private companion object {
        val toolName = Regex("[a-zA-Z0-9_-]{1,64}")
    }
}

/**
 * A function a model may call. The usual way to create one is from a `@Serializable` class that
 * holds the input, which also gives the model the input's schema:
 *
 * ```kotlin
 * @Serializable
 * data class Forecast(
 *     @Description("The city, for example \"Madrid\"") val city: String,
 *     val days: Int = 1,
 * )
 *
 * val forecast = Tool<Forecast>("forecast", "Returns the weather forecast of a city.") { input ->
 *     weatherService.forecast(input.city, input.days)
 * }
 * ```
 *
 * A tool that charges, books or sends something can also say how to find out whether a call that
 * started did its work, with a `reconcile`. See the overload that takes one.
 *
 * Use these constructors when you write the schema by hand.
 *
 * @property spec what the model is told about the tool.
 * @property reconcile tells whether a call whose step stopped after the tool started did its work:
 * the text the model gets back if it did, `null` if it did not. `null` for a tool without one.
 * @property execute runs the tool with the input the model sent and returns the text the model gets
 * back. An exception it throws reaches the model as an error result; see [List.execute].
 */
public class Tool(
    public val spec: ToolSpec,
    public val reconcile: (suspend (JsonObject) -> String?)?,
    public val execute: suspend (JsonObject) -> String,
) {
    /** Creates a tool without a [reconcile]. */
    public constructor(spec: ToolSpec, execute: suspend (JsonObject) -> String) : this(spec, null, execute)
}

/**
 * Creates a [Tool] whose input is the `@Serializable` type [Input]. The input schema is built from
 * the type, with the text of [Description] annotations as the description of each property.
 *
 * A property is required unless it has a default value or is nullable. Supported property types are
 * strings, numbers, booleans, enums, lists, maps with string keys, value classes and other
 * `@Serializable` classes. Use `Tool<Unit>` for a tool that takes no input.
 *
 * @param name how the model refers to the tool: 1 to 64 letters, digits, `_` or `-`.
 * @param description tells the model what the tool does and when to use it.
 * @param execute runs the tool and returns the text the model gets back.
 * @throws GraphValidationException if [name] is not valid, or [Input] is not a class or has a
 * property of a type that cannot be described.
 */
public inline fun <reified Input> Tool(name: String, description: String, noinline execute: suspend (Input) -> String): Tool =
    Tool(name, description, serializer<Input>(), execute)

/** Creates a [Tool] whose input is read with [serializer]. See the overload with a reified type. */
public fun <Input> Tool(name: String, description: String, serializer: KSerializer<Input>, execute: suspend (Input) -> String): Tool =
    Tool(ToolSpec(name, description, inputSchema(serializer.descriptor))) { input ->
        execute(modelJson.decodeFromJsonElement(serializer, input))
    }

/**
 * Creates a [Tool] whose input is the `@Serializable` type [Input], for a tool that does something
 * that must not happen twice, such as a payment.
 *
 * [toolLoop] saves that a call of this tool started before it runs, with an id that [effectId]
 * returns while the tool runs. Pass that id to the service as its idempotency key. When the step
 * runs again and finds the call started but without a result, because the process stopped or
 * another tool of the step failed, [reconcile] is asked first, with the same id from [effectId]:
 * it returns the text for the model when the work was done, and `null` when it was not, and the
 * tool runs again with the same id.
 *
 * ```kotlin
 * val charge = Tool<Charge>(
 *     "charge_card",
 *     "Charges the card of the customer.",
 *     reconcile = { input -> payments.findByIdempotencyKey(effectId()!!)?.let { "Charged ${it.amount}." } },
 * ) { input -> "Charged ${payments.charge(input.amount, idempotencyKey = effectId()!!).amount}." }
 * ```
 *
 * For a service that takes idempotency keys, `reconcile = { null }` is enough: the tool runs again
 * with the same key, and the service answers with what it did the first time. A [reconcile] that
 * cannot tell throws: the run fails, and a person who checked resumes it with a
 * [ChatMessage.ToolResult] for the call, which is then not run.
 *
 * @param name how the model refers to the tool: 1 to 64 letters, digits, `_` or `-`.
 * @param description tells the model what the tool does and when to use it.
 * @param reconcile tells whether a call that started before did its work.
 * @param execute runs the tool and returns the text the model gets back.
 * @throws GraphValidationException if [name] is not valid, or [Input] cannot be described.
 */
public inline fun <reified Input> Tool(
    name: String,
    description: String,
    noinline reconcile: suspend (Input) -> String?,
    noinline execute: suspend (Input) -> String,
): Tool = Tool(name, description, serializer<Input>(), reconcile, execute)

/** Creates a [Tool] with a reconcile whose input is read with [serializer]. See the overload with a reified type. */
public fun <Input> Tool(
    name: String,
    description: String,
    serializer: KSerializer<Input>,
    reconcile: suspend (Input) -> String?,
    execute: suspend (Input) -> String,
): Tool =
    Tool(
        ToolSpec(name, description, inputSchema(serializer.descriptor)),
        reconcile = { input -> reconcile(modelJson.decodeFromJsonElement(serializer, input)) },
        execute = { input -> execute(modelJson.decodeFromJsonElement(serializer, input)) },
    )

/**
 * Describes a property to the model: of the input class of a [Tool], or of the class a model is
 * asked for with [ask].
 *
 * ```kotlin
 * @Serializable
 * data class Forecast(@Description("The city, for example \"Madrid\"") val city: String)
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class Description(
    val value: String,
)

/**
 * Runs [calls] in parallel on these tools and returns one result for each call, in the order of
 * the calls.
 *
 * A call never fails the caller. When the tool throws, when the input is not JSON
 * ([ToolCall.invalidInput]) or does not match the tool's schema, or when no tool has the call's
 * name, the result has [ChatMessage.ToolResult.isError] set and the message of the error as its
 * text, so the model can correct itself. That includes what a browser throws in place of an
 * exception, such as the `Error` of a request that got no response.
 *
 * Only two things are propagated: a cancellation of the caller, and an error of the program or of
 * its machine, which is a subclass of `Error` such as `OutOfMemoryError` or `AssertionError`.
 *
 * In a node, the [AgentObserver]s of the run are told about each call.
 */
public suspend fun List<Tool>.execute(calls: List<ToolCall>): List<ChatMessage.ToolResult> =
    coroutineScope {
        calls.map { call -> async { execute(call) } }.awaitAll()
    }

private suspend fun List<Tool>.execute(call: ToolCall): ChatMessage.ToolResult = observeToolCall(call) { executeOne(call) }

private suspend fun List<Tool>.executeOne(call: ToolCall): ChatMessage.ToolResult {
    val tool = firstOrNull { it.spec.name == call.name } ?: return call.failed("There is no tool named '${call.name}'.")
    call.invalidInput?.let { return call.failed("The input is not a JSON object: $it") }
    return try {
        ChatMessage.ToolResult(call.id, call.name, tool.execute(call.input))
    } catch (e: CancellationException) {
        // Propagate a cancellation of the caller. If the caller is still active, the tool cancelled
        // only itself (for example its own withTimeout expired), which is a failure of the tool.
        currentCoroutineContext().ensureActive()
        call.failed(e.message)
    } catch (e: Throwable) {
        // A subclass of Error is no failure of the tool. The pause of interrupt() is one as well.
        if (e is Error && e::class != Error::class) throw e
        call.failed(e.message)
    }
}

private fun ToolCall.failed(message: String?): ChatMessage.ToolResult =
    ChatMessage.ToolResult(id, name, message ?: "The tool failed.", isError = true)

/** Reads what a model wrote: models leave optional properties out and may add ones the schema does not have. */
internal val modelJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }
