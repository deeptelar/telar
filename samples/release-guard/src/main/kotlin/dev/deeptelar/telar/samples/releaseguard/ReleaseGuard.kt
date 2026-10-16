package dev.deeptelar.telar.samples.releaseguard

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.checkpoint.file.FileCheckpointer
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class Severity { OK, WARNING, BLOCKER }

@Serializable
data class Finding(
    val check: String,
    val severity: Severity,
    val message: String,
)

/** Who decided, and when: the process that approves is not the one that audited. */
@Serializable
data class Decision(
    val approved: Boolean,
    val by: String,
    val atMillis: Long,
    val pid: Long,
)

/** The whole release, as the checkpoint file holds it between the two processes. */
@Serializable
data class ReleaseState(
    val repository: String,
    val auditedAtMillis: Long,
    val auditedByPid: Long,
    val commit: String = "",
    val branch: String = "",
    val previousTag: String? = null,
    val tag: String = "",
    val commits: List<String> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val decision: Decision? = null,
    val published: Boolean = false,
    val outcome: String = "",
) {
    val blockers: List<Finding> get() = findings.filter { it.severity == Severity.BLOCKER }
    val shortCommit: String get() = commit.take(7)
}

const val TAG = "tag"
const val THREAD = "release"

/**
 * The audit. `inspect` reads where HEAD is and which tag comes next; four checks run in parallel;
 * `verdict` joins them and routes to `blocked`, or to `tag`, which waits for a person. `tag` runs in
 * a later process, so it looks at the repository again before it trusts what was audited.
 */
fun releaseGuardGraph(repository: Repository, notes: File): CompiledGraph<ReleaseState> =
    StateGraph<ReleaseState> {
        val inspect = node("inspect") { inspect(repository, it) }
        val checks =
            listOf(
                node("working_tree", work = { workingTree(repository, it) }) { state, finding -> state + finding },
                node("tag_is_free", work = { tagIsFree(repository, it) }) { state, finding -> state + finding },
                node("changelog", work = { changelog(repository, it) }) { state, finding -> state + finding },
                node("version", work = { version(repository, it) }) { state, finding -> state + finding },
            )
        val verdict = node("verdict") { it }
        val blocked = node("blocked") { it.copy(outcome = "Blocked: ${it.blockers.joinToString("; ") { b -> b.message }}") }
        val tag = node(TAG) { publish(repository, notes, it) }

        START then inspect
        checks.forEach { check -> inspect then check then verdict }
        conditionalEdge(verdict, setOf(blocked, tag)) { if (it.blockers.isEmpty()) tag else blocked }
        blocked then END
        tag then END
    }.compile()

/** The run is saved in [home], so a later process (or a later day) can continue it. */
fun releaseGuardConfig(home: File): GraphConfig<ReleaseState> =
    GraphConfig(
        threadId = THREAD,
        checkpointer = FileCheckpointer(Path(home.path, "checkpoints"), KotlinxStateSerializer<ReleaseState>()),
        interruptBefore = setOf(TAG),
    )

private operator fun ReleaseState.plus(finding: Finding?): ReleaseState = if (finding == null) this else copy(findings = findings + finding)

private suspend fun inspect(repository: Repository, state: ReleaseState): ReleaseState {
    val head = repository.git("rev-parse", "--verify", "HEAD")
    if (!head.ok) {
        return state + Finding("inspect", Severity.BLOCKER, "${repository.directory} is not a git repository with a commit (${head.text})")
    }
    val branch = repository.git("rev-parse", "--abbrev-ref", "HEAD").text
    val previousTag = repository.git("describe", "--tags", "--abbrev=0").takeIf { it.ok }?.text
    val range = if (previousTag == null) "HEAD" else "$previousTag..HEAD"
    val commits =
        repository
            .git("log", "--no-merges", "--format=%s", range)
            .text
            .lines()
            .filter { it.isNotBlank() }
    // The version the build says it is, unless that tag already exists: then the tag after the last one.
    val version = repository.read("gradle.properties")?.let { propertyOf(it, "VERSION_NAME") }?.removeSuffix("-SNAPSHOT")
    val tag =
        when {
            version != null && !tagExists(repository, "v$version") -> "v$version"
            previousTag != null -> nextTag(previousTag)
            else -> "v0.1.0"
        }
    val since = if (previousTag == null) "in the whole history" else "since $previousTag"
    val inspected = state.copy(commit = head.text, branch = branch, previousTag = previousTag, tag = tag, commits = commits)
    return when {
        commits.isEmpty() -> inspected + Finding("inspect", Severity.BLOCKER, "No commits $since: nothing to release")
        branch != "main" && branch != "master" ->
            inspected + Finding("inspect", Severity.WARNING, "HEAD ${inspected.shortCommit} is on $branch, not on main")
        else -> inspected + Finding("inspect", Severity.OK, "HEAD ${inspected.shortCommit} on $branch, ${commits.size} commits $since")
    }
}

