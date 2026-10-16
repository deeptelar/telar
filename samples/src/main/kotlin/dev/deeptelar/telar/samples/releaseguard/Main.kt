package dev.deeptelar.telar.samples.releaseguard

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.checkpoint.file.FileCheckpointer
import dev.deeptelar.telar.mcp.mcpTools
import dev.deeptelar.telar.mergeRules
import dev.deeptelar.telar.samples.connectInProcess
import dev.deeptelar.telar.samples.modelFor
import dev.deeptelar.telar.samples.scriptedModel
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import dev.deeptelar.telar.serialization.effect
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// 1. Plain immutable state, serializable for FileCheckpointer.
@Serializable
data class ReleaseState(
    val version: String,
    val findings: List<String> = emptyList(),
    val riskScore: Int = 0,
    val releaseNotes: String = "",
    val approved: Boolean = false,
    val status: String = "pending",
)

/** The risk from which a person has to approve the release. */
const val RISK_LIMIT = 50

/** The node that a risky release pauses before, until a person decides. */
const val APPROVE = "approve"

// 2. The workflow graph. The three branches run in the same step, and mergeRules merges what they write.
fun buildReleaseGraph(mcpTools: List<Tool>, model: ChatModel): CompiledGraph<ReleaseState> {
    // Branch A's agent: a graph of its own, built once, with the tools of the MCP server.
    val auditor =
        toolAgent(
            model = model,
            tools = mcpTools,
            system =
                "Inspect the release diff using MCP tools and report any security risks in one sentence. " +
                    "Start it with SECRET if the diff adds a secret, and with CLEAN if it does not.",
        )

    return StateGraph<ReleaseState> {
        // Branch A: the agent as a subgraph, so that all of its tool calls happen inside one step and
        // the three branches reach the gate together. It gets a new conversation on every run.
        val securityAgent =
            subgraph(
                "security_agent",
                auditor,
                state = { release -> AgentState("Audit release ${release.version}") },
                update = { release, agent ->
                    val report = agent.answer ?: "No issues found"
                    release.copy(
                        findings = release.findings + "[Security MCP] $report",
                        // The first word, not a word anywhere: "CLEAN: no secrets found" must not count as a secret.
                        riskScore = if (report.startsWith("SECRET")) 80 else 10,
                    )
                },
            )

        // Branch B: the dependency and license check.
        val dependencyAudit =
            node("dependency_audit") { release ->
                release.copy(
                    findings = release.findings + "[Deps] Checked 14 KMP artifacts: 1 alpha dependency detected (ktor-3.x)",
                    riskScore = 40,
                )
            }

        // Branch C: a node with a work and an update, which drafts the release notes.
        val changelogWriter =
            node(
                "changelog_writer",
                work = { release -> "## ${release.version}\n- Parallel mergeRules\n- MCP tool integration\n- File checkpoints" },
                update = { release, notes -> release.copy(releaseNotes = notes) },
            )

        // The join: it runs once, on the merged state of the three branches, and decides whether a person has to look.
        val gate = node("gate") { it.copy(status = "risk ${it.riskScore}") }

        // GraphConfig.interruptBefore pauses before this node, and only risky releases come here.
        val approve =
            node(APPROVE) { release ->
                if (release.approved) release else release.copy(status = "aborted: rejected by reviewer")
            }

        // The side effect runs once: a resume or a retry of this step returns the saved receipt.
        val publish =
            node("publish") { release ->
                val receipt = effect("publish") { "published ${release.version} to Maven Central" }
                release.copy(status = receipt)
            }

        START then securityAgent then gate
        START then dependencyAudit then gate
        START then changelogWriter then gate

        conditionalEdge(gate, targets = setOf(approve, publish)) { if (it.riskScore >= RISK_LIMIT) approve else publish }
        conditionalEdge(approve, targets = setOf(publish, NodeRef.END)) { if (it.approved) publish else NodeRef.END }
        publish then END
    }.compile(
        // Every property that the parallel branches write needs a rule.
        rules =
            mergeRules {
                append(ReleaseState::findings) { copy(findings = it) }
                merge(ReleaseState::riskScore, combine = { _, changed -> changed.max() }) { copy(riskScore = it) }
                replace(ReleaseState::releaseNotes) { copy(releaseNotes = it) }
            },
    )
}

/** Checkpoints are saved to disk, so the approval can arrive later, in another process. */
fun releaseConfig(directory: Path, version: String): GraphConfig<ReleaseState> =
    GraphConfig(
        threadId = "release-$version",
        checkpointer = FileCheckpointer(directory, KotlinxStateSerializer<ReleaseState>()),
        interruptBefore = setOf(APPROVE),
    )

/** The diffs that the repository server of this sample knows. A real server reads them from git. */
private val diffs =
    mapOf(
        "1.4.0" to "Renames GraphSession.start and updates its KDoc.",
        "1.5.0" to "Adds samples/local.properties with API_KEY=sk-live-1234.",
    )

