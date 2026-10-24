# Level 8: Your own workflow

**Goal:** put every move together, then design a graph for a problem of your own.

**New moves:** none. This is the final level: you have all the pieces.

## The complete help desk

Levels 1 to 7 each showed one move on a small graph. Here they are in one:

```mermaid
flowchart LR
    S([START]) --> read
    read -.delivery.-> look_up
    read -.refund.-> prepare
    read -.menu.-> model
    read -.anything else.-> write
    look_up --> kitchen --> write
    look_up --> driver --> write
    write --> check
    check -.has a problem.-> write
    check -.good.-> E([END])
    prepare --> P{{pause}} --> pay --> E
    model -.asks for a tool.-> tools --> model
    model -.has its answer.-> send --> E
```

[`level8/Level8.kt`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/tutorial/level8/Level8.kt)
has the whole program. This is the part that draws the map:

```kotlin
START then read
conditionalEdge(read, targets = setOf(lookUp, prepare, agent, write)) { ticket ->
    when (ticket.topic) {
        "delivery" -> lookUp
        "refund" -> prepare
        "menu" -> agent
        else -> write
    }
}

lookUp then kitchen then write
lookUp then driver then write
write then check
conditionalEdge(check, targets = setOf(write, NodeRef.END)) { ticket ->
    if (ticket.problem.isEmpty() || ticket.attempts >= MAX_ATTEMPTS) NodeRef.END else write
}

prepare then pay then END
send then END
```

```bash
./gradlew :samples:runLevel8
```

```
ticket-1: Hi Ana, your pizza left the oven and the driver is 5 minutes away.
ticket-2: a manager approves the refund of 12 euros
ticket-2: Sorry Ana! We sent you 12 euros.
ticket-3: Hi Ana! One salad costs 6 euros.
ticket-4: Hi Ana, a colleague will reply soon.
```

Open the file and find each move:

| Move | Where | Level |
|---|---|---|
| A choice | The conditional edge after `read` | 2 |
| Two things at once | `look_up` has two arrows, to `kitchen` and `driver`, which each have a `work` and an `update` | 4 |
| A loop | `check` sends a reply without the customer's name back to `write` | 3 |
| A pause | `interruptBefore = setOf(PAY)` in `main`, then `resume` | 5 |
| A save slot per job | `threadId = "ticket-1"`, `"ticket-2"`, ... | 5 |
| An agent with tools | `agent` is a `toolLoop` with the price tool of level 6, for questions about the menu. `helpDesk(model)` takes its model as a parameter, and `main` passes the pretend model. | 6 |

Two details are new. The arrows of the agent are not in the code above, because `toolLoop` draws
them: from `model` to `tools` and back, and from `model` to `send`, the node given as `then`.

And a router returns one name, so it cannot start two nodes. To split into parallel
work after a choice, route to one node and give that node two arrows. That is all `look_up` is for:
its function returns the ticket unchanged.

## A recipe for your own graph

When you start from an empty file, go through these questions in order.

**1. What is the job, from input to result?** Say it in one sentence: "Take a customer message and
produce a reply." The input and the result are the first fields of your state.

**2. What are the pieces of work?** Write them as a list of verbs: read, look up, write, check,
pay. Each one becomes a node. A good node does one thing, and you can say what it reads from the
state and what it adds. If a node needs "and" to describe, split it.

**3. What does each piece need and produce?** Everything a node produces that another node needs
becomes a field of the state. Give every field a starting value (`""`, `0`, `emptyList()`) unless
it is part of the input.

**4. What comes after what?** Draw the arrows on paper first. Start at `START` and make sure every
path reaches `END`.

**5. Where does the path depend on the result?** Each such place is a conditional edge. Let a node
write the fact into the state, and let the router only look at it.

**6. Does anything repeat?** A loop is a conditional edge that points back. Put a counter in the
state and a limit in the router.

**7. Can anything run at the same time?** Work that does not depend on each other, such as several
lookups, can share a step. Draw several arrows from one node, and give each of those nodes a
`work` and an `update`.

**8. Does a step have to decide by itself what to look up?** When you know which lookups a job
needs, write them as nodes: they are cheaper and easier to test. When that depends on what the
customer wrote, give a model the lookups as tools and add it with `toolLoop`.

**9. Where must a human look first?** Anything that costs money, cannot be undone, or goes out to a
customer. Put `interruptBefore` there, give the run a checkpointer and a `threadId`, and continue
with `resume`.

**10. What can fail?** Every node that calls a model or a network. With a checkpointer you can retry
from the last save with `resume`.

Start with steps 1 to 4 and get a straight line working, as in level 1. Then add one move at a
time and run it after each. That is how this tutorial was built, and it works for real graphs too.

