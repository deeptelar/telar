# Testing

A graph is plain Kotlin, so a test runs it like any other code: `graph.invoke(input)`, then compare
the state. A graph that asks a model needs one more thing, a model that answers the same way every
time, without an API key and without a network. `telar-test` has it, and checks for the path a
run takes and for a run that failed in production, run again from its checkpoints:

```kotlin
dependencies {
    testImplementation("dev.deeptelar:telar-test:<version>")
}
```

> `telar-test` is not released yet: it comes with the version after `0.1.0-alpha09`.

It depends on no test library, so it works with `kotlin-test`, JUnit and Kotest, and on every
platform.

## A model that follows a script

`ScriptedChatModel` gives the answers the test wrote down, in order, and keeps every request it
received:

```kotlin
@Test
fun `the agent looks up the order and answers with what it found`() = runTest {
    val model = ScriptedChatModel {
        calls("order_status", OrderLookup(customer = "Ana")) // the first answer asks for a tool
        says("Your pizza is 5 minutes away.")                // the second one is the final text
    }

    val state = helpDeskAgent(model).invoke(AgentState("Where is my pizza?")).state

    assertEquals("Your pizza is 5 minutes away.", state.answer)
    // What the model was asked the second time: the result of the tool is the last message.
    val result = model.requests[1].messages.last() as ChatMessage.ToolResult
    assertEquals("The pizza for Ana left the oven and the driver is 5 minutes away.", result.text)
}
```

The tools are the real ones. The script stands for the model only, so the test checks what your
code does with an answer: which tool runs, what it returns, what ends up in the state.

| In the script | The model |
|---|---|
| `says("Hello!")` | answers with text |
| `calls("menu_price", MenuLookup("cola"))` | asks for one tool, with an instance of its input class |
| `calls(call("menu_price", MenuLookup("cola")), call("opening_hours"))` | asks for several tools in one answer |
| `answers(Verdict(refund = true, reason = "..."))` | answers with the JSON of a value, for a node that calls `ask<Verdict>` |
| `fails("API error 529: Overloaded")` | fails the call with a `ChatModelException` |
| `responds { request -> ChatResponse(...) }` | answers with what the function returns for the request |

`says` and `calls` take `usage = TokenUsage(...)`, the tokens the provider reports for the answer,
and `calls` takes `text = "Let me check."` for what the model writes next to a call.

What to know:

- **A script that is too short fails the test.** A model that is asked once more than its script
  has answers throws an `AssertionError` that says what it was asked. It reaches the test as it
  is, not inside a `NodeExecutionException`.
- **`model.remaining` is what is left of the script.** Compare it with 0 to check that the graph
  asked for every answer.
- **The ids of the tool calls are made for you:** `call-1`, `call-2` and so on.
- **A tool input that does not fit** is written as a `Map` or a `JsonObject` in place of the input
  class: `calls("menu_price", mapOf("dish" to "cola"))`. The tool returns an error to the model, and
  the test checks what happens next.
- **Nodes that run at the same time** should each get a model of their own. With one model for
  both, which node gets which answer is not fixed.

## What a run did

`record()` collects a streamed run and returns what happened in a form a test can compare:

```kotlin
val run = helpDeskAgent(model).stream(AgentState("Where is my pizza?")).record()

assertEquals(listOf("model", "tools", "model"), run.nodes) // the path the run took
assertEquals("Your pizza is 5 minutes away.", run.text)    // what a screen showed
assertEquals("Your pizza is 5 minutes away.", run.state.answer)
```

| Property | What it holds |
|---|---|
| `nodes` | The nodes that finished, in the order they finished |
| `steps` | The nodes of each step. Compare these for a graph with parallel branches, where the order inside a step can change |
| `text` | The text the models wrote, joined as a screen shows it. The text of a try that failed and was tried again is left out |
| `result`, `state` | How the run ended, completed or paused, and its last state |
| `events` | Every event of the run, for everything else |

A scripted model writes a streamed answer word by word, so `text` and
[`textDelta`](streaming.md) are tested with several pieces and no setup.

## The path of a run

