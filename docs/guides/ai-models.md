# AI models

A node is a `suspend` function, so it can call any AI model with any client library:

```kotlin
val classify = node("classify") { email -> email.copy(category = askMyModel(email.body)) }
```

`telar-agent` has a small interface for the model, `ChatModel`, so that the same graph works
with any provider and on every platform. Pick an implementation:

```kotlin
// Claude, on every platform (telar-anthropic). HttpClient is the Ktor client.
val model: ChatModel = AnthropicChatModel(HttpClient(), apiKey = key, model = "claude-opus-5-5")

// OpenAI, on every platform (telar-openai).
val model: ChatModel = OpenAiChatModel(HttpClient(), apiKey = key, model = "gpt-5")

// Gemini, on every platform (telar-openai): Google has an address with the API of OpenAI.
val model: ChatModel = OpenAiChatModel(HttpClient(), apiKey = key, model = "gemini-3.8-flash", baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai")

// A model that Ollama runs on your machine, without a key (telar-openai).
val model: ChatModel = OpenAiChatModel.ollama(HttpClient(), model = "llama3.2")

// Any LangChain4j model, on the JVM (telar-langchain4j): Gemini, Bedrock, Mistral, ...
val model: ChatModel = LangChain4jChatModel(GoogleAiGeminiChatModel.builder().apiKey(key).modelName("gemini-3.8-flash").build())

// In a test, a lambda.
val model = ChatModel { request -> ChatResponse(ChatMessage.Assistant("Thanks for your email!")) }
```

A call to `AnthropicChatModel` or `OpenAiChatModel` may take five minutes, also with an engine of
Ktor that has a shorter limit of its own, such as the 15 seconds of CIO. Change the limit with
`timeout`. `timeout = null` leaves the limits to the client:

```kotlin
val model: ChatModel = OpenAiChatModel(HttpClient(), apiKey = key, model = "gpt-5", timeout = 10.minutes)
```

A node that needs one piece of text from the model asks for it with `chat`:

```kotlin
val answer = node(
    "answer",
    // The slow call. It can run next to other nodes, see "Parallel branches".
    work = { email -> model.chat("Write a short, friendly reply to this support email: ${email.body}") },
) { email, reply -> email.copy(reply = reply) } // Puts the model's answer into the state.
```

A node that needs a value, not a text, asks for a `@Serializable` class with `ask`:

```kotlin
@Serializable
data class Verdict(
    val category: Category,
    @Description("One sentence that says why") val reason: String,
    val urgent: Boolean = false,
)

val classify = node(
    "classify",
    work = { email -> model.ask<Verdict>("Classify this support email: ${email.body}") },
) { email, verdict -> email.copy(category = verdict.category, urgent = verdict.urgent) }
```

The model gets the schema of the class and is told to reply with JSON that matches it, so `ask`
works with every `ChatModel`. The schema is built as the one of a [tool's input](agents-with-tools.md):
`@Description` describes a property, and a property with a default value or a nullable one may be
left out. An answer that cannot be read goes back to the model with what was wrong, and the model
is asked once more; `attempts` says how often it is asked at most. After that, `ask` throws a
`ChatModelException`.

A `require` in the `init` of the class is checked in the same way, so it is the place for a rule
that a schema cannot say. Its message goes to the model:

```kotlin
@Serializable
data class Rating(val stars: Int) {
    init {
        require(stars in 1..5) { "stars must be from 1 to 5, but was $stars." }
    }
}
```

To ask for a value at the end of a conversation, pass a `ChatRequest`. The result has the value,
the message it was read from and the tokens of the calls it took. The request cannot have tools:
in an agent, let `toolLoop` finish and ask in the node after it.

```kotlin
val response = model.ask<Verdict>(ChatRequest(ticket.messages + ChatMessage.User("What is your verdict?")))
response.value   // Verdict(category = REFUND, ...)
response.message // add it to the conversation to continue it
```

`ChatModel` has one function, `chat(ChatRequest): ChatResponse`, so a model of your own is a few
lines. A failed call throws `ChatModelException`. When the call is made in a node, the run fails
with a `NodeExecutionException` that names the node and has the `ChatModelException` as its `cause`.

To show an answer while the model writes it, `model.stream(request)` returns a `Flow` with a
`ChatEvent.TextDelta` for each piece of text and a final `ChatEvent.Completed` with the whole
answer. In a node, call `chatWithProgress` in place of `chat`, and the pieces arrive in the stream
of the run:

```kotlin
val answer = node(
    "answer",
    work = { email -> model.chatWithProgress("Write a short, friendly reply to this support email: ${email.body}") },
) { email, reply -> email.copy(reply = reply) }

graph.stream(email).collect { event ->
    event.textDelta?.let { piece -> print(piece) } // null for every other event
}
```

`AnthropicChatModel` and `OpenAiChatModel` stream on every platform. `LangChain4jChatModel` streams
when you give it a LangChain4j streaming model as well: `LangChain4jChatModel(gemini, streamingModel)`.
A model that cannot stream delivers its text in one piece, so the same code works with every model.

`OpenAiChatModel` speaks the Chat Completions API, which many servers besides OpenAI have. Give it
their address as `baseUrl`, up to the part before `/chat/completions`:

```kotlin
// Groq, OpenRouter, LM Studio, vLLM, a gateway of your company, ...
val model = OpenAiChatModel(client, apiKey = key, model = "llama-3.3-70b-versatile", baseUrl = "https://api.groq.com/openai/v1")

// More fields for every request go in `parameters`.
val careful = OpenAiChatModel(client, apiKey = key, model = "gpt-5", parameters = buildJsonObject { put("reasoning_effort", "high") })
```

An agent with tools needs a model that can call tools. With Ollama, pick one that lists "tools"
among what it can do. Gemini calls tools and streams through its address at the top of this page.
It sends a thought signature with each tool call and wants it back with the result:
`OpenAiChatModel` keeps it in `ChatMessage.Assistant.providerContent` and returns it, so a
`toolAgent` needs nothing more.

On the JVM, `telar-langchain4j` also builds a node straight from a
[LangChain4j](https://docs.langchain4j.dev) model with `chatNode` (one text in, one text out) and
`chatMessagesNode` (a list of LangChain4j messages). Both run the blocking call on `Dispatchers.IO`.
The [`ChatAgent`](https://github.com/deeptelar/telar/blob/main/samples/src/main/kotlin/dev/deeptelar/telar/samples/ChatAgent.kt) sample uses them.
