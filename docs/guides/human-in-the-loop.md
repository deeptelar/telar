# Human-in-the-loop

Some steps should not run until a person has said yes, for example sending money. Tell the run where
to pause and where to save its progress. `invoke` then stops before that node, and `resume`
continues later, even after the app was restarted.

Two terms: a **checkpoint** is a saved run (the state, and which node comes next), and a **thread**
is one job with its own checkpoints, named by its `threadId`.

```kotlin
@Serializable // lets the state be written to a file
data class RefundState(
    val orderId: String,
    val amount: Int,
    val approved: Boolean = false,           // written by the human reviewer
    val log: List<String> = emptyList(),
)

val graph = StateGraph<RefundState> {
    val prepare = node("prepare") { it.copy(log = it.log + "Prepared refund of ${it.amount}") }
    val issue = node("issue_refund") {
        it.copy(log = it.log + if (it.approved) "Refund issued" else "Refund rejected by reviewer")
    }

    START then prepare then issue then END
}.compile()

val config = GraphConfig(
    // Names this job. Each order gets its own saved run.
    threadId = "order-1001",
    // Where the run is saved: one JSON file per thread in the "checkpoints" directory.
    checkpointer = FileCheckpointer(Path("checkpoints"), KotlinxStateSerializer<RefundState>()),
    // Stop before this node runs.
    interruptBefore = setOf("issue_refund"),
)

// Runs "prepare", then stops before "issue_refund" and returns Interrupted.
when (val result = graph.invoke(RefundState(orderId = "1001", amount = 250), config)) {
    is GraphResult.Interrupted -> showApprovalDialog(result.state) // your UI: ask the reviewer
    is GraphResult.Completed -> showResult(result.state)           // not reached here: the run pauses first
}

// Later, possibly after an app restart: write the decision into the state and continue.
// resume() loads the saved run of "order-1001" and runs "issue_refund".
val finished = graph.resume(config) { state -> state.copy(approved = true) }
```

Runnable version: [`HumanInTheLoop`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/HumanInTheLoop.kt).

- `invoke` always starts a new run for the thread. `resume` continues the saved one, optionally
  editing the state first.
- A checkpoint is saved after every step, so a run can also be resumed after a crash. Such a
  `resume` still pauses before an `interruptBefore` node; only a run that already paused there
  continues past it.
- A step that fails is not saved, so `resume` runs all of its nodes again, including the ones that
  had already finished. Run side effects such as sending an email with
  [`effect`](side-effects.md), which saves them and does not repeat them, or make them safe to
  repeat.
- `interruptAfter` pauses after a node instead of before it.
- `MemoryCheckpointer` keeps checkpoints in memory, which is what tests want. `FileCheckpointer`
  keeps them in files, `SqlDelightCheckpointer` in a SQLite database on every platform, and
  `LocalStorageCheckpointer` in the storage of a browser.

## Approve or send back

To let the reviewer ask for changes, pause before a node that does nothing and put a conditional
edge after it. `resume` writes the decision into the state, and the conditional edge reads it:

```kotlin
data class AnnouncementState(
    val topic: String,
    val draft: String = "",
    val approved: Boolean = false,   // written by the reviewer
    val feedback: String = "",       // written by the reviewer: what to change
    val published: Boolean = false,
)

val graph = StateGraph<AnnouncementState> {
    // Writes a draft, using the reviewer's feedback if there is any, then clears the feedback.
    val draft = node("draft") { it.copy(draft = write(it.topic, it.feedback), feedback = "") }
    // Does nothing. It is the place where the run waits for the reviewer.
    val review = node("review") { it }
    val publish = node("publish") { it.copy(published = true) }

    START then draft then review
    // Approved: publish. Not approved: back to "draft", which makes this a loop.
    conditionalEdge(review, targets = setOf(publish, draft)) { state ->
        if (state.approved) publish else draft
    }
    publish then END
}.compile()

// Pause every time the run is about to enter "review", that is, whenever a new draft is ready.
val config = GraphConfig(checkpointer = MemoryCheckpointer<AnnouncementState>(), interruptBefore = setOf("review"))

var result = graph.invoke(AnnouncementState(topic = "the 1.0 release"), config)
// Interrupted means a draft is waiting. Completed means it was published.
while (result is GraphResult.Interrupted) {
    val feedback = askReviewer(result.state.draft)   // your UI; returns "" when the reviewer approves
    result = graph.resume(config) { it.copy(approved = feedback.isEmpty(), feedback = feedback) }
}
```

Runnable version: [`ReviewLoop`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/ReviewLoop.kt).

## Ask from inside a node

