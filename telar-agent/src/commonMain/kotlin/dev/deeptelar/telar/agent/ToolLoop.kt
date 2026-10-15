package dev.deeptelar.telar.agent

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.Retry
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.effect
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Adds the loop of a tool-calling agent to this graph and returns its first node.
 *
 * The loop has two nodes. The model node sends the conversation to [model]. When the answer asks
 * for tools, the tools node runs them, at the same time unless [inOrder] or [stateTools] say
 * otherwise, and the model is asked again with their results. When the answer asks for none, the
 * graph continues at [then].
 *
 * ```kotlin
 * data class Ticket(val messages: List<ChatMessage>, val reply: String = "")
 *
 * val graph = StateGraph<Ticket> {
 *     val send = node("send") { it.copy(reply = it.messages.last().text) }
 *     val agent = toolLoop(
 *         model = model,
 *         tools = listOf(orderStatus, menuPrice),
 *         messages = { it.messages },
 *         append = { ticket, new -> ticket.copy(messages = ticket.messages + new) },
 *         then = send,
 *     )
 *     START then agent
 *     send then END
 * }.compile()
 *
 * graph.invoke(Ticket(listOf(ChatMessage.User("Where is my pizza?"))))
 * ```
 *
 * Both nodes have a `work` and an `update`, so the loop can run next to other branches without a
 * `Reducer`; a loop with [stateTools] is the exception, see below. Every round of tools takes two steps of the run, so raise `GraphConfig.maxIterations`
 * for an agent that needs many rounds.
 *
 * [messages] and [append] read and write the same list: [messages] must return what [append] stored.
 * When the conversation starts from other fields of the state, do not build the first message in
 * [messages], because an [append] that adds to the stored list would then lose it. Give it to
 * [firstMessage] instead, and the loop stores it with the model's first answer:
 *
 * ```kotlin
 * data class Order(val customer: String, val question: String, val messages: List<ChatMessage> = emptyList())
 *
 * toolLoop(
 *     model = model,
 *     tools = tools,
 *     messages = { it.messages },
 *     append = { order, new -> order.copy(messages = order.messages + new) },
 *     firstMessage = { "${it.customer} writes: ${it.question}" },
 * )
 * ```
 *
 * When the run is collected with `stream`, the model node streams: each piece of text the model
 * writes arrives as a `GraphEvent.NodeProgress` of the model node, and [textDelta] reads it. With
 * `invoke`, the model is asked for the whole answer at once.
 *
 * When the model reaches its output limit in the text of its answer, the loop ends with that answer
 * and its [ChatMessage.Assistant.truncated] is `true`, so [append] and the code that reads the state
 * can tell. When it reaches the limit in a tool call, the model node fails with a
 * [ChatModelException], because a part of a call cannot be run.
 *
 * To approve tool calls by hand, run the graph with `interruptBefore = setOf("tools")`. The calls
 * that wait are `messages.pendingToolCalls()`. Resume the run to let them through, or resume it with
 * a state that has a [ChatMessage.ToolResult] for each call you reject: a call that already has a
 * result is not run.
 *
 * A tool that acts on the state of the run is a [StateTool], and goes into [stateTools]:
 *
 * ```kotlin
 * val addLine = StateTool<Order, NewLine>("add_line", "Adds a line to the order.") { order, line ->
 *     ToolOutcome(order.copy(lines = order.lines + line.item), "Added ${line.item}.")
 * }
 *
 * toolLoop(
 *     model = model,
 *     tools = listOf(menuPrice),
 *     stateTools = listOf(addLine),
 *     messages = { it.messages },
 *     append = { order, new -> order.copy(messages = order.messages + new) },
 * )
 * ```
 *
 * A loop with state tools works differently in three ways:
 * - **The calls of an answer run one after the other**, in the order the model wrote them, and each
 *   call gets the state the call before it left. A call that fails changes nothing, and the calls
 *   after it still run.
 * - **[append] is called once for each result**, before the next call runs. A state tool that calls
 *   `interrupt` with the state it received therefore saves what the calls before it did, with their
 *   results, and `resume` continues with the call that paused.
 * - **The tools node returns a whole state.** When it can run in the same step as another node
 *   that returns a whole state, `compile()` asks for a `Reducer`, as for any two such nodes.
 *
 * To know what the loop costs, give it [countTokens], which adds the tokens of each answer of the
 * model to the state. To limit it, give it [stopWhen]. It is asked before each call to the model,
 * and when it returns `true` the model is not asked and the graph continues at [then], as after a
 * last answer:
 *
 * ```kotlin
 * data class Order(val messages: List<ChatMessage>, val tokens: TokenUsage = TokenUsage())
 *
 * toolLoop(
 *     model = model,
 *     tools = tools,
 *     messages = { it.messages },
 *     append = { order, new -> order.copy(messages = order.messages + new) },
 *     countTokens = { order, used -> order.copy(tokens = order.tokens + used) },
 *     stopWhen = { order -> order.tokens.total >= 50_000 },
 * )
 * ```
 *
 * The count has the numbers the provider reported for the calls so far, so a loop with a limit on
 * its tokens ends up to one call above it. A loop that was stopped ends with the results of its
 * last tools, or with the message of the user, and not with an answer of the model.
 *
 * The tools node saves the result of each call of a [Tool] that succeeded as soon as the tool
 * returns, as an [effect] keyed by the [ToolCall.id]. When its step runs again, because another
 * call of the same answer called `interrupt`, another node of the step failed or the process
 * stopped, `resume` gives those calls their saved results and runs only the others: a tool that
 * charged a card does not charge it twice. A call with an error result is not saved, and runs
 * again. A [StateTool] is not saved this way, because it changes the state: call [effect] in it
 * for what it does outside the run.
 *
 * A call to the model that fails ends the run. Give the loop a [retry] to ask the model again
 * first. Tools need none: a tool that fails returns an error to the model, which can call it again.
 *
 * ```kotlin
 * toolLoop(model = model, tools = tools, messages = { it.messages }, append = ::append, retry = Retry(attempts = 3))
 * ```
 *
 * In a streamed run, the text of a try that failed has already arrived. The stream then has a
 * [GraphEvent.NodeRetry] for the model node, and the text of the next try starts from its beginning.
 *
 * @param model the model to ask.
 * @param tools the tools the model may call that only need their input.
 * @param messages reads the conversation from the state.
 * @param append returns the state with the given messages added to its conversation. It is called
 * with the model's answer, and with the results of the tools.
 * @param firstMessage returns the user message that starts the conversation. It is used only while
 * [messages] returns an empty list, and [append] receives it together with the model's first answer.
 * Leave it out when the state already holds the first message.
 * @param system instructions for the model.
 * @param then where the graph continues when the model is done. The default ends the branch.
 * @param modelNode the name of the model node.
 * @param toolsNode the name of the tools node.
 * @param stateTools the tools the model may call that read and change the state of the run.
 * @param inOrder `true` to run the calls of an answer one after the other, in the order the model
 * wrote them, for tools that depend on each other. A loop with [stateTools] always does.
 * @param countTokens returns the state with the tokens of one answer of the model added to its
 * count. It is not called for an answer whose provider reports no usage.
 * @param stopWhen returns `true` when the model must not be asked again, for example because the
 * state says that a budget is used.
 * @param retry how the model node is tried again when a call to the model fails. `null` ends the
 * run at the first failure.
 * @throws GraphValidationException if two tools have the same name, or a node name is already used.
 */
