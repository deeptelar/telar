package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One message of a conversation with a [ChatModel].
 *
 * Messages are `@Serializable`, so a state that holds them can be saved by a checkpointer that uses
 * kotlinx.serialization.
 */
@Serializable
public sealed interface ChatMessage {
    /** The text of the message. Empty for an assistant message that only asks for tools. */
    public val text: String

    /**
     * What the user said.
     *
     * @property attachments files that go to the model with [text], such as images or PDFs.
     */
    @Serializable
    @SerialName("user")
    public data class User(
        override val text: String,
        val attachments: List<Attachment> = emptyList(),
    ) : ChatMessage

    /**
     * What the model answered: [text], requests to run tools, or both.
     *
     * @property toolCalls the tools the model wants to run before it continues. Each of them needs a
     * [ToolResult] in the next request.
     * @property providerContent the message as the model's API returned it, when the [ChatModel]
     * needs it back unchanged in later requests (Claude's thinking blocks, for example). The model
     * that produced the message reads it; leave it out of messages you build yourself.
     * @property truncated `true` when the model reached its output limit before it finished, so
     * [text] is incomplete. Raise the limit of the model, or ask it to continue.
     */
    @Serializable
    @SerialName("assistant")
    public data class Assistant(
        override val text: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
        val providerContent: JsonElement? = null,
        val truncated: Boolean = false,
    ) : ChatMessage

    /**
     * The outcome of one [ToolCall], sent back to the model.
     *
     * @property toolCallId the [ToolCall.id] this result answers.
     * @property toolName the [ToolCall.name] this result answers.
     * @property text what the tool returned, or what went wrong when [isError] is `true`.
     * @property isError `true` when the tool failed. The model sees the error and can try again.
     * @property attachments files that the tool returned with [text], such as a screenshot. A tool
     * adds them with [attach].
     */
    @Serializable
    @SerialName("tool_result")
    public data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        override val text: String,
        val isError: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
    ) : ChatMessage
}

/**
 * A model's request to run the tool named [name] with [input].
 *
 * @property id identifies the call, so its [ChatMessage.ToolResult] can refer to it.
 * @property input the arguments, in the shape of the tool's [ToolSpec.inputSchema].
 * @property invalidInput the text the model sent as the arguments when it is not a JSON object, and
 * `null` when it is one. [input] is then empty, and [List.execute] answers the call with an error
 * that quotes this text instead of running the tool, so the model can send the call again.
 */
@Serializable
public data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject,
    val invalidInput: String? = null,
)

/**
 * What [keepLast] tells a model in place of the messages it leaves out. Without it a model takes the
 * gap for work it never did, and does the work again from the start.
 */
internal const val LEFT_OUT: String = "[Older messages of this conversation were left out to save space. What was done in them was done.]"

/**
 * Returns a function for `shorten` of [toolLoop] that leaves out the oldest messages of a conversation
 * of more than [messages] messages, so that a long conversation stays within what a model can read:
 *
 * ```kotlin
 * val agent = toolAgent(model, tools = tools, shorten = keepLast(messages = 40))
 * ```
 *
 * The messages up to the first [ChatMessage.User] stay, because they usually say what the
 * conversation is about, and after them a short message tells the model that older messages were
 * left out. A cut never separates a tool call from its results, which a provider would refuse. These
 * rules can leave more messages than asked for, when the last round has more results than [messages].
 *
 * The cut moves by half of [messages] at a time, so a model reads between half of [messages] and all
 * of them. A cut that moved with every new message would change the start of every request, and a
 * provider can only reuse a start that stays the same.
 *
 * @param messages the most messages the model reads. At least 3.
 * @throws GraphValidationException if [messages] is less than 3.
 */
public fun keepLast(messages: Int): (List<ChatMessage>) -> List<ChatMessage> {
    if (messages < 3) throw GraphValidationException("keepLast needs at least 3 messages, but was $messages.")
    return shorten@{ conversation ->
        val opening = conversation.indexOfFirst { it is ChatMessage.User } + 1
        val rest = conversation.size - opening
        if (rest <= messages - opening) return@shorten conversation
        // One place goes to the message that says that older ones were left out.
        val room = (messages - opening - 1).coerceAtLeast(1)
        val step = (room / 2).coerceAtLeast(1)
        // A whole number of steps, so that the cut only depends on how long the conversation is.
        val cut = opening + (rest - room + step - 1) / step * step

        fun clean(index: Int) =
            conversation[index] !is ChatMessage.ToolResult && conversation.subList(0, index).pendingToolCalls().isEmpty()
        val start = (cut until conversation.size).firstOrNull(::clean) ?: (cut - 1 downTo opening).firstOrNull(::clean)
        if (start == null || start == opening) {
            conversation
        } else {
            conversation.take(opening) + ChatMessage.User(LEFT_OUT) + conversation.drop(start)
        }
    }
}

/**
 * Returns the tool calls of the last assistant message that have no [ChatMessage.ToolResult] yet.
 *
 * [toolLoop] runs exactly these. When a run is paused before its tools node, they are the calls
 * waiting for approval.
 */
public fun List<ChatMessage>.pendingToolCalls(): List<ToolCall> {
    val index = indexOfLast { it is ChatMessage.Assistant }
    if (index < 0) return emptyList()
    val answered = drop(index + 1).filterIsInstance<ChatMessage.ToolResult>().mapTo(mutableSetOf()) { it.toolCallId }
    return (this[index] as ChatMessage.Assistant).toolCalls.filter { it.id !in answered }
}
