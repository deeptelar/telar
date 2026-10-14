# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, minor versions may contain
breaking changes.

## [Unreleased]

### Added

- `effect` runs a side effect, such as a payment or an email, once for the step of its node:
  `val receipt = effect("charge") { payments.charge(order) }`. Its result is saved in the checkpoint
  as soon as the block returns, and when the step runs again (a `retry`, a `resume` after a failed
  step, a `resume` after `interrupt`) the call returns that result instead of running the block.
  The nodes of a parallel step that had already finished no longer repeat what they did when
  another node of the step fails. `effect(key) { ... }` with a `@Serializable` result, a basic
  type or `Unit` is in `telar-serialization`; `telar-core` has `effect(key, serializer) { ... }`.
  The guide [Side effects](https://deeptelar.github.io/telar/guides/side-effects) shows it.

- `ChatModel.ask` in `telar-agent`: ask a model for a `@Serializable` class and get an instance of
  it, `model.ask<Verdict>("Classify this email: ...")`. The model gets the schema of the class, which
  is built as the input schema of a tool is. An answer that cannot be read goes back to the model
  with what was wrong, and the model is asked again, `attempts` times at most. It works with every
  `ChatModel`. The guide [AI models](https://deeptelar.github.io/telar/guides/ai-models) shows it.
- `StateTool` in `telar-agent`: a tool of `toolLoop` that reads the state of the run and changes
  it. It gets the state next to its input and returns a `ToolOutcome`, the state it leaves and the
  text for the model, so what it did is in every checkpoint. Give them to `toolLoop` as
  `stateTools`. The calls of an answer then run one after the other, each on the state the one
  before left, and a state tool that calls `interrupt` saves what the calls before it did.
- `toolLoop` and `toolAgent` have `inOrder`: `true` runs the tool calls of an answer one after the
  other, in the order the model wrote them, for tools that depend on each other. The guide
  [Agents with tools](https://deeptelar.github.io/telar/guides/agents-with-tools) shows both.
- The tokens of a run are counted. `AgentState` has `tokens`, the tokens of every answer of the
  model in its conversation, and `toolAgent(model, tools, tokenBudget = 50_000)` stops asking the
  model once the count has reached the budget. In `toolLoop`, `countTokens` adds the tokens of
  each answer to your state and `stopWhen` is asked before each call to the model: when it returns
  `true`, the loop ends and the graph continues at `then`. `TokenUsage` has `+` and `total`, and
  `TokenUsage()` is the start of a count.

- A node can be tried again when it fails, and can have a time limit:
  `node("stock", retry = Retry(attempts = 3), timeout = 30.seconds) { ... }`, and the same for a
  node with a `work` and an `update`. A node with a `Retry` runs again on the same state, in the
  same step, with a pause that grows from try to try, and the run fails only when the last try
  fails. A try that takes longer than `timeout` is cancelled and fails with a
  `NodeTimeoutException`. A stream has a `GraphEvent.NodeRetry` for each try that failed, and
  `NodeExecutionException` has `attempts`. `toolLoop` and `toolAgent` take a `retry` for the node
  that asks the model. The guide
  [Retries and time limits](https://deeptelar.github.io/telar/guides/retries) shows it.
- `telar-mcp`, a new module for every platform: `mcpTools(server)` returns the tools of a
  [Model Context Protocol](https://modelcontextprotocol.io) server as tools of an agent, so
  `toolAgent(model, tools = mcpTools(server) + ownTools)` uses them next to the tools of the app.
  It is built on the official Kotlin SDK of MCP: the app connects with the SDK, over HTTP on every
  platform or to a process, and closes the connection. A `filter` picks tools, a `prefix` keeps
  the names of two servers apart, and an error of the server reaches the model as an error result.
  The guide [Tools from MCP servers](https://deeptelar.github.io/telar/guides/mcp) shows it.
- `telar-test`, a new module with helpers for the tests of an app, for every platform and without a
  test framework. `ScriptedChatModel { calls("order_status", OrderLookup("Ana")); says("5 minutes.") }`
  is a `ChatModel` that gives the answers of its script in order and keeps the requests it
  received; its script also has answers for `ask`, calls that fail, and the tokens of an answer.
  `ScriptedDecisionModel { chooses("refund"); no() }` is the same for a `DecisionModel`. `record()`
  collects the stream of a run and returns its `nodes`, `steps`, `text` and `result`. The guide
  [Testing](https://deeptelar.github.io/telar/guides/testing) shows it.
- `graph.test(input) { ... }` in `telar-test` checks the path of a run, one step after the other:
  `expectNode("check")`, `expectStep("web", "papers")`, `expectInterruptBefore("pay") { state -> ... }`,
  `resumeWith { it.copy(approved = true) }`, `expectFinished { ... }` and `expectFailure { ... }`.
  The test fails with the path of the run when a check does not hold, and when the run did more than
  the test checked. `graph.replay(checkpoint) { ... }` has the same checks for a run that continues
  from a saved checkpoint: read the checkpoint of a run that failed in production with
  `CheckpointCodec`, and run it again with a scripted model or a fixed node.

### Changed

- `GraphEvent` has a new subclass, `NodeRetry`. A `when` over all events needs a branch for it.
- `TokenUsage` is `@Serializable`, so that a state with a count can be saved. `AgentState` is saved
  with its `tokens`; a state saved by an earlier version is read with a count of zero.
- `Checkpoint` has a new property, `effects`. A `Checkpointer` of your own has to store it; one
  built on `CheckpointCodec` does. The codec writes a checkpoint with effects as version 3 of its
  format, which earlier versions refuse to read.

### Fixed

- A tool call whose arguments are not JSON, which a model behind `OpenAiChatModel` or
  `LangChain4jChatModel` can send, no longer runs the tool with an empty input. The tool then failed
  as if a property were missing, so the model fixed the wrong thing, or, when every property has a
  default, it ran with none of what the model asked for. `ToolCall` has
  `invalidInput`, the text the model sent, and the call gets an error result that quotes it, so the
  model can send it again as JSON.

## [0.1.0-alpha09] - 2026-10-10

### Changed

- A node, a condition, a reducer or a tool that throws something that is neither an `Exception` nor
  an `Error`, a `Throwable` of your own, now fails with a `NodeExecutionException`, an
  `EdgeConditionException`, a `ReducerException` or an error result. It passed through the library
  before. The same holds for one that throws `Error` itself and not a subclass of it.

### Fixed

- In a browser, `AnthropicChatModel`, `OpenAiChatModel` and `TypeSafeDecisionModel` report a request
  that got no response as a `ChatModelException` or a `DecisionModelException`, as on every other
  platform: `Could not reach the API at http://localhost:11434/v1: Fail to fetch`. Ktor's engine for
  JS and Wasm reports such a request as an `Error`, which the three classes let pass, so a run
  ended with that `Error` and not with a `NodeExecutionException`. It happens when the server is
  not running, the device is offline, or the server does not take requests from the page.
- In a browser, a node, a conditional edge, a reducer or a tool that makes a request of its own
  fails like on every other platform when the request gets no response. The same holds for an error
  that JavaScript throws, such as one of an API of the browser. Neither is an `Exception` in
  Kotlin, so the engine let both pass: a run ended with the raw error and not with a
  `NodeExecutionException`, and a tool failed the whole run and gave the model no error result.
  What is still not treated as a failure is an error of the program or of its machine: a subclass
  of `Error`, such as `OutOfMemoryError`, `AssertionError` or the `NotImplementedError` of `TODO()`.
  The guide [Errors](https://deeptelar.github.io/telar/guides/errors) says what counts as a failure.

## [0.1.0-alpha08] - 2026-10-10

### Added

- `ChatDecisionModel` in `telar-agent`: a `DecisionModel` that asks any `ChatModel`, so `choose`,
  `isYes`, `score` and `decisionEdge` work with Claude, OpenAI, Gemini, Ollama or any other chat
  model. One call answers every question of a request. The model estimates how likely each option
  is, and `confidence` is computed from those as Jev computes it: how far the most likely option is
  above an even split.
- `AnthropicChatModel`, `OpenAiChatModel` and `TypeSafeDecisionModel` have a `timeout`: how long one
  call may take, from the request to the end of the answer. The default is five minutes for the two
  chat models and one minute for Jev.

### Changed

- The guides moved from the README to a documentation site, <https://deeptelar.github.io/telar/>,
  which also has the tutorial, a menu and a search. The API reference, which was at that address,
  is now at <https://deeptelar.github.io/telar/api/>.
- **A call to a model is no longer cut off by a limit of the Ktor engine.** The CIO engine ends a
  request after 15 seconds, also when a streamed answer is still arriving, and OkHttp ends one that
  waits 10 seconds for data. The `timeout` of the model now replaces the limits of the client and
  of its engine for the requests of that model. If you set those limits yourself with the
  `HttpTimeout` plugin and want to keep them, pass `timeout = null`.
- A call that ran out of time fails with a message that says so, such as `The API at
  https://api.openai.com/v1 did not finish its answer within 5m.` It said `Could not reach the API`,
  also when a part of the answer had arrived.

### Fixed

- `OpenAiChatModel` reports an error of Gemini with its status and its message, such as
  `API error 404 (NOT_FOUND): This model is no longer available.` Gemini sends the error inside a
  list, and the message of the exception was the whole body of the response.
- A `toolAgent` works with Gemini through `OpenAiChatModel`. Gemini sends a thought signature with a
  tool call and rejects the next request when the signature is not in it. `OpenAiChatModel` now
  keeps the `extra_content` of an answer and of its tool calls in
  `ChatMessage.Assistant.providerContent` and sends it back.

## [0.1.0-alpha07] - 2026-10-09

### Added

- `telar-openai`, a new module for every target. `OpenAiChatModel` calls a model through the Chat
  Completions API, with tools and streaming. OpenAI is the default, `OpenAiChatModel.ollama` calls a
  model that Ollama runs on your machine, and `baseUrl` reaches any other server with this API, such
  as LM Studio, vLLM, Groq or OpenRouter. Before, these models were reached through LangChain4j, on
  the JVM only.
- Decision models: models that decide instead of writing. `DecisionModel` in `telar-agent` is the
  interface, next to `ChatModel`: a model picks one of your options, answers yes or no, or rates on
  a scale, and says how sure it is. `choose`, `isYes` and `score` ask one question, and
  `decisionEdge` is a conditional edge on which such a model picks the next node and sends a
  decision below `minConfidence` to a `fallback`.
- `telar-typesafe`, a new module for every target. `TypeSafeDecisionModel` calls Jev, the decision
  model of TypeSafe AI, through its System One API.
- `interrupt(state)` pauses a run from inside a node. A node that finds out while it works that it
  needs a person saves a state with its question and stops there. `resume` writes the answer into
  the state and runs the node again from its first line. Before, a run paused only before or after
  the nodes that `GraphConfig` names.
- `mergeRules` builds a `Reducer` from one rule for each property, so that nodes that return a whole
  state can run in the same step without a reducer that merges whole states by hand: `append` adds
  what each node added to a list, `replace` takes the value of the node that changed it, and `merge`
  combines the changed values with a function of yours. A node that changes a property without a
  rule fails the run with a `MergeRuleException` as the cause, and loses nothing silently.
- `subgraph(name, graph, state, update)` in `StateGraph`: a compiled graph is a node of another
  graph. `state` reads the state of the subgraph out of the state of the graph around it, and
  `update` writes it back. When a node of the subgraph calls `interrupt`, the graph around pauses
  too, and `resume` continues inside the subgraph, at the node that paused. A subgraph with the same
  state type needs no mapping: `subgraph(name, graph)`. Not yet: `interruptBefore` for a node of a
  subgraph.
- `GraphEvent.SubgraphEvent`: a stream of a graph has the events of its subgraphs. Each one holds
  an event of the subgraph, with the subgraph's state, and names the node that runs it. `path` and
  `innermost` read an event of a subgraph inside a subgraph. **A `when` over every kind of
  `GraphEvent` needs a branch for it.**
- `GraphTopology.subgraphs`: the nodes and edges of the graph behind each subgraph node.
- `textDelta` also reads the text of a model that runs inside a subgraph.

### Changed

- `Checkpoint` has a fifth property, `subgraphs`: where a paused run stands inside its subgraphs.
  A `Checkpointer` of your own that does not use `CheckpointCodec` has to store it with the rest.
  `CheckpointCodec` writes a checkpoint that has it as format version 2. Every other checkpoint is
  written as before, and the checkpoints of earlier versions are read.
- **A thread keeps the checkpoint of every step**, where it kept only the last one.
  `CompiledGraph.history(config)` returns them, oldest first, and `fork(checkpoint, config)`
  continues from one of them on a new thread without changing the thread it comes from
  (`streamFork` for the events). A fork onto a thread that has a checkpoint fails with the new
  `ThreadAlreadyExistsException`.
- `Checkpointer` has a fourth function, `history`. Its default returns the latest checkpoint, so a
  `Checkpointer` of your own compiles and works as before, with a history of one. To keep more,
  store what `CheckpointCodec.append` returns and read it with `decodeHistory`.
- `MemoryCheckpointer` and `FileCheckpointer` keep every step and take a `maxHistory`.
  `LocalStorageCheckpointer` keeps the latest checkpoint unless it gets a `maxHistory`. A file or an
  entry with a history has one line of JSON for each checkpoint. What an earlier version stored is
  read, but **an earlier version cannot read a thread that has more than one checkpoint**.

## [0.1.0-alpha06] - 2026-10-07

### Added

- A [roadmap](ROADMAP.md): what is planned before `1.0`, in which order, and what is not planned.

### Changed

- **The project is now called Telar.** It was langgraph-kt. To update:
  - Dependencies: `io.github.cuento3yllevo2:langgraph-kt-<module>` becomes
    `dev.deeptelar:telar-<module>`, for example `dev.deeptelar:telar-core`.
  - Imports: the package `org.langgraphkt` becomes `dev.deeptelar.telar`.
  - `LangGraphException` is now `TelarException`.
  - `LocalStorageCheckpointer` stores its entries under `telar.checkpoint.` by default, where it
    was `langgraph.checkpoint.`. Pass `keyPrefix = "langgraph.checkpoint."` to read the runs an
    earlier version saved.
- The repository moved to <https://github.com/deeptelar/telar>, and the API reference to
  <https://deeptelar.github.io/telar/>. Links to the old repository still work; the old address
  of the API reference does not.

## [0.1.0-alpha05] - 2026-10-05

### Added

- `langgraph-kt-checkpoint-browser`, a new module for Kotlin/JS and Kotlin/Wasm in a browser.
  `LocalStorageCheckpointer` keeps the checkpoints in the page's `localStorage`, so a paused run is
  still there after a reload. A full or blocked storage is reported as a `LocalStorageException`.
- The tutorial teaches agents with tools. Level 6, "The agent", has a node that asks a model, a
  tool, `toolAgent`, and the same loop in a graph of your own with `toolLoop`. Its program runs
  without an API key (`./gradlew :samples:runLevel6`).

### Changed

- The tutorial has eight levels, one for each stage of the Pixel Pizza game, in place of eleven:
  1. A line of nodes (was "Your first graph" and "A line of nodes")
  2. Choices
  3. Loops
  4. Two things at once (was "Doing two things at once" and "Watching a run")
  5. Save points
  6. The agent (was "A real AI model", now on the `ChatModel` of `langgraph-kt-agent` in place of
     LangChain4j's `chatNode`, and with tools)
  7. Game over screens
  8. Your own workflow, whose complete help desk now has a tool agent

  The pages in `docs/`, the run tasks (`runLevel1` to `runLevel8`) and the sample packages are
  renumbered to match. "The idea" is an introduction without a number.

## [0.1.0-alpha04] - 2026-10-04

### Added

- Streaming of a model's answer. A `toolAgent` or `toolLoop` whose run is collected with `stream`
  delivers the text while the model writes it, and `event.textDelta` reads each piece.
  - `ChatModel.stream(request)` returns a `Flow` of `ChatEvent.TextDelta` and a final
    `ChatEvent.Completed`. A model that only has `chat` delivers its text in one piece.
  - `chatWithProgress` does the same for a model call in a node of your own. It takes a
    `ChatRequest` or a prompt, like `chat`.
  - `AnthropicChatModel` streams on every target, over server-sent events.
  - `LangChain4jChatModel` takes a LangChain4j `StreamingChatModel` as a second argument and streams
    through it.
- `reportProgress(value)` in `langgraph-kt-core`: a node reports what it is doing while it runs, and
  the value arrives in the stream of the run as the new `GraphEvent.NodeProgress`.
  `isProgressCollected()` tells a node whether anyone collects it.
- `AnthropicChatModel` keeps only the text of the first model when another model took over an answer
  through the `fallbacks` parameter, as the API requires for the next request.

### Changed

- `GraphEvent` has a sixth kind, `NodeProgress`. A `when` over the events without an `else` branch
  needs a branch for it.

## [0.1.0-alpha03] - 2026-10-04

### Added

- `toolLoop` takes a `firstMessage`: the user message that starts the conversation, built from other
  fields of the state. The loop stores it with the model's first answer, so `messages` and `append`
  only read and write one list. Before, the first message had to be built in `messages`, and an
  `append` that added to the stored list lost it without an error. The parameter comes before
  `system`, so pass `system` by name.

### Changed

- `ChatResponse.truncated` moved to the message: read `ChatMessage.Assistant.truncated`, and set it
  there in a `ChatModel` of your own. Before, an answer that was cut off at the model's output limit
  could not be told from a complete one once it was in the state, because `toolLoop` only passes
  messages to `append`. `AgentState.answerTruncated` reports it for a `toolAgent`.
- An exception of the library that a node throws is now wrapped in `NodeExecutionException` like any
  other exception. Before, it reached the caller as it was, so a failed model call in a `toolAgent`
  did not say which node failed. Where you caught `ChatModelException` from `invoke`, `resume` or
  `stream`, catch `NodeExecutionException` and read its `cause`. The same holds for the function of a
  conditional edge (`EdgeConditionException`) and for the reducer (`ReducerException`), and for a
  node that runs another graph: the failure of the inner graph is the `cause`.

## [0.1.0-alpha02] - 2026-10-04

### Added

- `langgraph-kt-agent`, a new module for every target, with what a graph needs to work with a
  language model:
  - `ChatModel`, a one-function interface to a model provider, with `ChatRequest`, `ChatResponse`
    and `ChatMessage` (`User`, `Assistant`, `ToolResult`). Messages are `@Serializable`.
  - `Tool`, a function the model may call. `Tool<Input>(name, description) { input -> ... }` builds
    the JSON Schema of the tool from a `@Serializable` input class, and `@Description` describes a
    property to the model.
  - `toolAgent(model, tools, system)`, a ready-made tool-calling agent graph over `AgentState`, and
    `toolLoop`, which adds the same loop to a graph with a state of your own.
  - `pendingToolCalls()`, the tool calls that wait when a run is paused before its tools.
  - `ChatModelException`.
- `langgraph-kt-anthropic`, a new module for every target: `AnthropicChatModel` calls Claude through
  Ktor.
- `LangChain4jChatModel` in `langgraph-kt-langchain4j`, which makes any LangChain4j model a
  `ChatModel`.
- The `ToolAgent` sample.

## [0.1.0-alpha01] - 2026-10-04

First public release. Everything below is new compared with the unpublished beta.

### Added

- Kotlin Multiplatform support: JVM/Android, iOS, macOS, Linux, Windows, JS and Wasm.
- `GraphResult` (`Completed` / `Interrupted`) as the return type of `invoke` and `resume`.
- `CompiledGraph.resume()` and `streamResume()` to continue a paused run, optionally editing the state.
- `CompiledGraph.lastResult()` to read where a thread stopped without running it.
- `GraphEvent` stream (`NodeStarted`, `NodeCompleted`, `StepCompleted`, `Interrupted`, `Completed`)
  and `Flow<GraphEvent>.states()`.
- `CompiledGraph.topology` (`GraphTopology`, `GraphEdge`) to inspect or draw a compiled graph.
- Graph validation in `compile()`: unknown, unreachable and duplicate nodes or edges, conflicting
  edge kinds, and two nodes that return a whole state in the same step without a reducer.
- `conditionalEdge(from, targets)` with declared targets, checked at compile time and at run time.
- Nodes with a `work` and an `update`, `node(name, work) { state, result -> ... }`, for parallel
  branches. Their work runs at the same time and their updates are applied one after another, so
  they need no `Reducer`.
- `NodeRef` and the infix `then` for type-safe edges: `START then a then b then END`.
- Conditional edges that route between node references instead of names:
  `conditionalEdge(a, targets = setOf(b, c)) { b }`, with `NodeRef.END` to finish.
- `LangGraphException` hierarchy: `GraphValidationException`, `NodeExecutionException`,
  `EdgeConditionException`, `ReducerException`, `InvalidRouteException`,
  `MaxIterationsExceededException`, `CheckpointNotFoundException`,
  `GraphAlreadyCompletedException`, `CheckpointCorruptedException`.
- `Checkpointer.delete()`, and a checkpoint after every step with a step counter.
- `langgraph-kt-serialization` module with `KotlinxStateSerializer`, and `CheckpointCodec` to build
  a checkpointer for any storage.
- `langgraph-kt-checkpoint-file` module: `FileCheckpointer` with atomic writes and a versioned format.
- `chatNode` and `chatMessagesNode` for LangChain4j 1.x `ChatModel`.
- Runnable samples in `samples/`.
- A step-by-step tutorial in `docs/`, with a runnable program for every level.

### Changed

- `invoke` always starts a new run; it no longer loads an existing checkpoint.
- `GraphConfig.interruptBefore` / `interruptAfter` are sets, and need a checkpointer.
- `Reducer.reduce` is a suspend function.
- `FileCheckpointer` takes a `kotlinx.io.files.Path` and lives in `org.langgraphkt.checkpoint.file`.
- `MemoryCheckpointer` is safe for concurrent use.
- Requires Kotlin 2.x; JVM artifacts target Java 11, except `langgraph-kt-langchain4j`, which needs
  Java 17 because LangChain4j does.

### Removed

- The `resume: Boolean` parameter of `invoke` and `stream`.
- `generateNode` and `generateSuspending` (replaced by `chatNode` and `chatSuspending`).
- `Checkpoint.nextNode` and the single-node constructor.
- Public access to `Node`, `Edge`, `ConditionalEdge` and the `CompiledGraph` constructor.

### Fixed

- A completed thread could not be run again.
- `invoke` ignored its input when the thread had a checkpoint.
- A `withTimeout` that expired inside a node cancelled the caller instead of raising
  `NodeExecutionException`.
- `resume` ran an `interruptBefore` node without pausing when the run had paused through
  `interruptAfter` or was resumed after a crash. `Checkpoint.interruptedBefore` records the pause.
- An exception thrown by the function of a conditional edge or by the reducer reached the caller
  unwrapped. It is now an `EdgeConditionException` or a `ReducerException`.
- A run that failed in its first step left no checkpoint, so `resume` could not retry it. The
  run's input is now saved before the first node runs.
- Different thread ids could map to the same checkpoint file.
- `START` counted toward `maxIterations`.
- `FileCheckpointer` did not write the format version into its files.

[Unreleased]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha09...HEAD
[0.1.0-alpha09]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha08...v0.1.0-alpha09
[0.1.0-alpha08]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha07...v0.1.0-alpha08
[0.1.0-alpha07]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha06...v0.1.0-alpha07
[0.1.0-alpha06]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha05...v0.1.0-alpha06
[0.1.0-alpha05]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha04...v0.1.0-alpha05
[0.1.0-alpha04]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha03...v0.1.0-alpha04
[0.1.0-alpha03]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha02...v0.1.0-alpha03
[0.1.0-alpha02]: https://github.com/deeptelar/telar/compare/v0.1.0-alpha01...v0.1.0-alpha02
[0.1.0-alpha01]: https://github.com/deeptelar/telar/releases/tag/v0.1.0-alpha01