public fun <State> StateGraph<State>.toolLoop(
    model: ChatModel,
    tools: List<Tool> = emptyList(),
    messages: (State) -> List<ChatMessage>,
    append: (State, List<ChatMessage>) -> State,
    firstMessage: ((State) -> String)? = null,
    system: String? = null,
    then: NodeRef = NodeRef.END,
    modelNode: String = "model",
    toolsNode: String = "tools",
    stateTools: List<StateTool<State>> = emptyList(),
    inOrder: Boolean = false,
    countTokens: ((State, TokenUsage) -> State)? = null,
    stopWhen: ((State) -> Boolean)? = null,
    retry: Retry? = null,
): NodeRef {
    val specs = tools.map { it.spec } + stateTools.map { it.spec }
    specs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
        throw GraphValidationException("Two tools are named '$it'. Tool names must be unique.")
    }

    val askModel =
        node(
            modelNode,
            work = { state ->
                if (stopWhen != null && stopWhen(state)) {
                    null
                } else {
                    val stored = messages(state)
                    val opening =
                        if (stored.isEmpty() &&
                            firstMessage != null
                        ) {
                            listOf(ChatMessage.User(firstMessage(state)))
                        } else {
                            emptyList()
                        }
                    val response = model.chatWithProgress(ChatRequest(opening + stored, system, specs))
                    if (response.message.truncated && response.message.toolCalls.isNotEmpty()) {
                        throw ChatModelException("The model reached its output limit in the middle of a tool call. Raise the limit.")
                    }
                    ModelTurn(opening + response.message, response.usage)
                }
            },
            retry = retry,
        ) { state, turn ->
            // No turn: the loop was stopped before the model was asked.
            if (turn == null) {
                state
            } else {
                val answered = append(state, turn.messages)
                if (countTokens != null && turn.usage != null) countTokens(answered, turn.usage) else answered
            }
        }
    val runTools =
        when {
            stateTools.isNotEmpty() ->
                node(toolsNode) { state ->
                    val calls = messages(state).pendingToolCalls()
                    calls.zip(effectKeys(calls)).fold(state) { current, (call, key) ->
                        val (after, result) = runOnState(call, key, current, tools, stateTools)
                        append(after, listOf(result))
                    }
                }
            inOrder ->
                node(toolsNode, work = { state ->
                    val calls = messages(state).pendingToolCalls()
                    calls.zip(effectKeys(calls)).map { (call, key) -> tools.executeOnce(call, key) }
                }) { state, results ->
                    append(state, results)
                }
            else ->
                node(toolsNode, work = { state ->
                    val calls = messages(state).pendingToolCalls()
                    coroutineScope {
                        calls.zip(effectKeys(calls)).map { (call, key) -> async { tools.executeOnce(call, key) } }.awaitAll()
                    }
                }) { state, results ->
                    append(state, results)
                }
        }

    conditionalEdge(askModel, targets = setOf(runTools, then)) { state ->
        if (messages(state).pendingToolCalls().isEmpty()) then else runTools
    }
    runTools then askModel
    return askModel
}