private suspend fun tagExists(repository: Repository, tag: String): Boolean =
    repository.git("rev-parse", "-q", "--verify", "refs/tags/$tag").ok

private suspend fun workingTree(repository: Repository, state: ReleaseState): Finding? {
    if (state.commit.isEmpty()) return null
    val changes =
        repository
            .git("status", "--porcelain")
            .text
            .lines()
            .filter { it.isNotBlank() }
    return if (changes.isEmpty()) {
        Finding("working_tree", Severity.OK, "Working tree is clean")
    } else {
        Finding("working_tree", Severity.WARNING, "${changes.size} uncommitted changes; the tag will not contain them")
    }
}

private suspend fun tagIsFree(repository: Repository, state: ReleaseState): Finding? {
    if (state.commit.isEmpty()) return null
    return if (tagExists(repository, state.tag)) {
        Finding("tag_is_free", Severity.BLOCKER, "${state.tag} already exists")
    } else {
        Finding("tag_is_free", Severity.OK, "${state.tag} does not exist yet")
    }
}

private suspend fun changelog(repository: Repository, state: ReleaseState): Finding? {
    if (state.commit.isEmpty()) return null
    val text = repository.read("CHANGELOG.md") ?: return Finding("changelog", Severity.WARNING, "No CHANGELOG.md")
    val version = state.tag.removePrefix("v")
    val hasEntries = { title: String -> changelogSection(text, title).orEmpty().any { it.startsWith("- ") } }
    return when {
        hasEntries(version) -> Finding("changelog", Severity.OK, "CHANGELOG.md describes $version")
        hasEntries("Unreleased") ->
            Finding("changelog", Severity.WARNING, "CHANGELOG.md has entries under [Unreleased], none under [$version]")
        else -> Finding("changelog", Severity.WARNING, "CHANGELOG.md has no entries for $version")
    }
}

private suspend fun version(repository: Repository, state: ReleaseState): Finding? {
    if (state.commit.isEmpty()) return null
    val properties = repository.read("gradle.properties") ?: return null
    val version = propertyOf(properties, "VERSION_NAME") ?: return null
    return if ("v$version" == state.tag) {
        Finding("version", Severity.OK, "VERSION_NAME is $version")
    } else {
        Finding("version", Severity.WARNING, "VERSION_NAME is $version; set it to ${state.tag.removePrefix("v")} before tagging")
    }
}

/**
 * Runs after the pause, in another process. The repository may have changed since the audit, so
 * this looks again; then it writes the release notes and prints the commands. It does not run them.
 */
private suspend fun publish(repository: Repository, notes: File, state: ReleaseState): ReleaseState {
    val decision = state.decision ?: return state.copy(outcome = "No decision was given")
    if (!decision.approved) return state.copy(outcome = "Rejected by ${decision.by}. No tag.")

    val head = repository.git("rev-parse", "--verify", "HEAD").text
    if (head != state.commit) {
        return state.copy(
            outcome = "Refused: HEAD moved from ${state.shortCommit} to ${head.take(7)} after the audit. Audit again.",
        )
    }
    if (tagExists(repository, state.tag)) {
        return state.copy(outcome = "Refused: ${state.tag} was created after the audit.")
    }

    val file = File(notes, "${state.tag}.md")
    withContext(Dispatchers.IO) {
        notes.mkdirs()
        file.writeText(
            buildString {
                appendLine(state.tag)
                appendLine()
                state.commits.forEach { appendLine("- $it") }
            },
        )
    }
    return state.copy(
        published = true,
        outcome =
            """
            Release notes: ${file.path}
            Publish with (Release Guard never changes your repository):
              git tag -a ${state.tag} ${state.shortCommit} -F ${file.path}
              git push origin ${state.tag}
            """.trimIndent(),
    )
}