## A skeleton to copy

```kotlin
data class MyState(
    val input: String,
    val result: String = "",
)

fun myGraph(): CompiledGraph<MyState> =
    StateGraph<MyState> {
        val first = node("first") { state -> state.copy(result = "...") }
        val second = node("second") { state -> state.copy(result = state.result + "...") }

        START then first then second then END
    }.compile()

suspend fun main() {
    val result = myGraph().invoke(MyState(input = "..."))
    println(result.state.result)
}
```

## Testing a graph

A graph is a function from a starting state to a final state, which makes it easy to test: run it
and compare. Two habits keep the tests fast and reliable:

- **Pass in what the nodes depend on.** The help desks of levels 6 and 8 take the model as a
  parameter, so a test passes a pretend model that answers at once and always the same.
- **Use `runTest`** from `kotlinx-coroutines-test`. It lets a test call `suspend` functions, and it
  skips waiting: a `delay` of one second takes no real time.

```kotlin
@Test
fun `rewrites the reply until it passes the check`() =
    runTest {
        val state = helpDesk().invoke(Ticket("Ana", "My pizza is late!")).state

        assertEquals(3, state.attempts)
        assertEquals("Sorry Ana, your pizza is late. It arrives in 10 minutes.", state.reply)
    }
```

All the levels are tested this way in
[`TutorialTest.kt`](https://github.com/deeptelar/telar/blob/main/samples/src/test/kotlin/dev/deeptelar/telar/samples/tutorial/TutorialTest.kt).
For tests that pause and resume, use `MemoryCheckpointer`.

For a graph with an agent, `telar-test` has the pretend model ready: a `ScriptedChatModel` gives
the answers the test wrote down, in order, and remembers what it was asked. The guide
[Testing](../guides/testing.md) shows it.

## Using the library in your own project

So far you worked inside the Telar repository. In your own Gradle project, add the library as a
dependency:

```kotlin
dependencies {
    implementation("dev.deeptelar:telar-core:0.1.0-alpha14")

    // Only if you need them:
    implementation("dev.deeptelar:telar-serialization:0.1.0-alpha14")      // save @Serializable states
    implementation("dev.deeptelar:telar-checkpoint-file:0.1.0-alpha14")    // FileCheckpointer
    implementation("dev.deeptelar:telar-checkpoint-browser:0.1.0-alpha14") // LocalStorageCheckpointer (browser)
    implementation("dev.deeptelar:telar-agent:0.1.0-alpha14")              // ChatModel, tools, toolAgent
    implementation("dev.deeptelar:telar-anthropic:0.1.0-alpha14")          // AnthropicChatModel
    implementation("dev.deeptelar:telar-openai:0.1.0-alpha14")             // OpenAiChatModel (OpenAI, Ollama)
    implementation("dev.deeptelar:telar-langchain4j:0.1.0-alpha14")        // LangChain4j models (JVM, Java 17+)
    implementation("dev.deeptelar:telar-typesafe:0.1.0-alpha14")           // TypeSafeDecisionModel (Jev)
}
```

Until `0.1.0-alpha05` the project was called langgraph-kt. If you used it under that name, the
[changelog](https://github.com/deeptelar/telar/blob/main/CHANGELOG.md) says how to update.

The artifacts are on Maven Central, so your project needs the `mavenCentral()` repository. The
[installation page](../installation.md) lists the targets of each module.

## Your turn: the final quest

Pick one and build it from an empty file, using the recipe:

- **Homework helper.** Input: a question. Nodes: `explain` writes an explanation, `quiz` writes a
  practice question about it, and a `check` loop makes sure the explanation is shorter than 300
  characters.
- **Trip planner.** Input: a city. Look up the weather and three sights at the same time, then
  write a plan for the day. Pause before `book` so the traveller can approve it.
- **Something of your own.** Any job you would otherwise do in several steps by hand.

Use pretend functions first, as the tutorial does. Plug in a real model when the graph works.

## Game complete

You can build a workflow with choices, loops, parallel work, live progress, pauses and retries. You
know where an AI model goes, and how to let it call your functions. From here:

- The [cheat sheet](cheat-sheet.md) has every word and every move on one page.
- The [guides](../guides/index.md) are shorter and denser, and include a reviewer who can send
  work back and a chat that continues over several turns.
- The other [samples](https://github.com/deeptelar/telar/tree/main/samples/src/main/kotlin/dev/deeptelar/telar/samples) are complete programs.
- The [demo app](https://github.com/deeptelar/telar-demo) shows graphs running behind a
  user interface, in the browser and on the desktop.
- The [API reference](https://deeptelar.github.io/telar/api/) describes every function.

[Back to level 7](07-game-over-screens.md) · [All levels](./)
