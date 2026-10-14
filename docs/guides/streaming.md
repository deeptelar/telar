# Streaming

`invoke()` returns when the run is over. `stream()` runs the same graph and returns a `Flow` that
reports what happens while it runs. With the `graph` of the [quick start](../quick-start.md):

```kotlin
graph.stream(SupportEmail(sender = "Ana", body = "I would like a refund.")).collect { event ->
    when (event) {
        // A node is about to run. A UI can show a spinner next to it.
        is GraphEvent.NodeStarted -> println("${event.node} started")
        // A running node reported something with reportProgress(). See below.
        is GraphEvent.NodeProgress -> println("${event.node} reports ${event.value}")
        // A node that runs another graph passes on what happens in it. See "Subgraphs".
        is GraphEvent.SubgraphEvent -> println("inside ${event.node}: ${event.event}")
        // A node with a retry failed and will run again. See "Retries and time limits".
        is GraphEvent.NodeRetry -> println("${event.node} failed, next try in ${event.pause}")
        // A node has returned its updated state.
        is GraphEvent.NodeCompleted -> println("${event.node} finished")
        // A step is over: every node that ran at the same time has finished.
        is GraphEvent.StepCompleted -> println("step ${event.step} done, ran ${event.nodes}")
        // Last event of a run that reached END. event.state is the final state.
        is GraphEvent.Completed -> println("done: ${event.state.reply}")
        // Last event of a run that paused for a person (see the next guide).
        is GraphEvent.Interrupted -> println("paused before ${event.nextNodes}")
    }
}
```

```
classify started
classify finished
step 1 done, ran [classify]
refund started
refund finished
step 2 done, ran [refund]
done: Hi Ana, your refund is on its way. It takes 3 to 5 days.
```

For a UI that only renders the latest state, `graph.stream(input).states()` is a `Flow` with just
the state after each step. A screen with buttons to start, approve and retry a run uses a
`GraphSession`, which also brings the run back after the app was killed: see
[Apps with a screen](apps-with-a-screen.md).

A node can report what it is doing while it runs. `reportProgress(value)` sends any value to the
stream, where it arrives as a `GraphEvent.NodeProgress` before the node finishes:

```kotlin
val download = node("download", work = { order ->
    order.files.forEachIndexed { index, file ->
        fetch(file)
        reportProgress("${index + 1} of ${order.files.size}") // event.value in the stream
    }
}) { order, _ -> order.copy(downloaded = true) }
```

- The call returns when the collector has handled the event, so a node cannot run ahead of a slow
  screen.
- With `invoke()` and `resume()` nobody collects, and the call does nothing.
- Progress is not part of the state and is not saved in a checkpoint.

The agent of `telar-agent` uses this to show a model's answer while the model writes it; see
[Agents with tools](agents-with-tools.md).
