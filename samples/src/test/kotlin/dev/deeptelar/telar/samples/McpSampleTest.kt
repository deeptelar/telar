package dev.deeptelar.telar.samples

import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.mcp.mcpTools
import dev.deeptelar.telar.test.ScriptedChatModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The agent of `McpTools.kt`, against the server of the MCP SDK that the sample runs in its own
 * process. The tests wait in real time, with `runBlocking`: the client and the server of the SDK
 * work on threads of their own, and the clock of `runTest` would run past the time limit of a call.
 */
class McpSampleTest {
    @Test
    fun `the agent gets the tools of the server and answers with what they return`() =
        runBlocking {
            val server = connectInProcess(pizzeriaServer())
            try {
                val tools = mcpTools(server)
                assertEquals(listOf("order_status", "menu_price"), tools.map { it.spec.name })
                assertEquals(setOf("customer"), (tools.first().spec.inputSchema["properties"] as JsonObject).keys)

                val state =
                    toolAgent(
                        scriptedModel,
                        tools,
                        system = HELP_DESK,
                    ).invoke(AgentState("I'm Ana. Where is my pizza, and how much is a cola?")).state

                assertEquals("The pizza for Ana left the oven and the driver is 5 minutes away. One cola costs 2 euros.", state.answer)
            } finally {
                server.close()
            }
        }

    @Test
    fun `an error of the server reaches the model as an error`() =
        runBlocking {
            val server = connectInProcess(pizzeriaServer())
            try {
                val model =
                    ScriptedChatModel {
                        calls("menu_price", MenuLookup(item = "tiramisu"))
                        says("Sorry, we do not sell tiramisu.")
                    }

                val state = toolAgent(model, mcpTools(server)).invoke(AgentState("Do you sell tiramisu?")).state

                val result = state.messages.filterIsInstance<ChatMessage.ToolResult>().single()
                assertEquals(true, result.isError)
                assertEquals("We do not sell tiramisu.", result.text)
                assertEquals("Sorry, we do not sell tiramisu.", state.answer)
            } finally {
                server.close()
            }
        }
}
