# Subgraphs

A compiled graph can be a node of another graph. That keeps a large workflow in parts that are built
and tested on their own, each with its own state class. Two functions connect the states: `state`
reads the state of the subgraph out of the state of the graph around it, and `update` writes it back.

```kotlin
@Serializable
data class ReturnCase(
    val customer: String,
    val items: List<Int>,
    val payout: Payout? = null,   // the state of the subgraph; null until the run enters it
    val reply: String = "",
)

// payoutGraph is the graph of "Ask from inside a node": a CompiledGraph<Payout> that asks before a large payout.
val graph = StateGraph<ReturnCase> {
    val check = node("check") { case -> case.copy(items = case.items.filter { it > 0 }) }
    val payout = subgraph(
        "payout", payoutGraph,
        // The state the subgraph works on: the saved one, or a new one on the first visit.
        state = { case -> case.payout ?: Payout(case.customer, case.items) },
        // Called when the subgraph finishes, and when it pauses.
        update = { case, payout -> case.copy(payout = payout) },
    )
    val reply = node("reply") { case -> case.copy(reply = "Dear ${case.customer}: ${case.payout?.log?.last()}.") }

    START then check then payout then reply then END
}.compile()

// One config and one checkpointer, for the graph around. The subgraph needs none of its own.
val config = GraphConfig(threadId = "return-7", checkpointer = MemoryCheckpointer<ReturnCase>())

val paused = graph.invoke(ReturnCase("Ben", items = listOf(200, 50)), config)
if (paused is GraphResult.Interrupted) {
    val answer = askManager(paused.state.payout?.question)   // your UI
    // The answer goes into the state of the subgraph, and the run continues inside it.
    graph.resume(config) { it.copy(payout = it.payout?.copy(approved = answer)) }
}
```

Runnable version: [`Subgraph`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/Subgraph.kt).

- The subgraph runs from its `START` to its `END` inside one step of the graph around it. Its node
  can run next to other nodes without a reducer, like a node with a `work` and an `update`.
- When a node of the subgraph calls `interrupt`, the graph around pauses too. The checkpoint
  remembers where the subgraph stands, so `resume` continues at the node that paused, and the nodes
  of the subgraph that came before it do not run again.
- For that, `state` has to return what `update` stored. Keep the whole state of the subgraph in a
  property of the outer state, as `payout` above. A subgraph that never pauses can get a new state
  on every visit and give back only its result:
  `state = { Payout(it.customer, it.items) }, update = { case, payout -> case.copy(reply = payout.log.last()) }`.
- `interruptBefore` and `interruptAfter` of the `GraphConfig` reach the nodes of a subgraph by
  their path: the name of the subgraph node, a `/` and the name of the node inside it. The graph
  around pauses in the step of the subgraph node, and `resume` continues inside the subgraph:

  ```kotlin
  // Pause before the node "pay" of payoutGraph for every payout, not only a large one.
  val config = GraphConfig("return-7", checkpointer, interruptBefore = setOf("payout/pay"))
  ```

  `GraphResult.Interrupted.nextNodes` names the subgraph node (`payout`), and the
  `subgraphs` of the checkpoint say where inside it the run stands. A path can go deeper, such
  as `"payout/checks/limit"` for a node of a subgraph inside the subgraph, and a pause after the
  last node of a subgraph comes before the graph around goes on. That is why a node name cannot
  hold a `/`.
- A subgraph with the same state class needs no functions: `subgraph("payout", payoutGraph)`.
- A stream of the graph around has the events of the subgraph too, each inside a
  `GraphEvent.SubgraphEvent` that names the subgraph's node. They are the events a stream of the
  subgraph alone would have, with the state of the subgraph:

  ```kotlin
  graph.stream(case, config).collect { event ->
      if (event is GraphEvent.SubgraphEvent) {
          val inside = event.event                    // a GraphEvent of payoutGraph
          if (inside is GraphEvent.NodeStarted) println("${event.node} > ${inside.node} started")
      }
  }
  ```

  For a subgraph inside a subgraph, `event.event` is a `SubgraphEvent` again; `event.path` lists the
  subgraph nodes from the outside in, and `event.innermost` is the event at the end. `textDelta`
  reads a model's text from any depth.
- A failure inside the subgraph is the `cause` of the `NodeExecutionException` of its node.
  `maxIterations` limits the steps of the subgraph on their own.
- A subgraph can contain subgraphs.
