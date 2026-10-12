# Cheat sheet

Every word and every move of the [tutorial](./) on one page.

## Words

| Word | Meaning | Level |
|---|---|---|
| Workflow | A job done in several pieces of work, in an order that can depend on the results | [The idea](00-the-idea.md) |
| Graph | The map of a workflow: its nodes and edges | [The idea](00-the-idea.md) |
| State | The data of one job, a `data class` you design. It is passed from node to node. | [The idea](00-the-idea.md) |
| Node | A named function that receives the state and returns an updated copy | [1](01-a-line-of-nodes.md) |
| Edge | An arrow that says what runs after a node | [1](01-a-line-of-nodes.md) |
| `START`, `END` | Where every run begins and finishes. They are not nodes you write. | [1](01-a-line-of-nodes.md) |
| Compile | Check the map and turn it into a graph you can run | [1](01-a-line-of-nodes.md) |
| Run | One trip through the graph, from `START` until `END` or a pause | [1](01-a-line-of-nodes.md) |
| Conditional edge | An arrow that picks the next node by looking at the state | [2](02-choices.md) |
| Router | The function of a conditional edge. It returns the name of the next node, or `END`. | [2](02-choices.md) |
| Step | One round of the engine: run the current nodes, then follow their arrows | [3](03-loops.md) |
| Fan-out | Several ordinary arrows from one place. Their nodes run at the same time, in one step. | [4](04-two-things-at-once.md) |
| `work` and `update` | The two parts of a node that can run next to others: `work` returns a result, `update` writes it into the state | [4](04-two-things-at-once.md) |
| Reducer | A function that merges whole states. Only needed when two nodes that each return a whole state run at the same time. `mergeRules { append(...); replace(...) }` builds one from a rule for each property. | [7](07-game-over-screens.md) |
| Event | A message about a run in progress: a node started, a step finished, ... | [4](04-two-things-at-once.md) |
| Checkpoint | A save: the state, and which nodes come next | [5](05-save-points.md) |
| Checkpointer | Where checkpoints are stored (memory, files, a browser, your own storage) | [5](05-save-points.md) |
| Thread | One job with its own saves, named by a `threadId` | [5](05-save-points.md) |
| Interrupt | A pause before or after a node, usually to wait for a human | [5](05-save-points.md) |
| Human-in-the-loop | A workflow in which a person decides something before it continues | [5](05-save-points.md) |
| Prompt | The text sent to an AI model | [6](06-the-agent.md) |
| Tool | A function the model may ask your program to run: a name, a description and an input class | [6](06-the-agent.md) |
| Agent | A model that decides by itself which tools to call, and how often, before it answers | [6](06-the-agent.md) |

## Building a graph

```kotlin
val graph = StateGraph<Ticket> {
    // A node: a name and a function from state to state.
    val read = node("read") { ticket -> ticket.copy(topic = topicOf(ticket.message)) }
    val answer = node("answer") { ticket -> ticket.copy(reply = "...") }
    val refund = node("refund") { ticket -> ticket.copy(reply = "...") }

    // Arrows that are always followed.
    START then read
    answer then END

    // An arrow that decides. The router returns the next node, or NodeRef.END to finish.
    conditionalEdge(read, targets = setOf(answer, refund)) { ticket ->
        if (ticket.topic == "refund") refund else answer
    }
}.compile()
```

| You want | You write |
|---|---|
| A line | `START then a then b then END` |
| A choice | `conditionalEdge(a, targets = setOf(b, c)) { state -> ... }` |
| A loop | A conditional edge whose router can return an earlier node, with a limit |
| Parallel nodes | Several arrows from one place, to nodes written as `node("a", work = { ... }) { state, result -> ... }` |
| An arrow by name | `edge("a", "b")` |
| A node that is tried again when it fails | `node("a", retry = Retry(attempts = 3)) { ... }` |
| A node with a time limit | `node("a", timeout = 30.seconds) { ... }` |
| A choice by name | `conditionalEdge("a", targets = setOf("b", END)) { state -> "b" }` |

## A graph inside a graph

