package dev.deeptelar.telar.otel

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.agent.Attachment
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Writes the content of the calls to models and tools as the attributes that the
 * [semantic conventions for generative AI](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
 * name, as JSON in strings. Each text is cut at [maxLength] characters, with a note of how long it
 * was, and an attachment becomes a note of its type and size: its bytes are never recorded.
 * `providerContent`, such as the thinking of Claude, is left out.
 */
internal class ConversationAttributes(
    private val maxLength: Int,
) {
    init {
        if (maxLength < 1) throw GraphValidationException("maxContentLength must be at least 1, was $maxLength.")
    }

    /** The attributes of the span of a model call for what it sends. */
    fun request(request: ChatRequest): Map<String, String> =
        buildMap {
            request.system?.let { system ->
                put(GEN_AI_SYSTEM_INSTRUCTIONS, buildJsonArray { add(textPart(system)) }.toString())
            }
            put(GEN_AI_INPUT_MESSAGES, JsonArray(request.messages.map(::message)).toString())
        }

    /** The attributes of the span of a model call for its answer. */
    fun response(response: ChatResponse): Map<String, String> {
        val answer = response.message
        val finishReason =
            when {
                answer.truncated -> "length"
                answer.toolCalls.isNotEmpty() -> "tool_call"
                else -> "stop"
            }
        val output =
            buildJsonObject {
                put("role", "assistant")
                put("parts", JsonArray(assistantParts(answer)))
                put("finish_reason", finishReason)
            }
        return mapOf(GEN_AI_OUTPUT_MESSAGES to JsonArray(listOf(output)).toString())
    }

    /** The attribute of the span of a tool call for its input: the JSON the model wrote. */
    fun toolInput(toolCall: ToolCall): Map<String, String> =
        mapOf(GEN_AI_TOOL_CALL_ARGUMENTS to cut(toolCall.invalidInput ?: toolCall.input.toString()))

    /** The attribute of the span of a tool call for its result. */
    fun toolResult(result: ChatMessage.ToolResult): Map<String, String> = mapOf(GEN_AI_TOOL_CALL_RESULT to resultText(result))

    private fun message(message: ChatMessage): JsonObject =
        when (message) {
            is ChatMessage.User ->
                buildJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(listOf(textPart(message.text)) + message.attachments.map { textPart(note(it), cut = false) }))
                }
            is ChatMessage.Assistant ->
                buildJsonObject {
                    put("role", "assistant")
                    put("parts", JsonArray(assistantParts(message)))
                }
            is ChatMessage.ToolResult ->
                buildJsonObject {
                    put("role", "tool")
                    put(
                        "parts",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "tool_call_response")
                                    put("id", message.toolCallId)
                                    put("response", resultText(message))
                                },
                            )
                        },
                    )
                }
        }

    private fun assistantParts(message: ChatMessage.Assistant): List<JsonObject> =
        listOfNotNull(message.text.takeIf { it.isNotEmpty() }?.let { textPart(it) }) +
            message.toolCalls.map { call ->
                buildJsonObject {
                    put("type", "tool_call")
                    put("id", call.id)
                    put("name", call.name)
                    val written = call.invalidInput ?: call.input.toString()
                    // The input stays an object while it fits, so a backend can show its fields.
                    val fits = call.invalidInput == null && written.length <= maxLength
                    if (fits) put("arguments", call.input) else put("arguments", cut(written))
                }
            }

    private fun resultText(result: ChatMessage.ToolResult): String =
        (listOf(cut(result.text)) + result.attachments.map(::note)).joinToString("\n")

    private fun textPart(text: String, cut: Boolean = true): JsonObject =
        buildJsonObject {
            put("type", "text")
            put("content", if (cut) cut(text) else text)
        }

    /** [text], or its first [maxLength] characters and a note of its length. */
    private fun cut(text: String): String =
        if (text.length <= maxLength) text else "${text.take(maxLength)}… [cut, ${text.length} characters in all]"

    /** What an attachment is, without its bytes. An address is no file, so it is kept. */
    private fun note(attachment: Attachment): String =
        when (attachment) {
            is Attachment.Data -> "[${attachment.mediaType}, ${size(attachment.base64)}]"
            is Attachment.Url -> "[${attachment.mediaType}: ${cut(attachment.url)}]"
        }

    /** The size of the bytes that [base64] holds. */
    private fun size(base64: String): String {
        val bytes = base64.length / 4 * 3 - base64.takeLast(2).count { it == '=' }
        return when {
            bytes < 1024 -> "$bytes bytes"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> ((bytes * 10L + 512 * 1024) / (1024 * 1024)).let { tenths -> "${tenths / 10}.${tenths % 10} MB" }
        }
    }

    internal companion object {
        const val DEFAULT_MAX_LENGTH: Int = 8192
        const val GEN_AI_SYSTEM_INSTRUCTIONS: String = "gen_ai.system_instructions"
        const val GEN_AI_INPUT_MESSAGES: String = "gen_ai.input.messages"
        const val GEN_AI_OUTPUT_MESSAGES: String = "gen_ai.output.messages"
        const val GEN_AI_TOOL_CALL_ARGUMENTS: String = "gen_ai.tool.call.arguments"
        const val GEN_AI_TOOL_CALL_RESULT: String = "gen_ai.tool.call.result"
    }
}
