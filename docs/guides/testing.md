# Testing

A graph is plain Kotlin, so a test runs it like any other code: `graph.invoke(input)`, then compare
the state. A graph that asks a model needs one more thing, a model that answers the same way every
time, without an API key and without a network. `telar-test` has it:

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

Runnable version: [`TestingAnAgentTest`](https://github.com/deeptelar/telar/blob/main/samples/src/test/kotlin/dev/deeptelar/telar/samples/TestingAnAgentTest.kt).
