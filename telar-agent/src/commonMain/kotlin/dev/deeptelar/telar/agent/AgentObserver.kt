package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphObserver
import dev.deeptelar.telar.ObservedCaller
import dev.deeptelar.telar.ObservedEdge
import dev.deeptelar.telar.ObservedNode
import dev.deeptelar.telar.forEachObserver

/**
 * A [GraphObserver] that is also told about the calls to models and tools inside the nodes of a
 * run and in the conditions of its edges: those of [toolLoop] and [toolAgent], of
 * [chatWithProgress], of [ask] (each time it asks), of a [ChatDecisionModel], also in a
 * [decisionEdge], and of [execute][List.execute]. Each call says which [caller][ObservedModelCall.caller]
 * made it: an [ObservedNode], or an [ObservedEdge] for the condition of an edge.
 * Use it to count the tokens of a run, log which tools ran, or show them in a debugger. Give it to a
 * run in `GraphConfig.observers`, like any observer:
 *
 * ```kotlin
 * val cost = object : AgentObserver {
 *     override fun onModelCallCompleted(call: ObservedModelCall, usage: TokenUsage?) {
 *         println("${call.caller}: ${usage?.total ?: 0} tokens")
 *     }
 * }
 * agent.invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(cost)))
 * ```
 *
 * The rules of [GraphObserver] hold:
 * - **No conversation.** An observer learns which model and which tool were called, the tokens and
 *   whether a tool returned an error, but not the messages, the input of a tool or its result. Only
 *   a [ConversationObserver] is told those. No observer gets the state of the graph.
 * - **Every start has one end.** A model call that started ends with exactly one of
 *   [onModelCallCompleted] and [onModelCallFailed], and a tool call with exactly one of
 *   [onToolCallCompleted] and [onToolCallFailed], also when the run is cancelled.
 * - **Calls from several coroutines**, because the tools of an answer run at the same time.
 *
 * A call outside a running node or condition, or straight to [ChatModel.chat], is not reported.
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
 * An [AgentObserver] that is also told what the calls send and get back: the request a model is
 * sent, its answer, the input of a tool and its result. Use it to record the conversation in a
 * trace, as `OtlpObserver` and `OpenTelemetryObserver` of telar-otel do with `recordContent = true`:
 *
 * ```kotlin
 * val log = object : ConversationObserver {
 *     override fun onModelCallResponse(call: ObservedModelCall, response: ChatResponse) {
 *         println("${call.caller}: ${response.message.text}")
 *     }
 * }
 * ```
 *
 * The conversation can hold personal data, such as what the users of an app wrote, so only an
 * observer that implements this interface gets it. The rules of [AgentObserver] hold, and:
 * - **The request is the one the model is sent:** the part of the conversation that `shorten`
 *   keeps, the prompt that [ask] adds to ask again, and the prompt that a [ChatDecisionModel] builds.
 * - **In the order of the call:** [onModelCallRequest] right after [onModelCallStarted], and
 *   [onModelCallResponse] right before [onModelCallCompleted]. A call that fails has its request
 *   but no response. A tool call is the same with [onToolCallInput] and [onToolCallResult].
 * - **A streamed answer** arrives whole in [onModelCallResponse], when the stream has ended.
 */
public interface ConversationObserver : AgentObserver {
    /** The model of [call] is sent [request]. Called right after [onModelCallStarted]. */
    public fun onModelCallRequest(call: ObservedModelCall, request: ChatRequest) {}

    /** The model of [call] answered with [response]. Called right before [onModelCallCompleted]. */
    public fun onModelCallResponse(call: ObservedModelCall, response: ChatResponse) {}

    /**
     * The model's [toolCall] is what the tool of [call] runs on. An input that is not JSON is in
     * [ToolCall.invalidInput]. Called right after [onToolCallStarted].
     */
    public fun onToolCallInput(call: ObservedToolCall, toolCall: ToolCall) {}

    /** The tool of [call] returned [result] for the model. Called right before [onToolCallCompleted]. */
    public fun onToolCallResult(call: ObservedToolCall, result: ChatMessage.ToolResult) {}
}

/**
 * A call to a model that an [AgentObserver] is told about. It is the same object from the start of
 * the call to its end.
 *
 * @property caller what makes the call: an [ObservedNode], or an [ObservedEdge] whose condition
 * makes it, such as that of a [decisionEdge].
 * @property model the model that is called. Its [name][ChatModel.name] is the model that was asked.
 */
public class ObservedModelCall internal constructor(
    public val caller: ObservedCaller,
    public val model: ChatModel,
) {
    /**
     * The model that answered, as the provider names it in its answer ([ChatResponse.model]). It is
     * known from [AgentObserver.onModelCallCompleted] on, and `null` before, or when the provider
     * does not say.
     */
    public var responseModel: String? = null
        internal set

    override fun toString(): String = "model call in $caller"
}

/**
 * A call to a tool that an [AgentObserver] is told about. It is the same object from the start of
 * the call to its end.
 *
 * @property caller what makes the call: an [ObservedNode], or an [ObservedEdge] whose condition
 * makes it.
 * @property name the name of the tool the model called.
 * @property id the id the model gave the call, which its result carries as well.
 */
public class ObservedToolCall internal constructor(
    public val caller: ObservedCaller,
    public val name: String,
    public val id: String,
) {
    override fun toString(): String = "call of tool '$name' in $caller"
}

/** Runs [block], which sends [request] to [model], and tells the agent observers of the run about it. */
internal suspend fun observeModelCall(model: ChatModel, request: ChatRequest, block: suspend () -> ChatResponse): ChatResponse {
    val (caller, observers) = agentObservers() ?: return block()
    val call = ObservedModelCall(caller, model)
    observers.forEach {
        it.onModelCallStarted(call)
        if (it is ConversationObserver) it.onModelCallRequest(call, request)
    }
    val response =
        try {
            block()
        } catch (failure: Throwable) {
            observers.forEach { it.onModelCallFailed(call, failure) }
            throw failure
        }
    call.responseModel = response.model
    observers.forEach {
        if (it is ConversationObserver) it.onModelCallResponse(call, response)
        it.onModelCallCompleted(call, response.usage)
    }
    return response
}

/** Runs [block], which runs the tool of [toolCall], and tells the agent observers of the run about it. */
internal suspend fun observeToolCall(toolCall: ToolCall, block: suspend () -> ChatMessage.ToolResult): ChatMessage.ToolResult {
    val (caller, observers) = agentObservers() ?: return block()
    val call = ObservedToolCall(caller, toolCall.name, toolCall.id)
    observers.forEach {
        it.onToolCallStarted(call)
        if (it is ConversationObserver) it.onToolCallInput(call, toolCall)
    }
    val result =
        try {
            block()
        } catch (failure: Throwable) {
            observers.forEach { it.onToolCallFailed(call, failure) }
            throw failure
        }
    observers.forEach {
        if (it is ConversationObserver) it.onToolCallResult(call, result)
        it.onToolCallCompleted(call, result.isError)
    }
    return result
}

/** The node or edge that runs this code and the agent observers of its run, or `null` when there are none. */
private suspend fun agentObservers(): Pair<ObservedCaller, List<AgentObserver>>? {
    var caller: ObservedCaller? = null
    val observers = mutableListOf<AgentObserver>()
    forEachObserver { observer, observed ->
        caller = observed
        if (observer is AgentObserver) observers += observer
    }
    return caller?.takeIf { observers.isNotEmpty() }?.let { it to observers }
}
