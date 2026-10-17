# Parallel branches

When several edges leave the same place, their target nodes run at the same time. Write such a node
in two parts: `work` is the slow part, for example a call to a model, and returns a result. The
block after it is the node's `update`, which writes that result into the state.

```kotlin
data class ResearchState(
    val question: String,
    val findings: List<String> = emptyList(),
    val summary: String = "",
)

val graph = StateGraph<ResearchState> {
    // The `work` of both nodes runs at the same time. Their updates are then applied one after the other.
    val web = node("web", work = { searchWeb(it.question) }) { state, found ->
        state.copy(findings = state.findings + found)
    }
    val docs = node("docs", work = { searchDocs(it.question) }) { state, found ->
        state.copy(findings = state.findings + found)
    }
    val summarize = node("summarize") { it.copy(summary = summarize(it.findings)) }

    // Two edges leave START, so "web" and "docs" run at the same time.
    START then web then summarize
    START then docs then summarize
    // "summarize" runs once, after both have finished, with the findings of both.
    summarize then END
}.compile()
```

Runnable version: [`ParallelResearch`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/ParallelResearch.kt).

- The updates are applied in the order the nodes were added to the graph (`web`, then `docs`), each
  to the state that the previous one produced. If two nodes write the same property, the one added
  later wins.
