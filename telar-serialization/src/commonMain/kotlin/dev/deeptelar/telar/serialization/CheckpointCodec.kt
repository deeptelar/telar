package dev.deeptelar.telar.serialization

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CheckpointCorruptedException
import dev.deeptelar.telar.Checkpointer
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.SubgraphPosition
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Converts the checkpoints of a thread to and from a string, so a [Checkpointer] for any storage
 * only has to read and write strings:
 *
 * ```kotlin
 * class PreferencesCheckpointer<State>(
 *     private val preferences: Preferences,
 *     private val codec: CheckpointCodec<State>,
 * ) : Checkpointer<State> {
 *     // Adds the checkpoint to the history that is stored, and keeps the last 20.
 *     override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) =
 *         preferences.put(threadId, codec.append(preferences.get(threadId), checkpoint, maxHistory = 20))
 *
 *     override suspend fun load(threadId: String): Checkpoint<State>? =
 *         preferences.get(threadId)?.let { codec.decode(threadId, it) }
 *
 *     override suspend fun history(threadId: String): List<Checkpoint<State>> =
 *         preferences.get(threadId)?.let { codec.decodeHistory(threadId, it) }.orEmpty()
 *
 *     override suspend fun delete(threadId: String) = preferences.remove(threadId)
 * }
 * ```
 *
 * The format is versioned and shared by every checkpointer built on this class, including
 * `FileCheckpointer`. A checkpoint is one line of JSON, and a history is its checkpoints, one on
 * each line, oldest first. A checkpoint with the results of effects is written as version 3,
 * and one of a run that paused inside a subgraph as version 2, so that a library without that
 * feature refuses to read them instead of losing what they hold. Every other checkpoint is still
 * version 1.
 *
 * @param serializer converts the graph state to and from a string.
 */
public class CheckpointCodec<State>(
    private val serializer: StateSerializer<State>,
) {
    /** Returns [checkpoint] as a JSON string. */
    public fun encode(checkpoint: Checkpoint<State>): String =
        format.encodeToString(
            SerializedCheckpoint(
                version =
                    when {
                        checkpoint.effects.isNotEmpty() -> 3
                        checkpoint.subgraphs.isNotEmpty() -> 2
                        else -> 1
                    },
                state = serializer.serialize(checkpoint.state),
                nextNodes = checkpoint.nextNodes,
                step = checkpoint.step,
                interruptedBefore = checkpoint.interruptedBefore,
                subgraphs = checkpoint.subgraphs.mapValues { it.value.serialized() },
                effects = checkpoint.effects,
            ),
        )

    /**
     * Returns the history [stored] with [checkpoint] added, for a [Checkpointer] that keeps the
     * checkpoints of a thread in one string. [checkpoint] replaces the checkpoints with its step or
     * a later one, as [Checkpointer.save] asks, and of the result the last [maxHistory] are kept.
     *
     * The states of the stored checkpoints are not read. A line of [stored] that is not a
     * checkpoint is left out, so that a damaged history does not stop a run from being saved.
     *
     * @param stored what [append] or [encode] returned before, or `null` for a thread without checkpoints.
     * @param maxHistory how many checkpoints to keep, counted from the latest.
     */
    public fun append(stored: String?, checkpoint: Checkpoint<State>, maxHistory: Int = Int.MAX_VALUE): String {
        val earlier = lines(stored.orEmpty()).filter { line -> envelope(line)?.let { it.step < checkpoint.step } ?: false }
        return (earlier + encode(checkpoint)).takeLast(maxHistory.coerceAtLeast(1)).joinToString("\n")
    }

    /**
     * Reads the latest checkpoint of what [encode] or [append] returned.
     *
     * @param threadId the thread the data belongs to; only used in the error.
     * @throws CheckpointCorruptedException if [data] is not a checkpoint, was written in a newer
     * format, or holds a state that [serializer] cannot read.
     */
    public fun decode(threadId: String, data: String): Checkpoint<State> = checkpoint(threadId, lines(data).lastOrNull().orEmpty())

    /**
     * Reads every checkpoint of what [append] or [encode] returned, oldest first.
     *
     * @param threadId the thread the data belongs to; only used in the error.
     * @throws CheckpointCorruptedException as [decode] does, for any of the checkpoints.
     */
    public fun decodeHistory(threadId: String, data: String): List<Checkpoint<State>> = lines(data).map { checkpoint(threadId, it) }

    /** The checkpoints in [data], each still as JSON. An envelope has no line break: the state inside it is a JSON string. */
    private fun lines(data: String): List<String> = data.lineSequence().filter { it.isNotBlank() }.toList()

    private fun envelope(line: String): SerializedCheckpoint? =
        try {
            format.decodeFromString<SerializedCheckpoint>(line)
        } catch (_: SerializationException) {
            null
        }

    private fun checkpoint(threadId: String, line: String): Checkpoint<State> {
        val envelope =
            try {
                format.decodeFromString<SerializedCheckpoint>(line)
            } catch (e: SerializationException) {
                throw CheckpointCorruptedException(threadId, "the stored checkpoint is not valid", e)
            }
        if (envelope.version > SerializedCheckpoint.FORMAT_VERSION) {
            throw CheckpointCorruptedException(threadId, "format version ${envelope.version} is newer than this library supports")
        }
        val state =
            try {
                serializer.deserialize(envelope.state)
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException, as are most parsing failures.
                throw CheckpointCorruptedException(threadId, "the stored state does not match the state type", e)
            }
        return Checkpoint(
            state,
            envelope.nextNodes,
            envelope.step,
            envelope.interruptedBefore,
            envelope.subgraphs.mapValues {
                it.value.position()
            },
            envelope.effects,
        )
    }

    private companion object {
        val format =
            Json {
                ignoreUnknownKeys = true
                // Without this the version, which has a default, would be left out of the output.
                encodeDefaults = true
            }
    }
}

/** Creates a [CheckpointCodec] for the reified `@Serializable` [State] type. */
public inline fun <reified State> CheckpointCodec(json: Json = Json): CheckpointCodec<State> =
    CheckpointCodec(KotlinxStateSerializer<State>(json))

/**
 * Envelope around the serialized state. [version] allows the format to evolve.
 *
 * @property subgraphs left out when it is empty, so that a checkpoint without it reads as before.
 * @property effects the same.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SerializedCheckpoint(
    val version: Int = FORMAT_VERSION,
    val state: String,
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val subgraphs: Map<String, SerializedPosition> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val effects: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Version 2 added [subgraphs], version 3 [effects]. */
        const val FORMAT_VERSION = 3
    }
}

/** A [SubgraphPosition] in the envelope. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SerializedPosition(
    val nextNodes: List<String>,
    val step: Int = 0,
    val interruptedBefore: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val subgraphs: Map<String, SerializedPosition> = emptyMap(),
)

private fun SubgraphPosition.serialized(): SerializedPosition =
    SerializedPosition(nextNodes, step, interruptedBefore, subgraphs.mapValues { it.value.serialized() })

private fun SerializedPosition.position(): SubgraphPosition =
    SubgraphPosition(nextNodes, step, interruptedBefore, subgraphs.mapValues { it.value.position() })
