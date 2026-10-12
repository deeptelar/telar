# Installation

```kotlin
dependencies {
    // The graph builder and the engine. This is all the quick start needs.
    implementation("dev.deeptelar:telar-core:0.1.0-alpha09")

    // Optional modules. Add only the ones you use.
    implementation("dev.deeptelar:telar-serialization:0.1.0-alpha09")      // save @Serializable states
    implementation("dev.deeptelar:telar-checkpoint-file:0.1.0-alpha09")    // save runs as JSON files
    implementation("dev.deeptelar:telar-checkpoint-browser:0.1.0-alpha09") // save runs in a browser's localStorage (JS and Wasm)
    implementation("dev.deeptelar:telar-agent:0.1.0-alpha09")              // chat models, tools and the tool-calling agent
    implementation("dev.deeptelar:telar-anthropic:0.1.0-alpha09")          // call Claude, on every platform
    implementation("dev.deeptelar:telar-openai:0.1.0-alpha09")             // call OpenAI, Ollama and compatible servers, on every platform
    implementation("dev.deeptelar:telar-langchain4j:0.1.0-alpha09")        // call AI models through LangChain4j (JVM)
    implementation("dev.deeptelar:telar-typesafe:0.1.0-alpha09")           // ask Jev, a decision model, on every platform
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
| `telar-agent` | same as core | `ChatModel`, `Tool`, and the tool-calling agent: `toolAgent` / `toolLoop` |
| `telar-anthropic` | same as core | `AnthropicChatModel`, Claude through Ktor |
| `telar-openai` | same as core | `OpenAiChatModel`: OpenAI, Ollama and other servers with the Chat Completions API, through Ktor |
| `telar-langchain4j` | JVM (Java 17+) | `LangChain4jChatModel` and `chatNode` / `chatMessagesNode` for LangChain4j 1.x models |
| `telar-typesafe` | same as core | `TypeSafeDecisionModel`: Jev, the decision model of TypeSafe AI, through Ktor |
| `telar-test` | same as core | `ScriptedChatModel`, `ScriptedDecisionModel` and `record()`, for [tests](guides/testing.md) of a graph with an agent. Not released yet: it comes with the version after `0.1.0-alpha09`. |

Requires Kotlin 2.x. JVM artifacts target Java 11, except `telar-langchain4j`, which needs
Java 17 because LangChain4j does.

## Status

Alpha. `0.1.0-alpha09` is the latest release, and it is on Maven Central. The API may still change
before `1.0`; the [changelog](https://github.com/deeptelar/telar/blob/main/CHANGELOG.md) lists what changes in each version, and the
[roadmap](https://github.com/deeptelar/telar/blob/main/ROADMAP.md) lists what is planned.
