# Installation

```kotlin
dependencies {
    // The graph builder and the engine. This is all the quick start needs.
    implementation("dev.deeptelar:telar-core:0.1.0-alpha10")

    // Optional modules. Add only the ones you use.
    implementation("dev.deeptelar:telar-serialization:0.1.0-alpha10")      // save @Serializable states
    implementation("dev.deeptelar:telar-checkpoint-file:0.1.0-alpha10")    // save runs as JSON files
    implementation("dev.deeptelar:telar-checkpoint-browser:0.1.0-alpha10") // save runs in a browser's localStorage (JS and Wasm)
    implementation("dev.deeptelar:telar-checkpoint-sqldelight:0.1.0-alpha10") // save runs in SQLite through SQLDelight, on every platform
    implementation("dev.deeptelar:telar-agent:0.1.0-alpha10")              // chat models, tools and the tool-calling agent
    implementation("dev.deeptelar:telar-anthropic:0.1.0-alpha10")          // call Claude, on every platform
    implementation("dev.deeptelar:telar-openai:0.1.0-alpha10")             // call OpenAI, Ollama and compatible servers, on every platform
    implementation("dev.deeptelar:telar-langchain4j:0.1.0-alpha10")        // call AI models through LangChain4j (JVM)
    implementation("dev.deeptelar:telar-typesafe:0.1.0-alpha10")           // ask Jev, a decision model, on every platform
    implementation("dev.deeptelar:telar-mcp:0.1.0-alpha10")                // the tools of an MCP server as tools of an agent
    implementation("dev.deeptelar:telar-otel:0.1.0-alpha10")               // send runs to OpenTelemetry (JVM and Android)
    testImplementation("dev.deeptelar:telar-test:0.1.0-alpha10")           // scripted models and checks for the tests of a graph
}
```

> Until `0.1.0-alpha05` the project was called langgraph-kt, and its artifacts were
> `io.github.cuento3yllevo2:langgraph-kt-*`. The [changelog](https://github.com/deeptelar/telar/blob/main/CHANGELOG.md) says how to update.

| Module | Targets | Purpose |
|---|---|---|
| `telar-core` | JVM/Android, iOS, macOS, Linux, Windows, JS, Wasm | Graph builder, execution engine, checkpointing interfaces |
| `telar-serialization` | same as core | `KotlinxStateSerializer` for `@Serializable` states, `CheckpointCodec` for custom checkpointers |
| `telar-checkpoint-file` | same as core (Node.js only for JS/Wasm) | `FileCheckpointer`, one JSON file per thread |
| `telar-checkpoint-browser` | JS and Wasm in a browser | `LocalStorageCheckpointer`, runs that survive a page reload |
| `telar-checkpoint-sqldelight` | same as core | `SqlDelightCheckpointer`: runs in a SQLite database through SQLDelight, on Android, iOS, desktop, servers and the browser (in memory there). |
| `telar-agent` | same as core | `ChatModel`, `Tool`, and the tool-calling agent: `toolAgent` / `toolLoop` |
| `telar-anthropic` | same as core | `AnthropicChatModel`, Claude through Ktor |
| `telar-openai` | same as core | `OpenAiChatModel`: OpenAI, Ollama and other servers with the Chat Completions API, through Ktor |
| `telar-langchain4j` | JVM (Java 17+) | `LangChain4jChatModel` and `chatNode` / `chatMessagesNode` for LangChain4j 1.x models |
| `telar-typesafe` | same as core | `TypeSafeDecisionModel`: Jev, the decision model of TypeSafe AI, through Ktor |
| `telar-mcp` | same as core | `mcpTools`: the tools of a [Model Context Protocol server](guides/mcp.md) as tools of an agent, on the Kotlin SDK of MCP. |
| `telar-otel` | JVM/Android | `OpenTelemetryObserver`: the runs and nodes of a graph as [OpenTelemetry traces](guides/tracing.md). |
| `telar-test` | same as core | `ScriptedChatModel`, `ScriptedDecisionModel`, `record()`, and `graph.test { }` / `graph.replay { }` for the path of a run, for [tests](guides/testing.md) of a graph. |

Requires Kotlin 2.x. JVM artifacts target Java 11, except `telar-langchain4j`, which needs
Java 17 because LangChain4j does.

## Status

Alpha. `0.1.0-alpha10` is the latest release, and it is on Maven Central. The API may still change
before `1.0`; the [changelog](https://github.com/deeptelar/telar/blob/main/CHANGELOG.md) lists what changes in each version, and the
[roadmap](https://github.com/deeptelar/telar/blob/main/ROADMAP.md) lists what is planned.
