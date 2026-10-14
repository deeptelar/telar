package dev.deeptelar.telar.checkpoint.file

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CheckpointCorruptedException
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import dev.deeptelar.telar.serialization.effect
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class SerializableState(
    val count: Int = 0,
)

class FileCheckpointerTest {
    private val tempDir = Path(SystemTemporaryDirectory, "telar-test-${Random.nextLong().toULong()}")
    private val serializer = KotlinxStateSerializer<SerializableState>()

    private fun newCheckpointer() = FileCheckpointer(tempDir, serializer)

    private fun files(): List<String> = SystemFileSystem.list(tempDir).map { it.name }.sorted()

    @AfterTest
    fun cleanUp() {
        deleteRecursively(tempDir)
    }

    @Test
    fun `saves and loads a checkpoint`() =
        runTest {
            val checkpointer = newCheckpointer()
            val checkpoint = Checkpoint(SerializableState(42), listOf("nodeA", "nodeB"), step = 3, interruptedBefore = true)

            checkpointer.save("thread-x", checkpoint)

            assertEquals(listOf("thread-x.json"), files())
            assertEquals(checkpoint, checkpointer.load("thread-x"))
        }

    @Test
    fun `an effect that finished is not repeated after a restart`() =
        runTest {
            var charges = 0
            var crash = true
            val graph =
                StateGraph<SerializableState> {
                    START then
                        node("pay") { state ->
                            val receipt = effect("charge") { ++charges }
                            if (crash) error("The process stops here.")
                            state.copy(count = receipt)
                        } then END
                }.compile()

            assertFailsWith<NodeExecutionException> {
                graph.invoke(SerializableState(), GraphConfig(threadId = "t", checkpointer = newCheckpointer()))
            }
            crash = false
            // A new checkpointer reads the files, as one in a new process would.
            val finished = graph.resume(GraphConfig(threadId = "t", checkpointer = newCheckpointer()))

            assertEquals(GraphResult.Completed(SerializableState(count = 1)), finished)
            assertEquals(1, charges)
        }

    @Test
    fun `a thread keeps the checkpoint of every step in its file`() =
        runTest {
            val checkpointer = newCheckpointer()
            assertEquals(emptyList(), checkpointer.history("thread-x"))
            val steps = (0..2).map { Checkpoint(SerializableState(it), listOf("node$it"), step = it) }

            steps.forEach { checkpointer.save("thread-x", it) }
            // A pause at the last step is saved again, in its place.
            checkpointer.save("thread-x", steps.last().copy(interruptedBefore = true))

            assertEquals(steps.dropLast(1) + steps.last().copy(interruptedBefore = true), checkpointer.history("thread-x"))
            assertEquals(steps.last().copy(interruptedBefore = true), checkpointer.load("thread-x"))
            // Another instance, as after a restart, reads the same history.
            assertEquals(3, newCheckpointer().history("thread-x").size)
            assertEquals(listOf("thread-x.json"), files())

            checkpointer.delete("thread-x")
            assertEquals(emptyList(), checkpointer.history("thread-x"))
        }

    @Test
    fun `maxHistory limits how many checkpoints a file keeps`() =
        runTest {
            val lastTwo = FileCheckpointer(tempDir, serializer, maxHistory = 2)

            repeat(4) { lastTwo.save("thread-x", Checkpoint(SerializableState(it), listOf("a"), step = it)) }

            assertEquals(listOf(2, 3), lastTwo.history("thread-x").map { it.step })
            assertFailsWith<GraphValidationException> { FileCheckpointer(tempDir, serializer, maxHistory = 0) }
        }

    @Test
    fun `a file of a version without a history is read and continued`() =
        runTest {
            SystemFileSystem.createDirectories(tempDir)
            SystemFileSystem.sink(Path(tempDir, "old.json")).buffered().use {
                it.writeString("""{"version":1,"state":"{\"count\":4}","nextNodes":["a"],"step":2}""")
            }
            val checkpointer = newCheckpointer()

            assertEquals(Checkpoint(SerializableState(4), listOf("a"), step = 2), checkpointer.load("old"))
            checkpointer.save("old", Checkpoint(SerializableState(5), emptyList(), step = 3))

            assertEquals(listOf(2, 3), checkpointer.history("old").map { it.step })
        }

