package dev.deeptelar.telar.agent

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

    /** What the user said. */
    @Serializable
    @SerialName("user")
    public data class User(
        override val text: String,
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
     */
    @Serializable
    @SerialName("tool_result")
    public data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        override val text: String,
        val isError: Boolean = false,
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
