package dev.deeptelar.telar.samples.releaseguard

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Each test is a repository of its own. The tests wait in real time, with `runBlocking`: the client and
 * the server of the MCP SDK work on threads of their own, and the clock of `runTest` would run past
 * the time limit of a call.
 */
class ReleaseGuardTest {
    private val repository = Files.createTempDirectory("release-guard-repo").toFile()
    private val home = Files.createTempDirectory("release-guard-home").toFile()
    private val saved = File(home, ".checkpoints/release-1-1-0.json")
    private val notes = File(home, "build/release-notes/v1.1.0.md")

    @AfterTest
    fun deleteDirectories() {
        repository.deleteRecursively()
        home.deleteRecursively()
    }

    /** A repository released as v1.0.0, with two commits since, one of which adds a pre-release dependency. */
    private fun releasedRepository() {
        git("init", "-q", "-b", "main")
        commit("README.md" to "Hello\n", message = "Release 1.0.0")
        git("tag", "v1.0.0")
        commit(
            "gradle.properties" to "VERSION_NAME=1.1.0\n",
            "gradle/libs.versions.toml" to "[versions]\nkotlin = \"2.4.21\"\nmcp = \"0.16.0-RC1\"\n\n[libraries]\n",
            message = "Feature: Read tools from MCP",
        )
        commit("README.md" to "Hello, world\n", message = "Fix: Greet the world")
    }