`interruptBefore` pauses every time, and before the node has done anything. When only the node can
tell whether a person is needed, or which question to ask, the node pauses the run itself with
`interrupt`:

```kotlin
@Serializable
data class Payout(
    val customer: String,
    val items: List<Int>,
    val question: String? = null,    // what the node asks; null when it asks nothing
    val approved: Boolean? = null,   // the person's answer; null while nobody has answered
    val log: List<String> = emptyList(),
)

val graph = StateGraph<Payout> {
    val pay = node("pay") { payout ->
        val total = payout.items.sum()
        if (total > 100 && payout.approved == null) {
            // Saves this state, with the question in it, and ends the run here.
            interrupt(payout.copy(question = "Pay $total to ${payout.customer}?"))
        }
        val line = if (payout.approved == false) "Payout of $total rejected" else "Paid $total"
        payout.copy(question = null, log = payout.log + line)
    }

    START then pay then END
}.compile()

// No interruptBefore: the node decides. The run still needs a checkpointer.
val config = GraphConfig(threadId = "payout-2", checkpointer = MemoryCheckpointer<Payout>())

val paused = graph.invoke(Payout("Ben", items = listOf(200, 50)), config)
if (paused is GraphResult.Interrupted) {
    val answer = askManager(paused.state.question)   // your UI
    // Runs "pay" again from its first line, now with the answer in the state.
    graph.resume(config) { it.copy(approved = answer) }
}
```

Runnable version: [`AskFromANode`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/AskFromANode.kt).

- The question and the answer are fields of the state, so they are saved with the run, and
  `lastResult` shows the question after a restart.
- `resume` runs the node again from its first line, and the node reads the state to see whether it
  has an answer. Here `approved` is `null` until a person has decided.
- What the node did before `interrupt` happens a second time. Call `interrupt` before a side effect
  such as a payment, or run the side effect with [`effect`](side-effects.md), which saves its result
  and does not repeat it.
- When other nodes run in the same step, they are cancelled, and `resume` runs the whole step again.
- Do not put the call inside `runCatching` or a `catch (e: Throwable)`: the node would go on instead
  of pausing. A `catch (e: Exception)` is fine.

## Where a thread stands

`lastResult` reads the thread's checkpoint without running anything. It returns the same
`GraphResult` that `invoke` or `resume` returned, so a screen can be restored after a restart with
the code that already handles a result:

```kotlin
when (val result = graph.lastResult(config)) {
    is GraphResult.Interrupted -> showApprovalDialog(result.state) // the run is waiting for a person
    is GraphResult.Completed -> showResult(result.state)           // the run reached END
    null -> showEmptyForm()                                        // this thread has never run
}
```

A run that stopped because a node failed is reported as `Interrupted` as well. The run's input is
saved when it starts and its state after every finished step, so `resume(config)` retries from the
step that failed, even when that was the first one.

## Going back to an earlier step

A thread keeps the checkpoint of every step, not only the last one. `history` reads them, oldest
first: the one saved when the run started, and one for each step that finished.

```kotlin
graph.history(config).forEach { checkpoint ->
    println("after step ${checkpoint.step}: next ${checkpoint.nextNodes}, state ${checkpoint.state}")
}
```

`fork` continues from one of them on a **new thread**, and the thread it comes from stays as it is.
That answers "what if the reviewer had said no?" without running the steps before the review again,
and it lets you repeat a step after you fixed its node:

```kotlin
// The checkpoint at which the run waited for the review.
val atReview = graph.history(config).first { it.nextNodes == listOf("review") }

// The same run from there, with the other answer, on a thread of its own.
val rejected = graph.fork(atReview, config.copy(threadId = "ticket-42-rejected")) { it.copy(approved = false) }
```

- The new thread must not have a checkpoint yet, so a fork cannot overwrite a run. It fails with a
  `ThreadAlreadyExistsException` otherwise.
- A run that paused has the state it paused with in the checkpoint of its last step: a thread has
  one checkpoint for each step.
- `invoke` starts the history of its thread again.
- `MemoryCheckpointer` and `FileCheckpointer` keep every step. Give them a `maxHistory` for a thread
  that runs for hundreds of steps. `LocalStorageCheckpointer` keeps only the latest checkpoint
  unless you give it one, because a browser has little room.
- The new thread runs the [effects](side-effects.md) of its first step again, also the ones the
  checkpoint already has: they belong to the thread it comes from.
