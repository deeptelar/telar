# Retries and time limits

A call to a model or to a service fails now and then, and sometimes it does not come back at all.
A node says how often it may be tried again, and how long one try may take:

```kotlin
val stock = node("stock", retry = Retry(attempts = 3), timeout = 30.seconds) { order ->
    order.copy(stock = warehouse.stock(order.item))
}

// A node with a work and an update takes the same two settings.
val classify = node(
    "classify",
    work = { email -> model.ask<Verdict>("Classify this support email: ${email.body}") },
    retry = Retry(attempts = 3),
) { email, verdict -> email.copy(category = verdict.category) }
```

Without them a node runs once, for as long as it takes, and a failure ends the run.

## Try a node again

With a `retry`, a node that fails runs again, on the same state and in the same step. The run fails
only when the last try fails. `attempts` counts every try, also the first.

The engine waits between two tries, and longer each time:

```kotlin
Retry(
    attempts = 3,           // how often the node runs at most
    pause = 1.seconds,      // the wait before the second try
    factor = 2.0,           // each wait is this much longer than the one before
    maxPause = 30.seconds,  // and never longer than this
)
```

These are the defaults: one second before the second try and two before the third.

Every failure of the node is tried again. To pick, give the `Retry` a function. It gets what the
node threw:

```kotlin
val retry = Retry(attempts = 3) { failure -> failure is ChatModelException }
```

Some things are never tried again:

- **A `GraphValidationException`.** The code is wrong, and a second try fails in the same way.
- **What is no failure of the node.** A cancelled run, a pause with `interrupt`, and an error of
  the program such as an `AssertionError`. See [Errors](errors.md).

What to know:

- **The node starts again from its first line.** Make its side effects safe to repeat: a node that
  charges a card and then fails would charge it again.
- **Nothing is saved between two tries.** They all belong to one step. A run that fails after its
  last try is continued with `resume`, as before, and the node then has all of its tries again.
- **The other nodes of the step go on** while one node waits for its next try. When a node fails
  for good, the others are cancelled, also one that is waiting.
- **Tries are not steps.** They do not count for `maxIterations`.
- **The exception says how often the node ran.** `NodeExecutionException.attempts` is 3 after three
  tries, and its message reads `Node 'stock' failed after 3 attempts: ...`.

## A time limit

With a `timeout`, a try that takes longer is cancelled, and it fails with a `NodeTimeoutException`.
A `retry` treats that like any other failure, and each try has the whole time:

```kotlin
// Three tries of at most ten seconds each.
val stock = node("stock", retry = Retry(attempts = 3), timeout = 10.seconds) { order -> ... }
```

Without a `retry`, the run fails with a `NodeExecutionException` that has the
`NodeTimeoutException` as its `cause`.

- **The limit stops code that suspends**, such as a request with a Ktor client or a `delay`. Code
  that blocks its thread goes on until it returns, and the node fails then.
- **A model class has a `timeout` too.** That one limits one call to the provider, and the one of a
  node limits everything the node does in a try. The shorter one ends the try. For a node that
  makes one call to a model, the `timeout` of the model is enough.

## In a stream

A stream has a `GraphEvent.NodeRetry` for each try that failed and is followed by another. It
arrives before the wait:

```kotlin
graph.stream(order).collect { event ->
    if (event is GraphEvent.NodeRetry) {
        println("${event.node}: try ${event.attempt} failed (${event.cause.message}), next try in ${event.pause}")
    }
}
```

```
stock: try 1 failed (The warehouse did not answer.), next try in 1s
```

A node has one `NodeStarted` and one `NodeCompleted`, however often it is tried. A node that
[reports progress](streaming.md) reports again on its next try. A screen that shows the text of a
model clears it for that node when the event arrives.

With `invoke` nobody collects the events, and the tries happen without a word.

## An agent

`toolLoop` and `toolAgent` take a `retry` for the node that asks the model:

```kotlin
val agent = toolAgent(model, tools = listOf(menuPrice, orderStatus), retry = Retry(attempts = 3))
```

A call that failed leaves nothing in the conversation and adds no tokens to the count. The tools
need no retry: a tool that fails returns an error to the model, which can call it again. See
[Agents with tools](agents-with-tools.md).

## What has none yet

A node added with `subgraph` has no `retry` and no `timeout`, but the nodes of the subgraph can
have them. The function of a conditional edge has none either, so a `decisionEdge` whose model
fails ends the run.
