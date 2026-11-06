# Memory across conversations

A checkpoint holds one thread, and a conversation is a thread. What an agent learns about a user
in one conversation, such as how they eat, is gone in the next one unless it is kept somewhere
else. A `Store` is that place. It belongs to no thread, so every run that gets the same store
reads what the others wrote.

```kotlin
val store = MemoryStore()

val agent = toolAgent(
    model,
    tools = memoryTools(),
    system = "You plan meals. Remember what the traveller tells you about how they eat.",
)

// The first conversation: the agent calls remember("diet", "Piscivegetarian.").
agent.invoke(AgentState("I'm piscivegetarian."), GraphConfig("plan-1", store = store.within("users", userId)))

// A week later, on a new thread: the agent calls recall() and finds it.
agent.invoke(AgentState("Plan my lunch in Porto."), GraphConfig("plan-2", store = store.within("users", userId)))
```

`MemoryStore` keeps its items in memory, for tests and for apps that remember only while they run.
An app that remembers for longer uses one of the stores [below](#stores-that-keep-what-they-remember).

## What a store holds

A value is text under a namespace and a key. A namespace is a list of names, and `list` finds
everything under a part of it, the last changed first:

```kotlin
store.put(listOf("users", "ana", "preferences"), "diet", "Piscivegetarian.")
store.put(listOf("users", "ana", "trips"), "last", "Lisbon, in May.")

store.get(listOf("users", "ana", "preferences"), "diet")?.value  // "Piscivegetarian."
store.list(listOf("users", "ana"))                              // both items, the trip first
store.delete(listOf("users", "ana", "preferences"), "diet")
```

A `put` under a key that is used replaces its value. Each item also has the time it was put last,
`updatedAt`.

With `telar-serialization`, a `@Serializable` value is kept as JSON. Import
`dev.deeptelar.telar.serialization.get` and `dev.deeptelar.telar.serialization.put`:

```kotlin
@Serializable
data class Diet(val name: String, val avoids: List<String>)

store.put(listOf("preferences"), "diet", Diet("piscivegetarian", listOf("meat")))
val diet = store.get<Diet>(listOf("preferences"), "diet")
```

A `String` is kept as it is, without JSON. Without `telar-serialization`, `get` and `put` take a
`StateSerializer` instead.

## One store for every user

`store.within("users", userId)` is a view of the store whose namespaces all start with those
parts. Give each run the view of its user, and the same graph, compiled once, serves every user
without reading what another user's runs wrote. The items a view returns leave out the parts it
adds.

## Reading and writing in a node

A node reaches the store of its run with `store()`. So does the `work` of a node, a tool that a
node runs, the nodes of a subgraph and the agents of a team:

```kotlin
data class Trip(val request: String, val remembered: String = "", val messages: List<ChatMessage> = emptyList())

val graph = StateGraph<Trip> {
    val load = node("load") { trip ->
        trip.copy(remembered = store().list(listOf("memories")).joinToString("\n") { "${it.key}: ${it.value}" })
    }
    START then load then toolLoop(
        model,
        tools = memoryTools(),
        messages = { it.messages },
        append = { trip, new -> trip.copy(messages = trip.messages + new) },
        firstMessage = { trip -> "${trip.request}\n\nWhat you remember about me:\n${trip.remembered}" },
    )
}.compile()
```

The node `load` gives the model what it remembers before it starts, without a call to `recall`.

`store()` throws a `GraphValidationException` when the run has no store, or outside a running
node. In a tool, the model gets that as an error result.

## The tools of the agent

`memoryTools(namespace = listOf("memories"))` returns three tools:

- `remember(key, text)` keeps a text under a short name that the model chooses, such as `diet`.
  Remembering under a name that is used replaces what was there, so a preference that changes
  stays one memory.
- `forget(key)` removes it, for a user who asks the agent to forget something.
- `recall()` lists what is kept, the last changed first, at most `limit` items.

Tell the model in its system prompt what is worth remembering. A model that is not told tends to
remember nothing, or everything.

## When a step runs again

A write goes straight to the store, and is not part of the checkpoint:

- **A tool call of the model is made once.** `toolLoop` runs each call as an
  [effect](side-effects.md), so when its step runs again after a failure or a pause, `remember` is
  not called again.
- **A `put` from a node is made again.** A key holds one value, so the store ends up with the value
  of the last try, which is the try whose state the run continues with.
- **A node that reads a value and writes one made from it**, such as a counter, wraps the two in an
  [`effect`](side-effects.md), as it would with any other service.
- **A step that fails and is never resumed keeps what it wrote.** Deleting a thread, and `fork`,
  leave the store as it is.

## Stores that keep what they remember

Each module with a checkpointer has a store too, for the same platforms:

| Store | Module | Where it keeps the items |
|---|---|---|
| `FileStore(directory)` | `telar-checkpoint-file` | A folder for each part of a namespace, and in it one JSON file with the items of that namespace. Every platform with a file system; JS and Wasm on Node.js only |
| `SqlDelightStore(driver)` | `telar-checkpoint-sqldelight` | A table `telar_store` with a row for each item, in any SQLite database of SQLDelight: Android, iOS, desktop, servers, and the browser, where the database of SQLDelight's worker is in memory |
| `LocalStorageStore()` | `telar-checkpoint-browser` | An entry of the page's `localStorage` for each item, so the items survive a reload |

`SqlDelightStore` creates its table when it first needs it, so one database, and one driver, can
hold the checkpoints and the memories:

```kotlin
val driver = AndroidSqliteDriver(SqlDelightCheckpointer.Schema, context, "runs.db")
val config = GraphConfig(
    threadId = "plan-7",
    checkpointer = SqlDelightCheckpointer(driver, KotlinxStateSerializer<Trip>()),
    store = SqlDelightStore(driver).within("users", userId),
)
```

A write of each store is atomic: a crash leaves the items as they were or with the write done. Two
writes of one store never get the same `updatedAt`, so `list` keeps their order also with a clock
that counts only milliseconds, as a browser's does. A store that cannot read what it finds throws a
`StoreCorruptedException`.

## Your own store

`Store` is an interface with four functions, `get`, `put`, `delete` and `list`. Check what you are
given with `Store.checkAddress`, `Store.checkNamespace` and `Store.checkLimit`, as `MemoryStore`
does, so that a store behaves the same whichever one an app uses.
