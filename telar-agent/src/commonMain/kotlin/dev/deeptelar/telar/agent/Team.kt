package dev.deeptelar.telar.agent

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.Retry
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An agent of a team: what it is good at, and the model, tools and instructions it works with.
 *
 * @property name how the team refers to the agent. It is the name of its node in the graph, and the
 * model that leads the team calls it with the tool `ask_<name>`: letters, digits, `_` and `-`.
 * @property description tells the model that leads the team what this agent does and when to use it.
 * @property model the model of this agent. A team can use a fast model for simple work and a better
 * one for hard work.
 * @property tools the tools of this agent.
 * @property system the instructions of this agent.
 * @property inOrder `true` to run the tool calls of an answer one after the other. See [toolLoop].
 * @property retry how this agent's model is asked again when a call to it fails. See [toolLoop].
 * @property shorten returns the part of this agent's conversation its model reads. See [toolLoop].
 */
public class TeamAgent(
    public val name: String,
    public val description: String,
    public val model: ChatModel,
    public val tools: List<Tool> = emptyList(),
    public val system: String? = null,
    public val inOrder: Boolean = false,
    public val retry: Retry? = null,
    public val shorten: ((List<ChatMessage>) -> List<ChatMessage>)? = null,
)

/**
 * A task that a [supervisor] gave to one of its agents, and the work the agent did on it.
 *
 * @property index the place of this assignment in [TeamState.assignments].
 * @property agent the [TeamAgent.name] of the agent.
 * @property toolCallId the id of the supervisor's tool call, which the agent's answer goes back to.
 * @property toolName the name of that tool call.
 * @property conversation the agent's own conversation, which starts with the task, and its tokens.
 * @property tokenBudget the tokens the agent may use, or `null` when there is no limit.
 * @property finished `true` once the agent has stopped working on the task.
 */
@Serializable
public data class Assignment(
    val index: Int,
    val agent: String,
    val toolCallId: String,
    val toolName: String,
    val conversation: AgentState,
    val tokenBudget: Int? = null,
    val finished: Boolean = false,
)

/**
 * The state of a [supervisor]: its conversation with the user and the work of its agents.
 *
 * It is `@Serializable`, so a checkpointer that uses kotlinx.serialization can save it.
 *
 * @property messages the conversation of the supervisor, oldest message first. A task it gave an
 * agent is a tool call in it, and the agent's answer is the result of that call.
 * @property tokens the tokens of every answer of the supervisor, and of the agents that finished.
 * @property assignments every task the supervisor gave in this conversation, oldest first, with what
 * the agent did on it.
 */
@Serializable
public data class TeamState(
    val messages: List<ChatMessage> = emptyList(),
    val tokens: TokenUsage = TokenUsage(),
    val assignments: List<Assignment> = emptyList(),
) {
    /** Starts a conversation with [question] as the user's first message. */
    public constructor(question: String) : this(listOf(ChatMessage.User(question)))

    /**
     * The text of the supervisor's final answer, or `null` while the conversation does not end with
     * one: before the first run, when the run paused in an agent, or when the team used its token
     * budget before it had an answer. The text is incomplete when [answerTruncated] is `true`.
     */
    public val answer: String?
        get() = messages.finalAnswer()

    /** `true` when the model reached its output limit before it finished its last message, so [answer] is cut off. */
    public val answerTruncated: Boolean
        get() = messages.endsTruncated()

    /** Returns this state with [text] and its [attachments] added as the user's next message, to continue the conversation. */
    public fun withUserMessage(text: String, attachments: List<Attachment> = emptyList()): TeamState =
        copy(messages = messages + ChatMessage.User(text, attachments))
}

