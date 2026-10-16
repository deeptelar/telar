package dev.deeptelar.telar.samples.releaseguard

import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.mcp.mcpTools
import dev.deeptelar.telar.samples.connectInProcess
import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The release guard of `releaseguard/Main.kt` against its MCP server in this process. The tests wait
 * in real time, with `runBlocking`, as `McpSampleTest` explains.
 */
class ReleaseGuardTest {
    private fun withServer(block: suspend (Client) -> Unit) =
        runBlocking {
            val server = connectInProcess(repositoryServer())
            try {
                block(server)
            } finally {
                server.close()
            }
        }

    private fun directory() = Path(Files.createTempDirectory("release-guard").toString())

    @Test
    fun `a clean release is merged from the three branches and published without a pause`() =
        withServer { server ->
            val graph = buildReleaseGraph(mcpTools(server), auditorScript)

            val result = graph.invoke(ReleaseState("1.4.0"), releaseConfig(directory(), "1.4.0"))

            assertIs<GraphResult.Completed<ReleaseState>>(result)
            assertEquals(
                listOf(
                    "[Security MCP] CLEAN: the diff of 1.4.0 adds no secrets. Renames GraphSession.start and updates its KDoc.",
                    "[Deps] Checked 14 KMP artifacts: 1 alpha dependency detected (ktor-3.x)",
                ),
                result.state.findings,
            )
            assertEquals(40, result.state.riskScore)
            assertEquals("## 1.4.0\n- Parallel mergeRules\n- MCP tool integration\n- File checkpoints", result.state.releaseNotes)
            assertEquals("published 1.4.0 to Maven Central", result.state.status)
        }

    @Test
    fun `a release with a secret waits for a person, also after a restart`() =
        withServer { server ->
            val directory = directory()

            val paused = buildReleaseGraph(mcpTools(server), auditorScript).invoke(ReleaseState("1.5.0"), releaseConfig(directory, "1.5.0"))
            assertIs<GraphResult.Interrupted<ReleaseState>>(paused)
            assertEquals(listOf(APPROVE), paused.nextNodes)
            assertEquals(80, paused.state.riskScore)

            // A new graph and checkpointer, as in another process.
            val finished =
                buildReleaseGraph(mcpTools(server), auditorScript).resume(releaseConfig(directory, "1.5.0")) { it.copy(approved = true) }
            assertEquals("published 1.5.0 to Maven Central", finished.state.status)
        }

    @Test
    fun `the command line checks a release in one process and approves it in the next`() =
        withServer { server ->
            val directory = directory()

            // Each command gets a new graph and checkpointer, as each runs in a process of its own.
            suspend fun run(command: String?): List<String> =
                buildList {
                    releaseGuard(
                        command,
                        buildReleaseGraph(mcpTools(server), auditorScript),
                        releaseConfig(directory, "1.5.0"),
                        "1.5.0",
                    ) { add(it) }
                }

            assertEquals(
                listOf("Release 1.5.0 is not waiting for approval. Run the sample without arguments first."),
                run(APPROVE_COMMAND),
            )
            val first = run(null)
            assertEquals("  risk      80", first[1])
            assertEquals("Paused before $APPROVE: a person has to decide. The run is saved, and this process ends.", first[5])
            assertEquals(listOf("published 1.5.0 to Maven Central", "5 checkpoints of this run are saved on disk."), run(APPROVE_COMMAND))
            assertEquals(
                listOf("Release 1.5.0 is not waiting for approval. Run the sample without arguments first."),
                run(REJECT_COMMAND),
            )
        }

    @Test
    fun `a rejected release is not published`() =
        withServer { server ->
            val graph = buildReleaseGraph(mcpTools(server), auditorScript)
            val config = releaseConfig(directory(), "1.5.0")

            graph.invoke(ReleaseState("1.5.0"), config)
            val finished = graph.resume(config) { it }

            assertEquals("aborted: rejected by reviewer", finished.state.status)
        }
}