/**
 * Returns a graph that is a tool-calling agent: [model] answers the conversation in the state, and
 * runs [tools] as often as it needs to before it answers.
 *
 * ```kotlin
 * val agent = toolAgent(model, tools = listOf(forecast), system = "You are a travel assistant.")
 *
 * val first = agent.invoke(AgentState("What is the weather in Madrid?")).state
 * println(first.answer)
 * val second = agent.invoke(first.withUserMessage("And in Lisbon?")).state
 *
 * // Or show the answer while the model writes it.
 * agent.stream(AgentState("What is the weather in Madrid?")).collect { event -> event.textDelta?.let(::print) }
 * ```
 *
 * The graph is a [toolLoop] with its default node names. Use [toolLoop] directly to put the agent
 * inside a larger graph, or to keep the conversation in a state of your own.
 *
 * The state counts the tokens of the conversation in [AgentState.tokens]. With a [tokenBudget], the
 * model is not asked again once the count has reached it, and the run ends without an answer:
 *
 * ```kotlin
 * val agent = toolAgent(model, tools = listOf(forecast), tokenBudget = 50_000)
 *
 * val state = agent.invoke(AgentState("Plan a week in Portugal.")).state
 * println("${state.tokens.total} tokens")
 * if (state.answer == null) println("The agent used its budget before it had an answer.")
 * ```
 *
 * @param inOrder `true` to run the tool calls of an answer one after the other, in the order the
 * model wrote them, for tools that depend on each other. By default they run at the same time.
 * @param tokenBudget the tokens the conversation in the state may use. The count is compared with
 * it before each call to the model, with the numbers of the calls so far, so a run ends up to one
 * call above it. `null` is no limit.
 * @param retry how the model is asked again when a call to it fails, for example
 * `Retry(attempts = 3)`. `null` ends the run at the first failure.
 * @throws GraphValidationException if two tools have the same name, or [tokenBudget] is less than 1.
 */
