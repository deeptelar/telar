package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.Description
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.pendingToolCalls
import dev.deeptelar.telar.agent.textDelta
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.anthropic.AnthropicChatModel
import dev.deeptelar.telar.openai.OpenAiChatModel
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class OrderLookup(
    @Description("The customer's name, for example \"Ana\"") val customer: String,
)

@Serializable
data class MenuLookup(
    @Description("The item, for example \"margherita\"") val item: String,
)

private val menu = mapOf("margherita" to 9, "pepperoni" to 11, "salad" to 6, "cola" to 2)

/** The input class gives the model the schema of the tool, and the tool gets the input already parsed. */
val orderStatus: Tool =
    Tool<OrderLookup>("order_status", "Returns where a customer's order is right now.") { lookup ->
        "The pizza for ${lookup.customer} left the oven and the driver is 5 minutes away."
    }

/** A tool that throws does not stop the run: the model gets the message of the exception and can react to it. */
val menuPrice: Tool =
    Tool<MenuLookup>("menu_price", "Returns the price of one item on the menu, or an error if we do not sell it.") { lookup ->
        val item = lookup.item.trim().lowercase()
        val price = menu[item] ?: throw IllegalArgumentException("We do not sell $item.")
        "One $item costs $price euros."
    }

const val HELP_DESK: String =
    "You work at the help desk of a pizzeria. Answer the customer in one or two friendly sentences. " +
        "Use order_status for questions about a delivery and menu_price for questions about what we sell or what it costs. " +
        "Never guess a price."

/**
 * A help desk agent: the model answers the customer, and looks up orders and prices with the tools
 * as often as it needs to.
 *
 * Pass any [ChatModel]: `AnthropicChatModel` or `OpenAiChatModel` on every platform, or
 * `LangChain4jChatModel` on the JVM.
 */
fun helpDeskAgent(model: ChatModel): CompiledGraph<AgentState> =
    toolAgent(model, tools = listOf(orderStatus, menuPrice), system = HELP_DESK)

/** Offline stand-in for a real model, so the sample runs without an API key. */
val scriptedModel: ChatModel =
    ChatModel { request ->
        val results = request.messages.takeLastWhile { it is ChatMessage.ToolResult }
        val question =
            request.messages
                .last { it is ChatMessage.User }
                .text
                .lowercase()
        val calls =
            buildList {
                if ("where" in question) add(ToolCall("call-status", "order_status", buildJsonObject { put("customer", "Ana") }))
                (menu.keys + "tiramisu").filter { it in question }.forEach { item ->
                    add(ToolCall("call-price-$item", "menu_price", buildJsonObject { put("item", item) }))
                }
            }
        ChatResponse(
            when {
                results.isNotEmpty() -> ChatMessage.Assistant(results.joinToString(" ") { it.text })
                calls.isNotEmpty() -> ChatMessage.Assistant(toolCalls = calls)
                else -> ChatMessage.Assistant("Thanks for writing to us. A colleague will reply soon.")
            },
        )
    }

/**
 * The model that the environment asks for: Claude with ANTHROPIC_API_KEY (ANTHROPIC_MODEL names
 * another model than claude-opus-5-5), OpenAI with OPENAI_API_KEY (OPENAI_MODEL names another model
 * than gpt-5), or the model of a local Ollama that OLLAMA_MODEL names. OPENAI_BASE_URL sends the
 * requests for OpenAI to another server with the same API, such as Gemini or Groq. Without any of
 * them, the scripted model answers.
 */
fun modelFor(client: HttpClient, environment: Map<String, String> = System.getenv()): ChatModel {
    fun setting(name: String): String? = environment[name]?.takeIf { it.isNotBlank() }
    val claudeKey = setting("ANTHROPIC_API_KEY")
    val openAiKey = setting("OPENAI_API_KEY")
    val ollamaModel = setting("OLLAMA_MODEL")
    return when {
        claudeKey != null -> AnthropicChatModel(client, apiKey = claudeKey, model = setting("ANTHROPIC_MODEL") ?: "claude-opus-5-5")
        openAiKey != null ->
            OpenAiChatModel(
                client,
                apiKey = openAiKey,
                model = setting("OPENAI_MODEL") ?: "gpt-5",
                baseUrl = setting("OPENAI_BASE_URL") ?: OpenAiChatModel.OPENAI_BASE_URL,
            )
        ollamaModel != null -> OpenAiChatModel.ollama(client, model = ollamaModel)
        else -> scriptedModel
    }
}

suspend fun main() {
    val client = HttpClient()
    val agent = helpDeskAgent(modelFor(client))

    for (question in listOf("I'm Ana. Where is my pizza, and how much is a cola?", "Do you sell tiramisu?")) {
        println("Customer: $question")
        var writing = false
        agent.stream(AgentState(question)).collect { event ->
            // The text of the model node, piece by piece while the model writes it.
            event.textDelta?.let { piece ->
                print(if (writing) piece else "Agent: $piece")
                writing = true
            }
            when (event) {
                is GraphEvent.NodeCompleted -> {
                    if (writing) println()
                    writing = false
                    if (event.node == "model") {
                        event.state.messages
                            .pendingToolCalls()
                            .forEach { println("  calls ${it.name} ${it.input}") }
                    } else {
                        event.state.messages
                            .takeLastWhile { it is ChatMessage.ToolResult }
                            .forEach { println("  gets  ${it.text}") }
                    }
                }
                is GraphEvent.Completed -> println()
                else -> Unit
            }
        }
    }
    client.close()
}