/**
 * The repository as a Model Context Protocol server, written with the server side of the MCP SDK.
 * Its one tool returns the diff of a release, and says when the diff holds a secret.
 */
fun repositoryServer(): Server =
    Server(Implementation("repository", "1.0"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools()))) {
        addTool(
            "scan_diff",
            "Returns the diff of a release, and whether it adds a secret.",
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("version") {
                            put("type", "string")
                            put("description", "The version of the release, for example \"1.4.0\"")
                        }
                    },
                required = listOf("version"),
            ),
        ) { request ->
            val version =
                request.arguments
                    ?.get("version")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            val diff = diffs[version]
            when {
                diff == null -> CallToolResult(listOf(TextContent("There is no release $version.")), isError = true)
                "API_KEY" in diff ->
                    CallToolResult(
                        listOf(TextContent("SECRET: the diff of $version adds API_KEY in samples/local.properties.")),
                    )
                else -> CallToolResult(listOf(TextContent("CLEAN: the diff of $version adds no secrets. $diff")))
            }
        }
    }

/** A model that scans the diff of the release it is asked about, then reports what the tool returned. */
val auditorScript: ChatModel =
    ChatModel { request ->
        val result = request.messages.lastOrNull() as? ChatMessage.ToolResult
        val version =
            request.messages
                .last { it is ChatMessage.User }
                .text
                .substringAfterLast(' ')
        ChatResponse(
            if (result != null) {
                ChatMessage.Assistant(result.text)
            } else {
                ChatMessage.Assistant(toolCalls = listOf(ToolCall("call-scan", "scan_diff", buildJsonObject { put("version", version) })))
            },
        )
    }

/** The command that resumes the run with the release approved, and the one that rejects it. */
const val APPROVE_COMMAND = "--approve"
const val REJECT_COMMAND = "--reject"

/**
 * One command of the release guard, in its own process. Without a [command] it checks [version]:
 * a clean release is published at once, a risky one is saved to disk and the process ends. A later
 * process continues it with [APPROVE_COMMAND] or [REJECT_COMMAND].
 */
suspend fun releaseGuard(
    command: String?,
    graph: CompiledGraph<ReleaseState>,
    config: GraphConfig<ReleaseState>,
    version: String,
    say: (String) -> Unit = { println(it) },
) {
    when (command) {
        null -> {
            say("Checking release $version: three branches at once")
            val result = graph.invoke(ReleaseState(version), config)
            say("  risk      ${result.state.riskScore}")
            result.state.findings.forEach { say("  finding   $it") }
            say("  notes     ${result.state.releaseNotes.lines().first()}")
            when (result) {
                is GraphResult.Completed -> say(result.state.status)
                is GraphResult.Interrupted -> {
                    say("Paused before ${result.nextNodes.single()}: a person has to decide. The run is saved, and this process ends.")
                    say("Continue it in a new process with one of:")
                    say("  ./gradlew :samples:runReleaseGuard --args=\"$APPROVE_COMMAND\"")
                    say("  ./gradlew :samples:runReleaseGuard --args=\"$REJECT_COMMAND\"")
                }
            }
        }
        APPROVE_COMMAND, REJECT_COMMAND -> {
            // Read where the thread stopped before resuming it: resume fails when nothing waits.
            if (graph.lastResult(config) !is GraphResult.Interrupted) {
                say("Release $version is not waiting for approval. Run the sample without arguments first.")
                return
            }
            val result = graph.resume(config) { it.copy(approved = command == APPROVE_COMMAND) }
            say(result.state.status)
            say("${graph.history(config).size} checkpoints of this run are saved on disk.")
        }
        else -> say("Unknown command $command. Run without arguments, or with $APPROVE_COMMAND or $REJECT_COMMAND.")
    }
}

/**
 * The release guard as a command line program whose run outlives the process: the first command
 * checks the release and stops when a person has to decide, the next one continues from disk.
 *
 * RELEASE_VERSION names the release, 1.5.0 (which adds a secret) by default, or 1.4.0 (clean).
 * Without settings the model is [auditorScript], which still calls the tool of the MCP server;
 * with ANTHROPIC_API_KEY, OPENAI_API_KEY or OLLAMA_MODEL it is chosen as in `ToolAgent.kt`.
 */
suspend fun main(args: Array<String>) {
    val version = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() } ?: "1.5.0"
    val directory = Path(SystemTemporaryDirectory, "telar-release-guard")
    val http = HttpClient()
    val server = connectInProcess(repositoryServer())
    try {
        val model = modelFor(http).takeUnless { it === scriptedModel } ?: auditorScript
        val graph = buildReleaseGraph(mcpTools(server), model)
        releaseGuard(args.firstOrNull(), graph, releaseConfig(directory, version), version)
    } finally {
        server.close()
        http.close()
    }
}
