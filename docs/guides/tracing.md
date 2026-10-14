# Tracing

A run in production should leave a trace: which nodes ran and how long each took, which nodes the
edges chose, which tries failed, and where the run stopped. A `GraphObserver` is told all of that,
and `telar-otel` sends it to OpenTelemetry, so it shows up in the tools a team already uses, such
as Jaeger, Grafana Tempo or Honeycomb.

## OpenTelemetry

```kotlin
dependencies {
    implementation("dev.deeptelar:telar-otel:<version>")
}
```

> `telar-otel` is not released yet: it comes with the version after `0.1.0-alpha09`. It is for the
> JVM and Android, because the OpenTelemetry API it is built on is a Java library.

Give an `OpenTelemetryObserver` to the runs you want to trace:

```kotlin
val tracing = OpenTelemetryObserver(openTelemetry)
val config = GraphConfig(threadId = "ticket-42", checkpointer = checkpointer, observers = listOf(tracing))

graph.invoke(ticket, config)
```

`openTelemetry` is the `OpenTelemetry` of your app, the one that knows where the spans go. Without
an argument, the observer takes the one the OpenTelemetry Java agent or `GlobalOpenTelemetry.set`
registered. One observer can be shared by every run of the app.

A run then looks like this:

```
telar.run                    telar.thread_id=ticket-42  telar.outcome=completed
├─ telar.node classify       telar.step=1
├─ telar.node research       telar.step=2
│  └─ telar.run              (the subgraph that "research" runs)
│     ├─ telar.node search   telar.step=1
│     └─ telar.node sum      telar.step=2
└─ telar.node reply          telar.step=3
   └─ POST api.anthropic.com (the span of an instrumented HTTP client)
```

- **The run** is a span named `telar.run`, with `telar.thread_id` and `telar.outcome`:
  `completed`, `interrupted`, `failed` or `cancelled`. A run that paused also has
  `telar.next_nodes`. An event `telar.step` for each step names the nodes that ran and the nodes the
  edges chose next, and an event `telar.checkpoint` marks each saved checkpoint.
- **A node** is a span named `telar.node <name>` inside its run, with `telar.node`, `telar.step`
  and `telar.outcome`. Each try that failed and was followed by another is an event `telar.retry`,
  with the attempt, the pause and the exception.
- **A subgraph** is a `telar.run` inside the span of the node that runs it.
- **A failure** is recorded on the node and on the run, with the status `ERROR`. A cancelled node,
  for example the other node of a step in which one node failed, is not an error.

The span of a node is current while the node runs. What the node calls opens its spans inside it:
an HTTP client with OpenTelemetry instrumentation, a database driver, another traced library. And a
run that starts inside a span, such as the span of a request to your server, is a child of that
span.

## What is never recorded

The state of a run is not recorded, and neither is what a node reports with `reportProgress`, such
as the text a model writes. Both can hold personal data. An observer is not even given the state.
To see what a step wrote, read the [history](human-in-the-loop.md) of the thread or
[stream](streaming.md) the run.

## An observer of your own

`GraphObserver` is in `telar-core`, so it works on every platform. Override what you need; every
other function does nothing:

```kotlin
val slowNodes =
    object : GraphObserver {
        private val started = ConcurrentHashMap<ObservedNode, TimeMark>()

        override fun onNodeStarted(node: ObservedNode) {
            started[node] = TimeSource.Monotonic.markNow()
        }

        override fun onNodeCompleted(node: ObservedNode) {
            val took = started.remove(node)?.elapsedNow() ?: return
            if (took > 5.seconds) log.warn("${node.name} took $took in step ${node.step}")
        }

        override fun onNodeFailed(node: ObservedNode, cause: Throwable) {
            started.remove(node)
        }
    }
```

An observer is told:

| Function | When |
|---|---|
| `onRunStarted` | A run starts: `invoke`, `stream`, `resume`, `fork` or a subgraph |
| `onRunCompleted`, `onRunInterrupted`, `onRunFailed` | The run ended. Exactly one of them, also when the run is cancelled |
| `onNodeStarted` | A node starts. Once, however often it is tried |
| `onNodeRetry` | A try failed, and the node runs again after a pause |
| `onNodeProgress` | A node called `reportProgress`. Only in a run that is streamed |
| `onNodeCompleted`, `onNodeInterrupted`, `onNodeFailed` | The node ended. Exactly one of them |
| `onStepCompleted` | A step ended, with the nodes the edges chose next |
| `onCheckpointSaved` | The checkpointer saved a checkpoint |
| `nodeContext` | Returns what to add to the coroutine context of a node, such as a trace span |

A run is the same `ObservedRun` from its start to its end, and a node the same `ObservedNode`, so
an observer can keep what it needs in a map with them as keys. The `parent` of the run of a subgraph
is the node that runs it.

What to keep in mind:

- **Return quickly.** The functions are not `suspend`, and the engine waits for them. Hand slow
  work, such as sending data over the network, to a queue.
- **Be safe for threads.** The nodes of a step run in parallel and call the observer at the same
  time, maybe on different threads.
- **Do not throw.** What an observer throws fails the run.