`graph.test(input) { ... }` runs a graph and checks, one after the other, what it did: which nodes
finished, where it paused, how it ended. A test of a person who approves a refund reads like the
run:

```kotlin
@Test
fun `a refund waits for a person and is issued on approval`() = runTest {
    val config = GraphConfig<RefundState>(checkpointer = MemoryCheckpointer(), interruptBefore = setOf("issue_refund"))

    refundGraph().test(RefundState(orderId = "1001", amount = 250), config) {
        expectNode("prepare")
        expectInterruptBefore("issue_refund") { state ->
            assertEquals("Prepared refund of 250 for order 1001", state.log.last())
        }
        resumeWith { it.copy(approved = true) }
        expectNode("issue_refund")
        expectFinished { state ->
            assertEquals("Refund issued", state.log.last())
        }
    }
}
```

| Check | What happened next |
|---|---|
| `expectNode("prepare")` | the node `prepare` finished |
| `expectNodes("model", "tools", "model")` | these nodes finished, one after another |
| `expectStep("web", "papers")` | a step ran these nodes, in any order. Use it for [parallel branches](parallel-branches.md) |
| `expectInterruptBefore("issue_refund")` | the run paused, and `issue_refund` runs when it is resumed |
| `expectFinished()` | the run completed |
| `expectFailure()` | the run failed, usually with a `NodeExecutionException` |
| `resumeWith { it.copy(approved = true) }` | not a check: continues the paused run, like `resume` |

Each check takes a function that gets the state at that point, and also returns that state:
`val state = expectFinished()`.

What to know:

- **The checks are strict.** Each one is about what happened right after the one before it, and
  after the last check, the test fails if the run did more: every node that finished, and the pause
  or the end of the run, has to be checked. A check that does not hold fails with the path of the
  run, the part not checked yet in brackets:
  `Expected node "pay" next, but the next was node "check" in step 1. The run so far: [node "check" in step 1], [node "pay" in step 2], [the end of the run]`.
- **A pause is a pause, wherever it comes from.** `expectInterruptBefore("pay")` holds when the run
  paused before `pay` because of `interruptBefore`, after the node before it because of
  `interruptAfter`, or in `pay` because the node called [`interrupt`](human-in-the-loop.md).
- **The test has a checkpointer.** When the config has none, the test adds a `MemoryCheckpointer`,
  so a node can pause the run and `resumeWith` can continue it.
- **The run goes to its next pause or end before the first check.** A check after `expectNode` sees
  everything a scripted model was asked up to that pause, not only up to that node.
- **Not checked:** the events inside a [subgraph](subgraphs.md), retries and progress. Read them
  with `record()`.
- **An `AssertionError` is not a failure of the run.** The error of a scripted model whose script
  is too short fails the test, also in a test that expects a failure.

## A run that failed, run again

The checkpoints of a run are what a test needs to run it again: the state after a step and the
nodes that come next. Copy the checkpoints of a run that went wrong in production into the
resources of the tests, and `graph.replay(checkpoint) { ... }` continues from one of them, with the
same checks as `test`:

```kotlin
@Test
fun `the ticket that failed in production is answered when it runs again`() = runTest {
    val file = checkNotNull(javaClass.getResource("/checkpoints/ticket-42.json")).readText()
    val history = CheckpointCodec<AgentState>().decodeHistory("ticket-42", file)
    val model = ScriptedChatModel { says("Your pizza is 5 minutes away.") }

    helpDeskAgent(model).replay(history.last()) {
        expectNode("model")
        expectFinished { state ->
            assertEquals("Your pizza is 5 minutes away.", state.answer)
        }
    }

    // The model was asked with the conversation of the production run, tool result included.
    val result = model.requests.single().messages.last() as ChatMessage.ToolResult
    assertEquals("The pizza for Ana left the oven and the driver is 5 minutes away.", result.text)
}
```

In production, the model asked for `order_status`, the tool answered, and the second call to the
model failed. A step that fails is not saved, so the last checkpoint is the one after step 2, and
the replay starts with the step that failed. The other checkpoints go back further:
`history.first { it.step == 1 }` runs the tool again.

Where the checkpoints come from:

