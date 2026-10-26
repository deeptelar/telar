# Roadmap

Telar aims to be the open-source library a Kotlin developer reaches for to build AI agents and
workflows: agents first, simple to start with, and with the most complete set of workflow features.
Models, and the ways of working with them, change fast. This plan changes with them: what
developers need for their agents goes on this page, and the order follows what they ask for.

This page lists what is planned for Telar before `1.0`. It is a plan and not a promise: there
are no dates, and the order can change with what users ask for. The [changelog](CHANGELOG.md) lists
what is already released.

To ask for a feature or to move one up, add a 👍 to its issue or
[start a discussion](https://github.com/deeptelar/telar/discussions).

Last updated: 2026-10-26.

## Where the project is

`0.1.0-alpha14` is the latest release on Maven Central. Telar has the graph builder and the engine:
loops, parallel branches with rules for what they write, subgraphs, streaming, checkpoints with the
history of a run, pausing for a person before a node or in the middle of its work, retries and time
limits for a node, nodes that run once for each item of a list, and side effects that run once per
step. Around the engine it has checkpointers for memory, files, the browser and SQLite on every
platform, a tool-calling agent for every platform with tools that change the state, typed answers, a
token budget and the tools of MCP servers, model modules for Claude, for OpenAI, Ollama and the
other servers with the Chat Completions API, for LangChain4j, and for Jev, a decision model, and
modules for tests and for OpenTelemetry. The API can still change in any release.

Section 1 is done, a node can run once for each item of a list, and a message can carry images and
PDFs ([#103]). Section 2 is what is worked on now, and it is the last part that changes an API that
exists: first a subgraph that can pause before one of its nodes ([#34]), then the order of the
reducer and the updates of a step ([#101]). After that comes section 3, which starts with long
conversations ([#105]), because a long conversation is still sent to the model in full every time,
and then teams of agents and a memory that an agent keeps across conversations.

## The way to 1.0

| Stage | What happens | The public API |
|---|---|---|
| **Alpha** (now) | The features below are added, in the order of this page | May change in any release |
| **Beta** | No new features. The API is reviewed and the documentation completed | Changes only for a good reason, and the changelog says how to update |
| **1.0** | Released when the beta has been used in real apps without API changes | Stable. A breaking change waits for `2.0` |

The features are numbered in the order they are worked on. Two rules decide it:

- **What an agent needs in a real app comes first.** These are the things a developer looks for
  when choosing a library, and the things the samples of Telar had to write by hand.
- **A change to an API that exists comes before a new module.** Sections 1 and 2 change
  `telar-core` and `telar-agent`, so that their API can stop moving as early as possible. Most of
  section 3 is new modules, which change nothing that exists.

The issues have the same order as labels: `priority: now` for section 2, the one worked on now
(section 1 is done), and `priority: later` for section 3.

Each item has one of four states:

- **Done**: in `main`, and in the release the [changelog](CHANGELOG.md) names.
- **In progress**: someone is writing it.
- **Planned**: it will be in `1.0`.
- **Exploring**: wanted, but the design is open. It may arrive after `1.0`.

## 1. An agent for a real app

| | Feature | What you get | State | Issue |
|---|---|---|---|---|
| 1 | Typed answers from a model | Ask a model for a `@Serializable` class and get an instance of it, built on the schema generator that tools already use: `ChatModel.ask`. | Done | [#85] |
| 2 | Tools that change the state | A tool of `toolLoop` reads the state of the run and writes to it, so that a checkpoint holds what the tool did. And calls that depend on each other run in order. `StateTool`, and `inOrder` for `toolLoop`. | Done | [#98] |
| 3 | Token count and budget | A run knows how many tokens its models used, and stops at a limit. `AgentState.tokens`, `tokenBudget`, and `countTokens` / `stopWhen` for `toolLoop`. | Done | [#99] |
| 4 | Retry and time limit for a node | A node says how often it may be tried again and how long it may take. `retry = Retry(...)` and `timeout` on `node`. | Done | [#83] |
| 5 | Helpers for tests | `telar-test`: a model that gives the answers a test wrote down, so that a graph with an agent is tested without an API key and without a model of your own. Also `graph.test { }` and `graph.replay { }` for the path of a run. | Done | [#86] |
| 6 | Tools from MCP servers | Use the tools of a Model Context Protocol server as `Tool`s, without writing each by hand: `telar-mcp`. | Done | [#104] |

## 2. Finish the engine and the messages

These change how a graph runs or what a message holds, so they come before the beta.

| | Feature | What you get | State | Issue |
|---|---|---|---|---|
| 7 | Fan-out over a list | One node runs once per item of a list, all at the same time, when the number of items is only known at run time: `node(name, forEach = { ... }, work = { ... }) { ... }`, with a retry and a time limit for each item, and with each result saved so that a resume runs only the items that are missing. | Done | [#82] |
| 8 | Images and files in messages | Send a model a picture or a PDF with the question, and let a tool return one: `ChatMessage.User(text, attachments)`, `attach()` in a tool, and the images and PDFs of MCP results. Audio, video and images that a model makes are not part of it. | Done | [#103] |
| 9 | Subgraphs | A compiled graph becomes a node of another graph, with a mapping between the two states and one thread for both. Released: `subgraph`, a run that pauses inside a subgraph and resumes there, the events of a subgraph in the stream of the graph around it, and the subgraph in `topology`. Still to come, and the next thing to be built: `interruptBefore` for a node of a subgraph, and a retry after a failure that continues inside the subgraph. | In progress | [#34] |
| 10 | One rule for parallel nodes that write the same property | You decide what happens, and the rule is the same for both ways to write a node: `compile(rules = mergeRules { ... })`, or the same rules given as `reducer`, merges every node of a step, so a node with `work` and `update` no longer overwrites another without a word. Released: `compile(rules = mergeRules { ... })`, and `mergeRules` given as the reducer holds for every node too. Still open: the order of the reducer and the updates when no rules are given. | In progress | [#101] |

## 3. Run it in production

New modules and additions. They change nothing that exists. The maintainers build them in the order
of this table, and someone who needs one sooner can build it at any time.

| | Feature | What you get | State | Issue |
|---|---|---|---|---|
| 11 | Long conversations | A conversation that is kept short for the model, by dropping or summarizing old messages, and that pays less for what the provider already read. Today every request sends everything. | Planned | [#105] |
| 12 | Teams of agents | Ready-made graphs for more than one agent, built on subgraphs: a supervisor that gives each task to the agent that fits it, and agents that hand the conversation to each other. | Planned | |
| 13 | Memory across threads | A store that an agent reads and writes in every conversation of a user, next to the checkpoint of one thread, so that it remembers what it learned last time. | Planned | |
| 14 | Tracing | Hooks in the engine that report every node, edge, tool call and token count, and a module that sends them to OpenTelemetry. Released: `GraphObserver` for runs, nodes, steps and checkpoints, `AgentObserver` for each call to a model and each tool call, with its token count, and `telar-otel`, which sends runs to OpenTelemetry on every platform, the browser included. Still to come: a span in `telar-otel` for each call to a model and each tool call ([#138]), and a report of the calls that an edge condition makes, such as the model of a `decisionEdge` ([#157]). | In progress | [#38] |
| 15 | Database checkpointer | Saves runs in a SQL database. Released: SQLite through SQLDelight, `telar-checkpoint-sqldelight`, for Android, iOS, desktop and the browser. Still to come: a database server such as PostgreSQL ([#137]). | In progress | [#84] |
| 16 | One version for all modules | `telar-bom`: a bill of materials. An app names the version of Telar once, and every module it adds uses that version. | Planned | [#79] |

## 4. Beta: make it stable

- **API review.** Every public declaration is checked once more: its name, its defaults, and whether
  it can be `internal`. `apiCheck` already fails a build that changes the public API by accident.
- **A marker for experimental API.** An opt-in annotation, so that a feature can be added after
  `1.0` without being frozen on its first day.
- **A checkpoint format you can rely on.** A checkpoint written by one version can be read by every
  later `1.x` version, and a guide explains how to change a state class when saved runs exist.
- **Tests in a real browser.** The JS and Wasm tests of the agent and model modules run on Node.js
  today. Only the browser checkpointer is tested in a browser ([#81]).
- **Documentation.** A guide for every feature on this page, a tutorial level where one fits, and a
  page for people who know LangGraph for Python: what has the same name and what is different
  ([#80]).
- **Written rules.** Which Kotlin versions are supported, and how long a deprecated declaration
  stays before it is removed.

## Exploring

Ideas without a place in the plan yet. Say so in the issue or in a discussion if you need one.

| Idea | What it is | Issue |
|---|---|---|
| Functional API | Write a small workflow as ordinary `suspend` functions, without a graph, and keep checkpoints and streaming. | [#39] |
| More typed answers | The provider's own structured output, a sealed class as the answer, and a typed final answer from an agent that called tools. | [#134] |
| More events for a run | How many items a node for each item of a list runs, and when an effect gives back its saved result instead of running. | [#153], [#154] |
| More model providers | Modules of their own for Gemini and Amazon Bedrock. Gemini works today through `OpenAiChatModel` and its `baseUrl`. | |
| Ktor and Spring Boot | A plugin or a starter, so that a server app adds Telar with one dependency and gets its models and checkpointer from its configuration. | |
| A graph that others call | Offer a compiled graph as a tool of an MCP server, or to other agents over the Agent2Agent protocol, from inside your app. | |
| Evaluations | Run a graph over a list of inputs and score its answers, next to the helpers of `telar-test`. | |

## Not planned

- **A port of LangChain.** No prompt templates, vector stores or document loaders. A node is a
  `suspend` function and can call any library.
- **A hosted service or a server that runs graphs.** The library runs inside your app.
- **The same API as LangGraph for Python.** A feature comes over when it fits Kotlin: typed
  immutable state, coroutines, and checks in `compile()`.
- **Reading checkpoints written by LangGraph for Python.**

## Helping

To build something on this page, comment on its issue first, or open one if it has none, so the
approach can be discussed. [CONTRIBUTING.md](CONTRIBUTING.md) has the setup and the design rules.

For a first contribution, the issues labeled
[good first issue](https://github.com/deeptelar/telar/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)
are small and say where to start. The ones labeled
[help wanted](https://github.com/deeptelar/telar/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22)
are larger, or need something the maintainers lack, such as a key for another model provider.

[#34]: https://github.com/deeptelar/telar/issues/34
[#38]: https://github.com/deeptelar/telar/issues/38
[#39]: https://github.com/deeptelar/telar/issues/39
[#79]: https://github.com/deeptelar/telar/issues/79
[#80]: https://github.com/deeptelar/telar/issues/80
[#81]: https://github.com/deeptelar/telar/issues/81
[#82]: https://github.com/deeptelar/telar/issues/82
[#83]: https://github.com/deeptelar/telar/issues/83
[#84]: https://github.com/deeptelar/telar/issues/84
[#85]: https://github.com/deeptelar/telar/issues/85
[#86]: https://github.com/deeptelar/telar/issues/86
[#98]: https://github.com/deeptelar/telar/issues/98
[#99]: https://github.com/deeptelar/telar/issues/99
[#101]: https://github.com/deeptelar/telar/issues/101
[#103]: https://github.com/deeptelar/telar/issues/103
[#104]: https://github.com/deeptelar/telar/issues/104
[#105]: https://github.com/deeptelar/telar/issues/105
[#134]: https://github.com/deeptelar/telar/issues/134
[#137]: https://github.com/deeptelar/telar/issues/137
[#138]: https://github.com/deeptelar/telar/issues/138
[#153]: https://github.com/deeptelar/telar/issues/153
[#154]: https://github.com/deeptelar/telar/issues/154
[#157]: https://github.com/deeptelar/telar/issues/157
