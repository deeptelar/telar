package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.TelarException
import dev.deeptelar.telar.isProgressCollected
import dev.deeptelar.telar.reportProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * A language model that continues a conversation. It is the only thing the rest of this module
 * needs from a model provider, so a graph written against it runs on every Kotlin target and with
 * any provider.
 *
 * Use an implementation from an integration module (`telar-anthropic`, `telar-openai`,
 * `telar-langchain4j`), or write one. In a test, a lambda is enough:
 *
 * ```kotlin
 * val model = ChatModel { request -> ChatResponse(ChatMessage.Assistant("Hello!")) }
 * ```
 *
 * [chat] waits for the whole answer and [stream] delivers it piece by piece. A model of your own
 * only needs [chat].
 */
public fun interface ChatModel {
    /**
     * Returns the model's next message for [request].
     *
     * @throws ChatModelException when the call fails or the model declines to answer.
     */
    public suspend fun chat(request: ChatRequest): ChatResponse

    /**
     * Returns the model's next message for [request] as the model writes it: a [ChatEvent.TextDelta]
     * for each piece of text, and then one [ChatEvent.Completed] with the whole answer. The flow is
     * cold: the model is asked when the flow is collected.
     *
     * ```kotlin
     * model.stream(request).collect { event ->
     *     when (event) {
     *         is ChatEvent.TextDelta -> print(event.text)
     *         is ChatEvent.Completed -> conversation += event.response.message
     *     }
     * }
     * ```
     *
     * A model that cannot stream keeps this default, which asks with [chat] and emits the text of
     * the answer as one piece, so code that shows the pieces works with every model. The flow fails
     * with a [ChatModelException] when the call fails or the model declines to answer; pieces that
     * arrived before the failure are not an answer, so drop them.
     */
    public fun stream(request: ChatRequest): Flow<ChatEvent> =
        flow {
            val response = chat(request)
            if (response.message.text.isNotEmpty()) emit(ChatEvent.TextDelta(response.message.text))
            emit(ChatEvent.Completed(response))
        }
}

/** What a [ChatModel] emits while it writes an answer. See [ChatModel.stream]. */
public sealed interface ChatEvent {
    /**
     * The model wrote [text], the next piece of its answer. The pieces of one answer, joined in
     * order, are the text of its message.
     */
    public data class TextDelta(
        val text: String,
    ) : ChatEvent

    /** The model finished. [response] holds the whole answer, including its tool calls. */
    public data class Completed(
        val response: ChatResponse,
    ) : ChatEvent
}

/**
 * Returns the model's next message for [request], like [ChatModel.chat], and lets a streamed run
 * watch the model write it. Use it in a node in place of [ChatModel.chat]:
 *
 * ```kotlin
 * val answer = node("answer", work = { email -> model.chatWithProgress(ChatRequest(email.messages)).message }) { email, reply ->
 *     email.copy(messages = email.messages + reply)
 * }
 *
 * graph.stream(email).collect { event -> event.textDelta?.let(::print) }
 * ```
 *
 * In a node of a run that is collected with `stream` or `streamResume`, the model is asked with
 * [ChatModel.stream] and each [ChatEvent.TextDelta] is passed to [reportProgress], so it arrives in
 * the run's flow as a `GraphEvent.NodeProgress`; [textDelta] reads it there. Anywhere else nobody
 * would see the pieces, and the model is asked with [ChatModel.chat].
 *
 * @throws ChatModelException when the call fails or the model declines to answer.
 */
public suspend fun ChatModel.chatWithProgress(request: ChatRequest): ChatResponse {
    if (!isProgressCollected()) return chat(request)
    var response: ChatResponse? = null
    stream(request).collect { event ->
        when (event) {
            is ChatEvent.TextDelta -> reportProgress(event)
            is ChatEvent.Completed -> response = event.response
        }
    }
    return response ?: throw ChatModelException("The model's stream ended before the answer was complete.")
}

/**
 * Sends [prompt] as a single user message and returns the text of the answer, like [chat] with a
 * prompt, and lets a streamed run watch the model write it. See the overload with a [ChatRequest].
 *
 * ```kotlin
 * node("summarize", work = { model.chatWithProgress("Summarize: ${it.notes}") }) { state, summary ->
 *     state.copy(summary = summary)
 * }
 * ```
 */
public suspend fun ChatModel.chatWithProgress(prompt: String, system: String? = null): String =
    chatWithProgress(ChatRequest(listOf(ChatMessage.User(prompt)), system)).message.text

/**
 * The piece of text a model wrote, when this event is a node's report of one, and `null` for every
 * other event. [toolLoop] and [chatWithProgress] report these. A report from inside a subgraph is
 * read as well.
 *
 * ```kotlin
 * agent.stream(AgentState("Where is my pizza?")).collect { event -> event.textDelta?.let(::print) }
 * ```
 */
public val GraphEvent<*>.textDelta: String?
    get() =
        when (this) {
            is GraphEvent.NodeProgress<*> -> (value as? ChatEvent.TextDelta)?.text
            is GraphEvent.SubgraphEvent<*> -> innermost.textDelta
            else -> null
        }

/**
 * Sends [prompt] as a single user message and returns the text of the answer, for a node that only
 * needs a piece of text from the model. Call [ChatModel.chat] with a [ChatRequest] when you need to
 * know whether the answer was cut off.
 *
 * ```kotlin
 * node("summarize", work = { model.chat("Summarize: ${it.notes}") }) { state, summary ->
 *     state.copy(summary = summary)
 * }
 * ```
 */
public suspend fun ChatModel.chat(prompt: String, system: String? = null): String =
    chat(ChatRequest(listOf(ChatMessage.User(prompt)), system)).message.text

/**
 * What a [ChatModel] is asked.
 *
 * @property messages the conversation so far, oldest first.
 * @property system instructions for the model that are not part of the conversation.
 * @property tools the tools the model may ask for.
 */
public data class ChatRequest(
    val messages: List<ChatMessage>,
    val system: String? = null,
    val tools: List<ToolSpec> = emptyList(),
)

/**
 * What a [ChatModel] answered.
 *
 * @property message the model's message. Add it to the conversation before the next request. Its
 * [ChatMessage.Assistant.truncated] tells whether the model reached its output limit.
 * @property usage the tokens the call used, when the provider reports them.
 */
public data class ChatResponse(
    val message: ChatMessage.Assistant,
    val usage: TokenUsage? = null,
)

/**
 * The tokens a call to a [ChatModel] read ([inputTokens]) and wrote ([outputTokens]), or those of
 * several calls added up. `TokenUsage()` is no tokens, the start of a count:
 *
 * ```kotlin
 * data class Order(val messages: List<ChatMessage>, val tokens: TokenUsage = TokenUsage())
 *
 * node("summarize", work = { order -> model.chat(ChatRequest(order.messages)) }) { order, response ->
 *     order.copy(messages = order.messages + response.message, tokens = order.tokens + response.usage)
 * }
 * ```
 *
 * It is `@Serializable`, so a state that holds one can be saved by a checkpointer that uses
 * kotlinx.serialization.
 */
@Serializable
public data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    /** The tokens read and written together. */
    public val total: Int get() = inputTokens + outputTokens

    /** Returns these tokens and those of [other] added up. A call whose provider reports no usage has `null`, which adds nothing. */
    public operator fun plus(other: TokenUsage?): TokenUsage =
        if (other == null) this else TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}

/**
 * A call to a [ChatModel] failed: the provider could not be reached, it returned an error, or the
 * model declined to answer.
 */
public class ChatModelException(
    message: String,
    cause: Throwable? = null,
) : TelarException(message, cause)
