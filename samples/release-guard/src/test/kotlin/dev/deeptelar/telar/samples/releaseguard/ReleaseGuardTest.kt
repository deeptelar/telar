package dev.deeptelar.telar.samples.releaseguard

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReleaseGuardTest {
    private val repository = Files.createTempDirectory("release-guard-repo").toFile()
    private val home = Files.createTempDirectory("release-guard-home").toFile()

    @AfterTest
    fun deleteDirectories() {
        repository.deleteRecursively()
        home.deleteRecursively()
    }

    /** A repository released as v1.0.0, with one commit since and its changelog entry. */
    private fun releasedRepository() {
        git("init", "-q", "-b", "main")
        commit("CHANGELOG.md" to "# Changelog\n\n## [1.0.0]\n\n- First release\n", message = "Release 1.0.0")
        git("tag", "v1.0.0")
        commit(
            "CHANGELOG.md" to "# Changelog\n\n## [1.1.0]\n\n- Faster audits\n\n## [1.0.0]\n\n- First release\n",
            "gradle.properties" to "VERSION_NAME=1.1.0\n",
            message = "Make audits faster",
        )
    }

    private fun commit(vararg files: Pair<String, String>, message: String) {
        files.forEach { (name, text) -> File(repository, name).writeText(text) }
        git("add", ".")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", message)
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    private suspend fun run(command: String?): Pair<Int, String> {
        val output = StringBuilder()
        val code = releaseGuard(command, repository, home) { output.appendLine(it) }
        return code to output.toString()
    }

    @Test
    fun `the audit pauses before the tag and a later call publishes it`() =
        runTest {
            releasedRepository()

            val (audited, report) = run(null)
            assertEquals(0, audited, report)
            assertTrue("Paused before \"tag\"" in report, report)
            assertTrue("Proposed tag: v1.1.0" in report, report)
            assertTrue("CHANGELOG.md describes 1.1.0" in report, report)
            assertTrue(File(home, "checkpoints/release.json").isFile)

            // Nothing is shared with the call that audited but the checkpoint file.
            val (approved, outcome) = run("approve")
            assertEquals(0, approved, outcome)
            assertTrue("git tag -a v1.1.0 ${git("rev-parse", "--short=7", "HEAD")}" in outcome, outcome)
            assertEquals("v1.1.0\n\n- Make audits faster\n", File(home, "notes/v1.1.0.md").readText())
            assertFalse(File(home, "checkpoints/release.json").exists(), "a finished run is deleted")
            assertEquals("", git("tag", "-l", "v1.1.0"), "the guard never creates the tag")
        }

    @Test
    fun `a run that waits is shown again instead of audited again`() =
        runTest {
            releasedRepository()
            run(null)

            val (code, output) = run(null)
            assertEquals(0, code)
            assertTrue("is waiting for a decision" in output, output)
        }

    @Test
    fun `the tag is refused when HEAD moved after the audit`() =
        runTest {
            releasedRepository()
            run(null)
            commit("README.md" to "changed after the audit\n", message = "Sneak in a change")

            val (code, output) = run("approve")
            assertEquals(1, code)
            assertTrue("Refused: HEAD moved" in output, output)
            assertFalse(File(home, "notes/v1.1.0.md").exists())
        }

    @Test
    fun `a rejected release publishes nothing`() =
        runTest {
            releasedRepository()
            run(null)

            val (code, output) = run("reject")
            assertEquals(0, code)
            assertTrue("Rejected by" in output, output)
            assertFalse(File(home, "notes/v1.1.0.md").exists())
        }

    @Test
    fun `a release without new commits is blocked without a pause`() =
        runTest {
            releasedRepository()
            git("tag", "v1.1.0")

            val (code, output) = run(null)
            assertEquals(1, code)
            assertTrue("No commits since v1.1.0" in output, output)
            assertFalse(File(home, "checkpoints/release.json").exists())
        }

    @Test
    fun `a decision without a waiting run is an error`() =
        runTest {
            releasedRepository()

            val (code, output) = run("approve")
            assertEquals(1, code)
            assertTrue("No run is waiting" in output, output)
        }

    @Test
    fun `the decision is made in another process than the audit`() {
        releasedRepository()

        val audit = launch()
        assertEquals(0, audit.first, audit.second)
        val approve = launch("approve")
        assertEquals(0, approve.first, approve.second)

        val auditPid = Regex("""Release Guard, process (\d+)""").find(audit.second)!!.groupValues[1]
        val approvePid = Regex("""Release Guard, process (\d+)""").find(approve.second)!!.groupValues[1]
        assertNotEquals(auditPid, approvePid)
        assertTrue("Audited by process $auditPid" in approve.second, approve.second)
        assertTrue(File(home, "notes/v1.1.0.md").isFile)
    }

    /** Starts `main` in a new JVM, as `./gradlew :samples:release-guard:run` does. */
    private fun launch(vararg args: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
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
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "The process did not end: $output" }
        return process.exitValue() to output
    }
}
