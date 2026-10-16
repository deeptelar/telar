package dev.deeptelar.telar.samples.releaseguard

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.chat
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.checkpoint.file.FileCheckpointer
import dev.deeptelar.telar.mergeRules
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
    val source: String,
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

/**
 * The whole release, as the checkpoint file holds it between processes. The first properties are
 * what git says before the graph runs; the rest is what the nodes, a person or a fork add.
 */
@Serializable
data class ReleaseState(
    val repository: String,
    val tag: String,
    val commit: String,
    val previousTag: String?,
    val commits: List<String>,
    val auditedAtMillis: Long,
    val auditedByPid: Long,
    // The three branches write these at the same time; the merge rules combine them.
    val findings: List<Finding> = emptyList(),
    val risk: Int = 0,
    val changelog: String = "",
    val securitySummary: String = "",
    // Written later: by a person when the run goes on, by a fork, by the last node.
    val decision: Decision? = null,
    val revision: Int = 1,
    val outcome: String = "",
    val published: Boolean = false,
) {
    val blockers: List<Finding> get() = findings.filter { it.severity == Severity.BLOCKER }
    val shortCommit: String get() = commit.take(7)

    /** Where the release starts: the last tag, or nothing for a first release. */
    val from: String get() = previousTag ?: EMPTY_TREE
}

const val SECURITY_AGENT = "security_agent"
const val DEPENDENCY_AUDIT = "dependency_audit"
const val CHANGELOG_WRITER = "changelog_writer"
const val VERDICT = "verdict"
const val PUBLISH = "publish"

private const val SECURITY_PROMPT =
    "You review a release before it is tagged. Call read_diff to see what changed, then scan_secrets. " +
        "Answer in two sentences: what the release changes, and whether anything must stop it."

/**
 * Reads where HEAD is, which tag comes next and what changed since the last one. Returns `null`
 * when [repository] is not a git repository with a commit. What git shows here becomes the first
 * findings.
 */
suspend fun prepareRelease(repository: Repository, now: Long, pid: Long): ReleaseState? {
    val head = repository.git("rev-parse", "--verify", "HEAD")
    if (!head.ok) return null
    val branch = repository.git("rev-parse", "--abbrev-ref", "HEAD").text
    val previousTag = repository.git("describe", "--tags", "--abbrev=0").takeIf { it.ok }?.text
    val commits =
        repository
            .git("log", "--no-merges", "--format=%s", if (previousTag == null) "HEAD" else "$previousTag..HEAD")
            .text
            .lines()
            .filter { it.isNotBlank() }
    // The version the build says it is, unless that tag already exists: then the tag after the last one.
    val version = repository.read("gradle.properties")?.let { propertyOf(it, "VERSION_NAME") }
    val release = version?.removeSuffix("-SNAPSHOT")
    val tag =
        when {
            release != null && !tagExists(repository, "v$release") -> "v$release"
            previousTag != null -> nextTag(previousTag)
            else -> "v0.1.0"
        }
    val since = if (previousTag == null) "in the whole history" else "since $previousTag"
    val dirty =
        repository
            .git("status", "--porcelain")
            .text
            .lines()
            .count { it.isNotBlank() }
    val findings =
        buildList {
            add(
                when {
                    commits.isEmpty() -> Finding("git", Severity.BLOCKER, "No commits $since: nothing to release")
                    branch != "main" && branch != "master" -> Finding("git", Severity.WARNING, "HEAD is on $branch, not on main")
                    else -> Finding("git", Severity.OK, "${commits.size} commits $since on $branch")
                },
            )
            if (tagExists(repository, tag)) add(Finding("git", Severity.BLOCKER, "$tag already exists"))
            if (dirty > 0) add(Finding("git", Severity.WARNING, "$dirty uncommitted changes; the tag will not contain them"))
            if (version != null && "v$version" != tag) {
                add(Finding("git", Severity.WARNING, "VERSION_NAME is $version; set it to ${tag.removePrefix("v")} before tagging"))
            }
        }
    return ReleaseState(
        repository = repository.directory.path,
        tag = tag,
        commit = head.text,
        previousTag = previousTag,
        commits = commits,
        auditedAtMillis = now,
        auditedByPid = pid,
        findings = findings,
        risk = if (findings.any { it.severity == Severity.WARNING }) 2 else 0,
    )
}

