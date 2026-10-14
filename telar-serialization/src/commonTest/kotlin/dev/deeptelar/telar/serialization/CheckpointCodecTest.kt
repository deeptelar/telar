package dev.deeptelar.telar.serialization

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CheckpointCorruptedException
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.SubgraphPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CheckpointCodecTest {
    private val codec = CheckpointCodec<ChatState>()

    @Test
    fun `round-trips every field of a checkpoint`() {
        val checkpoint = Checkpoint(ChatState(listOf("hi"), turn = 1), listOf("a", "b"), step = 3, interruptedBefore = true)

        assertEquals(checkpoint, codec.decode("t", codec.encode(checkpoint)))
    }

    @Test
    fun `writes the format version`() {
        assertTrue(codec.encode(Checkpoint(ChatState(), emptyList())).startsWith("""{"version":1,"""))
    }

    @Test
    fun `a checkpoint without subgraphs is written as before`() {
        val checkpoint = Checkpoint(ChatState(turn = 4), listOf("a"), step = 2, interruptedBefore = true)

        assertEquals(
            """{"version":1,"state":"{\"turn\":4}","nextNodes":["a"],"step":2,"interruptedBefore":true}""",
            codec.encode(checkpoint),
        )
    }

    @Test
    fun `round-trips where a run stands inside its subgraphs`() {
        val inner = SubgraphPosition(listOf("ask"), step = 1, interruptedBefore = true)
        val outer = SubgraphPosition(listOf("inner"), step = 2, interruptedBefore = true, subgraphs = mapOf("inner" to inner))
        val checkpoint =
            Checkpoint(
                ChatState(turn = 4),
                listOf("outer"),
                step = 3,
                interruptedBefore = true,
                subgraphs =
                    mapOf(
                        "outer" to outer,
                    ),
            )

        val encoded = codec.encode(checkpoint)

        // An older library leaves out what it does not know, so it must not read this one.
        assertTrue(encoded.startsWith("""{"version":2,"""))
        assertEquals(checkpoint, codec.decode("t", encoded))
    }

    @Test
    fun `round-trips the results of effects`() {
        val checkpoint = Checkpoint(ChatState(turn = 4), listOf("pay"), step = 3, effects = mapOf("pay/charge" to """{"id":"r-1"}"""))

        val encoded = codec.encode(checkpoint)

        // An older library would leave the effects out and run them again, so it must not read this one.
        assertTrue(encoded.startsWith("""{"version":3,"""))
        assertEquals(checkpoint, codec.decode("t", encoded))
    }

    @Test
    fun `reads a checkpoint that lacks the optional fields`() {
        val decoded = codec.decode("t", """{"state":"{\"turn\":4}","nextNodes":["a"]}""")

        assertEquals(Checkpoint(ChatState(turn = 4), listOf("a"), step = 0, interruptedBefore = false), decoded)
    }

    @Test
    fun `data that is not a checkpoint is reported with the thread id`() {
        val error = assertFailsWith<CheckpointCorruptedException> { codec.decode("thread-7", "not json") }

        assertEquals("thread-7", error.threadId)
    }

    @Test
    fun `append keeps one checkpoint for each step on a line of its own`() {
        val start = Checkpoint(ChatState(), listOf("a"), step = 0)
        val first = Checkpoint(ChatState(listOf("line one\nline two"), turn = 1), listOf("b"), step = 1)
        val paused = first.copy(interruptedBefore = true)
        val second = Checkpoint(ChatState(turn = 2), emptyList(), step = 2)

        val history = listOf(start, first, paused, second).fold(null as String?) { stored, checkpoint -> codec.append(stored, checkpoint) }

        assertEquals(3, history?.lines()?.size)
        assertEquals(listOf(start, paused, second), codec.decodeHistory("t", history.orEmpty()))
        assertEquals(second, codec.decode("t", history.orEmpty()))
        assertEquals(codec.encode(start), codec.append(null, start))
    }

    @Test
    fun `append drops the steps after the one it saves and keeps the last of maxHistory`() {
        val steps = (0..4).map { Checkpoint(ChatState(turn = it), listOf("a"), step = it) }
        val all = steps.fold(null as String?) { stored, checkpoint -> codec.append(stored, checkpoint) }

        val rewound = codec.append(all, Checkpoint(ChatState(turn = 20), listOf("a"), step = 2))
        val lastTwo = steps.fold(null as String?) { stored, checkpoint -> codec.append(stored, checkpoint, maxHistory = 2) }
        val latest = codec.append(all, Checkpoint(ChatState(turn = 5), listOf("a"), step = 5), maxHistory = 1)

        assertEquals(listOf(0, 1, 20), codec.decodeHistory("t", rewound).map { it.state.turn })
        assertEquals(listOf(3, 4), codec.decodeHistory("t", lastTwo.orEmpty()).map { it.step })
        assertEquals(listOf(5), codec.decodeHistory("t", latest).map { it.step })
    }

    @Test
    fun `append reads what a version without a history stored and leaves out lines that are no checkpoints`() {
        val old = """{"version":1,"state":"{\"turn\":4}","nextNodes":["a"],"step":2,"interruptedBefore":true}"""
        val next = Checkpoint(ChatState(turn = 5), listOf("b"), step = 3)

        val history = codec.append("damaged\n$old\n\n", next)

        assertEquals(
            listOf(Checkpoint(ChatState(turn = 4), listOf("a"), step = 2, interruptedBefore = true), next),
            codec.decodeHistory("t", history),
        )
    }

    @Test
    fun `a damaged checkpoint in a history is reported when the history is read`() {
        val history = "damaged\n" + codec.encode(Checkpoint(ChatState(), listOf("a")))

        assertEquals(Checkpoint(ChatState(), listOf("a")), codec.decode("t", history))
        assertFailsWith<CheckpointCorruptedException> { codec.decodeHistory("t", history) }
        assertFailsWith<CheckpointCorruptedException> { codec.decode("t", "") }
        assertEquals(emptyList(), codec.decodeHistory("t", ""))
    }

    @Test
    fun `a newer format version is rejected`() {
        assertFailsWith<CheckpointCorruptedException> { codec.decode("t", """{"version":99,"state":"{}","nextNodes":[]}""") }
    }

    @Test
    fun `a state the serializer cannot read is reported as corrupted`() {
        val error = assertFailsWith<CheckpointCorruptedException> { codec.decode("t", """{"state":"{\"turn\":\"x\"}","nextNodes":[]}""") }

        assertIs<IllegalArgumentException>(error.cause)
    }

    @Test
    fun `works with a StateSerializer that does not use kotlinx serialization`() {
        val numbers =
            CheckpointCodec(
                object : StateSerializer<Int> {
                    override fun serialize(state: Int): String = state.toString()

                    override fun deserialize(data: String): Int = data.toInt()
                },
            )

        assertEquals(Checkpoint(42, listOf("a")), numbers.decode("t", numbers.encode(Checkpoint(42, listOf("a")))))
        assertFailsWith<CheckpointCorruptedException> { numbers.decode("t", """{"state":"forty-two","nextNodes":[]}""") }
    }
}
