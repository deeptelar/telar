package dev.deeptelar.telar.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Answers with [replies] in order and records what it was asked. */
class ScriptedModel(
    vararg replies: ChatResponse,
) : ChatModel {
    private val replies = ArrayDeque(replies.toList())
    val requests = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        requests += request
        return replies.removeFirst()
    }
}

fun says(text: String): ChatResponse = ChatResponse(ChatMessage.Assistant(text))

fun calls(vararg calls: ToolCall): ChatResponse = ChatResponse(ChatMessage.Assistant(toolCalls = calls.toList()))

/** The same answer, for which the provider reports these tokens. */
fun ChatResponse.using(inputTokens: Int, outputTokens: Int): ChatResponse = copy(usage = TokenUsage(inputTokens, outputTokens))

/** The same answer, cut off at the model's output limit. */
fun ChatResponse.cutOff(): ChatResponse = copy(message = message.copy(truncated = true))

fun call(id: String, name: String, vararg input: Pair<String, String>): ToolCall =
    ToolCall(id, name, buildJsonObject { input.forEach { (key, value) -> put(key, value) } })

val noInput: JsonObject = JsonObject(emptyMap())
