# Tools from MCP servers

Many services publish a [Model Context Protocol](https://modelcontextprotocol.io) server: a file
system, a database, a browser, an issue tracker. Such a server lists its tools and runs them, so an
agent gets them without a `Tool` written by hand for each. `telar-mcp` turns the tools of a server
into tools of an agent:

```kotlin
dependencies {
    implementation("dev.deeptelar:telar-mcp:0.1.0-alpha14")
}
```

```kotlin
// 1. Connect, with the Kotlin SDK of MCP. Over HTTP this works on every platform.
val server = HttpClient().mcpStreamableHttp("https://mcp.example.com/mcp")

// 2. Read the tools of the server, and give them to an agent next to your own.
val agent = toolAgent(model, tools = mcpTools(server) + listOf(orderStatus))

val state = agent.invoke(AgentState("Which issues are open?")).state

// 3. Close the connection when the app is done with the agent.
server.close()
```

The module is built on the official
[Kotlin SDK of MCP](https://github.com/modelcontextprotocol/kotlin-sdk), which comes with it. The
app connects with the SDK and owns the connection, as it owns the `HttpClient` of a model.
`mcpTools` takes the connected `Client`, asks the server for all of its tools and returns a
`List<Tool>`. Each tool has the name, the description and the input schema the server gave it, and
running it calls the server.

## Connecting

`mcpTools` works with every connection the SDK has.

**Over HTTP**, for a server that runs somewhere else. This works on every platform, also in a
browser. `requestBuilder` adds what the server asks for, such as a token:

```kotlin
val server = HttpClient().mcpStreamableHttp("https://mcp.example.com/mcp") {
    header("Authorization", "Bearer $token")
}
```

**A process on the same machine**, for a server that is started with a command. This is the JVM
version:

```kotlin
val process = ProcessBuilder("npx", "-y", "@modelcontextprotocol/server-filesystem", "/home/ana/notes").start()

val server = Client(Implementation(name = "my-app", version = "1.0"))
server.connect(StdioClientTransport(process.inputStream.asSource().buffered(), process.outputStream.asSink().buffered()))

val tools = mcpTools(server)
// ...
server.close()
process.destroy()
```

## Picking tools

A server often has more tools than an agent should have. `mcpTools` takes a function that gets each
tool as the server describes it and returns `true` for the ones to keep:

```kotlin
// By name.
val tools = mcpTools(server) { tool -> tool.name in setOf("search_issues", "get_issue") }

// By what the server says a tool does. These are hints of the server, not a guarantee.
val readOnly = mcpTools(server) { tool -> tool.annotations?.readOnlyHint == true }
```

Two servers can both have a `search`, and `toolLoop` wants every name once. A `prefix` goes before
every name of a server:

```kotlin
val tools = mcpTools(github, prefix = "github_") + mcpTools(jira, prefix = "jira_")
```

To approve the calls by hand, run the graph with `interruptBefore = setOf("tools")`, as for your own
tools. See [Agents with tools](agents-with-tools.md).

## What the model gets back

- **Text.** The text parts of a result, joined.
- **Images and PDFs as files.** A JPEG, PNG, GIF or WebP image and a PDF reach the model as
  [attachments](ai-models.md#images-and-pdfs) of the result.
- **Anything else as one line.** Another image, a sound or a file becomes a line that says what it
  was, such as `[audio: audio/wav]` or `[resource: Report, file:///report.pdf]`.
- **An error as an error.** A result the server marks as failed reaches the model as an error
  result, like a tool of your own that throws. So does a call the server refused, and a call that
  got no answer in time. The run goes on, and the model can react.

A call may take 60 seconds, the limit of the SDK. `mcpTools(server, timeout = 10.seconds)` changes
it for the tools it returns.

## What to know

- **Read the tools before you build the graph.** `mcpTools` is a `suspend` function that talks to
  the server, and `toolLoop` checks the names of its tools when the graph is built.
- **The list is read once.** A server that adds or removes tools later is not followed. Call
  `mcpTools` again and build the agent again.
- **A name is changed when a model would not accept it.** Models accept letters, digits, `_` and
  `-`. Any other character, such as the dot of `repo.search`, is given to the model as `_`, and
  the server is called with the real name. A name longer than 64 characters is rejected: leave
  that tool out.
- **Tools only.** The resources and the prompts of a server are not turned into anything. The
  `Client` is yours, so `server.listResources()` and `server.readResource(...)` are there.
- **The tools of a server are not your code.** A tool can do what its server allows, and its
  description is written by someone else and read by your model. Connect servers you trust, and
  give an agent the tools it needs, not all of them.
- **In a test, no server is needed.** A scripted model can ask for any tool, and a tool is a
  function: see [Testing](testing.md).

Runnable version: [`McpTools`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/McpTools.kt). It
starts a small server in its own process, so it runs anywhere:

```bash
./gradlew :samples:runMcpTools

# With a real server that is reached over HTTP, and a real model:
MCP_SERVER_URL=http://localhost:3001/mcp MCP_QUESTION="What is 20 plus 22?" ANTHROPIC_API_KEY=your-key ./gradlew :samples:runMcpTools
```
