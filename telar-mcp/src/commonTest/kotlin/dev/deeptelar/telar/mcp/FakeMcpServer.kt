package dev.deeptelar.telar.mcp

import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.modelcontextprotocol.kotlin.sdk.types.Tool as ServerTool

/**
 * A Model Context Protocol server in the process of the test. It speaks the protocol over channels
 * with a real client of the SDK: it answers `initialize`, `tools/list` and `tools/call`.
 *
 * @param pages the tools of the server, in the pages it lists them in.
 * @param call what a call of a tool returns. An [McpException] it throws is sent as a protocol error.
 */
class FakeMcpServer(
    private val pages: List<List<ServerTool>>,
    private val call: suspend (CallToolRequestParams) -> CallToolResult = { text("Done.") },
) {
    constructor(vararg tools: ServerTool, call: suspend (CallToolRequestParams) -> CallToolResult = { text("Done.") }) :
        this(listOf(tools.toList()), call)

    /** Every call of a tool the server received. */
    val calls = mutableListOf<CallToolRequestParams>()

    /** How often the server was asked for a page of its tools. */
    var listRequests = 0
        private set

    /** Returns a client that is connected to this server. The server lives as long as the test. */
    @OptIn(ExperimentalMcpApi::class)
    suspend fun connect(test: TestScope): Client {
        val toServer = Channel<JSONRPCMessage>(Channel.UNLIMITED)
        val toClient = Channel<JSONRPCMessage>(Channel.UNLIMITED)
        test.backgroundScope.launch {
            for (message in toServer) {
                // Each request is answered on its own, so that a slow call does not hold up the next one.
                if (message is JSONRPCRequest) launch { toClient.send(answer(message)) }
            }
        }
        // The dispatcher of the test, so that the time limits of the client follow the test's clock.
        val transport = ChannelTransport(toServer, toClient, StandardTestDispatcher(test.testScheduler))
        return Client(Implementation("telar-test-client", "1.0")).also { it.connect(transport) }
    }

    private suspend fun answer(request: JSONRPCRequest): JSONRPCMessage =
        when (request.method) {
            "initialize" ->
                JSONRPCResponse(
                    request.id,
                    InitializeResult(
                        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
                        serverInfo = Implementation("fake-server", "1.0"),
                    ),
                )
            "tools/list" -> {
                listRequests++
                val page =
                    request.params
                        ?.jsonObject
                        ?.get("cursor")
                        ?.jsonPrimitive
                        ?.content
                        ?.toInt() ?: 0
                JSONRPCResponse(request.id, ListToolsResult(pages[page], nextCursor = (page + 1).takeIf { it < pages.size }?.toString()))
            }
            "tools/call" -> {
                val params = McpJson.decodeFromJsonElement(CallToolRequestParams.serializer(), request.params!!)
                calls += params
                try {
                    JSONRPCResponse(request.id, call(params))
                } catch (e: McpException) {
                    JSONRPCError(request.id, RPCError(e.code, e.message.orEmpty()))
                }
            }
            else -> JSONRPCError(request.id, RPCError(RPCError.ErrorCode.METHOD_NOT_FOUND, "Method not found: ${request.method}"))
        }
}

/** A tool as a server describes it, with string properties. */
fun serverTool(name: String, description: String? = "Does $name.", vararg properties: String): ServerTool =
    ServerTool(
        name = name,
        description = description,
        inputSchema =
            ToolSchema(
                properties =
                    JsonObject(
                        properties.associateWith {
                            JsonObject(
                                mapOf(
                                    "type" to kotlinx.serialization.json.JsonPrimitive("string"),
                                ),
                            )
                        },
                    ),
                required = properties.toList().takeIf { it.isNotEmpty() },
            ),
    )

fun text(text: String): CallToolResult = CallToolResult(listOf(TextContent(text)))

/** A call that the server never answers. */
suspend fun never(): CallToolResult = awaitCancellation()