```kotlin
// payoutGraph is a CompiledGraph<Payout>. It runs from its START to its END as one node of this graph.
val payout = subgraph(
    "payout", payoutGraph,
    state = { ticket -> ticket.payout },                             // the subgraph's state, read from this graph's
    update = { ticket, payout -> ticket.copy(payout = payout) },     // and written back
)
```

A node of the subgraph can pause the run with `interrupt`, and `resume` continues inside the
subgraph. A stream has the events of the subgraph as `GraphEvent.SubgraphEvent`, and
`topology.subgraphs` has its nodes and edges. With the same state class on both sides: `subgraph("payout", payoutGraph)`.

## Running a graph

| Call | What it does |
|---|---|
| `graph.invoke(input)` | A new run. Returns when the run finishes or pauses. |
| `graph.invoke(input, config)` | The same, with settings |
| `graph.stream(input, config)` | A new run as a `Flow` of events |
| `graph.stream(input).states()` | Only the state after each step |
| `graph.resume(config) { state -> ... }` | Continue the saved run of a thread, after editing its state |
| `graph.streamResume(config) { ... }` | `resume` as a `Flow` of events |
| `graph.lastResult(config)` | Where a thread stopped, without running anything. `null` if it never ran. |

`invoke`, `resume` and `lastResult` return a `GraphResult`:

```kotlin
when (result) {
    is GraphResult.Completed -> result.state                // reached END
    is GraphResult.Interrupted -> result.nextNodes          // paused; resume() continues
}
```

## Settings of a run

```kotlin
val config = GraphConfig(
    threadId = "ticket-42",                        // the save slot; one per job
    checkpointer = MemoryCheckpointer<Ticket>(),   // where saves are stored
    interruptBefore = setOf("pay"),                // pause before these nodes
    interruptAfter = setOf("draft"),               // pause after these nodes
    maxIterations = 25,                            // step limit of one invoke or resume
)
```

Pauses need a checkpointer.

A thread keeps the checkpoint of every step. `graph.history(config)` lists them, and
`graph.fork(checkpoint, config.copy(threadId = "another")) { it.copy(...) }` continues from one on a
new thread.

A node can also pause the run itself, in the middle of its work. `resume` then runs that node again
from its first line:

```kotlin
val pay = node("pay") { ticket ->
    // Saves this state and stops the run here. The answer comes back in the state.
    if (ticket.approved == null) interrupt(ticket.copy(question = "Pay ${ticket.refund} euros?"))
    ticket.copy(question = null, paid = ticket.approved)
}
```

## Events

| Event | When |
|---|---|
| `GraphEvent.NodeStarted` | A node is about to run |
| `GraphEvent.NodeProgress` | A running node called `reportProgress(value)` |
| `GraphEvent.SubgraphEvent` | Something happened in the subgraph a node runs: `event.event` is the event of that graph |
| `GraphEvent.NodeRetry` | A node with a `retry` failed and will run again after `event.pause` |
| `GraphEvent.NodeCompleted` | A node finished |
| `GraphEvent.StepCompleted` | All nodes of a step finished |
| `GraphEvent.Interrupted` | The run paused. Last event. |
| `GraphEvent.Completed` | The run reached `END`. Last event. |

## A model that decides

From `telar-agent`, with Jev in `telar-typesafe`. The guide is
[Decision models](../guides/decision-models.md).

```kotlin
val jev: DecisionModel = TypeSafeDecisionModel(HttpClient(), apiKey = key)

// The model picks the next node. Below minConfidence the run goes to the fallback.
decisionEdge(classify, jev, "What does the customer want?",
    routes = mapOf(refund to "Money back", technical to "Something does not work", escalate to "Anything else"),
    minConfidence = 0.6, fallback = escalate,
) { ticket -> ticket.message }

// One question, in a node.
jev.choose(text, "Which team?", mapOf("refund" to null, "technical" to null)).option
jev.isYes(text, "Is this urgent?").isYes
jev.score(text, "How angry?", listOf("Calm", "Annoyed", "Furious")).score
```

## An agent with tools

