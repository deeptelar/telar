# Agents with tools

An agent is a model that decides by itself which of your functions to call, and how often, before
it answers. In a graph that is a loop of two nodes: the model answers or asks for tools, the tools
run, and their results go back to the model. `telar-agent` has this loop ready-made.

A **tool** is a function with a name and a description that the model reads. Its input is a
`@Serializable` class, from which the library builds the schema the model needs:

```kotlin
@Serializable
data class MenuLookup(
    @Description("The item, for example \"margherita\"") val item: String,
)

val menuPrice = Tool<MenuLookup>("menu_price", "Returns the price of one item on the menu.") { lookup ->
    // Whatever your app does: a database, an HTTP call, a calculation. The model gets the text you return.
    val price = menu[lookup.item] ?: throw IllegalArgumentException("We do not sell ${lookup.item}.")
    "One ${lookup.item} costs $price euros."
}
```

`toolAgent` returns a graph like any other, so `invoke`, `stream`, checkpoints and pauses all work:

```kotlin
val agent = toolAgent(model, tools = listOf(menuPrice, orderStatus), system = "You work at the help desk of a pizzeria.")

val first = agent.invoke(AgentState("How much is a margherita?")).state
println(first.answer) // A margherita costs 9 euros.

// The state holds the conversation. Add the next message to continue it.
val second = agent.invoke(first.withUserMessage("And a cola?")).state
```

`invoke` starts a new run each time, so a chat passes the conversation so far as its input. With a
checkpointer, `lastResult` gives the state the previous turn ended with:

```kotlin
suspend fun send(question: String): AgentState {
    // The conversation of the previous turns, or an empty one on the first turn.
    val history = agent.lastResult(config)?.state ?: AgentState()
    return agent.invoke(history.withUserMessage(question), config).state
}
```

To show the answer while the model writes it, collect the run with `stream`. The model node
reports each piece of text, and `textDelta` reads it from the event:

```kotlin
agent.stream(AgentState("How much is a margherita?")).collect { event ->
    event.textDelta?.let { piece -> print(piece) }                // A, margherita, costs, ...
    if (event is GraphEvent.Completed) println()                  // event.state.answer is the whole text
}
```

What to know:

- **The model streams only when someone watches.** A run started with `stream` asks the model with
  `ChatModel.stream`; a run started with `invoke` asks for the whole answer at once.
- **Text before a failure is not an answer.** When a model call fails or the model declines in the
  middle of its answer, the run fails after the pieces that already arrived. Clear them.
- **Tools of one answer run at the same time.** If a tool throws, or the model sends input that does
  not fit, the run goes on: the model gets the error as the result and can try again.
- **Every round of tools is two steps.** Raise `GraphConfig.maxIterations` (25 by default) for an
  agent that needs more than twelve rounds.
- **An answer can be cut off.** When the model reaches its output limit in the text of its answer,
  the run ends with what it wrote, and `state.answerTruncated` is `true` (`truncated` on the
  `ChatMessage.Assistant`). When it reaches the limit in a tool call, the run fails.
- **`AgentState` is `@Serializable`**, so `KotlinxStateSerializer` and `FileCheckpointer` can save it.

To let a person approve the tool calls, pause before the node that runs them. It is named `tools`:

```kotlin
val config = GraphConfig(threadId = "ticket-42", checkpointer = MemoryCheckpointer<AgentState>(), interruptBefore = setOf("tools"))

val paused = agent.invoke(AgentState("Refund my last order"), config)
// The calls that wait for a yes: their names and inputs.
val waiting = paused.state.messages.pendingToolCalls()

// Yes: run them.
agent.resume(config)
// No: answer the call yourself. A call that already has a result is not run.
agent.resume(config) { state ->
    state.copy(messages = state.messages + waiting.map { ChatMessage.ToolResult(it.id, it.name, "The reviewer said no.", isError = true) })
}
```

`toolAgent` is a whole graph. To make the agent one part of a larger graph, with the conversation
in a state of your own, add the same loop with `toolLoop`:

```kotlin
data class Ticket(val messages: List<ChatMessage>, val reply: String = "")

val graph = StateGraph<Ticket> {
    val send = node("send") { it.copy(reply = it.messages.last().text) }
    val agent = toolLoop(
        model = model,
        tools = listOf(menuPrice, orderStatus),
        // Where the conversation is in your state, and how to add messages to it.
        messages = { it.messages },
        append = { ticket, new -> ticket.copy(messages = ticket.messages + new) },
        // Where the graph goes when the model has its answer. The default is END.
        then = send,
    )

    START then agent
    send then END
}.compile()
```

`messages` must return what `append` stored. When the conversation starts from other fields of your
state, give the first message to `firstMessage` instead of building it in `messages`. The loop uses
it while the conversation is empty and stores it with the model's first answer:

```kotlin
data class Order(val customer: String, val question: String, val messages: List<ChatMessage> = emptyList())

toolLoop(
    model = model,
    tools = listOf(menuPrice, orderStatus),
    messages = { it.messages },
    append = { order, new -> order.copy(messages = order.messages + new) },
    firstMessage = { "${it.customer} writes: ${it.question}" },
)
```

Runnable version: [`ToolAgent`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/ToolAgent.kt). It
runs without an API key, with Claude when `ANTHROPIC_API_KEY` is set, with OpenAI when
`OPENAI_API_KEY` is set, and with a model of Ollama when `OLLAMA_MODEL` names one.
`ANTHROPIC_MODEL` names another model than `claude-opus-5-5` and `OPENAI_MODEL` another than
`gpt-5`. `OPENAI_BASE_URL` names another server with the API of OpenAI, such as Gemini or Groq. With
Gemini:

```bash
OPENAI_API_KEY=your-gemini-key OPENAI_MODEL=gemini-3.8-flash \
  OPENAI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai ./gradlew :samples:runToolAgent
```

[Level 6 of the tutorial](../tutorial/06-the-agent.md) explains the same agent step by step.
