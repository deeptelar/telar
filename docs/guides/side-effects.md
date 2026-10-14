# Side effects

A step can run more than once. A node with a `retry` is tried again, `resume` runs the whole step
of a run that failed, including the nodes of that step that had already finished, and it runs the
step of a node that called `interrupt` again from its first line. A node that charges a card would
charge it each time.

`effect` runs a side effect once for the step and saves its result:

```kotlin
@Serializable
data class Receipt(val id: String, val amount: Int)

val pay = node("pay") { order ->
    // Runs once. When the step runs again, the call returns the receipt it saved.
    val receipt = effect("charge") { payments.charge(order.id, order.amount) }
    effect("email") { mailer.send(order.customer, "Paid: ${receipt.id}") }
    order.copy(receipt = receipt, status = "paid")
}
```

`effect` with a `@Serializable` result is in `telar-serialization`. Import
`dev.deeptelar.telar.serialization.effect`. The result can be a `@Serializable` class, a basic type
such as `String`, or `Unit` for an effect without one.

## When a step runs again

As soon as the block returns, its result is saved in the checkpoint that the step continues from.
The next time the step runs, the call returns that result and the block does not run:

```kotlin
val graph = StateGraph<Order> {
    val pay = node("pay") { order -> order.copy(receipt = effect("charge") { payments.charge(order.id, order.amount) }) }
    val ship = node("ship", work = { order -> courier.book(order) }) { order, tracking -> order.copy(tracking = tracking) }
    START then pay then END
    START then ship then END
}.compile()

graph.invoke(order, config)  // "pay" charges the card, then "ship" fails: the courier did not answer
graph.resume(config)         // runs "pay" and "ship" again. "pay" gets its receipt without a new charge
```

The same holds for the tries of a node with a [`retry`](retries.md), and for a node that
[calls `interrupt`](human-in-the-loop.md) after an effect: a payment made before the question is
not made again when `resume` brings the answer.

The result is saved when another node of the step fails at the same time and cancels the node of
the effect, and the checkpoint is written with each effect, so a process that stops in the middle
of a step keeps what the step already did.

## What to know

- **A key names one effect of one node.** Two nodes may use the same key. One run of a node may
  not: a second call with the same key throws a `GraphValidationException`. An effect for each item
  of a list needs a key for each item:

  ```kotlin
  order.items.forEach { item -> effect("reserve-${item.id}") { warehouse.reserve(item) } }
  ```

- **An effect belongs to one step.** When the node runs again in a later step, in a loop, its
  effects run again. A checkpoint after a step that finished has none.
- **A block that throws saves nothing**, and runs again the next time. The node may also catch the
  failure and call `effect` with the same key again.
- **Between the end of the block and the save, a crash can still repeat the effect.** The window is
  short, but where a service takes an idempotency key, such as a payment provider, pass it one too.
- **The result is saved with the run.** It is serialized into the checkpoint, so keep it small: the
  id of an email rather than its text. A result whose type changed after it was saved cannot be
  read, and the node fails with a `CheckpointCorruptedException`.
- **`fork` runs the effects again.** They belong to the thread they were saved in, and a fork starts
  a thread of its own. See [Going back to an earlier step](human-in-the-loop.md#going-back-to-an-earlier-step).
- **Inside a subgraph** an effect is saved in the step of the outermost graph, and is not repeated
  when that step runs again.
- **Without a checkpointer** the results are kept in memory, for the tries of a `retry`.
- **Call it in a node**: in the action, or in the `work` of a node with a `work` and an `update`.
  Outside a running node the block runs every time.

## Without kotlinx.serialization

`telar-core` has `effect` too. It takes a `StateSerializer` for the result, which turns it into a
string and back:

```kotlin
object ReceiptId : StateSerializer<String> {
    override fun serialize(state: String): String = state
    override fun deserialize(data: String): String = data
}

val receiptId = effect("charge", ReceiptId) { payments.charge(order.id, order.amount).id }
```

## Where the results are

`Checkpoint.effects` holds the results of the step a checkpoint continues with, by the node and the
key, as strings. A checkpointer of your own has to store them with the rest. One built on
`CheckpointCodec` does: it writes a checkpoint with effects in version 3 of its format, which a
library from before effects refuses to read instead of running the effects again.
