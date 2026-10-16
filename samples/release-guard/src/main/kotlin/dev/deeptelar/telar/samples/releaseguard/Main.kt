package dev.deeptelar.telar.samples.releaseguard

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.chat
import dev.deeptelar.telar.mcp.mcpTools
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

private const val RUN = "./gradlew :samples:release-guard:run"
private const val CHANGELOG_LINES = 15

/** What `--fork` asks the changelog writer for when it is given nothing else. */
const val DEFAULT_FORK_INSTRUCTION = "Group the changes by area."

/**
 * One process of Release Guard. Each command does one thing and returns, so the process ends:
 * nothing waits in memory between the audit and the decision, only the checkpoint files on disk.
 *
 * - no command, or `--audit`: audits [repository] and pauses before `publish`, or shows the run that waits
 * - `--approve` / `--reject`: continues the run that waits with a decision
 * - `--fork [instruction]`: goes back to the step before `verdict` and rewrites the changelog, on a new thread
 * - `--reset`: deletes the saved runs
 *
 * Returns the exit code of the process.
 */
suspend fun releaseGuard(args: List<String>, repository: File, home: File, environment: Map<String, String>, show: (String) -> Unit): Int {
    val pid = ProcessHandle.current().pid()
    show("Release Guard, process $pid")
    val http = HttpClient()
    try {
        val (model, modelName) = modelFor(http, environment)
        val guard = Guard(Repository(repository), home, model, pid, show)
        return when (val command = args.firstOrNull()?.removePrefix("--") ?: "audit") {
            "audit" -> guard.audit(modelName)
            "approve", "reject" -> guard.decide(approved = command == "approve")
            "fork" -> guard.fork(args.drop(1).joinToString(" ").ifBlank { DEFAULT_FORK_INSTRUCTION })
            "reset" -> guard.reset()
            else -> {
                show("Unknown command \"$command\". Use: $RUN [--args=--approve | --args=--reject | --args=--fork | --args=--reset]")
                2
            }
        }
    } finally {
        http.close()
    }
}

