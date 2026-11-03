# Teams of agents

A team splits the work of one agent: each agent has its own instructions and tools. There are two
ready-made shapes:

- **A [supervisor](#a-supervisor)** gives each part of a task to the agent that fits it, reads what
  comes back, and answers. The agents see only their task.
- **[Hand-offs](#hand-offs)** pass the whole conversation from agent to agent, as a call is passed
  between departments. The last agent answers.

## A supervisor

```kotlin
val team = supervisor(
    model = opus,
    system = "You answer the customers of a pizza shop.",
    agents = listOf(
        TeamAgent("orders", "Knows where an order is.", haiku, tools = listOf(orderStatus)),
        TeamAgent("menu", "Knows the dishes and their prices.", haiku, tools = listOf(menuPrice)),
    ),
)

val state = team.invoke(TeamState("Where is my pizza, and what does a calzone cost?")).state
println(state.answer)

// The conversation goes on with the supervisor.
val next = team.invoke(state.withUserMessage("And a margherita?")).state
```

`supervisor` returns a `CompiledGraph<TeamState>`, so everything a graph does works here: a
checkpointer, `stream`, `toMermaid`. To make a team one step of a larger graph, add it with
[`subgraph`](subgraphs.md).

### What each one sees

The supervisor gives a task with a tool call, one tool per agent: `ask_orders`, `ask_menu`.

- **An agent sees only its task**, in a conversation of its own. The tool tells the supervisor to put
  in the task all that the agent needs to know. Say in `system` what your agents need when it is
  not obvious.
- **The supervisor sees only the answer** of the agent, as the result of its tool call. That keeps
  what it reads small, however many tools the agent called.
- **Each task is a new conversation.** An agent does not remember its earlier tasks.
- **The state keeps all of it.** `state.assignments` has one `Assignment` per task, with the agent's
  whole conversation in `conversation`:

  ```kotlin
  state.assignments.forEach { println("${it.agent}: ${it.conversation.answer}") }
  ```

Tasks of one answer for different agents run at the same time. Tasks for the same agent run one
after the other. A supervisor can also give a task, read the answer and give the next one.

### Tokens

`state.tokens` counts the supervisor, and each agent when it finishes, and `conversation.tokens` of an
`Assignment` counts one task. `tokenBudget` limits the whole team:

```kotlin
val team = supervisor(model = opus, agents = agents, tokenBudget = 200_000)

val state = team.invoke(TeamState("Plan a week in Portugal.")).state
if (state.answer == null) println("The team used its budget before it had an answer.")
```

An agent gets what is left of the budget when it starts. An agent that reaches it stops, and the
supervisor reads an error in place of its answer. Tasks that run at the same time do not see each
other's tokens, so together they can go above the budget.

An [`AgentObserver`](tracing.md) is told about every call to a model and a tool. For an agent, `node.run.parent`
of the call is the node of that agent, and `null` for the supervisor.

### Stop and continue inside an agent

Each agent is a [subgraph](subgraphs.md) with its name, with the nodes `model`, `tools` and `finish`.
A run pauses and continues there as in any subgraph, and a checkpointer holds the agent's work:

```kotlin
// A person approves the tool calls of the agent "orders".
val config = GraphConfig("ticket-7", checkpointer, interruptBefore = setOf("orders/tools"))

val paused = team.invoke(TeamState("Cancel my order."), config)
if (paused is GraphResult.Interrupted) {
    val waiting = paused.state.assignments.last().conversation.messages.pendingToolCalls()
    if (approvedByPerson(waiting)) team.resume(config) { it }
}
```

A call to a model that fails ends the run, and `resume` continues inside the agent that failed. Give
each `TeamAgent` a `retry` and the `supervisor` its own to try again first. `shorten` works the same
way, on each conversation of its own. See [Agents with tools](agents-with-tools.md).

`GraphConfig.maxIterations` limits the steps of the team, and the steps of an agent on their own for
each task. Each round of tools takes an agent two steps.

### Show it on a screen

In a stream, the events of an agent arrive inside a `GraphEvent.SubgraphEvent` that names it. Its text
is in `textDelta` too, so tasks that run at the same time mix. To show only the supervisor's answer,
leave those events out:

```kotlin
team.stream(TeamState("Where is my pizza?")).collect { event ->
    when {
        event is GraphEvent.SubgraphEvent -> showWorking(event.node)
        else -> event.textDelta?.let(::print)
    }
}
```

## Hand-offs

Each agent can end its turn by handing the conversation to another agent, which goes on from there.
The first agent of the list starts:

```kotlin
val team = handoffTeam(
    listOf(
        TeamAgent("reception", "Greets the customer.", haiku, system = "Find out what the customer needs."),
        TeamAgent("billing", "Handles invoices and refunds.", haiku, tools = listOf(refund), system = "You handle invoices."),
        TeamAgent("orders", "Knows where an order is.", haiku, tools = listOf(orderStatus), system = "You track orders."),
    ),
)

val state = team.invoke(TeamState("I want my money back for order 7.")).state
println("${state.agent}: ${state.answer}")

// The next message goes to the agent that has the conversation.
val next = team.invoke(state.withUserMessage("How long will it take?")).state
```

- **A hand-off is a tool call.** Each agent gets a tool `transfer_to_<name>` for every other agent,
  with the `description` of that agent.
- **All agents share one conversation.** The agent that takes over reads all that was said and done
  so far, under its own `system` and with its own tools.
- **The run ends when an agent answers** without handing over. `state.agent` says who has the
  conversation, and is `null` while it is still with the first agent.
- **A hand-off changes the conversation in the state.** A model takes the turns of another agent for
  its own: shown a tool call that it does not recognize, it tells the customer that it made a mistake.
  So the tool calls and results of the agent that hands over become messages in brackets, such as
  `[reception called the tool lookup with {"order":7}, which returned: Paid.]`, and the hand-off
  becomes one too. What only a provider can read goes as well, such as Claude's thinking, because the
  provider ties it to the instructions and tools it was written under. The agents' texts stay.

`tokenBudget`, `state.tokens` and a stream work as for a supervisor. Each agent is a subgraph here too,
with the nodes `model`, `tools` and `handover`, so `interruptBefore = setOf("billing/tools")` approves
its calls.

Two agents can keep handing the conversation to each other. That ends at `GraphConfig.maxIterations`
with a `MaxIterationsExceededException`, or at the `tokenBudget`.

## Which one to use

Use a supervisor when a task has parts that one model can plan and put together, such as research
for a report. What an agent reads stays small, and agents can work at the same time.

Use hand-offs when the agent that fits changes during a conversation with a person, such as customer
support. Each agent needs the conversation so far, so what each one reads grows with it, and only
one works at a time.

## Test a team

A team needs no API key in a test: give each model a `ScriptedChatModel` of `telar-test`. A
supervisor's script gives a task with a tool call to `ask_<name>` with the input `task`, and a
hand-off is a tool call to `transfer_to_<name>` without input. See [Testing](testing.md).
