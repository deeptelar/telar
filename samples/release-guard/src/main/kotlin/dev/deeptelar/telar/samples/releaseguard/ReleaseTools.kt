package dev.deeptelar.telar.samples.releaseguard

import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

/** The tree of a repository with no files: the start of the range of a first release. */
const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

private const val MAX_FILES = 40

/** What a secret looks like in an added line, by kind. */
private val secretPatterns =
    mapOf(
        "AWS access key" to Regex("""AKIA[0-9A-Z]{16}"""),
        "private key" to Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        "GitHub token" to Regex("""gh[pousr]_[A-Za-z0-9]{36}"""),
        "Anthropic key" to Regex("""sk-ant-[A-Za-z0-9_-]{20,}"""),
    )

/**
 * The release tools as a Model Context Protocol server, written with the server side of the MCP
 * SDK: `read_diff` and `scan_secrets` over the changes from [from] to [to]. A team would run a
 * server like this once for all its agents; here it runs inside the process, so nothing has to be
 * installed.
 */
fun releaseToolsServer(repository: Repository, from: String, to: String): Server =
    Server(Implementation("release-tools", "1.0"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools()))) {
        val noInput = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList())
        addTool(
            "read_diff",
            "Returns the files that the release changes, with the number of lines added and removed in each.",
            noInput,
        ) {
            val stat = repository.git("diff", "--stat=120", from, to)
            if (!stat.ok) return@addTool CallToolResult(listOf(TextContent(stat.text)), isError = true)
            val lines = stat.text.lines()
            val shown =
                if (lines.size <=
                    MAX_FILES + 1
                ) {
                    lines
                } else {
                    lines.take(MAX_FILES) + "... ${lines.size - MAX_FILES - 1} more files" + lines.last()
                }
            CallToolResult(listOf(TextContent(shown.joinToString("\n"))))
        }
        addTool(
            "scan_secrets",
            "Scans every line that the release adds for keys, tokens and passwords. " +
                "Returns one line per secret, as SECRET <file>:<line> <kind>, or says that there are none.",
            noInput,
        ) {
            val diff = repository.git("diff", "--unified=0", "--no-color", from, to)
            if (!diff.ok) return@addTool CallToolResult(listOf(TextContent(diff.text)), isError = true)
            val found = scanForSecrets(diff.text)
            val added = diff.text.lines().count { it.startsWith("+") && !it.startsWith("+++") }
            val text = if (found.isEmpty()) "No secrets in the $added lines the release adds." else found.joinToString("\n")
            CallToolResult(listOf(TextContent(text)))
        }
    }

/** The secrets in the added lines of a unified [diff], as `SECRET <file>:<line> <kind>`. */
fun scanForSecrets(diff: String): List<String> {
    var file = ""
    var line = 0
    val found = mutableListOf<String>()
    for (text in diff.lines()) {
        when {
            text.startsWith("+++ ") -> file = text.removePrefix("+++ ").removePrefix("b/")
            text.startsWith("@@") ->
                line = Regex("""\+(\d+)""")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            text.startsWith("+") -> {
                secretPatterns.filterValues { it.containsMatchIn(text) }.keys.forEach { found += "SECRET $file:$line $it" }
                line++
            }
        }
    }
    return found
}

/** Connects a client to [server] inside this process, over a pair of channels in place of a network. */
@OptIn(ExperimentalMcpApi::class)
suspend fun connectInProcess(server: Server): Client {
    val transports = ChannelTransport.createLinkedPair()
    server.createSession(transports.serverTransport)
    return Client(Implementation("release-guard", "1.0")).also { it.connect(transports.clientTransport) }
}