/**
 * The audit. Three branches run at the same time from [START]: an agent that reviews the diff with
 * the tools of an MCP server ([securityTools]), a check of the version catalog, and a model that
 * drafts the changelog. The merge rules combine what they return. `verdict` routes to `blocked`, or
 * to `publish`, which waits for a person and runs in a later process.
 */
fun releaseGuardGraph(repository: Repository, model: ChatModel, securityTools: List<Tool>, notes: File): CompiledGraph<ReleaseState> =
    StateGraph<ReleaseState> {
        val security =
            subgraph(
                SECURITY_AGENT,
                toolAgent(model, securityTools, system = SECURITY_PROMPT),
                state = { AgentState("Review the release ${it.tag}: ${it.commits.size} commits since ${it.previousTag ?: "the start"}.") },
                update = { release, agent -> securityReport(release, agent) },
            )
        val dependencies =
            node(DEPENDENCY_AUDIT, work = { dependencyAudit(repository, it) }) { release, (found, risk) ->
                release.copy(findings = release.findings + found, risk = maxOf(release.risk, risk))
            }
        val changelog =
            node(CHANGELOG_WRITER, work = { model.chat(changelogPrompt(it, instruction = null)) }) { release, text ->
                release.copy(changelog = text.trim())
            }
        val verdict = node(VERDICT) { it }
        val blocked = node("blocked") { it.copy(outcome = "Blocked: ${it.blockers.joinToString("; ") { b -> b.message }}") }
        val publish = node(PUBLISH) { publishRelease(repository, notes, it) }

        for (branch in listOf(security, dependencies, changelog)) {
            START then branch then verdict
        }
        conditionalEdge(verdict, setOf(blocked, publish)) { if (it.blockers.isEmpty()) publish else blocked }
        blocked then END
        publish then END
    }.compile(
        // Every node of a step gets the state from before the step, and these rules combine what they return.
        rules =
            mergeRules {
                append(ReleaseState::findings) { copy(findings = it) }
                merge(ReleaseState::risk, combine = { before, changed -> maxOf(before, changed.max()) }) { copy(risk = it) }
                replace(ReleaseState::changelog) { copy(changelog = it) }
                replace(ReleaseState::securitySummary) { copy(securitySummary = it) }
                replace(ReleaseState::outcome) { copy(outcome = it) }
                replace(ReleaseState::published) { copy(published = it) }
            },
    )

/** The runs are saved in [home]`/.checkpoints`, one file per thread, so a later process can continue them. */
fun releaseGuardConfig(home: File, threadId: String): GraphConfig<ReleaseState> =
    GraphConfig(
        threadId = threadId,
        checkpointer = FileCheckpointer(Path(File(home, ".checkpoints").path), KotlinxStateSerializer<ReleaseState>()),
        interruptBefore = setOf(PUBLISH),
    )

/** The thread of a release: `release-1-2-0` for `v1.2.0`, and `release-1-2-0-r2` for its first fork. */
fun threadOf(tag: String, revision: Int): String {
    val base = "release-" + tag.removePrefix("v").lowercase().replace(Regex("[^a-z0-9]+"), "-")
    return if (revision == 1) base else "$base-r$revision"
}

/** What the changelog writer is asked. [instruction] is what a person asked for in a fork. */
fun changelogPrompt(release: ReleaseState, instruction: String?): String =
    buildString {
        appendLine("Write the changelog of ${release.tag} for its users from these commits. Answer with Markdown only, no heading.")
        release.commits.forEach { appendLine("- $it") }
        if (instruction != null) append("$INSTRUCTION $instruction")
    }

/** What the security agent found: the scan of the MCP server is the evidence, the agent's answer the summary. */
private fun securityReport(release: ReleaseState, agent: AgentState): ReleaseState {
    val scans = agent.messages.filterIsInstance<ChatMessage.ToolResult>().filter { it.toolName == "scan_secrets" && !it.isError }
    val secrets = scans.flatMap { scan -> scan.text.lines().filter { it.startsWith("SECRET ") } }
    val (findings, risk) =
        when {
            secrets.isNotEmpty() ->
                secrets.map { Finding(SECURITY_AGENT, Severity.BLOCKER, "Secret at ${it.removePrefix("SECRET ")}") } to
                    10
            scans.isEmpty() -> listOf(Finding(SECURITY_AGENT, Severity.WARNING, "The agent did not scan the diff for secrets")) to 5
            else ->
                listOf(
                    Finding(
                        SECURITY_AGENT,
                        Severity.OK,
                        scans
                            .last()
                            .text
                            .lines()
                            .first(),
                    ),
                ) to 1
        }
    return release.copy(
        findings = release.findings + findings,
        risk = maxOf(release.risk, risk),
        securitySummary = agent.answer.orEmpty(),
    )
}