/**
 * Returns a graph that is a team of agents with a supervisor: [model] reads the task, gives each part
 * to the agent that fits it, reads what comes back, and answers when the task is done.
 *
 * ```kotlin
 * val team = supervisor(
 *     model = opus,
 *     system = "You answer the customers of a pizza shop.",
 *     agents = listOf(
 *         TeamAgent("orders", "Knows where an order is.", haiku, tools = listOf(orderStatus)),
 *         TeamAgent("menu", "Knows the dishes and their prices.", haiku, tools = listOf(menuPrice)),
 *     ),
 * )
 *
 * val state = team.invoke(TeamState("Where is my pizza, and what does a calzone cost?")).state
 * println(state.answer)
 * ```
 *
 * The supervisor gives a task with a tool call. An agent sees only that task, in a conversation of
 * its own, and the supervisor sees only the agent's answer, so what the agents read stays small. Each
 * task starts a new conversation: an agent does not remember its earlier tasks. The conversation is
 * in [TeamState.assignments], for the code that reads it.
 *
 * Tasks of one answer for different agents run at the same time. Tasks for the same agent run one
 * after the other.
 *
 * Each agent is a subgraph with its [TeamAgent.name], with the nodes `model` and `tools` of a
 * [toolLoop] and a last node `finish`. So a run pauses and continues inside an agent as in any
 * subgraph, and a checkpointer holds the agent's work:
 *
 * ```kotlin
 * // Approve the tool calls of the agent "orders" by hand.
 * val config = GraphConfig("ticket-7", checkpointer, interruptBefore = setOf("orders/tools"))
 * ```
 *
 * A failure of an agent's model fails the run, and `resume` continues inside the agent. The steps of
 * the agent count on their own against `GraphConfig.maxIterations`, for each task.
 *
 * In a stream, the events of an agent arrive inside a `GraphEvent.SubgraphEvent` that names it. Its
 * text is in [textDelta] too, so check for that event to show only the supervisor's answer.
 *
 * [TeamState.tokens] counts the supervisor, and each agent when it finishes. [Assignment.conversation]
 * has the tokens of one task. An [AgentObserver] is told about every call, and for an agent the
 * `parent` of the call's run is the node of the agent.
 *
 * @param model the model of the supervisor. It should be good at planning: it decides who does what.
 * @param agents the team.
 * @param system instructions for the supervisor.
 * @param tokenBudget the tokens the whole team may use. The supervisor is not asked again once the
 * count has reached it, and the run ends without an answer. An agent gets what is left when it
 * starts, so tasks that run at the same time can together go above it.
 * @param retry how the supervisor's model is asked again when a call to it fails.
 * @param shorten returns the part of the conversation the supervisor reads, such as [keepLast].
 * @throws GraphValidationException if [agents] is empty, two agents have the same name, an agent is
 * named `supervisor`, a name cannot be part of a tool name, or [tokenBudget] is less than 1.
 */
public fun supervisor(
    model: ChatModel,
    agents: List<TeamAgent>,
    system: String? = null,
    tokenBudget: Int? = null,
    retry: Retry? = null,
    shorten: ((List<ChatMessage>) -> List<ChatMessage>)? = null,
): CompiledGraph<TeamState> {
    if (agents.isEmpty()) throw GraphValidationException("A supervisor needs at least one agent.")
    if (tokenBudget != null && tokenBudget < 1) {
        throw GraphValidationException("A token budget must be at least 1, but was $tokenBudget.")
    }
    val byTool = agents.associateBy { it.spec().name }
    val stopped: (TeamState) -> Boolean = { state -> tokenBudget != null && state.tokens.total >= tokenBudget }

    return StateGraph<TeamState> {
        val lead =
            askModelNode(
                name = "supervisor",
                model = model,
                specs = agents.map { it.spec() },
                messages = { it.messages },
                append = { state, new -> state.withAnswer(new, byTool, tokenBudget) },
                firstMessage = null,
                system = system,
                countTokens = { state, used -> state.withTokensOfAnswer(used) },
                stopWhen = stopped,
                retry = retry,
                shorten = shorten,
            )
        val workers =
            agents.associate { agent ->
                agent.name to
                    subgraph(
                        agent.name,
                        agent.graph(),
                        // After a pause behind its last node, the task of an agent is finished and the agent goes on with it.
                        state = { team ->
                            team.assignments.firstOrNull { it.agent == agent.name && !it.finished }
                                ?: team.assignments.last { it.agent == agent.name }
                        },
                        update = TeamState::withWork,
                    )
            }
        val waiting: (TeamState) -> Set<NodeRef> = { state ->
            state.assignments.filterNot { it.finished }.mapTo(mutableSetOf()) { workers.getValue(it.agent) }
        }

        START then lead
        conditionalFanOut(lead, targets = workers.values.toSet() + lead + NodeRef.END) { state ->
            when {
                waiting(state).isNotEmpty() -> waiting(state)
                // Every call of the answer had an error, which the model reads.
                state.messages.lastOrNull() is ChatMessage.ToolResult && !stopped(state) -> setOf(lead)
                else -> setOf(NodeRef.END)
            }
        }
        workers.values.forEach { worker ->
            conditionalFanOut(worker, targets = workers.values.toSet() + lead) { state -> waiting(state).ifEmpty { setOf(lead) } }
        }
    }.compile()
}

