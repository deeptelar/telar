package dev.deeptelar.telar.samples.releaseguard

import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.anthropic.AnthropicChatModel
import dev.deeptelar.telar.openai.OpenAiChatModel
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject

/**
 * The model when no key is set, so the sample runs anywhere and gives the same result every time.
 * As the security agent (asked with tools) it reads the diff, scans it, and reports what the scan
 * said. As the changelog writer (asked without tools) it lists the commits, grouped by area when
 * asked for something else; a real model follows any instruction.
 */
val scriptedModel: ChatModel =
    ChatModel { request ->
        val results = request.messages.filterIsInstance<ChatMessage.ToolResult>()
        val call = { name: String -> ChatMessage.Assistant(toolCalls = listOf(ToolCall("call-$name", name, JsonObject(emptyMap())))) }
        val answer =
            when {
                request.tools.isEmpty() -> ChatMessage.Assistant(scriptedChangelog(request.messages.last().text))
                results.isEmpty() -> call("read_diff")
                results.size == 1 -> call("scan_secrets")
                else -> ChatMessage.Assistant("I read the diff and scanned the added lines. ${results.last().text.lines().first()}")
            }
        ChatResponse(answer)
    }

/** The commits of the prompt as a list, or grouped by the area before their colon when the prompt has an instruction. */
private fun scriptedChangelog(prompt: String): String {
    val commits = prompt.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
    if (INSTRUCTION !in prompt) return commits.joinToString("\n") { "- $it" }
    return commits
        .groupBy({ it.substringBefore(": ", "Other") }, { it.substringAfter(": ") })
        .entries
        .joinToString("\n\n") { (area, changes) -> "### $area\n" + changes.joinToString("\n") { "- $it" } }
}

/** How the changelog writer marks an instruction of a person in its prompt. */
const val INSTRUCTION = "Instruction from the release manager:"

/**
 * Claude with ANTHROPIC_API_KEY (ANTHROPIC_MODEL names another model than claude-opus-5-5), OpenAI
 * with OPENAI_API_KEY (OPENAI_MODEL names another model than gpt-5), and [scriptedModel] without a key.
 */
fun modelFor(client: HttpClient, environment: Map<String, String> = System.getenv()): Pair<ChatModel, String> {
    fun setting(name: String): String? = environment[name]?.takeIf { it.isNotBlank() }
    val claudeKey = setting("ANTHROPIC_API_KEY")
    val openAiKey = setting("OPENAI_API_KEY")
    return when {
        claudeKey != null -> {
            val model = setting("ANTHROPIC_MODEL") ?: "claude-opus-5-5"
            AnthropicChatModel(client, apiKey = claudeKey, model = model) to model
        }
        openAiKey != null -> {
            val model = setting("OPENAI_MODEL") ?: "gpt-5"
            OpenAiChatModel(client, apiKey = openAiKey, model = model) to model
        }
        else -> scriptedModel to "scripted (no API key set)"
    }
}
