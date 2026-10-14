# Apps with a screen

An app with Compose, Compose Multiplatform or another UI toolkit usually works in one direction:
an immutable state goes down to the screen as a `StateFlow`, and what the person does goes up as
function calls. A graph of Telar already has an immutable state, so a `GraphSession` puts the two
together: it runs one thread of a graph and gives the screen a `StateFlow` of where the run stands.

```kotlin
@Serializable
data class Refund(
    val orderId: String,
    val amount: Int,
    val approved: Boolean = false,
    val log: List<String> = emptyList(),
)

class RefundViewModel(graph: CompiledGraph<Refund>, checkpointer: Checkpointer<Refund>) : ViewModel() {
    val session = GraphSession(
        graph = graph,
        config = GraphConfig("refund-1001", checkpointer, interruptBefore = setOf("issue_refund")),
        scope = viewModelScope,
    )

    init {
        // Back to where the run stopped, also after the operating system ended the app.
        session.restore()
    }

    fun start() = session.start(Refund(orderId = "1001", amount = 250))
    fun approve() = session.resume { it.copy(approved = true) }
    fun retry() = session.resume()
}
```

The screen draws the `snapshot` with a `when`. Each kind of snapshot holds what that screen needs:

```kotlin
@Composable
fun RefundScreen(viewModel: RefundViewModel) {
    when (val snapshot = viewModel.session.snapshot.collectAsState().value) {
        GraphSnapshot.Idle -> Button(onClick = { viewModel.start() }) { Text("Ask for a refund") }
        GraphSnapshot.Restoring -> CircularProgressIndicator()
        is GraphSnapshot.Running -> Text("Working: ${snapshot.nodes.joinToString()}")
        is GraphSnapshot.Interrupted -> ApproveRefund(snapshot.state.amount, onApprove = viewModel::approve)
        is GraphSnapshot.Completed -> Text(snapshot.state.log.last())
        is GraphSnapshot.Stopped -> RetryButton(snapshot.cause, onRetry = viewModel::retry)
    }
}
```

| Snapshot | When | What it holds |
|---|---|---|
| `Idle` | Nothing has run, and `restore` found no run of the thread | |
| `Restoring` | `restore` is reading the last checkpoint | |
| `Running` | The run is going on | The state, which changes once per step, and the `nodes` of the step that have not finished |
| `Interrupted` | The run paused, usually for a person | The state, and the `nextNodes` that run when it continues |
| `Completed` | The run reached `END` | The final state |
| `Stopped` | A node failed after its last try, or `cancel` stopped the run | The last state the session knew, and the `cause` (`null` after `cancel`) |

Every snapshot has `state`, which is `null` for `Idle`, `Restoring`, and a `Stopped` that knew no
state.

## The commands

- `start(input)` starts a new run of the thread. An earlier run of the thread is replaced, as with
  `invoke`.
- `resume(update)` continues the saved run: after `Interrupted`, with the decision of the person in
  `update`, or after `Stopped`, to try the step that failed again.
- `restore()` reads where the run of the thread stopped, without running anything.
- `cancel()` stops the run. With a checkpointer, `resume` continues from the last step that finished.

One command runs at a time. A command stops what the session is doing before it begins, so
`start` during a run replaces that run. They can be called from any thread, and each returns a
`Job` that a test can `join`.

`resume` and `restore` need a checkpointer in the config. Without one, they throw a
`GraphValidationException` at once.

## When the app is killed

A phone ends an app in the background whenever it needs the memory, and a person closes a
desktop app in the middle of a run. With a checkpointer that stores to disk, nothing is lost: a
checkpoint is saved after every step and when a run pauses. Call `restore()` when the screen
opens, and the snapshot is where the last step left the run:

- A run that waited for a person is `Interrupted` again, with the same state, and the screen
  shows the same question.
- A run that the app lost in the middle of a step is `Interrupted` before that step, as
  [`lastResult`](human-in-the-loop.md#where-a-thread-stands) reports it. `resume()` runs the step
  again. Which of the two it is, the state tells: write into it what the screen needs to know,
  such as whether the run waits for an approval.

`FileCheckpointer` stores to disk on Android, iOS and desktop, and `LocalStorageCheckpointer` in a
browser.

## Text as a model writes it

The snapshot changes once per step. What happens inside a step, such as the text a model writes,
arrives on `events`: every `GraphEvent` of the run, as `stream` emits it. Collect it while the
screen is shown:

```kotlin
class ChatViewModel(agent: CompiledGraph<AgentState>, checkpointer: Checkpointer<AgentState>) : ViewModel() {
    val session = GraphSession(agent, GraphConfig("chat", checkpointer), viewModelScope)

    private val _writing = MutableStateFlow("")
    val writing: StateFlow<String> = _writing

    init {
        viewModelScope.launch {
            session.events.collect { event ->
                event.textDelta?.let { delta -> _writing.update { it + delta } }
                if (event is GraphEvent.StepCompleted) _writing.value = ""
            }
        }
    }
}
```

`textDelta` is in `telar-agent`, and reads the text of a model that `toolLoop` reports. An event
reaches the collectors that are there when it happens, and the run waits until they have it.
Nothing is kept for a collector that comes later: the snapshot is what a screen that opens later
draws.

## Failures

A failure of the run does not crash the app. It becomes a `Stopped` snapshot with the exception
as its `cause`, such as a `NodeExecutionException` when a node failed after its last try. With a
checkpointer, `resume()` tries the step again. An `Error` that is no failure of the code, such as
an `AssertionError` of a test, still fails the scope, as it fails `invoke`.

The nodes run in the coroutines of the scope, which is the main thread for `viewModelScope`. A node
that waits for a model or the network does not hold the thread. A node that computes for long
moves that work off it with `withContext(Dispatchers.Default)`.

## Testing

A session runs in any `CoroutineScope`. In a test, give it the `backgroundScope` of `runTest`, and
`join` the commands:

```kotlin
@Test
fun `the refund waits for an approval`() = runTest {
    val session = GraphSession(graph, GraphConfig(checkpointer = MemoryCheckpointer(), interruptBefore = setOf("issue_refund")), backgroundScope)

    session.start(Refund(orderId = "1001", amount = 250)).join()

    assertIs<GraphSnapshot.Interrupted<Refund>>(session.snapshot.value)
}
```