- `streamFork` is `fork` with the events of the run.
- In a test, `graph.replay(checkpoint) { ... }` of `telar-test` continues from a checkpoint and
  checks the path the run takes from there, also from the checkpoints of a run that failed in
  production. See [Testing](testing.md#a-run-that-failed-run-again).

## In a database, on every platform

`SqlDelightCheckpointer`, from `telar-checkpoint-sqldelight`, keeps the checkpoints in a SQLite
database through [SQLDelight](https://sqldelight.github.io/sqldelight/). The checkpointer is the
same on every platform, and only the driver changes:

| Platform | Driver, from SQLDelight |
|---|---|
| Android | `AndroidSqliteDriver` of `app.cash.sqldelight:android-driver` |
| iOS, macOS, Linux, Windows | `NativeSqliteDriver` of `app.cash.sqldelight:native-driver` |
| Desktop and servers (JVM) | `JdbcSqliteDriver` of `app.cash.sqldelight:sqlite-driver` |
| Browser (JS and Wasm) | `WebWorkerDriver` of `app.cash.sqldelight:web-worker-driver` |

```kotlin
// In the Android part of the app. In the iOS part: NativeSqliteDriver(SqlDelightCheckpointer.Schema, "runs.db")
val driver = AndroidSqliteDriver(SqlDelightCheckpointer.Schema, context, "runs.db")

// In common code.
val config = GraphConfig(
    threadId = "refund-42",
    checkpointer = SqlDelightCheckpointer(driver, KotlinxStateSerializer<RefundState>()),
    interruptBefore = setOf("pay"),
)
```

- The table is created the first time it is needed, so the database can also be the one the app
  already has, with its own schema: pass the driver of that database. `SqlDelightCheckpointer.Schema`
  is for a database that only holds checkpoints. `table` changes the name of the table, which is
  `telar_checkpoints` by default.
- The table has one row for each step of a thread, so `history` and `fork` work. A thread keeps
  every step unless you give it a `maxHistory`.
- A save is one transaction, so a crash in the middle of one leaves the checkpoints as they were.
- The calls of Android and the other drivers that block run on `Dispatchers.IO`, so the main thread
  of a screen can save and load.
- The module only needs the runtime of SQLDelight, not its Gradle plugin. An app that uses
  SQLDelight for its own tables keeps its setup as it is.

With a [`GraphSession`](apps-with-a-screen.md), this brings a screen back where the run stopped
after the operating system ended the app. The sample
[`AppRestart`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/AppRestart.kt)
shows it: quit while a draft waits for approval, start again, and the draft is still there.

## In a browser

A web app has no file system. `LocalStorageCheckpointer`, from `telar-checkpoint-browser`,
keeps the checkpoints in the page's `localStorage`, so a paused run is still there after the page
is reloaded or the browser is closed:

```kotlin
val config = GraphConfig(
    threadId = "refund-42",
    // Every page of your site shares one localStorage, so give the keys a prefix of your own.
    checkpointer = LocalStorageCheckpointer(KotlinxStateSerializer<RefundState>(), keyPrefix = "myapp.refund."),
    interruptBefore = setOf("pay"),
)

// When the page opens: is a run of this thread waiting for a decision?
if (graph.lastResult(config) is GraphResult.Interrupted) showTheDecision()
```

A browser keeps about 5 MB for a site. When that is full, the run fails with a
`LocalStorageException` and the checkpoint stored before stays as it was. The person using the
browser can read `localStorage`, so do not keep secrets in the state.

## Storing checkpoints somewhere else

To store checkpoints in another database, in the preferences of a phone or on a server, implement
the `Checkpointer` interface. `CheckpointCodec` turns the checkpoints of a thread into a string and
back, so only the storage calls are left to write:

```kotlin
class DatabaseCheckpointer<State>(private val runs: RunTable, private val codec: CheckpointCodec<State>) : Checkpointer<State> {
    // Called after every step, and when an effect finished. append() adds the checkpoint to the history that is stored.
    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) =
        runs.upsert(threadId, codec.append(runs.find(threadId), checkpoint, maxHistory = 50))

    // Called by resume() and lastResult(). Returns null if this thread has no saved run.
    override suspend fun load(threadId: String): Checkpoint<State>? =
        runs.find(threadId)?.let { codec.decode(threadId, it) }

    // Called by history(). Leave it out to keep only the latest checkpoint: save() then stores codec.encode(checkpoint).
    override suspend fun history(threadId: String): List<Checkpoint<State>> =
        runs.find(threadId)?.let { codec.decodeHistory(threadId, it) }.orEmpty()

    override suspend fun delete(threadId: String) = runs.delete(threadId)
}

val checkpointer = DatabaseCheckpointer(runs, CheckpointCodec<RefundState>())
```
