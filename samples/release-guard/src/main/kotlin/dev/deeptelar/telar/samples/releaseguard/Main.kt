package dev.deeptelar.telar.samples.releaseguard

import dev.deeptelar.telar.GraphResult
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private const val RUN = "./gradlew :samples:release-guard:run"

/**
 * One process of Release Guard. Each command does one thing and returns, so the process ends:
 * nothing waits in memory between the audit and the decision, only the checkpoint file on disk.
 *
 * - no command: audits [repository] and pauses before the tag, or shows the audit that is waiting
 * - `approve` / `reject`: continues the saved run with a decision
 * - `reset`: deletes the saved run
 *
 * Returns the exit code of the process.
 */
suspend fun releaseGuard(command: String?, repository: File, home: File, show: (String) -> Unit): Int {
    val graph = releaseGuardGraph(Repository(repository), notes = File(home, "notes"))
    val config = releaseGuardConfig(home)
    val checkpointer = checkNotNull(config.checkpointer)
    val checkpointFile = File(home, "checkpoints/$THREAD.json")
    val pid = ProcessHandle.current().pid()
    show("Release Guard, process $pid")

    val saved = graph.lastResult(config)
    return when (command) {
        null, "audit" -> {
            if (saved is GraphResult.Interrupted) {
                show("A run saved by process ${saved.state.auditedByPid} is waiting for a decision.")
                report(saved.state, show)
                instructions(checkpointFile, show)
                return 0
            }
            show("Auditing $repository")
            val result = graph.invoke(ReleaseState(repository.path, System.currentTimeMillis(), pid), config)
            report(result.state, show)
            when (result) {
                is GraphResult.Interrupted -> {
                    show("")
                    show("Paused before \"$TAG\". The run is saved in $checkpointFile")
                    show("This process now exits. Nothing is kept in memory.")
                    instructions(checkpointFile, show)
                    0
                }
                is GraphResult.Completed -> {
                    checkpointer.delete(THREAD)
                    show(result.state.outcome)
                    1
                }
            }
        }
        "approve", "reject" -> {
            if (saved !is GraphResult.Interrupted) {
                show("No run is waiting for a decision. Start one with: $RUN")
                return 1
            }
            val waited = (System.currentTimeMillis() - saved.state.auditedAtMillis).milliseconds.inWholeSeconds
            show("Loaded $checkpointFile")
            show("Audited by process ${saved.state.auditedByPid} ${waited}s ago; this is process $pid.")
            val decision = Decision(command == "approve", System.getProperty("user.name") ?: "unknown", System.currentTimeMillis(), pid)
            val finished = graph.resume(config) { it.copy(decision = decision) }.state
            checkpointer.delete(THREAD)
            show(finished.outcome)
            if (finished.published || !decision.approved) 0 else 1
        }
        "reset" -> {
            checkpointer.delete(THREAD)
            show("Deleted the saved run.")
            0
        }
        else -> {
            show("Unknown command \"$command\". Use: $RUN [--args=approve | --args=reject | --args=reset]")
            2
        }
    }
}

private fun report(state: ReleaseState, show: (String) -> Unit) {
    val width = state.findings.maxOfOrNull { it.check.length } ?: 0
    state.findings.forEach { finding ->
        val mark =
            when (finding.severity) {
                Severity.OK -> "[ok]   "
                Severity.WARNING -> "[warn] "
                Severity.BLOCKER -> "[block]"
            }
        show("  $mark ${finding.check.padEnd(width)}  ${finding.message}")
    }
    if (state.tag.isNotEmpty()) show("Proposed tag: ${state.tag} at ${state.shortCommit}")
}

private fun instructions(checkpointFile: File, show: (String) -> Unit) {
    show("")
    show("Look at the saved run:  cat $checkpointFile")
    show("Approve the tag:        $RUN --args=approve")
    show("Reject it:              $RUN --args=reject")
}

suspend fun main(args: Array<String>) {
    val repository = File(System.getProperty("releaseGuard.repository") ?: ".").absoluteFile.normalize()
    val home = File(System.getProperty("releaseGuard.home") ?: "build/release-guard").absoluteFile.normalize()
    exitProcess(releaseGuard(args.firstOrNull(), repository, home, ::println))
}