private val versionLine = Regex("""^\s*([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""")
private val preRelease = Regex("""(?i)(alpha|beta|rc|eap|dev|-M\d)""")

/** Checks the version catalog: a snapshot stops the release, a pre-release or a changed version is worth a look. */
private suspend fun dependencyAudit(repository: Repository, release: ReleaseState): Pair<List<Finding>, Int> {
    val catalog = "gradle/libs.versions.toml"
    val text = repository.read(catalog) ?: return listOf(Finding(DEPENDENCY_AUDIT, Severity.OK, "No $catalog to audit")) to 0
    val versions =
        text
            .substringAfter("[versions]", "")
            .substringBefore("\n[")
            .lines()
            .mapNotNull { line -> versionLine.find(line)?.destructured?.let { (name, version) -> name to version } }
    val changed =
        repository
            .git("diff", "--unified=0", release.from, release.commit, "--", catalog)
            .text
            .lines()
            .filter { it.startsWith("+") && !it.startsWith("+++") }
            .mapNotNull { versionLine.find(it.drop(1))?.groupValues?.get(1) }
    val snapshots = versions.filter { (_, version) -> version.endsWith("-SNAPSHOT") }
    val previews = versions.filter { (_, version) -> preRelease.containsMatchIn(version) } - snapshots.toSet()
    val findings =
        buildList {
            snapshots.forEach { (name, version) -> add(Finding(DEPENDENCY_AUDIT, Severity.BLOCKER, "$name is a snapshot: $version")) }
            previews.forEach { (name, version) -> add(Finding(DEPENDENCY_AUDIT, Severity.WARNING, "$name is a pre-release: $version")) }
            if (changed.isNotEmpty()) {
                add(Finding(DEPENDENCY_AUDIT, Severity.WARNING, "${changed.size} versions changed: ${changed.joinToString()}"))
            }
            if (isEmpty()) add(Finding(DEPENDENCY_AUDIT, Severity.OK, "${versions.size} versions, all stable, none changed"))
        }
    val risk =
        when {
            snapshots.isNotEmpty() -> 8
            previews.isNotEmpty() -> 4
            changed.isNotEmpty() -> 3
            else -> 0
        }
    return findings to risk
}

/**
 * Runs after the pause, in another process. The repository may have changed since the audit, so
 * this looks again; then it writes the release notes and prints the commands. It does not run them.
 */
private suspend fun publishRelease(repository: Repository, notes: File, release: ReleaseState): ReleaseState {
    val decision = release.decision ?: return release.copy(outcome = "No decision was given")
    if (!decision.approved) return release.copy(outcome = "Rejected by ${decision.by}. No tag.")

    val head = repository.git("rev-parse", "--verify", "HEAD").text
    if (head != release.commit) {
        return release.copy(outcome = "Refused: HEAD moved from ${release.shortCommit} to ${head.take(7)} after the audit. Audit again.")
    }
    if (tagExists(repository, release.tag)) {
        return release.copy(outcome = "Refused: ${release.tag} was created after the audit.")
    }

    val file = File(notes, "${release.tag}.md")
    withContext(Dispatchers.IO) {
        notes.mkdirs()
        file.writeText("${release.tag}\n\n${release.changelog}\n")
    }
    return release.copy(
        published = true,
        outcome =
            """
            Approved by ${decision.by}. Release notes: ${file.path}
            Publish with (Release Guard never changes your repository):
              git tag -a ${release.tag} ${release.shortCommit} -F ${file.path}
              git push origin ${release.tag}
            """.trimIndent(),
    )
}

private suspend fun tagExists(repository: Repository, tag: String): Boolean =
    repository.git("rev-parse", "-q", "--verify", "refs/tags/$tag").ok
