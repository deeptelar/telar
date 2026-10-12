package dev.deeptelar.telar.samples

import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.mcp.mcpTools
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private fun oneText(name: String, description: String): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject(name) {
                    put("type", "string")
                    put("description", description)
                }
            },
        required = listOf(name),
    )

private fun CallToolRequest.text(name: String): String =
    arguments
        ?.get(name)
        ?.jsonPrimitive
        ?.content
        .orEmpty()

/**
 * The order system of the pizzeria as a Model Context Protocol server, written with the server side
 * of the MCP SDK. It has the two tools that `ToolAgent.kt` writes as `Tool`s. A real server of this
 * kind runs somewhere else, and is written by the people who own the order system.
 */
fun pizzeriaServer(): Server =
    Server(Implementation("pizzeria", "1.0"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools()))) {
        addTool(
            "order_status",
            "Returns where a customer's order is right now.",
            oneText("customer", "The customer's name, for example \"Ana\""),
        ) { request ->
            CallToolResult(listOf(TextContent("The pizza for ${request.text("customer")} left the oven and the driver is 5 minutes away.")))
        }
        addTool(
            "menu_price",
            "Returns the price of one item on the menu, or an error if we do not sell it.",
            oneText("item", "The item, for example \"margherita\""),
        ) { request ->
            val item = request.text("item").trim().lowercase()
            val price = mapOf("margherita" to 9, "pepperoni" to 11, "salad" to 6, "cola" to 2)[item]
            if (price == null) {
                // A server reports a tool that failed in the result. The model gets it as an error.
                CallToolResult(listOf(TextContent("We do not sell $item.")), isError = true)
            } else {
                CallToolResult(listOf(TextContent("One $item costs $price euros.")))
            }
        }
    }

/** Connects a client to [server] inside this process, over a pair of channels in place of a network. */
@OptIn(ExperimentalMcpApi::class)
suspend fun connectInProcess(server: Server): Client {
    val transports = ChannelTransport.createLinkedPair()
    server.createSession(transports.serverTransport)
    return Client(Implementation("telar-samples", "1.0")).also { it.connect(transports.clientTransport) }
}

/**
 * A help desk agent whose tools all come from an MCP server: the agent of `ToolAgent.kt`, without
 * a `Tool` written in the app.
 *
 * Without settings the server is [pizzeriaServer] in this process and the model is the scripted
 * one, so the sample runs anywhere. `MCP_SERVER_URL` names a real server that is reached over HTTP,
 * and `MCP_QUESTION` what the agent is asked about it. The model is chosen as in `ToolAgent.kt`.
 */
suspend fun main() {
    val http = HttpClient()
    val url = System.getenv("MCP_SERVER_URL")?.takeIf { it.isNotBlank() }
    // The app opens the connection, and closes it at the end.
    val server = if (url != null) http.mcpStreamableHttp(url) else connectInProcess(pizzeriaServer())
    try {
        // Read the tools of the server once, before the graph is built.
        val tools = mcpTools(server)
        println("The server has ${tools.size} tools:")
        tools.forEach { println("  ${it.spec.name}: ${it.spec.description.lineSequence().firstOrNull().orEmpty()}") }

        val agent = toolAgent(modelFor(http), tools, system = HELP_DESK.takeIf { url == null })
        val question = System.getenv("MCP_QUESTION")?.takeIf { it.isNotBlank() } ?: "I'm Ana. Where is my pizza, and do you sell tiramisu?"
        println("\nCustomer: $question")
        val state = agent.invoke(AgentState(question)).state
        state.messages.forEach { message ->
            when (message) {
                is ChatMessage.Assistant -> message.toolCalls.forEach { println("  calls ${it.name} ${it.input}") }
                is ChatMessage.ToolResult -> println("  gets  ${if (message.isError) "an error: " else ""}${message.text}")
                is ChatMessage.User -> Unit
            }
        }
        println("Agent: ${state.answer}")
    } finally {
        server.close()
        http.close()
    }
}
