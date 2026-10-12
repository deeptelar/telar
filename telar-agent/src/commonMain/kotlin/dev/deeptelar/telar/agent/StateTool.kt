package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * A function a model may call that reads the state of the run and changes it: add a line to an
 * order, book a seat, move a piece. What it did is in the state, so a checkpoint holds it and the
 * next node sees it.
 *
 * ```kotlin
 * @Serializable
 * data class NewLine(@Description("The item, for example \"margherita\"") val item: String)
 *
 * val addLine = StateTool<Order, NewLine>("add_line", "Adds a line to the order.") { order, line ->
 *     ToolOutcome(order.copy(lines = order.lines + line.item), "Added ${line.item}.")
 * }
 * ```
 *
 * Give it to [toolLoop] as one of its `stateTools`. A [Tool] is enough for a function that only
 * needs its input, such as a lookup.
 *
 * Use this constructor when you write the schema by hand.
 *
 * @property spec what the model is told about the tool.
 * @property execute runs the tool with the state of the run and the input the model sent, and
 * returns the state it leaves and the text the model gets back. An exception it throws reaches the
 * model as an error result, and the state stays as it was.
 */
public class StateTool<State>(
    public val spec: ToolSpec,
    public val execute: suspend (State, JsonObject) -> ToolOutcome<State>,
)

/**
 * Creates a [StateTool] for a graph with the state [State], whose input is the `@Serializable` type
 * [Input]. The input schema is built from the type as for a [Tool]. Use `StateTool<State, Unit>`
 * for a tool that takes no input.
 *
 * @param name how the model refers to the tool: 1 to 64 letters, digits, `_` or `-`.
 * @param description tells the model what the tool does and when to use it.
 * @param execute runs the tool and returns the state it leaves and the text the model gets back.
 * @throws GraphValidationException if [name] is not valid, or [Input] is not a class or has a
 * property of a type that cannot be described.
 */
public inline fun <State, reified Input> StateTool(
    name: String,
    description: String,
    noinline execute: suspend (State, Input) -> ToolOutcome<State>,
): StateTool<State> = StateTool(name, description, serializer<Input>(), execute)

/** Creates a [StateTool] whose input is read with [serializer]. See the overload with a reified type. */
public fun <State, Input> StateTool(
    name: String,
    description: String,
    serializer: KSerializer<Input>,
    execute: suspend (State, Input) -> ToolOutcome<State>,
): StateTool<State> =
    StateTool(ToolSpec(name, description, inputSchema(serializer.descriptor))) { state, input ->
        execute(state, modelJson.decodeFromJsonElement(serializer, input))
    }

/**
 * What a [StateTool] did.
 *
 * @property state the state of the run after the tool. Return the state the tool received when it
 * only read it.
 * @property text what the model gets back.
 */
public data class ToolOutcome<out State>(
    val state: State,
    val text: String,
)

/**
 * Runs [call] on [state] and returns the state after it with the result for the model. The call
 * goes to the state tool with its name, or else to the tool with its name.
 *
 * A call that fails leaves [state] as it was and has an error result, as [List.execute] describes.
 */
internal suspend fun <State> runOnState(
    call: ToolCall,
    state: State,
    tools: List<Tool>,
    stateTools: List<StateTool<State>>,
): Pair<State, ChatMessage.ToolResult> {
    val stateTool = stateTools.firstOrNull { it.spec.name == call.name } ?: return state to tools.execute(listOf(call)).single()
    var after = state
    // As a tool of this one call, so that a failure is handled as the failure of any tool is.
    val onThisState = Tool(stateTool.spec) { input -> stateTool.execute(state, input).also { after = it.state }.text }
    val result = listOf(onThisState).execute(listOf(call)).single()
    return after to result
}
