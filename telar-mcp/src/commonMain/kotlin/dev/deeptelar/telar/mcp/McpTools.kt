package dev.deeptelar.telar.mcp

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.TelarException
import dev.deeptelar.telar.agent.Attachment
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolSpec
import dev.deeptelar.telar.agent.attach
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration
import io.modelcontextprotocol.kotlin.sdk.types.Tool as ServerTool

/**
 * Returns the tools of the Model Context Protocol server that [server] is connected to, as tools of
 * an agent. Many services publish such a server, so an agent gets their tools without a [Tool]
 * written by hand for each.
 *
 * ```kotlin
 * // The app connects, with the Kotlin SDK of MCP. Over HTTP this works on every platform.
 * val server = HttpClient().mcpStreamableHttp("https://mcp.example.com/mcp")
 *
 * val agent = toolAgent(model, tools = mcpTools(server) + ownTools)
 * agent.invoke(AgentState("Which issues are open?"))
 *
 * server.close()
 * ```
 *
 * The function asks the server for all of its tools once, page by page. Each tool keeps the name,
 * the description and the input schema the server gave it, and running it calls the server. Call
 * this function before you build the graph, and close [server] when the app is done with the agent:
 * the connection belongs to the app. A server that changes its tools later is not followed: call
 * the function again and build the agent again.
 *
 * What a tool returns to the model:
 * - The text parts of the result, joined.
 * - Its images (JPEG, PNG, GIF and WebP) and PDFs, as attachments of the result. Any other image,
 *   sound or file becomes one line of the text that says what it was, such as `[audio: audio/wav]`.
 * - A result the server marks as an error is an error result for the model, like a tool of the app
 *   that throws. So is a call that failed or got no answer in time. The run goes on, and the model
 *   can react.
 *
 * To let an agent use only some tools, pick them with [filter], which gets what the server says
 * about each:
 *
 * ```kotlin
 * val readOnly = mcpTools(server) { tool -> tool.annotations?.readOnlyHint == true }
 * ```
 *
 * To approve the calls by hand, run the graph with `interruptBefore = setOf("tools")`, as for the
 * tools of the app.
 *
 * A model accepts letters, digits, `_` and `-` in the name of a tool. Any other character of a
 * name, such as the dot of `repo.search`, is given to the model as `_`, and the server is called
 * with the real name.
 *
 * @param server a client of the MCP SDK that is connected to the server.
 * @param prefix put before the name of every tool, for example `"github_"`, so that the tools of
 * two servers, or of a server and the app, cannot have the same name.
 * @param timeout how long one call of a tool may take. `null` leaves the 60 seconds of the SDK.
 * @param filter returns `true` for the tools to keep. It gets each tool as the server describes it.
 * @throws McpServerException when the server cannot be asked for its tools.
 * @throws GraphValidationException if a name is longer than 64 characters with its [prefix], or two
 * tools end up with the same name.
 */
public suspend fun mcpTools(
    server: Client,
    prefix: String = "",
    timeout: Duration? = null,
    filter: (ServerTool) -> Boolean = { true },
): List<Tool> {
    val options = timeout?.let { RequestOptions(timeout = it) }
    val tools = server.allTools().filter(filter).map { tool -> tool.asTool(server, prefix, options) }
    tools.groupingBy { it.spec.name }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
        throw GraphValidationException("Two tools of the MCP server are named '$it' for the model. Leave one out with filter.")
    }
    return tools
}

/**
 * A request to a Model Context Protocol server failed: the server could not be asked for its
 * tools, a tool reported an error, or a call got no answer. The failure of the SDK, when there is
 * one, is the [cause].
 *
 * A tool from [mcpTools] throws it, and an agent turns it into an error result for the model.
 */
public class McpServerException(
    message: String,
    cause: Throwable? = null,
) : TelarException(message, cause)

/** Reads every page of the server's list of tools. */
private suspend fun Client.allTools(): List<ServerTool> {
    val tools = mutableListOf<ServerTool>()
    var cursor: String? = null
    do {
        val page =
            asking("The MCP server could not be asked for its tools") {
                listTools(ListToolsRequest(cursor?.let { PaginatedRequestParams(cursor = it) }))
            }
        tools += page.tools
        cursor = page.nextCursor
    } while (cursor != null)
    return tools
}

private fun ServerTool.asTool(server: Client, prefix: String, options: RequestOptions?): Tool {
    val realName = name
    val spec = ToolSpec(prefix + realName.replace(notInAName, "_"), description ?: title ?: "", inputSchema.asJsonSchema())
    return Tool(spec) { input ->
        val result =
            asking("The tool '$realName' of the MCP server could not be called") {
                server.callTool(CallToolRequest(CallToolRequestParams(realName, input)), options)
            }
        val text = result.asText()
        if (result.isError == true) throw McpServerException(text)
        result.content.mapNotNull { it.asAttachment() }.forEach { attach(it) }
        text
    }
}

/** Runs [request] and reports what the SDK throws as a [McpServerException] that starts with [what]. */
private inline fun <T> asking(what: String, request: () -> T): T =
    try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw McpServerException("$what: ${e.message ?: e::class.simpleName}", e)
    }

/** The schema as the JSON Schema object that a model is given. */
private fun ToolSchema.asJsonSchema(): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", properties ?: JsonObject(emptyMap()))
        required?.takeIf { it.isNotEmpty() }?.let { names -> put("required", JsonArray(names.map(::JsonPrimitive))) }
        defs?.let { put("\$defs", it) }
    }

/**
 * The result as the text a model gets: its text parts, and one line for each part that is neither
 * text nor an attachment. A result without parts is its structured content, or a sentence that says
 * there was nothing.
 */
internal fun CallToolResult.asText(): String =
    when {
        content.isNotEmpty() -> content.filter { it.asAttachment() == null }.joinToString("\n") { it.asText() }
        structuredContent != null -> structuredContent.toString()
        else -> "The tool returned no content."
    }

private fun ContentBlock.asText(): String =
    when (this) {
        is TextContent -> text
        is ImageContent -> "[image: $mimeType]"
        is AudioContent -> "[audio: $mimeType]"
        is ResourceLink -> "[resource: $name, $uri]"
        is EmbeddedResource ->
            when (val contents = resource) {
                is TextResourceContents -> contents.text
                is BlobResourceContents -> "[file: ${contents.mimeType ?: "unknown type"}, ${contents.uri}]"
                else -> "[resource: ${contents.uri}]"
            }
    }

/** The media types that a model class sends as a file. */
private val attachable = listOf("image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf")

/** The part as a file for the model, when it is an image or a file of a type that model classes send. */
internal fun ContentBlock.asAttachment(): Attachment? =
    when (this) {
        is ImageContent -> Attachment.Data(data, mimeType).takeIf { mimeType in attachable }
        is EmbeddedResource ->
            (resource as? BlobResourceContents)?.let { blob ->
                blob.mimeType?.takeIf { it in attachable }?.let { Attachment.Data(blob.blob, it) }
            }
        else -> null
    }

private val notInAName = Regex("[^a-zA-Z0-9_-]")