    @Test
    fun `a run saved to files can be forked from an earlier step`() =
        runTest {
            val graph =
                StateGraph<SerializableState> {
                    val add = node("add") { it.copy(count = it.count + 1) }
                    val double = node("double") { it.copy(count = it.count * 2) }
                    START then add then double then END
                }.compile()
            val config = GraphConfig(threadId = "sum", checkpointer = newCheckpointer())
            graph.invoke(SerializableState(1), config)

            val afterAdd = graph.history(config).first { it.nextNodes == listOf("double") }
            val forked = graph.fork(afterAdd, config.copy(threadId = "sum-2")) { it.copy(count = 10) }

            assertEquals(GraphResult.Completed(SerializableState(20)), forked)
            assertEquals(GraphResult.Completed(SerializableState(4)), graph.lastResult(config))
            assertEquals(listOf("sum-2.json", "sum.json"), files())
        }

    @Test
    fun `load returns null for an unknown thread`() =
        runTest {
            assertNull(newCheckpointer().load("nobody"))
        }

    @Test
    fun `save replaces the previous checkpoint and leaves no temporary file`() =
        runTest {
            val checkpointer = newCheckpointer()

            checkpointer.save("t", Checkpoint(SerializableState(1), listOf("a"), step = 1))
            checkpointer.save("t", Checkpoint(SerializableState(2), emptyList(), step = 2))

            assertEquals(Checkpoint(SerializableState(2), emptyList(), step = 2), checkpointer.load("t"))
            assertEquals(listOf("t.json"), files())
        }

    @Test
    fun `delete removes the checkpoint and ignores unknown threads`() =
        runTest {
            val checkpointer = newCheckpointer()
            checkpointer.save("t", Checkpoint(SerializableState(1), emptyList()))

            checkpointer.delete("t")
            checkpointer.delete("never-existed")

            assertNull(checkpointer.load("t"))
            assertTrue(files().isEmpty())
        }

    @Test
    fun `thread ids that look alike are stored separately`() =
        runTest {
            val checkpointer = newCheckpointer()
            val ids = listOf("a/b", "a_b", "a b", "A_B", "../a_b", "ä_b")

            ids.forEachIndexed { index, id -> checkpointer.save(id, Checkpoint(SerializableState(index), emptyList())) }

            ids.forEachIndexed { index, id -> assertEquals(index, checkpointer.load(id)?.state?.count, "thread id '$id'") }
            assertEquals(ids.size, files().size)
        }

    @Test
    fun `file names never escape the directory`() {
        assertEquals("%2E%2E%2Fetc%2Fpasswd", encodeFileName("../etc/passwd"))
        assertEquals("user-42_chat", encodeFileName("user-42_chat"))
        assertEquals("%41bc", encodeFileName("Abc"))
        assertEquals("%C3%A4", encodeFileName("ä"))
    }

    @Test
    fun `a damaged file is reported as CheckpointCorruptedException`() =
        runTest {
            val checkpointer = newCheckpointer()
            SystemFileSystem.sink(Path(tempDir, "broken.json")).buffered().use { it.writeString("{ not json") }

            assertEquals("broken", assertFailsWith<CheckpointCorruptedException> { checkpointer.load("broken") }.threadId)
        }

    @Test
    fun `a file from a newer format version is rejected`() =
        runTest {
            val checkpointer = newCheckpointer()
            val json = """{"version":99,"state":"{}","nextNodes":[]}"""
            SystemFileSystem.sink(Path(tempDir, "future.json")).buffered().use { it.writeString(json) }

            assertFailsWith<CheckpointCorruptedException> { checkpointer.load("future") }
        }

    @Test
    fun `a paused graph resumes from disk with a new checkpointer instance`() =
        runTest {
            val app =
                StateGraph<SerializableState> {
                    node("draft") { it.copy(count = it.count + 1) }
                    node("publish") { it.copy(count = it.count + 10) }
                    edge(START, "draft")
                    edge("draft", "publish")
                    edge("publish", END)
                }.compile()

            val firstProcess = GraphConfig(threadId = "doc-1", checkpointer = newCheckpointer(), interruptBefore = setOf("publish"))
            assertEquals(GraphResult.Interrupted(SerializableState(1), listOf("publish")), app.invoke(SerializableState(), firstProcess))

            // A new instance reading the same directory stands in for a restarted process.
            val secondProcess = firstProcess.copy(checkpointer = newCheckpointer())
            assertEquals(GraphResult.Completed(SerializableState(11)), app.resume(secondProcess))
        }

    private fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach(::deleteRecursively)
        }
        SystemFileSystem.delete(path)
    }
}
