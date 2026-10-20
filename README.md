<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/telar-lockup-on-dark.svg">
    <img alt="Telar" src="docs/brand/telar-lockup.svg" width="340">
  </picture>
</h1>

[![CI](https://github.com/deeptelar/telar/actions/workflows/ci.yml/badge.svg)](https://github.com/deeptelar/telar/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.deeptelar/telar-core?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.deeptelar/telar-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)

**AI agents and workflows for Kotlin, as small typed graphs.** You write each step as a `suspend`
function on a `data class` of your own, and connect the steps with arrows. Telar runs the graph on
every Kotlin platform, from a server to Android, iOS and the browser, and takes care of loops,
parallel steps, live streaming, save points, and pausing until a person approves.

**[Try it in your browser](https://deeptelar.github.io/telar-demo/):** Pixel Pizza is a
small game in which every stage runs a Telar graph, from two nodes in a row to a full agent
workflow. No account and no API key needed.

**[Plan a food trip](https://deeptelar.github.io/telar-foodie/):** Foodie Route is a complete app
on Telar. An agent asks what you like, reads a travel guide and the street map at the same time,
plans a route of meals and changes it until you say yes, all in your browser.
[Its source](https://github.com/deeptelar/telar-foodie) shows each part of Telar at work.

**[Documentation](https://deeptelar.github.io/telar/)** ·
[Quick start](https://deeptelar.github.io/telar/quick-start) ·
[Tutorial](https://deeptelar.github.io/telar/tutorial/) ·
[Guides](https://deeptelar.github.io/telar/guides/) ·
[API reference](https://deeptelar.github.io/telar/api/)

## An agent in a few lines

A model that calls your functions until it can answer. `toolAgent` is that loop, ready-made:

```kotlin
import dev.deeptelar.telar.agent.*
import dev.deeptelar.telar.anthropic.AnthropicChatModel
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

@Serializable
data class City(@Description("A city, for example \"Lisbon\"") val name: String)

// A tool: a function the model may call. Its input class becomes the schema the model reads.
val weather = Tool<City>("weather", "Returns today's weather in a city.") { city ->
    "Light rain and 17 °C in ${city.name}." // call your real service here
}

suspend fun main() {
    val model = AnthropicChatModel(HttpClient(), apiKey = System.getenv("ANTHROPIC_API_KEY"), model = "claude-opus-5-5")
    val agent = toolAgent(model, tools = listOf(weather), system = "You are a travel assistant.")

    // The agent is a graph like any other: stream it, save it, pause it before a tool runs.
    agent.stream(AgentState("Do I need an umbrella in Lisbon today?")).collect { event ->
        event.textDelta?.let { print(it) } // the answer, piece by piece
    }
}
```

```kotlin
// build.gradle.kts. `@Serializable` needs the plugin kotlin("plugin.serialization").
implementation("dev.deeptelar:telar-agent:0.1.0-alpha12")
implementation("dev.deeptelar:telar-anthropic:0.1.0-alpha12") // or telar-openai: OpenAI, Gemini, Ollama, Groq, ...
implementation("io.ktor:ktor-client-cio:3.6.0")                // any Ktor engine
```

The same agent with OpenAI, Gemini or a local Ollama model, a person who approves each tool call,
and a chat that continues across turns: [Agents with tools](https://deeptelar.github.io/telar/guides/agents-with-tools).

## A workflow that waits for a person

When an agent alone is not enough, draw the workflow yourself. Nodes are steps, `then` is an arrow,
and a conditional edge picks the next step by looking at the state:

```kotlin
import dev.deeptelar.telar.*

data class Refund(val orderId: String, val amount: Int, val approved: Boolean = false, val status: String = "new")

val refunds = StateGraph<Refund> {
    val check = node("check") { it.copy(status = if (it.amount > 1000) "too large" else "checked") }
    val pay = node("pay") { it.copy(status = if (it.approved) "paid" else "rejected") }
    val decline = node("decline") { it.copy(status = "declined") }

    START then check
    conditionalEdge(check, targets = setOf(pay, decline)) { if (it.status == "checked") pay else decline }
    pay then END
    decline then END
}.compile() // a node nothing leads to, or a target that is not declared, fails here

suspend fun main() {
    // Stop before money moves. The run is saved after every step.
    val config = GraphConfig(threadId = "order-1001", checkpointer = MemoryCheckpointer<Refund>(), interruptBefore = setOf("pay"))

    refunds.invoke(Refund("1001", amount = 250), config)             // runs "check", then waits before "pay"
    val result = refunds.resume(config) { it.copy(approved = true) } // a person said yes
    println(result.state.status)                                     // paid
}
```

This one needs only `telar-core`. With `FileCheckpointer` in place of `MemoryCheckpointer`, the run
waits in a file and continues after a restart:
[Human-in-the-loop](https://deeptelar.github.io/telar/guides/human-in-the-loop). The
[quick start](https://deeptelar.github.io/telar/quick-start) explains a graph line by line.

## Why Telar

- **Your state is a plain `data class`.** Each node returns a `.copy()` of it, so a run is easy to
  test, print and save, and parallel branches cannot overwrite each other by accident.
- **Mistakes in the graph fail in `compile()`.** An unknown node, a node nothing leads to, or two
  parallel nodes with no rule to merge their results are reported before anything runs.
- **Pause anywhere, continue later.** Stop before a node, or call `interrupt` from inside one when
  it finds out it needs a person. The run is saved, and `resume` continues it hours later, in
  another process. `history` and `fork` go back to any earlier step.
- **Every Kotlin platform.** The core depends only on kotlinx-coroutines and runs on the JVM,
  Android, iOS, macOS, Linux, Windows, JS and Wasm. Agents, models and checkpoints work there too,
  down to runs saved in SQLite on a phone or in the browser's `localStorage`.
- **Coroutines all the way.** Nodes, edges and models are `suspend` functions. Cancellation works
  as you expect, and parallel nodes use structured concurrency.
- **Any model, small surface.** Claude, OpenAI, Gemini, Ollama and every server with the API of
  OpenAI, plus any LangChain4j model on the JVM. `ChatModel` is one function, so a model of your
  own, or a fake one in a test, takes a few lines. Tools are `suspend` functions of your own or the
  tools of any MCP server, and `ask` returns a `@Serializable` class in place of text.
- **Ready for production.** A node can be tried again and given a time limit, a payment in it runs
  once even when its step runs again, a run reports its nodes to OpenTelemetry, and an agent stops
  at a token budget. `telar-test` tests a graph with an agent without an API key.

Choose Telar when the workflow itself is the hard part: several steps, branches that join, people
who approve, runs that must survive a restart, and state you want to read as ordinary Kotlin. If you
need a stable API today, many ready-made integrations, or runs saved in a database server, Koog,
LangGraph4j or LangChain4j are the safer choice. [Why Telar](https://deeptelar.github.io/telar/why-telar#telar-and-other-libraries) compares Telar with Koog,
LangGraph4j and LangChain4j.

> Telar is an independent project inspired by [LangGraph](https://github.com/langchain-ai/langgraph).
> It is not affiliated with or endorsed by LangChain, Inc.

## Installation

```kotlin
dependencies {
    implementation("dev.deeptelar:telar-core:0.1.0-alpha12")      // the graph builder and the engine
    implementation("dev.deeptelar:telar-agent:0.1.0-alpha12")     // chat models, tools and the tool-calling agent
    implementation("dev.deeptelar:telar-anthropic:0.1.0-alpha12") // Claude
    implementation("dev.deeptelar:telar-openai:0.1.0-alpha12")    // OpenAI, Gemini, Ollama and compatible servers
}
```

The artifacts are on Maven Central and need Kotlin 2.x. There are more modules, for saving runs to
files or in a browser, for LangChain4j models and for decision models:
[Installation](https://deeptelar.github.io/telar/installation) lists every module and its targets.

Telar is in alpha. `0.1.0-alpha12` is the latest release. The API may still change before `1.0`; the
[changelog](CHANGELOG.md) lists what changes in each version, and the [roadmap](ROADMAP.md) lists
what is planned.

## Learn more

| I want to | Go to |
|---|---|
| See a complete example with every line explained | [Quick start](https://deeptelar.github.io/telar/quick-start) |
| Learn graphs and agents from zero, in eight levels like a game | [Tutorial](https://deeptelar.github.io/telar/tutorial/) |
| Look up one feature: streaming, approvals, parallel steps, subgraphs, models, tools | [Guides](https://deeptelar.github.io/telar/guides/) |
| See graphs run before I write any code | [Pixel Pizza](https://deeptelar.github.io/telar-demo/), a game in the browser |
| See an agent app that uses all of it: parallel steps, approvals, tools, save points, streaming | [Foodie Route](https://deeptelar.github.io/telar-foodie/), which plans where to eat on a trip, in the browser ([source](https://github.com/deeptelar/telar-foodie)) |
| Run complete programs | [Samples](https://deeptelar.github.io/telar/samples), such as `./gradlew :samples:runQuickStart` |
| Read what a function does | [API reference](https://deeptelar.github.io/telar/api/) |
| See a complete app | [telar-demo](https://github.com/deeptelar/telar-demo), a Compose Multiplatform app for the browser, the desktop and Android |

The pages of the site are the Markdown files in [`docs/`](docs).

## Contributing

Contributions are welcome. The issues labeled [good first issue](https://github.com/deeptelar/telar/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)
are small and say where to start. [CONTRIBUTING.md](CONTRIBUTING.md) has the development setup and
the three design rules (immutable state, coroutines only, type-safe DSL), and the
[roadmap](ROADMAP.md) lists what is planned.

## License

[Apache License 2.0](LICENSE)