public fun toolAgent(
    model: ChatModel,
    tools: List<Tool> = emptyList(),
    system: String? = null,
    inOrder: Boolean = false,
    tokenBudget: Int? = null,
    retry: Retry? = null,
): CompiledGraph<AgentState> {
    if (tokenBudget != null && tokenBudget < 1) {
        throw GraphValidationException("A token budget must be at least 1, but was $tokenBudget.")
    }
    return StateGraph<AgentState> {
        START then
            toolLoop(
                model = model,
                tools = tools,
                messages = { it.messages },
                append = { state, new -> state.copy(messages = state.messages + new) },
                system = system,
                inOrder = inOrder,
                countTokens = { state, used -> state.copy(tokens = state.tokens + used) },
                stopWhen = tokenBudget?.let { budget -> { state -> state.tokens.total >= budget } },
                retry = retry,
            )
    }.compile()
}

/**
 * Runs [call] on these tools as an [effect] named [key] of the running node, so that when the step
 * runs again a call that succeeded returns its saved result and the tool does not run again. An
 * error result is not saved: the call runs again, as it would without the effect.
 */
internal suspend fun List<Tool>.executeOnce(call: ToolCall, key: String): ChatMessage.ToolResult =
    try {
        effect(key, ToolResultSerializer) {
            val result = execute(listOf(call)).single()
            if (result.isError) throw ErrorResult(result)
            result
        }
    } catch (e: ErrorResult) {
        e.result
    }

/**
 * Returns the name of the [effect] of each call: its id, so that it stays the same when the step
 * runs again with fewer pending calls. A model that sends the same id twice gets a number added.
 */
internal fun effectKeys(calls: List<ToolCall>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return calls.map { call ->
        val count = (seen[call.id] ?: 0) + 1
        seen[call.id] = count
        if (count == 1) "tool-call:${call.id}" else "tool-call:${call.id}#$count"
    }
}

/** Carries an error result out of an [effect], so that it saves nothing. */
private class ErrorResult(
    val result: ChatMessage.ToolResult,
) : Exception()

private object ToolResultSerializer : StateSerializer<ChatMessage.ToolResult> {
    override fun serialize(state: ChatMessage.ToolResult): String = Json.encodeToString(ChatMessage.ToolResult.serializer(), state)

    override fun deserialize(data: String): ChatMessage.ToolResult = Json.decodeFromString(ChatMessage.ToolResult.serializer(), data)
}

/** What the model node of a [toolLoop] hands to its update: the messages to store and the tokens of the answer. */
private class ModelTurn(
    val messages: List<ChatMessage>,
    val usage: TokenUsage?,
)

/**
 * The state of a [toolAgent]: the conversation so far, oldest message first, and what it cost.
 *
 * It is `@Serializable`, so a checkpointer that uses kotlinx.serialization can save it.
 *
 * @property tokens the tokens of every answer of the model in this conversation, as far as the
 * provider reported them. A conversation that continues with [withUserMessage] keeps counting. Use
 * `copy(tokens = TokenUsage())` to start the count again, for example for a budget per question.
 */
@Serializable
public data class AgentState(
    val messages: List<ChatMessage> = emptyList(),
    val tokens: TokenUsage = TokenUsage(),
) {
    /** Starts a conversation with [question] as the user's first message. */
    public constructor(question: String) : this(listOf(ChatMessage.User(question)))

    /**
     * The text of the model's final answer, or `null` while the conversation does not end with one:
     * before the first run, when the run paused with tool calls waiting, or when the agent used its
     * token budget before it had an answer.
     *
     * The text is incomplete when [answerTruncated] is `true`.
     */
    public val answer: String?
        get() = (messages.lastOrNull() as? ChatMessage.Assistant)?.takeIf { it.toolCalls.isEmpty() }?.text

    /** `true` when the model reached its output limit before it finished its last message, so [answer] is cut off. */
    public val answerTruncated: Boolean
        get() = (messages.lastOrNull() as? ChatMessage.Assistant)?.truncated == true

    /** Returns this state with [text] added as the user's next message, to continue the conversation. */
    public fun withUserMessage(text: String): AgentState = copy(messages = messages + ChatMessage.User(text))
}