/** What the model of a [supervisor] is told about this agent. */
private fun TeamAgent.spec(): ToolSpec =
    ToolSpec(
        name = "ask_$name",
        description = "$description It sees only the task you give it, so put in the task all that it needs to know.",
        inputSchema =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject(TASK) {
                        put("type", "string")
                        put("description", "What the agent has to do, with all that it needs to know.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive(TASK)) }
            },
    )

/** A [toolLoop] of this agent over an [Assignment], which ends by marking it finished. */
private fun TeamAgent.graph(): CompiledGraph<Assignment> =
    StateGraph<Assignment> {
        val finish = node("finish") { it.copy(finished = true) }
        START then
            toolLoop(
                model = model,
                tools = tools,
                messages = { it.conversation.messages },
                append = { work, new -> work.copy(conversation = work.conversation.copy(messages = work.conversation.messages + new)) },
                system = system,
                then = finish,
                inOrder = inOrder,
                countTokens = { work, used -> work.copy(conversation = work.conversation.copy(tokens = work.conversation.tokens + used)) },
                stopWhen = { work -> work.tokenBudget != null && work.conversation.tokens.total >= work.tokenBudget },
                retry = retry,
                shorten = shorten,
            )
        finish then END
    }.compile()

/**
 * Returns this state with an answer of the supervisor. Each call in it that gives an agent a task
 * becomes an [Assignment], and any other call gets an error for the model right away.
 */
private fun TeamState.withAnswer(new: List<ChatMessage>, byTool: Map<String, TeamAgent>, tokenBudget: Int?): TeamState {
    val calls = new.filterIsInstance<ChatMessage.Assistant>().flatMap { it.toolCalls }
    return calls.fold(copy(messages = messages + new)) { state, call ->
        val agent = byTool[call.name]
        val task = (call.input[TASK] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        when {
            agent == null -> state.withError(call, "There is no tool named '${call.name}'.")
            task == null -> state.withError(call, "The agent needs a task: send it as the text '$TASK'.")
            else -> {
                val assignment =
                    Assignment(
                        index = state.assignments.size,
                        agent = agent.name,
                        toolCallId = call.id,
                        toolName = call.name,
                        conversation = AgentState(task),
                        tokenBudget = tokenBudget?.let { it - state.tokens.total },
                    )
                state.copy(assignments = state.assignments + assignment)
            }
        }
    }
}

/** Returns this state with the tokens of an answer of the supervisor, which the agents it just gave a task can no longer use. */
private fun TeamState.withTokensOfAnswer(used: TokenUsage): TeamState =
    copy(
        tokens = tokens + used,
        assignments = assignments.map { if (it.finished) it else it.copy(tokenBudget = it.tokenBudget?.minus(used.total)) },
    )

private fun TeamState.withError(call: ToolCall, text: String): TeamState =
    copy(messages = messages + ChatMessage.ToolResult(call.id, call.name, text, isError = true))

/**
 * Returns this state with the work of an agent. The first time the work is finished, the supervisor
 * gets the answer. The engine also calls this when the agent pauses, and again after a pause behind
 * the agent's last node.
 */
private fun TeamState.withWork(work: Assignment): TeamState {
    val reported = assignments[work.index].finished
    val saved = copy(assignments = assignments.map { if (it.index == work.index) work else it })
    if (!work.finished || reported) return saved
    val answer = work.conversation.answer
    val result =
        ChatMessage.ToolResult(
            toolCallId = work.toolCallId,
            toolName = work.toolName,
            text = answer ?: "The agent ${work.agent} stopped before it had an answer: the team used its token budget.",
            isError = answer == null,
        )
    return saved.copy(messages = messages + result, tokens = tokens + work.conversation.tokens)
}

/** The input of the tool that gives an agent a task. */
private const val TASK: String = "task"
