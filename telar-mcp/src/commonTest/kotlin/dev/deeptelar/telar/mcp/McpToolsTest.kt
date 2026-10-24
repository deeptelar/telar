package dev.deeptelar.telar.mcp

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.Attachment
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.execute
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.test.ScriptedChatModel
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.UnknownResourceContents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.modelcontextprotocol.kotlin.sdk.types.Tool as ServerTool

@OptIn(ExperimentalCoroutinesApi::class)
class McpToolsTest {
    private val noInput = JsonObject(emptyMap())

    private fun input(vararg properties: Pair<String, String>) =
        JsonObject(
            properties.associate { (name, value) ->
                name to
                    JsonPrimitive(value)
            },
        )

    @Test
    fun `the tools of a server become tools with its names and descriptions and schemas`() =
        runTest {
            val server =
                FakeMcpServer(serverTool("search_issues", "Finds issues by their text.", "query", "state"), serverTool("list_labels"))

            val tools = mcpTools(server.connect(this))

            assertEquals(listOf("search_issues", "list_labels"), tools.map { it.spec.name })
            assertEquals("Finds issues by their text.", tools[0].spec.description)
            assertEquals(
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "string") }
                        putJsonObject("state") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("query"))
                        add(JsonPrimitive("state"))
                    }
                },
                tools[0].spec.inputSchema,
            )
            // A tool without properties has an empty schema, and no list of required ones.
            assertEquals(
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
                tools[1].spec.inputSchema,
            )
        }

    @Test
    fun `a schema keeps its definitions and a tool without a description has its title`() =
        runTest {
            val definitions = buildJsonObject { putJsonObject("Label") { put("type", "string") } }
            val properties = buildJsonObject { putJsonObject("label") { put("\$ref", "#/\$defs/Label") } }
            val titled =
                ServerTool("add_label", ToolSchema(properties = properties, defs = definitions), description = null, title = "Add a label")
            val bare = ServerTool("ping", ToolSchema())

            val tools = mcpTools(FakeMcpServer(titled, bare).connect(this))

            assertEquals("Add a label", tools[0].spec.description)
            assertEquals(definitions, tools[0].spec.inputSchema["\$defs"])
            assertEquals("", tools[1].spec.description)
        }

    @Test
    fun `running a tool calls the server with the input of the model`() =
        runTest {
            val server =
                FakeMcpServer(serverTool("search_issues", "Finds issues.", "query")) { call -> text("3 issues match ${call.arguments}.") }
            val tool = mcpTools(server.connect(this)).single()

            val result = tool.execute(input("query" to "crash"))

            assertEquals("3 issues match {\"query\":\"crash\"}.", result)
            assertEquals("search_issues", server.calls.single().name)
            assertEquals(input("query" to "crash"), server.calls.single().arguments)
        }

    @Test
    fun `every page of the list of a server is read`() =
        runTest {
            val server =
                FakeMcpServer(listOf(listOf(serverTool("one")), listOf(serverTool("two"), serverTool("three")), listOf(serverTool("four"))))

            val tools = mcpTools(server.connect(this))

            assertEquals(listOf("one", "two", "three", "four"), tools.map { it.spec.name })
            assertEquals(3, server.listRequests)
        }

    @Test
    fun `an agent uses the tools of a server next to its own`() =
        runTest {
            val server =
                FakeMcpServer(
                    serverTool("order_status", "Returns where an order is.", "customer"),
                ) { text("The driver is 5 minutes away.") }
            val model =
                ScriptedChatModel {
                    calls("order_status", mapOf("customer" to "Ana"))
                    says("Your pizza is 5 minutes away.")
                }

            val state = toolAgent(model, tools = mcpTools(server.connect(this))).invoke(AgentState("Where is my pizza?")).state

            assertEquals("Your pizza is 5 minutes away.", state.answer)
            val result = state.messages.filterIsInstance<ChatMessage.ToolResult>().single()
            assertEquals("The driver is 5 minutes away.", result.text)
            assertEquals(false, result.isError)
            // The model was told about the tool of the server.
            assertEquals(
                listOf("order_status"),
                model.requests
                    .first()
                    .tools
                    .map { it.name },
            )
        }

    @Test
    fun `a result the server marks as an error is an error for the model`() =
        runTest {
            val server =
                FakeMcpServer(
                    serverTool("delete_issue"),
                ) { CallToolResult(listOf(TextContent("You may not delete issues.")), isError = true) }
            val tools = mcpTools(server.connect(this))
            val model =
                ScriptedChatModel {
                    calls("delete_issue", noInput)
                    says("I cannot delete it.")
                }

            val exception = assertFailsWith<McpServerException> { tools.single().execute(noInput) }
            assertEquals("You may not delete issues.", exception.message)

            // In an agent the run goes on, and the model gets the error.
            val state = toolAgent(model, tools).invoke(AgentState("Delete issue 7.")).state
            val result = state.messages.filterIsInstance<ChatMessage.ToolResult>().single()
            assertTrue(result.isError)
            assertEquals("You may not delete issues.", result.text)
            assertEquals("I cannot delete it.", state.answer)
        }

    @Test
    fun `a call the server refuses is an error with what the server said`() =
        runTest {
            val server =
                FakeMcpServer(serverTool("search")) { throw McpException(RPCError.ErrorCode.INVALID_PARAMS, "Unknown tool: search") }
            val tool = mcpTools(server.connect(this)).single()

            val exception = assertFailsWith<McpServerException> { tool.execute(noInput) }

            assertEquals("The tool 'search' of the MCP server could not be called: Unknown tool: search", exception.message)
            assertIs<McpException>(exception.cause)
        }

    @Test
    fun `a call that gets no answer in time is an error`() =
        runTest {
            val server = FakeMcpServer(serverTool("slow")) { never() }
            val tool = mcpTools(server.connect(this), timeout = 5.seconds).single()

            val exception = assertFailsWith<McpServerException> { tool.execute(noInput) }

            assertEquals("The tool 'slow' of the MCP server could not be called: Request timed out", exception.message)
            assertEquals(5000, currentTime)
        }

    @Test
    fun `a server that is closed cannot be asked for its tools`() =
        runTest {
            val client = FakeMcpServer(serverTool("search")).connect(this)
            client.close()

            val exception = assertFailsWith<McpServerException> { mcpTools(client) }

            assertTrue(exception.message.orEmpty().startsWith("The MCP server could not be asked for its tools"))
        }

    @Test
    fun `a prefix goes before every name and the server is called with the real one`() =
        runTest {
            val server = FakeMcpServer(serverTool("search"), serverTool("create"))
            val tools = mcpTools(server.connect(this), prefix = "github_")

            tools.first().execute(noInput)

            assertEquals(listOf("github_search", "github_create"), tools.map { it.spec.name })
            assertEquals("search", server.calls.single().name)
        }

    @Test
    fun `a character that a model does not accept in a name becomes an underscore`() =
        runTest {
            val server = FakeMcpServer(serverTool("repo.search/v2"))
            val tool = mcpTools(server.connect(this)).single()

            tool.execute(noInput)

            assertEquals("repo_search_v2", tool.spec.name)
            assertEquals("repo.search/v2", server.calls.single().name)
        }

    @Test
    fun `only the tools that the filter keeps are returned`() =
        runTest {
            val read = serverTool("read_file").copy(annotations = ToolAnnotations(readOnlyHint = true))
            val delete = serverTool("delete_file").copy(annotations = ToolAnnotations(destructiveHint = true))

            val tools = mcpTools(FakeMcpServer(read, delete).connect(this)) { it.annotations?.readOnlyHint == true }

            assertEquals(listOf("read_file"), tools.map { it.spec.name })
        }

    @Test
    fun `two tools with the same name for the model are rejected`() =
        runTest {
            val client = FakeMcpServer(serverTool("repo.search"), serverTool("repo_search")).connect(this)

            val exception = assertFailsWith<GraphValidationException> { mcpTools(client) }

            assertEquals("Two tools of the MCP server are named 'repo_search' for the model. Leave one out with filter.", exception.message)
            // Without one of them the list is fine.
            assertEquals(1, mcpTools(client) { it.name != "repo.search" }.size)
        }

    @Test
    fun `a name that is too long for a model is rejected`() =
        runTest {
            val client = FakeMcpServer(serverTool("a".repeat(60))).connect(this)

            assertFailsWith<GraphValidationException> { mcpTools(client, prefix = "github_") }
        }

    @Test
    fun `the parts of a result that are neither text nor a file for the model become a line each`() {
        val result =
            CallToolResult(
                listOf(
                    TextContent("Here is the chart."),
                    ImageContent(data = "aGVsbG8=", mimeType = "image/png"),
                    ImageContent(data = "aGVsbG8=", mimeType = "image/svg+xml"),
                    AudioContent(data = "aGVsbG8=", mimeType = "audio/wav"),
                    ResourceLink(name = "report", uri = "file:///report.pdf"),
                    EmbeddedResource(TextResourceContents(text = "Total: 42", uri = "file:///total.txt")),
                    EmbeddedResource(BlobResourceContents(blob = "aGVsbG8=", uri = "file:///data.bin", mimeType = "application/zip")),
                    EmbeddedResource(BlobResourceContents(blob = "aGVsbG8=", uri = "file:///data.raw")),
                    EmbeddedResource(UnknownResourceContents(uri = "db://orders/7")),
                ),
            )

        assertEquals(
            """
            Here is the chart.
            [image: image/svg+xml]
            [audio: audio/wav]
            [resource: report, file:///report.pdf]
            Total: 42
            [file: application/zip, file:///data.bin]
            [file: unknown type, file:///data.raw]
            [resource: db://orders/7]
            """.trimIndent(),
            result.asText(),
        )
    }

    @Test
    fun `the images and PDFs of a result go to the model as files`() =
        runTest {
            val server =
                FakeMcpServer(serverTool("chart")) {
                    CallToolResult(
                        listOf(
                            TextContent("Here is the chart."),
                            ImageContent(data = "aGVsbG8=", mimeType = "image/png"),
                            EmbeddedResource(BlobResourceContents(blob = "cGRm", uri = "file:///report.pdf", mimeType = "application/pdf")),
                            EmbeddedResource(BlobResourceContents(blob = "emlw", uri = "file:///data.zip", mimeType = "application/zip")),
                        ),
                    )
                }
            val tools = mcpTools(server.connect(this))

            val result = tools.execute(listOf(ToolCall("call-1", "chart", noInput))).single()

            assertEquals("Here is the chart.\n[file: application/zip, file:///data.zip]", result.text)
            assertEquals(listOf(Attachment.Data("aGVsbG8=", "image/png"), Attachment.Data("cGRm", "application/pdf")), result.attachments)
        }

    @Test
    fun `a result without parts is its structured content or says that there was nothing`() {
        val structured = buildJsonObject { put("open", 3) }

        assertEquals("{\"open\":3}", CallToolResult(emptyList(), structuredContent = structured).asText())
        assertEquals("The tool returned no content.", CallToolResult(emptyList()).asText())
        // Text wins over structured content: a server sends both for clients that read only one.
        assertEquals("3 open", CallToolResult(listOf(TextContent("3 open")), structuredContent = structured).asText())
    }
}
