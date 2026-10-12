package dev.deeptelar.telar.test

import dev.deeptelar.telar.agent.ChatEvent
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * A [ChatModel] for tests: it gives the answers of its script, in the order they were written, and
 * keeps every request it received. A graph with an agent is tested with it without an API key and
 * without a network.
 *
 * ```kotlin
 * val model = ScriptedChatModel {
 *     calls("order_status", OrderLookup(customer = "Ana")) // the first answer asks for a tool
 *     says("Your pizza is 5 minutes away.")                // the second one is the final text
 * }
 *
 * val state = toolAgent(model, listOf(orderStatus)).invoke(AgentState("Where is my pizza?")).state
 *
 * assertEquals("Your pizza is 5 minutes away.", state.answer)
 * assertEquals(2, model.requests.size)
 * ```
 *
 * See [ChatScript] for what a script can hold.
 *
 * A model that is asked for more answers than its script has throws an `AssertionError` that says
 * what it was asked. The engine passes an `AssertionError` on as it is, so the test fails with that
 * message.
 *
 * In a streamed run, the model writes the text of an answer word by word, so [stream] emits one
 * [ChatEvent.TextDelta] for each word. The pieces joined are the text of the answer.
 *
 * When nodes that run at the same time ask the same model, which of them gets which answer is not
 * fixed. Give each of them a model of its own, or write the script with [ChatScript.responds].
 *
 * @param script the answers of the model, in order.
 */
public class ScriptedChatModel(
    script: ChatScript.() -> Unit,
) : ChatModel {
    private val steps: List<ChatStep> = ChatScript().apply(script).steps.toList()
    private val received = mutableListOf<ChatRequest>()
    private val lock = Mutex()

    /** Every request the model received so far, oldest first, also those of a call that failed. */
    public val requests: List<ChatRequest> get() = received.toList()

    /** How many answers of the script the model has not given yet. 0 when the whole script was used. */
    public val remaining: Int get() = (steps.size - received.size).coerceAtLeast(0)

    override suspend fun chat(request: ChatRequest): ChatResponse = next(request).respond(request)

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
        flow {
            val step = next(request)
            words(step.partialText).forEach { emit(ChatEvent.TextDelta(it)) }
            val response = step.respond(request)
            words(response.message.text).forEach { emit(ChatEvent.TextDelta(it)) }
            emit(ChatEvent.Completed(response))
        }

    private suspend fun next(request: ChatRequest): ChatStep =
        lock.withLock {
            received += request
            steps.getOrNull(received.size - 1)
                ?: throw AssertionError(
                    "The script of this model has ${steps.size} answers, but the model was asked ${received.size} times. " +
                        "The last message it was asked with: ${request.messages.lastOrNull()}",
                )
        }
}

/**
 * The script of a [ScriptedChatModel]: each call adds the answer to one request, in order.
 *
 * ```kotlin
 * ScriptedChatModel {
 *     says("Hello!")                                              // text
 *     calls("menu_price", MenuLookup("cola"))                     // one tool call
 *     calls(call("menu_price", MenuLookup("cola")), call("opening_hours")) // several in one answer
 *     answers(Verdict(Category.REFUND, "The pizza was cold."))    // JSON, for `ask<Verdict>`
 *     fails("API error 529: Overloaded")                          // a call that throws
 *     responds { request -> ChatResponse(ChatMessage.Assistant("You said: ${request.messages.last().text}")) }
 * }
 * ```
 */
public class ChatScript internal constructor() {
    internal val steps = mutableListOf<ChatStep>()
    private var toolCalls = 0

    /**
     * The model answers with [text].
     *
     * @param usage the tokens the provider reports for this answer, to test a count or a budget.
     * @param truncated `true` for an answer that was cut off at the model's output limit.
     */
    public fun says(text: String, usage: TokenUsage? = null, truncated: Boolean = false) {
        respondWith(ChatResponse(ChatMessage.Assistant(text, truncated = truncated), usage))
    }

    /**
     * The model answers with [value] as JSON, for code that asks with `ChatModel.ask`.
     * [Value] is a `@Serializable` class.
     *
     * @param usage the tokens the provider reports for this answer.
     */
    public inline fun <reified Value> answers(value: Value, usage: TokenUsage? = null) {
        says(json(serializer<Value>(), value), usage)
    }

    /**
     * The model answers with one call of the tool named [tool]. See [call] for what [input] may be.
     *
     * @param text what the model writes next to the call, such as "Let me check."
     * @param usage the tokens the provider reports for this answer.
     */
    public inline fun <reified Input> calls(tool: String, input: Input, text: String = "", usage: TokenUsage? = null) {
        calls(call(tool, input), text = text, usage = usage)
    }

    /**
     * The model answers with [calls], several tool calls in one answer. Build each with [call].
     *
     * @param text what the model writes next to the calls.
     * @param usage the tokens the provider reports for this answer.
     */
    public fun calls(vararg calls: ToolCall, text: String = "", usage: TokenUsage? = null) {
        respondWith(ChatResponse(ChatMessage.Assistant(text, calls.toList()), usage))
    }

    /**
     * Returns a call of the tool named [tool] with [input], for [calls]. Its id is the next of
     * `call-1`, `call-2` and so on.
     *
     * [input] is an instance of the input class of the tool. To test what a tool does with an input
     * that does not fit, pass a `JsonObject` or a `Map` in its place.
     *
     * @throws IllegalArgumentException if [input] is not written as a JSON object.
     */
    public inline fun <reified Input> call(tool: String, input: Input): ToolCall = call(tool, jsonObject(serializer<Input>(), input))

    /** Returns a call of the tool named [tool] without an input. */
    public fun call(tool: String): ToolCall = call(tool, JsonObject(emptyMap()))

    /**
     * The call to the model fails with a [ChatModelException] that has [message], as when the
     * provider is overloaded. Use it to test a `retry`, or what the app does with a failed run.
     *
     * @param partialText what a streamed run receives before the failure, as when a connection
     * breaks in the middle of an answer.
     */
    public fun fails(message: String, partialText: String = "") {
        fails(ChatModelException(message), partialText)
    }

    /** The call to the model throws [failure]. See the overload with a message. */
    public fun fails(failure: Throwable, partialText: String = "") {
        steps += ChatStep(partialText) { throw failure }
    }

    /** The model answers with what [answer] returns for the request, for an answer that depends on what was asked. */
    public fun responds(answer: suspend (ChatRequest) -> ChatResponse) {
        steps += ChatStep(respond = answer)
    }

    private fun respondWith(response: ChatResponse) {
        steps += ChatStep { response }
    }

    @PublishedApi
    internal fun call(tool: String, input: JsonObject): ToolCall = ToolCall("call-${++toolCalls}", tool, input)

    @PublishedApi
    internal fun <Value> json(serializer: KSerializer<Value>, value: Value): String = Json.encodeToString(serializer, value)

    @PublishedApi
    internal fun <Input> jsonObject(serializer: KSerializer<Input>, input: Input): JsonObject =
        Json.encodeToJsonElement(serializer, input) as? JsonObject
            ?: throw IllegalArgumentException("The input of a tool call must be written as a JSON object, but this one is not: $input")
}

/** One answer of a script: what a streamed run receives first, and the answer or the failure. */
internal class ChatStep(
    val partialText: String = "",
    val respond: suspend (ChatRequest) -> ChatResponse,
)

/** Splits [text] into its words, each with the space around it, so that the pieces joined are [text]. */
internal fun words(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    return word
        .findAll(text)
        .map { it.value }
        .toList()
        .ifEmpty { listOf(text) }
}

private val word = Regex("""\s*\S+\s*""")