From `telar-agent`. [Level 6](06-the-agent.md) teaches it, and the guide
[Agents with tools](../guides/agents-with-tools.md) is the short version.

```kotlin
@Serializable
data class MenuLookup(@Description("The item, for example \"cola\"") val item: String)

// A tool: a name, a description for the model, and a function that returns text.
val menuPrice = Tool<MenuLookup>("menu_price", "Returns the price of an item.") { lookup -> priceOf(lookup.item) }

// A graph of two nodes, "model" and "tools", that loops until the model has its answer.
val agent = toolAgent(model, tools = listOf(menuPrice), system = "You work at a pizzeria.")

val state = agent.invoke(AgentState("How much is a cola?")).state
state.answer                                 // the model's final text
agent.invoke(state.withUserMessage("And two?"))  // the next turn of the conversation
```

| You want | You write |
|---|---|
| A model | `AnthropicChatModel(...)`, `OpenAiChatModel(...)`, `OpenAiChatModel.ollama(...)`, `LangChain4jChatModel(...)`, or `ChatModel { request -> ... }` |
| One text from a model, in any node | `model.chat("...")` |
| A value of your own class from a model | `model.ask<Verdict>("...")`, with `Verdict` a `@Serializable` class |
| The agent inside your own graph | `toolLoop(model, tools, messages = { ... }, append = { state, new -> ... })` |
| A tool that reads and changes the state | `StateTool<Order, NewLine>("add_line", "...") { order, line -> ToolOutcome(order.copy(...), "Added.") }` in `toolLoop(..., stateTools = listOf(...))` |
| Tool calls of one answer in order | `toolLoop(..., inOrder = true)`; always with `stateTools` |
| What an agent cost | `state.tokens` of an `AgentState`; in `toolLoop`, `countTokens = { state, used -> state.copy(tokens = state.tokens + used) }` |
| A limit on what an agent may use | `toolAgent(model, tools, tokenBudget = 50_000)`; in `toolLoop`, `stopWhen = { it.tokens.total >= 50_000 }` |
| An agent that asks the model again after a failed call | `toolAgent(model, tools, retry = Retry(attempts = 3))`; the same in `toolLoop` |
| A conversation that starts from other fields of the state | `toolLoop(..., firstMessage = { state -> "..." })` |
| The answer while the model writes it | `agent.stream(input).collect { event -> event.textDelta?.let(::print) }` |
| The same in a node of your own | `model.chatWithProgress("...")` in place of `model.chat("...")` |
| To approve tool calls | `interruptBefore = setOf("tools")`, then `state.messages.pendingToolCalls()` |
| A model for a test | `ScriptedChatModel { calls("menu_price", MenuLookup("cola")); says("2 euros.") }` from `telar-test` |
| What a run did, in a test | `agent.stream(input).record()`, then `run.nodes`, `run.text`, `run.state` |

## Rules

1. State is a `data class` with `val` fields. Nodes return `state.copy(...)` and never change the
   state they receive.
2. Nodes do not call each other. They communicate through the state.
3. A router decides and does not change the state.
4. A node has either ordinary arrows or one conditional edge.
5. Every loop needs a limit.
6. Nodes that run in parallel have a `work` and an `update`. Slow calls and side effects go in
   `work`; the update only builds the new state.
7. `invoke` starts over. `resume` continues.

## Errors

All are `TelarException`s. [Level 7](07-game-over-screens.md) explains each one.

| Exception | Meaning |
|---|---|
| `GraphValidationException` | The graph or the settings are wrong. Found before anything runs. |
| `NodeExecutionException` | A node threw an exception |
| `EdgeConditionException` | A router threw an exception |
| `ReducerException` | The reducer threw an exception |
| `InvalidRouteException` | A router returned a name that is not allowed |
| `MaxIterationsExceededException` | The run took more steps than `maxIterations` |
| `CheckpointNotFoundException` | `resume` found no save |
| `GraphAlreadyCompletedException` | `resume` found a finished run |
| `CheckpointCorruptedException` | A save cannot be read |
| `ChatModelException` | A call to a model failed. In a run, it is the `cause` of a `NodeExecutionException` |