private class Guard(
    val repository: Repository,
    val home: File,
    val model: ChatModel,
    val pid: Long,
    val show: (String) -> Unit,
) {
    private val checkpoints = File(home, ".checkpoints")
    private val notes = File(home, "build/release-notes")

    private fun config(thread: String) = releaseGuardConfig(home, thread)

    private suspend fun delete(thread: String) = checkNotNull(config(thread).checkpointer).delete(thread)

    /**
     * Runs [block] with the graph. The MCP server of [release] runs in this process meanwhile, and
     * the graph gets its tools the way it would get those of a server elsewhere: with [mcpTools].
     */
    private suspend fun <T> withGraph(release: ReleaseState, block: suspend (CompiledGraph<ReleaseState>, List<Tool>) -> T): T {
        val client = connectInProcess(releaseToolsServer(repository, release.from, release.commit))
        try {
            val tools = mcpTools(client)
            return block(releaseGuardGraph(repository, model, tools, notes), tools)
        } finally {
            client.close()
        }
    }

    /** The threads saved in [checkpoints]. */
    private suspend fun threads(): List<String> =
        withContext(Dispatchers.IO) {
            checkpoints
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.startsWith("release-") && it.endsWith(".json") }
                .map { it.removeSuffix(".json") }
        }

    /** The saved runs that wait for a decision, the latest revision last. */
    private suspend fun waiting(): List<Pair<String, ReleaseState>> =
        threads()
            .mapNotNull { thread ->
                checkNotNull(config(thread).checkpointer)
                    .load(thread)
                    ?.takeIf { it.nextNodes.isNotEmpty() }
                    ?.let { thread to it.state }
            }.sortedBy { it.second.revision }

    suspend fun audit(modelName: String): Int {
        waiting().lastOrNull()?.let { (thread, release) ->
            show("The run of ${release.tag} that process ${release.auditedByPid} saved waits for a decision.")
            report(release)
            instructions(thread)
            return 0
        }
        val release = prepareRelease(repository, System.currentTimeMillis(), pid)
        if (release == null) {
            show("${repository.directory} is not a git repository with a commit.")
            return 1
        }
        val thread = threadOf(release.tag, revision = 1)
        delete(thread)
        show("Auditing ${release.tag} at ${release.shortCommit} in ${repository.directory}")
        show("Model: $modelName")
        val started = System.nanoTime()
        val result =
            withGraph(release) { graph, tools ->
                show("MCP server release-tools, in this process, has ${tools.joinToString { it.spec.name }}")
                show("Running $SECURITY_AGENT, $DEPENDENCY_AUDIT and $CHANGELOG_WRITER at the same time...")
                graph.invoke(release, config(thread))
            }
        show("Done in ${(System.nanoTime() - started).nanoseconds.inWholeMilliseconds} ms.")
        show("")
        report(result.state)
        return when (result) {
            is GraphResult.Interrupted -> {
                show("")
                show("Paused before \"$PUBLISH\". The run is saved in ${fileOf(thread)}")
                show("This process now exits. Nothing is kept in memory.")
                instructions(thread)
                0
            }
            is GraphResult.Completed -> {
                delete(thread)
                show(result.state.outcome)
                1
            }
        }
    }

    suspend fun decide(approved: Boolean): Int {
        val waiting = waiting()
        val (thread, release) = waiting.lastOrNull() ?: return nothingWaits()
        val waited = (System.currentTimeMillis() - release.auditedAtMillis).milliseconds.inWholeSeconds
        show("Loaded ${fileOf(thread)}")
        show(
            "${release.tag}, revision ${release.revision}, audited by process ${release.auditedByPid} ${waited}s ago; this is process $pid.",
        )
        val decision = Decision(approved, System.getProperty("user.name") ?: "unknown", System.currentTimeMillis(), pid)
        val finished = withGraph(release) { graph, _ -> graph.resume(config(thread)) { it.copy(decision = decision) } }.state
        // The release is decided, so the run and its forks are done.
        waiting.forEach { (done, _) -> delete(done) }
        show(finished.outcome)
        return if (finished.published || !approved) 0 else 1
    }

    suspend fun fork(instruction: String): Int {
        val (thread, release) = waiting().lastOrNull() ?: return nothingWaits()
        val revision = release.revision + 1
        val forked = threadOf(release.tag, revision)
        val result =
            withGraph(release) { graph, _ ->
                // The checkpoint after the three branches: their results stay, only the changelog is written again.
                val afterBranches = graph.history(config(thread)).last { VERDICT in it.nextNodes }
                show("Forking $thread at step ${afterBranches.step}, before \"$VERDICT\", into $forked")
                show("The changelog writer is asked: $instruction")
                show("$SECURITY_AGENT and $DEPENDENCY_AUDIT do not run again: their results come from the checkpoint.")
                graph.fork(afterBranches, config(forked)) {
                    it.copy(revision = revision, changelog = model.chat(changelogPrompt(it, instruction)).trim())
                }
            }
        show("")
        report(result.state)
        instructions(forked)
        return 0
    }

    suspend fun reset(): Int {
        threads().forEach { delete(it) }
        show("Deleted the saved runs.")
        return 0
    }

    private fun nothingWaits(): Int {
        show("No run waits for a decision. Start one with: $RUN")
        return 1
    }

    private fun fileOf(thread: String): File = File(checkpoints, "$thread.json")

    private fun report(release: ReleaseState) {
        val width = release.findings.maxOfOrNull { it.source.length } ?: 0
        release.findings.forEach { finding ->
            val mark =
                when (finding.severity) {
                    Severity.OK -> "[ok]   "
                    Severity.WARNING -> "[warn] "
                    Severity.BLOCKER -> "[block]"
                }
            show("  $mark ${finding.source.padEnd(width)}  ${finding.message}")
        }
        show("Risk: ${release.risk}/10, the highest that a branch reported")
        if (release.securitySummary.isNotEmpty()) show("Security agent: ${release.securitySummary}")
        if (release.changelog.isNotEmpty()) {
            val lines = release.changelog.lines()
            show("Changelog draft, revision ${release.revision}:")
            lines.take(CHANGELOG_LINES).forEach { show("  $it") }
            if (lines.size > CHANGELOG_LINES) show("  ... ${lines.size - CHANGELOG_LINES} more lines")
        }
        show("Proposed tag: ${release.tag} at ${release.shortCommit}")
    }

    private fun instructions(thread: String) {
        show("")
        show("Look at the saved run:  cat ${fileOf(thread)}")
        show("Approve the tag:        $RUN --args=--approve")
        show("Reject it:              $RUN --args=--reject")
        show("Rewrite the changelog:  $RUN --args=\"--fork Group the changes by area.\"")
    }
}

suspend fun main(args: Array<String>) {
    val repository = File(System.getProperty("releaseGuard.repository") ?: ".").absoluteFile.normalize()
    val home = File(System.getProperty("releaseGuard.home") ?: ".").absoluteFile.normalize()
    exitProcess(releaseGuard(args.toList(), repository, home, System.getenv(), ::println))
}
