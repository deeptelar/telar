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
  not fit, the run goes on: the model gets the error as the result and can try again. Input that is
  not JSON at all does not reach the tool: the error quotes what the model sent. For tools
  that depend on each other, `toolAgent(model, tools, inOrder = true)` runs them one after the
  other, in the order the model wrote them.
- **Every round of tools is two steps.** Raise `GraphConfig.maxIterations` (25 by default) for an
  agent that needs more than twelve rounds.
- **An answer can be cut off.** When the model reaches its output limit in the text of its answer,
  the run ends with what it wrote, and `state.answerTruncated` is `true` (`truncated` on the
  `ChatMessage.Assistant`). When it reaches the limit in a tool call, the run fails.
- **`AgentState` is `@Serializable`**, so `KotlinxStateSerializer` and `FileCheckpointer` can save it.

A tool can return a file with its text, such as the screenshot a browser tool took. It passes the
file to `attach`, and the model gets it with the result:

```kotlin
val screenshot = Tool<Page>("screenshot", "Takes a screenshot of a web page.") { page ->
    attach(Attachment.of(browser.screenshot(page.url), "image/png"))
    "The screenshot of ${page.url}."
}
```

The model classes send images and PDFs; see [Images and PDFs](ai-models.md#images-and-pdfs). The
Chat Completions API takes text only in the result of a tool, so `OpenAiChatModel` sends the files
of the results in a user message right after them.

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

A tool that pays, books or sends runs once for each call, also when its step runs again. The node
that runs the tools saves the result of each call as soon as the tool returns, as an
[`effect`](side-effects.md) named after the id of the call. When the model asks for `charge_card`
and `send_receipt` in one answer, the card is charged, and then the step stops before it ends,
because another tool called `interrupt`, another node of the step failed or the process stopped,
`resume` gives `charge_card` its saved result and runs only `send_receipt`:

```kotlin
val config = GraphConfig(threadId = "order-7", checkpointer = FileCheckpointer(Path("checkpoints"), KotlinxStateSerializer<AgentState>()))

agent.invoke(AgentState("Pay my order and send me the receipt."), config) // charges, then the process stops
agent.resume(config) // sends the receipt; the card is not charged again
```

- **A call with an error result is not saved.** It runs again with the step, as a call that never
  ran. A tool that can fail after it charged returns its text instead of throwing, so the model
  learns what happened.
- **A `StateTool` is not saved this way**, because what it did is in the state. Wrap what it does
  outside the run, such as a call to a payment service, in `effect` inside the tool.
- **The results belong to the step.** In the next round the tools node runs in a new step, and
  new calls run as usual.
- **A crash between the end of a tool and the save can still repeat it**, as for any `effect`.
  For a tool that must never run twice, give it a `reconcile`, as below.

A tool with a `reconcile` also saves that its call started, with an id that `effectId()` (in
`dev.deeptelar.telar`) returns
while the tool runs. Pass that id to the service as its idempotency key. When the step runs again
and finds the call started but without a result, `reconcile` is asked first: it returns the text
for the model when the work was done, or `null` when it was not, and the tool runs again with the
same id:

```kotlin
@Serializable
data class Charge(val amount: Int)

val charge = Tool<Charge>(
    "charge_card",
    "Charges the card of the customer.",
    reconcile = { input -> payments.findByIdempotencyKey(effectId()!!)?.let { "Charged ${it.amount} euros." } },
) { input ->
    val receipt = payments.charge(input.amount, idempotencyKey = effectId()!!)
    "Charged ${receipt.amount} euros."
}
```

- **With an idempotency key, `reconcile = { null }` is enough.** The tool runs again with the same
  key, and the provider answers with the first charge.
- **A `reconcile` that cannot tell throws.** The run fails, and the next `resume` asks again. A
  person who checked answers the call instead, and a call with a result is not run:

  ```kotlin
  agent.resume(config) { state ->
      val call = state.messages.pendingToolCalls().single { it.name == "charge_card" }
      state.copy(messages = state.messages + ChatMessage.ToolResult(call.id, call.name, "Charged 20 euros."))
  }
  ```

- **Do not use the id of the call as the key.** Some providers number the calls of every answer
  from `call_0`, and a thread that is invoked again starts its steps again, so the provider would
  answer a new charge with an old one. The id of `effectId()` is new for every call that starts.

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

A tool gets the input the model sent and returns a text. A tool that acts on the state of the
run, one that adds a line to an order, books a seat or moves a piece, is a `StateTool`. It gets the
state as well, and returns the state it leaves with the text for the model:

```kotlin
data class Order(val messages: List<ChatMessage>, val lines: List<String> = emptyList())

@Serializable
data class NewLine(@Description("The item, for example \"margherita\"") val item: String)

val addLine = StateTool<Order, NewLine>("add_line", "Adds a line to the order.") { order, line ->
    if (line.item !in menu) throw IllegalArgumentException("We do not sell ${line.item}.")
    ToolOutcome(order.copy(lines = order.lines + line.item), "Added ${line.item}. The order has ${order.lines.size + 1} lines.")
}

val graph = StateGraph<Order> {
    START then toolLoop(
        model = model,
        tools = listOf(menuPrice),    // tools that only need their input
        stateTools = listOf(addLine), // tools that read and change the state
        messages = { it.messages },
        append = { order, new -> order.copy(messages = order.messages + new) },
    )
}.compile()

graph.invoke(Order(listOf(ChatMessage.User("A margherita and a cola, please.")))).state.lines // [margherita, cola]
```

What the tool did is in the state: a checkpoint holds it, `history` and `fork` go back before it,
and the next node reads it. What changes in a loop with state tools:

- **The calls of one answer run one after the other**, in the order the model wrote them, and each
  call gets the state the call before it left. "Make the pizza, then bake it" works in one answer.
- **A call that fails changes nothing.** The model gets the error, and the calls after it still
  run, so a tool that depends on an earlier one checks in the state whether that one happened.
- **A state tool can ask a person.** It calls `interrupt` with the state it received, which has
  what the calls before it did, with their results. `resume` continues with the call that paused,
  and the calls before it are not run again. See [Human-in-the-loop](human-in-the-loop.md).
- **`append` is called for each result**, and not once for all results of an answer.
- **The node that runs the tools returns a whole state.** When it can run in the same step as
  another node that returns a whole state, `compile()` asks for a reducer, as the guide
  [Parallel branches](parallel-branches.md) explains. Next to nodes with a `work` and an `update`
  it needs none.

An agent sends the whole conversation with every request, so a long run costs more with each
round. The state of a `toolAgent` counts the tokens of the conversation, and `tokenBudget` limits
them:

```kotlin
val agent = toolAgent(model, tools = listOf(menuPrice, orderStatus), tokenBudget = 50_000)

val state = agent.invoke(AgentState("Where is my pizza, and how much is a cola?")).state
println("${state.tokens.total} tokens: ${state.tokens.inputTokens} read, ${state.tokens.outputTokens} written")
if (state.answer == null) println("The agent used its budget before it had an answer.")
```

In a `toolLoop`, the count is in your state. `countTokens` adds the tokens of each answer to it,
and `stopWhen` is asked before each call to the model:

```kotlin
data class Order(val messages: List<ChatMessage>, val tokens: TokenUsage = TokenUsage())

toolLoop(
    model = model,
    tools = listOf(menuPrice, orderStatus),
    messages = { it.messages },
    append = { order, new -> order.copy(messages = order.messages + new) },
    countTokens = { order, used -> order.copy(tokens = order.tokens + used) },
    stopWhen = { order -> order.tokens.total >= 50_000 },
    then = send,
)
```

What to know:

- **A run that was stopped is complete.** The model is not asked again, the graph continues at
  `then`, and the result is a `GraphResult.Completed`. The conversation ends with the results of
  the last tools and has no final answer. To go on, invoke the graph again with that state.
- **The limit is checked with real numbers.** They are the tokens the provider reported for the
  calls so far, so a run ends up to one call above its limit. A provider that reports no usage
  adds nothing.
- **The count belongs to the conversation.** A chat that continues with `withUserMessage` keeps
  counting. For a budget per question, start with `state.copy(tokens = TokenUsage())`.
- **`stopWhen` is not only for tokens.** It reads the state, so it ends a loop after a number of
  rounds or at a time of day as well.
- **Parallel branches and subgraphs add up.** Two loops in parallel branches add to the same
  property, and a subgraph hands its count to the graph around it in the `update` of `subgraph`.
- **A node of your own counts with `+`.** `chat(ChatRequest)` and `ask(ChatRequest)` return the
  usage of their calls: `order.copy(tokens = order.tokens + response.usage)`.

A call to the model that fails ends the run: the provider is overloaded, or the connection breaks.
With a `retry`, the agent asks the model again first:

```kotlin
val agent = toolAgent(model, tools = listOf(menuPrice, orderStatus), retry = Retry(attempts = 3))
```

- **A call that failed leaves nothing behind.** It adds no message to the conversation and no
  tokens to the count.
- **The tools are not tried again.** A tool that fails returns an error to the model, which can
  call it again.
- **In a streamed run the text starts again.** The pieces of a try that failed have already
  arrived. The stream then has a `GraphEvent.NodeRetry` for the model node, so a screen clears the
  text it showed.
- `toolLoop` takes the same `retry`. [Retries and time limits](retries.md) explains the pauses
  and how to pick the failures that are tried again.

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

The tools of an agent can also come from a server someone else wrote: see
[Tools from MCP servers](mcp.md).

[Level 6 of the tutorial](../tutorial/06-the-agent.md) explains the same agent step by step, and
[Testing](testing.md) shows how to test one without an API key.