    private fun commit(vararg files: Pair<String, String>, message: String) {
        files.forEach { (name, text) -> File(repository, name).apply { parentFile.mkdirs() }.writeText(text) }
        git("add", ".")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    /** One command, in this process, with the scripted model: the environment has no key. */
    private suspend fun run(vararg args: String): Pair<Int, String> {
        val output = StringBuilder()
        val code = releaseGuard(args.toList(), repository, home, environment = emptyMap()) { output.appendLine(it) }
        return code to output.toString()
    }

    @Test
    fun `the three branches run, their results are merged, and the run pauses before publish`() =
        runBlocking {
            releasedRepository()

            val (code, report) = run()
            assertEquals(0, code, report)
            assertTrue("MCP server release-tools, in this process, has read_diff, scan_secrets" in report, report)
            assertTrue("[ok]    security_agent    No secrets in the" in report, report)
            assertTrue("[warn]  dependency_audit  mcp is a pre-release: 0.16.0-RC1" in report, report)
            assertTrue("[warn]  dependency_audit  2 versions changed: kotlin, mcp" in report, report)
            // The security agent says 1 and the dependency audit 4: the merge rule keeps the highest.
            assertTrue("Risk: 4/10" in report, report)
            assertTrue("  - Fix: Greet the world\n  - Feature: Read tools from MCP" in report.replace("\r", ""), report)
            assertTrue("Paused before \"publish\"" in report, report)
            assertTrue(saved.isFile)
        }

    @Test
    fun `a later call approves the saved run and writes the release notes`() =
        runBlocking {
            releasedRepository()
            run()

            // Nothing is shared with the call that audited but the checkpoint file.
            val (code, outcome) = run("--approve")
            assertEquals(0, code, outcome)
            assertTrue("git tag -a v1.1.0 ${git("rev-parse", "--short=7", "HEAD")}" in outcome, outcome)
            assertEquals("v1.1.0\n\n- Fix: Greet the world\n- Feature: Read tools from MCP\n", notes.readText())
            assertFalse(saved.exists(), "a decided run is deleted")
            assertEquals("", git("tag", "-l", "v1.1.0"), "the guard never creates the tag")
        }

    @Test
    fun `a fork rewrites the changelog from the saved step and the approval takes the fork`() =
        runBlocking {
            releasedRepository()
            run()

            val (forked, report) = run("--fork", "Group", "the", "changes", "by", "area.")
            assertEquals(0, forked, report)
            assertTrue("before \"verdict\", into release-1-1-0-r2" in report, report)
            assertTrue("Changelog draft, revision 2:\n  ### Fix\n  - Greet the world" in report.replace("\r", ""), report)
            // The results of the branches come from the checkpoint.
            assertTrue("Risk: 4/10" in report, report)
            assertTrue(File(home, ".checkpoints/release-1-1-0-r2.json").isFile)
            assertTrue(saved.isFile, "the run it comes from is kept")

            val (approved, outcome) = run("--approve")
            assertEquals(0, approved, outcome)
            assertTrue("revision 2" in outcome, outcome)
            assertEquals("v1.1.0\n\n### Fix\n- Greet the world\n\n### Feature\n- Read tools from MCP\n", notes.readText())
            assertFalse(saved.exists())
            assertFalse(File(home, ".checkpoints/release-1-1-0-r2.json").exists())
        }

    @Test
    fun `a secret in the diff blocks the release without a pause`() =
        runBlocking {
            releasedRepository()
            // Built from two parts, so that this file does not hold a key.
            commit("deploy.properties" to "aws.key=AKIA" + "IOSFODNN7EXAMPLE\n", message = "Fix: Deploy")

            val (code, output) = run()
            assertEquals(1, code, output)
            assertTrue("Secret at deploy.properties:1 AWS access key" in output, output)
            assertTrue("Risk: 10/10" in output, output)
            assertFalse(saved.exists())
        }

    @Test
    fun `the tag is refused when HEAD moved after the audit`() =
        runBlocking {
            releasedRepository()
            run()
            commit("README.md" to "changed after the audit\n", message = "Fix: Sneak in a change")

            val (code, output) = run("--approve")
            assertEquals(1, code)
            assertTrue("Refused: HEAD moved" in output, output)
            assertFalse(notes.exists())
        }

    @Test
    fun `a rejected release publishes nothing`() =
        runBlocking {
            releasedRepository()
            run()

            val (code, output) = run("--reject")
            assertEquals(0, code)
            assertTrue("Rejected by" in output, output)
            assertFalse(notes.exists())
        }

    @Test
    fun `a run that waits is shown again instead of audited again`() =
        runBlocking {
            releasedRepository()
            run()

            val (code, output) = run()
            assertEquals(0, code)
            assertTrue("waits for a decision" in output, output)
        }

    @Test
    fun `a release without new commits is blocked`() =
        runBlocking {
            releasedRepository()
            git("tag", "v1.1.0")

            val (code, output) = run()
            assertEquals(1, code, output)
            assertTrue("No commits since v1.1.0" in output, output)
        }

    @Test
    fun `a decision without a run that waits is an error`() =
        runBlocking {
            releasedRepository()

            val (code, output) = run("--approve")
            assertEquals(1, code)
            assertTrue("No run waits" in output, output)
        }

    @Test
    fun `the decision is made in another process than the audit`() {
        releasedRepository()

        val audit = launch()
        assertEquals(0, audit.first, audit.second)
        val approve = launch("--approve")
        assertEquals(0, approve.first, approve.second)

        val pidOf = { output: String -> Regex("""Release Guard, process (\d+)""").find(output)!!.groupValues[1] }
        assertNotEquals(pidOf(audit.second), pidOf(approve.second))
        assertTrue("audited by process ${pidOf(audit.second)}" in approve.second, approve.second)
        assertTrue(notes.isFile)
    }

    /** Starts `main` in a new JVM, as `./gradlew :samples:release-guard:run` does. */
    private fun launch(vararg args: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val builder =
            ProcessBuilder(
                listOf(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "-DreleaseGuard.repository=$repository",
                    "-DreleaseGuard.home=$home",
                    "dev.deeptelar.telar.samples.releaseguard.MainKt",
                    *args,
                ),
            ).redirectErrorStream(true)
        // The scripted model, whatever keys the machine has.
        builder.environment().keys.removeAll { it.endsWith("_API_KEY") }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "The process did not end: $output" }
        return process.exitValue() to output
    }
}
