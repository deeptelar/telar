# Why Telar

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

## Telar and other libraries

A Kotlin project has other good choices for AI agents. This is how we see the differences on
2026-10-15; if something here is wrong or out of date, please [open an issue](https://github.com/deeptelar/telar/issues).

| | Telar | [Koog](https://github.com/JetBrains/koog) | [LangGraph4j](https://github.com/langgraph4j/langgraph4j) | [LangChain4j](https://github.com/langchain4j/langchain4j) |
|---|---|---|---|---|
| **What it is** | A graph engine for agents and workflows | JetBrains' framework for AI agents | A Java port of LangGraph | A Java toolkit for LLM apps |
| **Platforms** | JVM, Android, iOS, macOS, Linux, Windows, JS, Wasm | JVM, Android, iOS, JS, Wasm | JVM (Java 17+) | JVM (Java 17+) |
| **Workflow state** | Your own immutable `data class` | Values passed between the nodes of a strategy, and the agent's storage | A map of channels with reducers | Mostly the conversation memory |
| **Saved runs** | Memory, files, the browser, and SQLite on every platform | Agent persistence: checkpoints of an agent | Memory, and savers for PostgreSQL, MySQL, Redis, SQLite and more | Chat memory stores |
| **Around the agent** | MCP tools, typed answers, retries and time limits, side effects that run once, token budgets, OpenTelemetry, test helpers | MCP tools, retries, streaming, history compression, OpenTelemetry | Subgraphs, parallel nodes, streaming, OpenTelemetry, Mermaid and PlantUML diagrams | MCP tools, structured outputs, RAG, observability listeners |
| **Stage** | Alpha: the API can still change | Stable `1.x` | Stable `1.x` | Stable `1.x` |
| **Best at** | Workflows with typed state, checks before a run, pauses and save points, on every platform, from a server to a phone and a browser | A complete agent platform with many providers, backed by JetBrains | LangGraph's design on the JVM, from Java | Integrations: models, vector stores, RAG |

Choose Telar when the workflow itself is the hard part: several steps, branches that join, people
who approve, runs that must survive a restart, and state you want to read as ordinary Kotlin, on a
server as well as in an app on a phone or in a browser. If you need a stable API today, many
ready-made integrations, or runs saved in a database server, Koog, LangGraph4j or LangChain4j are
the safer choice. Telar works with LangChain4j: [`telar-langchain4j`](guides/ai-models.md) turns any
of its models into a `ChatModel`.

> Telar is an independent project inspired by [LangGraph](https://github.com/langchain-ai/langgraph).
> It is not affiliated with or endorsed by LangChain, Inc.
