# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
Read CONTRIBUTING.md as well; its design rules apply to every change.

## Build & Test Commands

```bash
./gradlew check                              # Everything: compile all targets, tests, ktlint, coverage gate
./gradlew check apiCheck                     # What CI runs; apiCheck fails if the public API changed
./gradlew :telar-core:jvmTest         # Fast loop: core tests on the JVM only
./gradlew :telar-core:allTests        # Core tests on every target this OS can run (jvm, js, wasmJs, native)
./gradlew :telar-langchain4j:test     # LangChain4j module (JVM-only module, so the task is `test`)
./gradlew :telar-agent:jvmTest        # Agent module on the JVM (`allTests` for every target)
./gradlew :telar-checkpoint-browser:allTests  # Browser module, in headless Chrome
./gradlew kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock  # After a dependency change that touches JS or Wasm
./gradlew ktlintFormat                       # Fix formatting
./gradlew apiDump                            # Update */api/*.api after an intentional public API change
./gradlew :samples:runQuickStart             # Run a sample
(cd docs && npm ci && npm run build)         # Build the documentation site; fails on a dead link
```

Single test class:

```bash
./gradlew :telar-core:jvmTest --tests "dev.deeptelar.telar.InterruptTest"
```

**Requirements:** JDK 17+. Use the `./gradlew` wrapper (Gradle 9.8, Kotlin 2.4). Versions live in
`gradle/libs.versions.toml`.

## Architecture Overview

Telar is a Kotlin Multiplatform execution engine for stateful AI agent workflows. Computation
is a directed graph: nodes transform an immutable state and edges decide what runs next.

The project was called langgraph-kt until `0.1.0-alpha05`, and the changelog entries of the releases
made under that name keep it. The repository is `deeptelar/telar` and the demo is
`deeptelar/telar-demo`.

### Modules

- **`telar-core`** (KMP, `commonMain` only, depends only on kotlinx-coroutines): graph
  builder, engine, checkpoint interfaces
- **`telar-serialization`** (KMP): `KotlinxStateSerializer` for `@Serializable` states,
  `CheckpointCodec` (the shared checkpoint format, also used by `FileCheckpointer`)
- **`telar-checkpoint-file`** (KMP): `FileCheckpointer` on kotlinx-io
- **`telar-checkpoint-browser`** (JS and Wasm, browser only, code in `webMain`):
  `LocalStorageCheckpointer` on `localStorage`. Its tests run in headless Chrome
- **`telar-agent`** (KMP): `ChatModel` (the provider-neutral model interface), `ChatMessage`,
  `Tool` (JSON Schema built from a `@Serializable` input class in `ToolSchema.kt`), and the
  tool-calling loop: `toolLoop` adds a model node and a tools node to any graph, `toolAgent` is that
  loop as a graph over `AgentState`. Also `DecisionModel` (see `telar-typesafe`) and
  `ChatDecisionModel`, which decides with any `ChatModel`
- **`telar-anthropic`** (KMP): `AnthropicChatModel`, the Claude Messages API on Ktor client
  core. The app supplies the `HttpClient` and its engine
- **`telar-openai`** (KMP): `OpenAiChatModel`, the Chat Completions API on Ktor client core. One
  class for OpenAI, Ollama (`OpenAiChatModel.ollama`) and every other server with that API, chosen
  by `baseUrl`
- **`telar-langchain4j`** (JVM): `LangChain4jChatModel` adapts a LangChain4j 1.x model to
  `ChatModel`; `chatNode` / `chatMessagesNode` build a node straight from a LangChain4j model
- **`telar-typesafe`** (KMP): `TypeSafeDecisionModel`, the Jev decision model of TypeSafe AI
  (System One API) on Ktor client core. It implements `DecisionModel` of `telar-agent`, the
  interface for models that pick an option, answer yes or no, or rate, instead of writing;
  `decisionEdge` routes a graph with one
- **`telar-mcp`** (KMP): `mcpTools(client)` reads the tools of a Model Context Protocol server
  and returns them as `Tool`s. It is built on the official MCP Kotlin SDK
  (`io.modelcontextprotocol:kotlin-sdk-client`, an `api` dependency): the app connects and closes
  the SDK's `Client`. Its tests talk to `FakeMcpServer`, a server over channels in `commonTest`,
  because the server side of the SDK has no iOS targets
- **`telar-test`** (KMP, no test framework): helpers for the tests of an app. `ScriptedChatModel`
  and `ScriptedDecisionModel` give the answers of a script in order and keep their requests; a
  script that is too short throws an `AssertionError`, which the engine passes on. `record()`
  collects a stream into a `RunRecord` (`nodes`, `steps`, `text`, `result`). The tests of
  `telar-agent` do not use it: they have their own `ScriptedModel`
- **`samples`**: runnable examples with tests; not published. `samples/.../tutorial/levelN` is the
  code of the tutorial in `docs/tutorial/`; a page shows its level's code and output, so change both together