- So an update reads what the updates before it wrote in the same step. That is why both findings
  above are kept without a rule: the update of `docs` adds to the list that already has the finding
  of `web`. [Where the order counts](#where-the-order-counts) has more on this.
- `update` only builds the new state, and the engine may call it more than once. Keep slow calls
  and side effects in `work`.
- If one branch fails, the others are cancelled and the error is rethrown as
  `NodeExecutionException` with the name of the failing node.

## Choosing the branches at run time

`conditionalFanOut` is a conditional edge that returns a set of nodes. They all run in the next
step, in parallel, like the targets of edges from one node:

```kotlin
conditionalFanOut(plan, targets = setOf(locate, web, docs)) { state ->
    // Find the place first if it is not known yet, else search both sources at once.
    if (state.place == null) setOf(locate) else setOf(web, docs)
}
```

- `targets` lists every node it may return, as for `conditionalEdge`. A node it returns that is not
  in `targets` fails the run with an `InvalidRouteException`.
- `NodeRef.END` in the set ends this branch while the other nodes run, and an empty set ends the
  branch too.
- Two `targets` that return a whole state can run in the same step, so `compile()` asks for a
  `Reducer` or `rules` for them, as it does for static edges.

## Merging whole states

A node written as `node(name) { ... }` returns a whole state. One such node can share a step with
the nodes above, but when two of them run in the same step there are two states and the run can
continue with only one. `compile()` then asks for a `Reducer`, which makes one state out of them:

```kotlin
data class Pitch(val product: String, val text: String = "")

// `current` is the state before the step. `updates` has one state per node that returned a whole state.
// Two writers each return a complete pitch, and the shorter one is kept.
val keepShortest = Reducer<Pitch> { current, updates -> updates.minBy { it.text.length } }

val graph = StateGraph<Pitch> {
    val formal = node("formal") { it.copy(text = writeFormally(it.product)) }
    val casual = node("casual") { it.copy(text = writeCasually(it.product)) }

    START then formal then END
    START then casual then END
}.compile(reducer = keepShortest) // without a reducer, compile() rejects this graph
```

A reducer written by hand has to carry over everything it wants to keep from `updates`. When the
nodes add to the state, `mergeRules` builds the reducer from one rule for each property they change:

```kotlin
data class Research(val notes: List<String> = emptyList(), val status: String = "", val score: Int = 0)

val graph = StateGraph<Research> {
    val web = node("web") { it.copy(notes = it.notes + searchWeb()) }
    val docs = node("docs") { it.copy(notes = it.notes + searchDocs(), status = "found") }

    START then web then END
    START then docs then END
}.compile(
    reducer = mergeRules {
        // A list gets what each node added to it.
        append(Research::notes) { copy(notes = it) }
        // A value is taken from the node that changed it.
        replace(Research::status) { copy(status = it) }
        // Anything else: say how the changed values combine.
        merge(Research::score, combine = { _, changed -> changed.max() }) { copy(score = it) }
    },
)
```

- A rule names a property and says how to write the merged value back, which is a `copy`.
- The rules hold for every node of the step, the nodes with a `work` and an `update` too. See
  [One rule for both forms](#one-rule-for-both-forms).
- A node that changes a property without a rule fails the run with a `ReducerException`. Nothing is
  lost without a word. The `cause` of the exception is a `MergeRuleException`, and its message names
  the property.
- So do two nodes that `replace` a property with different values. `merge` says which one counts:
  `combine = { _, changed -> changed.last() }` lets the last node of the step win, which is the one
  that was added to the graph last.

## When two branches write the same property

The two ways to write a node do not treat this the same way. Take two branches that both set
`status`. With a `work` and an `update`, there is no error:

```kotlin
data class Job(val status: String = "new", val notes: List<String> = emptyList())

// The updates are applied in the order the nodes were added, so "docs" writes over what "web" wrote.
// The run ends with status = "docs done".
val web = node("web", work = { searchWeb() }) { job, found -> job.copy(status = "web done", notes = job.notes + found) }
val docs = node("docs", work = { searchDocs() }) { job, found -> job.copy(status = "docs done", notes = job.notes + found) }
```

Written as whole states, the same two nodes need a reducer, and the rule for `status` decides. The
same rules decide for the nodes with a `work` and an `update` above, see
[One rule for both forms](#one-rule-for-both-forms):

```kotlin
val web = node("web") { it.copy(status = "web done", notes = it.notes + searchWeb()) }
val docs = node("docs") { it.copy(status = "docs done", notes = it.notes + searchDocs()) }

compile(
    reducer = mergeRules {
        append(Job::notes) { copy(notes = it) }
        // Two different values fail the run:
        // Property 'status' was set to different values by nodes of the same step: [web done, docs done].
        replace(Job::status) { copy(status = it) }
    },
)
```

| The nodes are written as | Two branches set `status` to different values |
|---|---|
| `node(name, work) { state, result -> ... }` without `mergeRules` | The node that was added to the graph later wins. Nothing is reported |
| Either form, with `mergeRules { replace(Job::status) ... }` | The run fails with a `ReducerException` |
| Either form, with `merge(Job::status, combine = ...)` | What `combine` returns. `{ _, changed -> changed.last() }` lets the node win that was added last |
| `node(name) { ... }` with a reducer of your own | What the reducer returns |
| `node(name) { ... }` without a reducer | `compile()` rejects the graph |

- "Later" and "last" are the order of the `node(...)` lines, not the order of the edges. Moving
  the line of such a node changes which value the run ends with.
- With `mergeRules`, changing the form of a node does not change this policy. Without them, a
  graph of nodes with a `work` and an `update` has no check at all.
- When two branches must not disagree about a property, give the property a `replace` rule. When
  one of them should win, say so with `merge`, or let only one branch write the property.

### One rule for both forms

Rules given to `compile()`, as `rules` or as `reducer`, hold for every node of a step, the nodes
with a `work` and an `update` too:

```kotlin
val web = node("web", work = { searchWeb() }) { job, found -> job.copy(status = "web done", notes = job.notes + found) }
val docs = node("docs", work = { searchDocs() }) { job, found -> job.copy(status = "docs done", notes = job.notes + found) }

compile(
    rules = mergeRules {
        append(Job::notes) { copy(notes = it) }
        // The run fails:
        // Property 'status' was set to different values by nodes of the same step: [web done, docs done].
        replace(Job::status) { copy(status = it) }
    },
)
```

With `mergeRules`, a step works like this:

- **What a node reads.** Each node gets the state from before the step, its `update` too. An update
  no longer sees what another node of the step wrote.
- **How the results are merged.** The rules merge what every node returned, in the order the nodes
  were added to the graph, whatever form the nodes have. A node that changes a property without a
  rule fails the run, and the error names the node.
- **A node alone.** A step with a single node continues with what it returned, and needs no rule.

So adding `mergeRules` can change a graph that worked without them. Two updates that each add to the
same list need an `append` rule, because both start from the list from before the step. And an
update that read what another node of the step wrote now reads the value from before the step.
Changing the form of a node no longer changes the policy: the rules decide for both.

## Where the order counts

The nodes of a step run at the same time, but their results are put together one after the other.
The two forms do this in different orders:

| The nodes are written as | Their results are put together in | What a node reads |
|---|---|---|
| `node(name, work) { state, result -> ... }` | The order of the `node(...)` lines | `work` reads the state from before the step. `update` gets the state that the update before it returned |
| `node(name) { ... }`, with a reducer of your own | The order in which the edges reach the nodes. For two edges that leave the same node, that is the order of the two `then` lines | The state from before the step |
| Either form, in a graph compiled with `mergeRules` (as `rules` or as `reducer`) | The order of the `node(...)` lines | The state from before the step, `update` too |

An update can depend on that order even when no other node writes its property:

```kotlin
data class Job(val status: String = "new", val label: String = "")

val web = node("web", work = { searchWeb() }) { job, _ -> job.copy(status = "found") }
// "label" is written by this node only, but it is computed from what "web" wrote in the same step.
// With "web" added first it is "found by docs". With this line above the one of "web" it is "new by docs".
val docs = node("docs", work = { searchDocs() }) { job, _ -> job.copy(label = "${job.status} by docs") }
```

- When an update should not depend on another node of its step, compute the value in `work`, which
  always reads the state from before the step, and let `update` only write it.
- A rule does not make a step independent of the order by itself. `replace`, and a `combine` such as
  `changed.max()` or a sum, return the same value in any order. `changed.last()`, `changed.first()`
  and a `combine` that joins texts do not, and `append` adds the items in that order.
- The same holds for a reducer of your own that picks by position, such as `updates.last()`.

Which one to use: `work` and `update` when a node does slow work and then adds its result;
`mergeRules` when nodes change different properties, or add to the same list, or must not
overwrite each other without a word; a reducer of your own when the step has to
choose or compare whole states.