- **A `FileCheckpointer`** writes a file per thread, `<thread>.json`. Copy it as it is.
- **Another checkpointer** that is built on `CheckpointCodec`, like `LocalStorageCheckpointer`,
  stores the same text.
- **Any checkpointer:** write the history of the thread with the codec, and read it in the test as
  above:
  ```kotlin
  val codec = CheckpointCodec<AgentState>()
  val text = graph.history(config).fold(null as String?) { stored, checkpoint -> codec.append(stored, checkpoint) }
  ```

`replay` runs the graph you give it, which only has to have the nodes of the checkpoint. That is
where the test changes what it needs:

- **The model:** build the graph with a scripted one, as above.
- **A node:** build the graph with the node you fixed, or with a fake for a node that talks to
  another system. A function that builds the graph from what its nodes use, like
  `helpDeskAgent(model)`, makes both a parameter.
- **The state:** `replay(checkpoint, update = { it.copy(approved = true) }) { ... }` changes the
  state of the checkpoint before the run continues.

The run continues like [`fork`](human-in-the-loop.md#going-back-to-an-earlier-step) does, with the step numbers of the run that
saved the checkpoint.

## Failures, retries and budgets

The script says when a call fails, so a test can check a [retry](retries.md):

```kotlin
val model = ScriptedChatModel {
    fails("API error 529: Overloaded")
    says("Hello!")
}

val state = toolAgent(model, retry = Retry(attempts = 2)).invoke(AgentState("Hi")).state

assertEquals("Hello!", state.answer)
```

In a streamed run, `fails("The connection broke.", partialText = "Your pizza")` writes the partial
text before it fails, as a connection that breaks in the middle of an answer does.

And it says what an answer cost, so a test can check a
[token budget](agents-with-tools.md):

```kotlin
val model = ScriptedChatModel {
    calls("order_status", OrderLookup(customer = "Ana"), usage = TokenUsage(inputTokens = 900, outputTokens = 200))
    says("Never said.")
}

val state = toolAgent(model, tools = listOf(orderStatus), tokenBudget = 1000).invoke(AgentState("Where is my pizza?")).state

assertNull(state.answer)           // the agent stopped before the second answer
assertEquals(1, model.remaining)
```

## A model that decides

`ScriptedDecisionModel` is the same for a [decision model](decision-models.md):

```kotlin
val model = ScriptedDecisionModel {
    chooses("refund")                       // the decision for the first request
    chooses("technical", confidence = 0.3)  // the model picks one, and is not sure
}
val support = routedSupport(model)

val sure = support.invoke(SupportEmail(sender = "Ana", body = "I want my money back.")).state
val unsure = support.invoke(SupportEmail(sender = "Ben", body = "Hm.")).state

assertEquals(Category.REFUND, sure.category)
assertEquals(Category.ESCALATION, unsure.category) // below minConfidence, a person reads it
```

| In the script | The model |
|---|---|
| `chooses("refund")` | picks an option. With a `decisionEdge`, the option is the name of a node |
| `yes()`, `no()` | answers a yes-or-no question. `yes(probability = 0.7)` is a yes the model is not sure of |
| `scores(2.0)` | rates on a scale, counted from 0 |
| `fails("API error 429: slow down")` | fails the call with a `DecisionModelException` |
| `decides { request -> DecisionResponse(...) }` | answers with what the function returns, for a request with several questions |

A decision of another kind than the question, such as `yes()` for a choice, fails the test with an
`AssertionError`.

## Without the module

`ChatModel` and `DecisionModel` are one function each, so a model for a single test is also a
lambda: `ChatModel { request -> ChatResponse(ChatMessage.Assistant("Hello!")) }`.

Runnable versions: [`TestingAnAgentTest`](https://github.com/deeptelar/telar/blob/main/samples/src/test/kotlin/dev/deeptelar/telar/samples/TestingAnAgentTest.kt)
and [`TestingAGraphTest`](https://github.com/deeptelar/telar/blob/main/samples/src/test/kotlin/dev/deeptelar/telar/samples/TestingAGraphTest.kt),
with the checkpoint file it replays.
