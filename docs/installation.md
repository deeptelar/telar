# Installation

```kotlin
dependencies {
    // The graph builder and the engine. This is all the quick start needs.
    implementation("dev.deeptelar:telar-core:0.1.0-alpha19")

    // Optional modules. Add only the ones you use.
    implementation("dev.deeptelar:telar-serialization:0.1.0-alpha19")      // save @Serializable states
    implementation("dev.deeptelar:telar-checkpoint-file:0.1.0-alpha19")    // save runs as JSON files
    implementation("dev.deeptelar:telar-checkpoint-browser:0.1.0-alpha19") // save runs in a browser's localStorage (JS and Wasm)
    implementation("dev.deeptelar:telar-checkpoint-sqldelight:0.1.0-alpha19") // save runs in SQLite through SQLDelight, on every platform
    implementation("dev.deeptelar:telar-checkpoint-postgres:0.1.0-alpha19") // save runs in PostgreSQL, for servers (JVM)
    implementation("dev.deeptelar:telar-agent:0.1.0-alpha19")              // chat models, tools and the tool-calling agent
    implementation("dev.deeptelar:telar-anthropic:0.1.0-alpha19")          // call Claude, on every platform
    implementation("dev.deeptelar:telar-openai:0.1.0-alpha19")             // call OpenAI, Ollama and compatible servers, on every platform
    implementation("dev.deeptelar:telar-langchain4j:0.1.0-alpha19")        // call AI models through LangChain4j (JVM)
    implementation("dev.deeptelar:telar-typesafe:0.1.0-alpha19")           // ask Jev, a decision model, on every platform
    implementation("dev.deeptelar:telar-mcp:0.1.0-alpha19")                // the tools of an MCP server as tools of an agent
    implementation("dev.deeptelar:telar-otel:0.1.0-alpha19")               // send runs to OpenTelemetry, on every platform
    testImplementation("dev.deeptelar:telar-test:0.1.0-alpha19")           // scripted models and checks for the tests of a graph
}
```

## One version for every module

`telar-bom` names the version of every module of Telar, so an app writes it once and cannot mix
versions:

```kotlin
dependencies {
    implementation(platform("dev.deeptelar:telar-bom:0.1.0-alpha19"))
    implementation("dev.deeptelar:telar-core")
    implementation("dev.deeptelar:telar-agent")
    implementation("dev.deeptelar:telar-anthropic")
    testImplementation("dev.deeptelar:telar-test")
}
```

It also names the artifact of each platform, such as `telar-core-jvm`, so a Maven build can import
it in `<dependencyManagement>` with `<scope>import</scope>` and leave the versions out too.

> Until `0.1.0-alpha05` the project was called langgraph-kt, and its artifacts were
> `io.github.cuento3yllevo2:langgraph-kt-*`. The [changelog](https://github.com/deeptelar/telar/blob/main/CHANGELOG.md) says how to update.

| Module | Targets | Purpose |
|---|---|---|
| `telar-core` | JVM/Android, iOS, macOS, Linux, Windows, JS, Wasm | Graph builder, execution engine, checkpointing interfaces |
| `telar-serialization` | same as core | `KotlinxStateSerializer` for `@Serializable` states, `CheckpointCodec` for custom checkpointers |
| `telar-checkpoint-file` | same as core (Node.js only for JS/Wasm) | `FileCheckpointer`, one JSON file per thread, and `FileStore` |
| `telar-checkpoint-browser` | JS and Wasm in a browser | `LocalStorageCheckpointer`, runs that survive a page reload, and `LocalStorageStore` |
| `telar-checkpoint-sqldelight` | same as core | `SqlDelightCheckpointer`: runs in a SQLite database through SQLDelight, on Android, iOS, desktop, servers and the browser (in memory there), and `SqlDelightStore` |
| `telar-checkpoint-postgres` | JVM | `PostgresCheckpointer` and `PostgresStore`: runs and memories in [PostgreSQL](guides/human-in-the-loop.md#in-postgresql-for-servers), shared by the instances of a server, through the app's JDBC `DataSource` |
| `telar-agent` | same as core | `ChatModel`, `Tool`, and the tool-calling agent: `toolAgent` / `toolLoop`; `memoryTools`, `EmbeddingModel` and `SemanticStore` |
| `telar-anthropic` | same as core | `AnthropicChatModel`, Claude through Ktor |
| `telar-openai` | same as core | `OpenAiChatModel`: OpenAI, Ollama and other servers with the Chat Completions API, through Ktor; `OpenAiEmbeddingModel` for their embeddings |
| `telar-langchain4j` | JVM (Java 17+) | `LangChain4jChatModel` and `chatNode` / `chatMessagesNode` for LangChain4j 1.x models |
| `telar-typesafe` | same as core | `TypeSafeDecisionModel`: Jev, the decision model of TypeSafe AI, through Ktor |
| `telar-mcp` | same as core | `mcpTools`: the tools of a [Model Context Protocol server](guides/mcp.md) as tools of an agent, on the Kotlin SDK of MCP. |
| `telar-otel` | same as core | `OtlpObserver`: the runs and nodes of a graph as [OpenTelemetry traces](guides/tracing.md), sent over HTTP with Ktor on every platform. On the JVM also `OpenTelemetryObserver`, for the OpenTelemetry SDK of Java. |
| `telar-bom` | none: a bill of materials | The version of every module above, for `platform(...)` |
| `telar-test` | same as core | `ScriptedChatModel`, `ScriptedDecisionModel`, `record()`, and `graph.test { }` / `graph.replay { }` for the path of a run, for [tests](guides/testing.md) of a graph. |

Telar is built with Kotlin 2.4. On the JVM, Kotlin 2.3 also works, verified with 2.3.21 for
`telar-core` in `0.1.0-alpha19`. For Native, JS and Wasm, use the Kotlin version used to build
the release. JVM artifacts target Java 11, except `telar-langchain4j`, which needs Java 17
because LangChain4j does.

See [Supported versions and releases](support.md) for the compiler, runtime and release rules.

## Status

Alpha. `0.1.0-alpha19` is the latest release, and it is on Maven Central. The API may still change
before `1.0`; the [changelog](https://github.com/deeptelar/telar/blob/main/CHANGELOG.md) lists what changes in each version, and the
[roadmap](https://github.com/deeptelar/telar/blob/main/ROADMAP.md) lists what is planned.
