package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphObserver
import dev.deeptelar.telar.ObservedNode
import dev.deeptelar.telar.forEachNodeObserver

/**
 * A [GraphObserver] that is also told about the calls to models and tools inside the nodes of a
 * run: those of [toolLoop] and [toolAgent], of [chatWithProgress], and of [execute][List.execute].
 * Use it to count the tokens of a run, log which tools ran, or show them in a debugger. Give it to a
 * run in `GraphConfig.observers`, like any observer:
 *
 * ```kotlin
 * val cost = object : AgentObserver {
 *     override fun onModelCallCompleted(call: ObservedModelCall, usage: TokenUsage?) {
 *         println("${call.node.name}: ${usage?.total ?: 0} tokens")
 *     }
 * }
 * agent.invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(cost)))
 * ```
 *
 * The rules of [GraphObserver] hold:
 * - **No conversation.** An observer learns which model and which tool were called, the tokens and
 *   whether a tool returned an error, but not the messages, the input of a tool or its result.
 * - **Every start has one end.** A model call that started ends with exactly one of
 *   [onModelCallCompleted] and [onModelCallFailed], and a tool call with exactly one of
 *   [onToolCallCompleted] and [onToolCallFailed], also when the run is cancelled.
 * - **Calls from several coroutines**, because the tools of an answer run at the same time.
 *
 * A call outside a running node, or straight to [ChatModel.chat], is not reported.
 */
public interface AgentObserver : GraphObserver {
    /** [call] asks its model. A try of a node with a `retry` asks again, as a new call. */
    public fun onModelCallStarted(call: ObservedModelCall) {}

    /** The model of [call] answered, with the tokens its provider reported, or `null` when it reported none. */
    public fun onModelCallCompleted(call: ObservedModelCall, usage: TokenUsage?) {}

    /** The model of [call] failed with [cause], or the call was cancelled. */
    public fun onModelCallFailed(call: ObservedModelCall, cause: Throwable) {}

    /** [call] runs its tool. */
    public fun onToolCallStarted(call: ObservedToolCall) {}

    /**
     * The tool of [call] returned a result for the model. [isError] is `true` when the tool failed,
     * or the call named no tool or had an input that does not fit, which the model is told as well.
     */
    public fun onToolCallCompleted(call: ObservedToolCall, isError: Boolean) {}

    /**
     * [call] ended without a result: it was cancelled, the tool paused the run with `interrupt`, or
     * it threw an error that ends the run, such as an `OutOfMemoryError`.
     */
    public fun onToolCallFailed(call: ObservedToolCall, cause: Throwable) {}
}

/**
 * A call to a model that an [AgentObserver] is told about. It is the same object from the start of
 * the call to its end.
 *
 * @property node the node that makes the call.
 * @property model the model that is called.
 */
public class ObservedModelCall internal constructor(
    public val node: ObservedNode,
    public val model: ChatModel,
) {
    override fun toString(): String = "model call in $node"
}

/**
 * A call to a tool that an [AgentObserver] is told about. It is the same object from the start of
 * the call to its end.
 *
 * @property node the node that makes the call.
 * @property name the name of the tool the model called.
 * @property id the id the model gave the call, which its result carries as well.
 */
public class ObservedToolCall internal constructor(
    public val node: ObservedNode,
    public val name: String,
    public val id: String,
) {
    override fun toString(): String = "call of tool '$name' in $node"
}

/** Runs [block], which calls [model], and tells the agent observers of the run about it. */
internal suspend fun observeModelCall(model: ChatModel, block: suspend () -> ChatResponse): ChatResponse {
    val (node, observers) = agentObservers() ?: return block()
    val call = ObservedModelCall(node, model)
    observers.forEach { it.onModelCallStarted(call) }
    val response =
        try {
            block()
        } catch (failure: Throwable) {
            observers.forEach { it.onModelCallFailed(call, failure) }
            throw failure
        }
    observers.forEach { it.onModelCallCompleted(call, response.usage) }
    return response
}

/** Runs [block], which runs the tool of [toolCall], and tells the agent observers of the run about it. */
internal suspend fun observeToolCall(toolCall: ToolCall, block: suspend () -> ChatMessage.ToolResult): ChatMessage.ToolResult {
    val (node, observers) = agentObservers() ?: return block()
    val call = ObservedToolCall(node, toolCall.name, toolCall.id)
    observers.forEach { it.onToolCallStarted(call) }
    val result =
        try {
            block()
        } catch (failure: Throwable) {
            observers.forEach { it.onToolCallFailed(call, failure) }
            throw failure
        }
    observers.forEach { it.onToolCallCompleted(call, result.isError) }
    return result
}

/** The node that runs this code and the agent observers of its run, or `null` when there are none. */
private suspend fun agentObservers(): Pair<ObservedNode, List<AgentObserver>>? {
    var node: ObservedNode? = null
    val observers = mutableListOf<AgentObserver>()
    forEachNodeObserver { observer, observed ->
        node = observed
        if (observer is AgentObserver) observers += observer
    }
    return node?.takeIf { observers.isNotEmpty() }?.let { it to observers }
}
