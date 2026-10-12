# Errors

Mistakes in the graph itself (an unknown node name, a node nothing leads to, two nodes that return
a whole state in the same step without a reducer) are reported by `compile()`, before anything runs. Everything the library throws
extends `TelarException`:

| Exception | When |
|---|---|
| `GraphValidationException` | The graph or `GraphConfig` is invalid. Thrown by `compile()` or when a run starts, and when a node calls `interrupt` in a run without a checkpointer. |
| `NodeExecutionException` | A node threw, or a `withTimeout` inside it expired. `nodeName` and the original `cause` are available, and `attempts` says how often the node ran. Every exception is wrapped, also one of this table that a graph inside the node threw. |
| `NodeTimeoutException` | A try of a node took longer than the `timeout` of the node. A run reports it as the `cause` of a `NodeExecutionException`. |
| `EdgeConditionException` | The function of a conditional edge threw. `from` and the original `cause` are available. |
| `ReducerException` | The reducer threw. `nodes` (the nodes whose states it was merging) and the original `cause` are available. |
| `InvalidRouteException` | A conditional edge returned a node that does not exist or is not a declared target. |
| `MaxIterationsExceededException` | The run took more steps than `GraphConfig.maxIterations` (default 25). |
| `CheckpointNotFoundException`, `GraphAlreadyCompletedException` | `resume` had nothing to continue. |
| `ThreadAlreadyExistsException` | `fork` was given a thread that already has a checkpoint. |
| `CheckpointCorruptedException` | A stored checkpoint could not be read. |
| `LocalStorageException` | The browser refused to read or write `localStorage`: it is full, or the page may not use it. From `telar-checkpoint-browser`. |
| `ChatModelException` | A call to a `ChatModel` failed, or the model declined to answer. From `telar-agent`. A run reports it as the `cause` of a `NodeExecutionException`. |

A node that fails ends the run, unless it has a `retry`: see
[Retries and time limits](retries.md). A run with a checkpointer that failed is continued with
`resume`, which runs the failed step again.

## What counts as a failure

A node, a conditional edge, a reducer and a tool fail with whatever they throw. Three things are no
failure and pass through the library as they are:

- **A cancellation of the run.** It arrives as a `CancellationException`.
- **A pause.** `interrupt` stops the node with a signal of its own, which the engine turns into a
  paused run.
- **An error of the program or of its machine.** That is a subclass of `Error`, such as
  `OutOfMemoryError`, the `AssertionError` of a test that asserts inside a node, or the
  `NotImplementedError` of `TODO()`. A retry would not help with any of them.

In a browser not every failure is an `Exception`. Ktor reports a request that got no response as a
plain `Error` that says `Fail to fetch`, and an error that JavaScript throws is not an `Exception`
in Kotlin either. Both are failures like any other: a node that runs into one fails with a
`NodeExecutionException`, and a tool returns an error result that the model can react to.

