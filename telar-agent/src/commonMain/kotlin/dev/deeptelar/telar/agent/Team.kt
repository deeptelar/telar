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
 * other models of the team reach it with a tool that has the name in it: letters, digits, `_` and `-`.
 * @property description tells the other models of the team what this agent does and when to use it.
 * @property model the model of this agent. A team can use a fast model for simple work and a better
 * one for hard work.
 * @property tools the tools of this agent.
 * @property system the instructions of this agent.
 * @property inOrder `true` to run the tool calls of an answer one after the other. See [toolLoop]. In
 * a [handoffTeam] they always do.
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
 * @property agent the [TeamAgent.name] of the agent that has the conversation in a [handoffTeam], or
 * `null` while it is still with the first agent.
 */
@Serializable
public data class TeamState(
    val messages: List<ChatMessage> = emptyList(),
    val tokens: TokenUsage = TokenUsage(),
    val assignments: List<Assignment> = emptyList(),
    val agent: String? = null,
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

/**
 * Returns a graph that is a team of agents who hand the conversation to each other: the first of
 * [agents] answers, and each agent can end its turn by handing the conversation to another, which
 * goes on from there.
 *
 * ```kotlin
 * val team = handoffTeam(
 *     listOf(
 *         TeamAgent("reception", "Greets the customer.", haiku, system = "Find out what the customer needs."),
 *         TeamAgent("billing", "Handles invoices and refunds.", haiku, tools = listOf(refund), system = "You handle invoices."),
 *         TeamAgent("orders", "Knows where an order is.", haiku, tools = listOf(orderStatus), system = "You track orders."),
 *     ),
 * )
 *
 * val state = team.invoke(TeamState("I want my money back for order 7.")).state
 * println("${state.agent}: ${state.answer}")
 *
 * // The next message goes to the agent that has the conversation.
 * val next = team.invoke(state.withUserMessage("How long will it take?")).state
 * ```
 *
 * Each agent gets a tool `transfer_to_<name>` for every other agent. All agents share one
 * conversation, so unlike with a [supervisor], the agent that takes over reads all that was said and
 * done so far, under its own instructions. The run ends when an agent answers
 * without handing over. [TeamState.agent] says who has the conversation.
 *
 * A hand-off changes the conversation in the state, because a model takes the turns of another agent
 * for its own: the tool calls and their results of the agent that hands over become messages of the
 * user that say who did what, in brackets, and the hand-off itself becomes one. It also removes
 * [ChatMessage.Assistant.providerContent], such as Claude's thinking, which a provider ties to the
 * instructions and tools it was written under. The texts of the agents stay.
 *
 * Each agent is a subgraph with its [TeamAgent.name], with the nodes `model` and `tools` of a
 * [toolLoop] and a last node `handover`, so `interruptBefore = setOf("billing/tools")` approves its
 * tool calls by hand. Two
 * agents that keep handing the conversation to each other end at `GraphConfig.maxIterations`, or at
 * a [tokenBudget].
 *
 * @param agents the team. The first one starts the conversation.
 * @param tokenBudget the tokens the whole team may use. It works as in [toolAgent].
 * @throws GraphValidationException if [agents] has fewer than two agents, two agents have the same
 * name, a name cannot be part of a tool name, or [tokenBudget] is less than 1.
 */
public fun handoffTeam(agents: List<TeamAgent>, tokenBudget: Int? = null): CompiledGraph<TeamState> {
    if (agents.size < 2) throw GraphValidationException("A hand-off needs at least two agents, but there were ${agents.size}.")
    if (tokenBudget != null && tokenBudget < 1) {
        throw GraphValidationException("A token budget must be at least 1, but was $tokenBudget.")
    }
    val first = agents.first().name
    val stopped: (TeamState) -> Boolean = { state -> tokenBudget != null && state.tokens.total >= tokenBudget }

    return StateGraph<TeamState> {
        val nodes = agents.associate { agent -> agent.name to subgraph(agent.name, agent.handoffGraph(agents, first, stopped)) }

        conditionalEdge(START, targets = nodes.values.toSet()) { state -> nodes[state.agent ?: first] ?: nodes.getValue(first) }
        nodes.forEach { (name, node) ->
            conditionalEdge(node, targets = nodes.values.toSet() - node + NodeRef.END) { state ->
                val next = state.agent ?: first
                if (next == name) NodeRef.END else nodes.getValue(next)
            }
        }
    }.compile()
}

/** A [toolLoop] of this agent over the shared conversation, which stops when the conversation goes to another agent. */
private fun TeamAgent.handoffGraph(team: List<TeamAgent>, first: String, stopped: (TeamState) -> Boolean): CompiledGraph<TeamState> =
    StateGraph<TeamState> {
        // Here and not in the transfer tool, because the other calls of its answer still have to run.
        val handOver =
            node("handover") { state ->
                val next = state.agent ?: first
                if (next == name) state else state.copy(messages = state.messages.handedOver(name, next))
            }
        handOver then END
        START then
            toolLoop(
                model = model,
                tools = tools,
                messages = { it.messages },
                append = { state, new -> state.copy(messages = state.messages + new) },
                system = system,
                then = handOver,
                stateTools = team.filter { it.name != name }.map { it.transfer() },
                countTokens = { state, used -> state.copy(tokens = state.tokens + used) },
                stopWhen = { state -> (state.agent ?: first) != name || stopped(state) },
                retry = retry,
                shorten = shorten,
            )
    }.compile()

/** The tool that hands the conversation to this agent. */
private fun TeamAgent.transfer(): StateTool<TeamState> =
    StateTool(
        ToolSpec(
            name = "$TRANSFER$name",
            description = "$description Hand the conversation to this agent when the rest of the work is for it. It goes on from there.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
        ),
    ) { state, _ ->
        ToolOutcome(state.copy(agent = name), "$name has the conversation now.")
    }

/**
 * Returns this conversation as the agent [to] reads it after the agent [from] handed it over.
 *
 * A model takes the assistant turns of a conversation for its own. Shown the tool calls of another
 * agent, it tells the user that it made a mistake with a tool that it does not have, also when a
 * message says that it did not make them. So the calls and their results become notes that name the
 * agent. The hand-off leaves only a last note. The texts stay as they are.
 *
 * The notes also leave no tool call behind for another provider to read, and provider content goes,
 * because what a provider wants back, such as Claude's thinking, belongs to the model, instructions
 * and tools that wrote it. Later requests read the same conversation, so nothing else changes.
 */
private fun List<ChatMessage>.handedOver(from: String, to: String): List<ChatMessage> {
    val calls = filterIsInstance<ChatMessage.Assistant>().flatMap { it.toolCalls }.associateBy { it.id }
    val kept =
        mapNotNull { message ->
            when (message) {
                is ChatMessage.User -> message
                is ChatMessage.Assistant -> message.takeIf { it.text.isNotEmpty() }?.copy(toolCalls = emptyList(), providerContent = null)
                is ChatMessage.ToolResult ->
                    message
                        .takeUnless {
                            it.toolName.startsWith(
                                TRANSFER,
                            )
                        }?.asNote(from, calls[message.toolCallId])
            }
        }
    return kept +
        ChatMessage.User(
            "[$from handed this conversation to you, $to. Notes in brackets say what other agents of your team did, not you. Go on from here.]",
        )
}

private fun ChatMessage.ToolResult.asNote(agent: String, call: ToolCall?): ChatMessage.User {
    val outcome = if (isError) "failed" else "returned"
    return ChatMessage.User("[$agent called the tool $toolName with ${call?.input ?: "{}"}, which $outcome: $text]", attachments)
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

/** How the name of a tool that hands the conversation over starts. */
private const val TRANSFER: String = "transfer_to_"

/** The input of the tool that gives an agent a task. */
private const val TASK: String = "task"
