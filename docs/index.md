---
layout: home
title: Telar
titleTemplate: AI agents and workflows for Kotlin

banner:
  label: AI workflows, woven
  title: AI agents and workflows for Kotlin, as small typed graphs
  tagline: Write each step as a suspend function on a data class of your own, and connect the steps with arrows. Telar runs the graph on every Kotlin platform.
  imageAlt: The Telar logo, a small loom
  actions:
    - text: Get started
      link: /quick-start
      primary: true
    - text: Tutorial
      link: /tutorial/
    - text: Play the demo
      link: https://deeptelar.github.io/telar-demo/
    - text: Plan a food trip
      link: https://deeptelar.github.io/telar-foodie/
    - text: GitHub
      link: https://github.com/deeptelar/telar

features:
  - title: Your state is a plain data class
    details: Each node returns a copy of it, so a run is easy to test, print and save, and parallel branches cannot overwrite each other by accident.
    link: /quick-start
    linkText: Quick start
  - title: Mistakes fail in compile()
    details: An unknown node, a node nothing leads to, or two parallel nodes with no rule to merge their results are reported before anything runs.
    link: /guides/errors
    linkText: Errors
  - title: Pause anywhere, continue later
    details: Stop before a node, or from inside one when it finds out it needs a person. The run is saved, and resume continues it hours later, in another process.
    link: /guides/human-in-the-loop
    linkText: Human-in-the-loop
  - title: Every Kotlin platform
    details: JVM, Android, iOS, macOS, Linux, Windows, JS and Wasm. Agents, models and checkpoints work there too, down to runs saved in SQLite on a phone or in a browser.
    link: /installation
    linkText: Modules and targets
  - title: Coroutines all the way
    details: Nodes, edges and models are suspend functions. Cancellation works as you expect, and parallel nodes use structured concurrency.
    link: /guides/parallel-branches
    linkText: Parallel branches
  - title: Any model, small surface
    details: Claude, OpenAI, Gemini, Ollama and every server with the API of OpenAI, plus any LangChain4j model on the JVM. Tools of your own or of any MCP server, and typed answers with ask.
    link: /guides/ai-models
    linkText: AI models
---

## An agent in a few lines

A model that calls your functions until it can answer. `toolAgent` is that loop, ready-made:

```kotlin
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

The same agent with OpenAI, Gemini or a local Ollama model, a person who approves each tool call,
and a chat that continues across turns: [Agents with tools](guides/agents-with-tools.md).

## A workflow that waits for a person

When an agent alone is not enough, draw the workflow yourself. Nodes are steps, `then` is an arrow,
and a conditional edge picks the next step by looking at the state:

```kotlin
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

With `FileCheckpointer` in place of `MemoryCheckpointer`, the run waits in a file and continues
after a restart: [Human-in-the-loop](guides/human-in-the-loop.md).

## Add it to your project

```kotlin
// build.gradle.kts
implementation("dev.deeptelar:telar-core:0.1.0-alpha13")      // graphs: all the workflow above needs
implementation("dev.deeptelar:telar-agent:0.1.0-alpha13")     // chat models, tools and the agent
implementation("dev.deeptelar:telar-anthropic:0.1.0-alpha13") // Claude. Or telar-openai: OpenAI, Gemini, Ollama, Groq, ...
```

Telar is in alpha: the API can still change before `1.0`. [Installation](installation.md) lists
every module and its targets, and [Why Telar](why-telar.md) compares it with Koog, LangGraph4j and
LangChain4j.

## Where to go next

| I want to | Go to |
|---|---|
| See a complete example with every line explained | [Quick start](quick-start.md) |
| Learn graphs and agents from zero, in eight levels | [Tutorial](tutorial/) |
| Look up one feature | [Guides](guides/index.md) |
| See graphs run before I write any code | [Pixel Pizza](https://deeptelar.github.io/telar-demo/), a game in the browser |
| See an agent app that uses all of it: parallel steps, approvals, tools, save points, streaming | [Foodie Route](https://deeptelar.github.io/telar-foodie/), which plans where to eat on a trip, in the browser ([source](https://github.com/deeptelar/telar-foodie)) |
| Read what a function does | [API reference](https://deeptelar.github.io/telar/api/) |