- **`docs`**: the documentation site (VitePress), published at <https://deeptelar.github.io/telar/>
  with the Dokka API reference under `api/`. `index.md` is the home page, `guides/` has a page per
  feature, `tutorial/` a page per level; the menu is in `docs/.vitepress/config.ts`. The README is
  the short pitch and links to the site, so a feature's guide goes in `docs/guides/`, not in the
  README. Links between pages are relative and end in `.md`; links to files outside `docs/` are
  full GitHub addresses
- **`build-logic`**: convention plugins `telar.kmp-library`, `telar.web-library`,
  `telar.jvm-library`, `telar.quality` (ktlint, Kover, Dokka), `telar.publishing`,
  `telar.root`

### Execution Flow

1. **Define** with the `StateGraph<State> { ... }` DSL. `node(name) { ... }` returns a whole state;
   `node(name, work) { state, result -> ... }` splits a node into slow work and a state update, for
   nodes that run in parallel. Both take a `retry` (a `Retry`: how often the node is tried and the
   pause between tries) and a `timeout` for one try; the tries happen inside the node's step, and a
   stream gets a `GraphEvent.NodeRetry` for each failed one. `subgraph(name, graph, state, update)`
   adds a compiled graph as a node, with the two functions mapping its state out of and into this
   graph's. All return a `NodeRef`; connect nodes with `START then a then b then END`,
   `edge(from, to)`, or `conditionalEdge(from, targets) { ... }`.
2. **Compile** with `.compile()`, which validates the graph and returns a `CompiledGraph<State>`. It
   needs a `reducer` only when two nodes that return a whole state can run in the same step.
3. **Execute** with `invoke(input, config)` (returns `GraphResult.Completed` or `.Interrupted`) or
   `stream(input, config)` (a `Flow<GraphEvent<State>>`). A node calls `reportProgress(value)` to
   send a `GraphEvent.NodeProgress` to a stream while it runs; `toolLoop` reports the model's text
   that way (`ChatModel.stream`, `chatWithProgress`, `GraphEvent.textDelta`). The events of a
   subgraph arrive wrapped in `GraphEvent.SubgraphEvent`. A node pauses the run
   from inside with `interrupt(state)`: the state it passes is saved and its step runs again.
   Continue a paused run with `resume(config) { state -> ... }` / `streamResume`.
   `lastResult(config)` reads where a thread stopped without running it.

The engine (`CompiledGraph.kt`) runs in steps: all active nodes run in parallel on the same input
state, their results are combined (the `Reducer` merges whole states, then the updates of
work/update nodes are applied in the order the nodes were added), and outgoing edges select the
next active nodes. A checkpoint is saved after every step when a checkpointer is configured.

### Core Abstractions

| Type | Purpose |
|------|---------|
| `NodeAction<State>` | `suspend (State) -> State`, a node's transformation |
| `EdgeCondition<State>` | `suspend (State) -> String`, routes to the next node name or `END` |
| `Reducer<State>` | `fun interface` with a suspend `reduce`; merges whole states of parallel nodes; not needed for work/update nodes. `mergeRules { append(...); replace(...); merge(...) }` builds one from a rule per property; `compile(rules = mergeRules { ... })` makes the rules hold for work/update nodes too (each node reads the state from before the step) |
| `GraphConfig<State>` | `threadId`, `checkpointer`, `interruptBefore/After` (sets), `maxIterations` |
| `GraphResult<State>` / `GraphEvent<State>` | Outcome of `invoke`/`resume`, and events from `stream` (per node and per step) |
| `GraphTopology` | Nodes and edges of a compiled graph, from `CompiledGraph.topology` |
| `Checkpointer<State>` | `save` / `load` / `delete` per thread, and `history` (one checkpoint per step; the default returns only the latest). `CompiledGraph.history` reads it and `fork` continues from an earlier checkpoint on a new thread |
| `Checkpoint<State>` | `state`, `nextNodes` (empty when complete), `step`, `interruptedBefore`, `subgraphs` (a `SubgraphPosition` per subgraph the run paused in) |
| `TelarException` | Base of all library exceptions (see `Exceptions.kt`) |

### Key Design Rules

1. **Immutability.** State is a `data class`; nodes return `.copy()`. No `var` or mutable
   collections in state.
2. **Coroutines only.** Everything is `suspend`. No blocking calls; wrap blocking libraries in
   `withContext(Dispatchers.IO)`. Never swallow `CancellationException`.
3. **Small, type-safe API.** Explicit API mode is on: public declarations need `public`, explicit
   types and KDoc. Keep internals `internal`. Validate in `compile()` rather than at run time, and
   throw `TelarException` subclasses.
4. **Core stays common.** No platform or third-party dependencies in `telar-core`.

### Testing

- Core tests live in `commonTest` and use `runTest { ... }`, so they run on every target.
- Use `MemoryCheckpointer` unless the test is about files.
- `check` enforces 90% line coverage per module (Kover). Kover measures the JVM only, so the
  browser-only module has no gate: cover its behavior with tests in `webTest`.
- After an intentional public API change, run `./gradlew apiDump` and commit `*/api/*`.
