package dev.deeptelar.telar.checkpoint.file

import dev.deeptelar.telar.Store
import dev.deeptelar.telar.StoreCorruptedException
import dev.deeptelar.telar.StoreItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * A persistent [Store] that keeps its items in files inside [directory]: a folder for each part of
 * a namespace, and in it one file of JSON with the items of that namespace. The items of
 * `listOf("users", "ana")` are in `users/ana/items.json`.
 *
 * ```kotlin
 * val store = FileStore(Path(appDirectory, "memories"))
 * graph.invoke(input, GraphConfig(threadId = "plan-7", store = store.within("users", userId)))
 * ```
 *
 * Works on every target that has a file system, as `FileCheckpointer` does: JVM/Android, Apple,
 * Linux and Windows native, and JS/Wasm on Node.js. It is not available in browsers.
 *
 * A write is atomic: the file of its namespace is written to a temporary file and then moved into
 * place, so a crash in the middle of a write leaves the items as they were. A write writes the
 * whole file of its namespace again, which is quick for the few hundred items a user has. One
 * instance is safe to share between coroutines; do not point several instances or processes at the
 * same directory.
 *
 * The names of folders are made from the parts of the namespace, with every character that is not
 * a lowercase letter, a digit, `_` or `-` written as `%XX`, so that two parts never get the same
 * folder, also on a file system that ignores case.
 *
 * @param directory where the items are kept. Created if it does not exist.
 * @param fileSystem the file system to use; replaceable in tests.
 * @param clock gives [StoreItem.updatedAt]. Two writes of one store never get the same time, so
 * `list` keeps their order also with a clock that is not precise.
 */
public class FileStore(
    private val directory: Path,
    private val fileSystem: FileSystem = SystemFileSystem,
    private val clock: Clock = Clock.System,
) : Store {
    private val writeLock = Mutex()
    private var lastWrite: Instant? = null

    init {
        fileSystem.createDirectories(directory)
    }

    override suspend fun get(namespace: List<String>, key: String): StoreItem? {
        Store.checkAddress(namespace, key)
        return withContext(ioDispatcher) { read(namespace) }.firstOrNull { it.key == key }
    }

    override suspend fun put(namespace: List<String>, key: String, value: String) {
        Store.checkAddress(namespace, key)
        writeLock.withLock {
            val item = StoreItem(namespace.toList(), key, value, nextTime())
            withContext(ioDispatcher) { write(namespace, read(namespace).filter { it.key != key } + item) }
        }
    }

    override suspend fun delete(namespace: List<String>, key: String) {
        Store.checkAddress(namespace, key)
        writeLock.withLock {
            withContext(ioDispatcher) {
                val items = read(namespace)
                if (items.any { it.key == key }) write(namespace, items.filter { it.key != key })
            }
        }
    }

    override suspend fun list(namespace: List<String>, limit: Int): List<StoreItem> {
        Store.checkNamespace(namespace)
        Store.checkLimit(limit)
        return withContext(ioDispatcher) {
            buildList { collect(folderOf(namespace), namespace, this) }
                .sortedByDescending { it.updatedAt }
                .take(limit)
        }
    }

    /** Adds the items of the namespace in [folder] and of the folders inside it to [items]. */
    private fun collect(folder: Path, namespace: List<String>, items: MutableList<StoreItem>) {
        if (fileSystem.metadataOrNull(folder)?.isDirectory != true) return
        items += read(namespace)
        for (child in fileSystem.list(folder)) {
            // A folder whose name no part of a namespace has is not one of this store.
            val part = decodeFileName(child.name) ?: continue
            if (fileSystem.metadataOrNull(child)?.isDirectory == true) collect(child, namespace + part, items)
        }
    }

    /** Returns the items of [namespace], the oldest first, or none when it has no file. */
    private fun read(namespace: List<String>): List<StoreItem> {
        val file = Path(folderOf(namespace), ITEMS)
        if (!fileSystem.exists(file)) return emptyList()
        val text = fileSystem.source(file).buffered().use { it.readString() }
        return try {
            json.decodeFromString(StoredNamespace.serializer(), text).items.map {
                StoreItem(namespace.toList(), it.key, it.value, Instant.parse(it.updatedAt))
            }
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, as is a time that cannot be parsed.
            throw StoreCorruptedException(namespace, null, "the file $file is not a namespace of a FileStore", e)
        }
    }

    /** Writes [items] as the items of [namespace], or removes its file when there are none. */
    private fun write(namespace: List<String>, items: List<StoreItem>) {
        val folder = folderOf(namespace)
        val file = Path(folder, ITEMS)
        if (items.isEmpty()) {
            fileSystem.delete(file, mustExist = false)
            return
        }
        fileSystem.createDirectories(folder)
        val stored = StoredNamespace(namespace, items.map { StoredItem(it.key, it.value, it.updatedAt.toString()) })
        val temporary = Path(folder, "$ITEMS.tmp")
        fileSystem.sink(temporary).buffered().use { it.writeString(json.encodeToString(StoredNamespace.serializer(), stored)) }
        fileSystem.atomicMove(temporary, file)
    }

    private fun folderOf(namespace: List<String>): Path = namespace.fold(directory) { folder, part -> Path(folder, encodeFileName(part)) }

    /** The time of a write: now, or just after the last write when the clock has not moved since. Called under [writeLock]. */
    private fun nextTime(): Instant {
        val now = clock.now()
        val last = lastWrite
        return (if (last != null && now <= last) last + 1.nanoseconds else now).also { lastWrite = it }
    }

    private companion object {
        /** The name of the file of a namespace. An encoded part of a namespace has no `.`, so no folder has this name. */
        const val ITEMS = "items.json"

        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The file of one namespace. */
@Serializable
private class StoredNamespace(
    val namespace: List<String>,
    val items: List<StoredItem>,
)

/** An item in the file of its namespace. */
@Serializable
private class StoredItem(
    val key: String,
    val value: String,
    val updatedAt: String,
)

/** Reads back a name that [encodeFileName] wrote, or returns `null` for a name it does not write. */
internal fun decodeFileName(name: String): String? {
    val bytes = ArrayList<Byte>(name.length)
    var index = 0
    while (index < name.length) {
        val char = name[index]
        if (char == '%') {
            val hex = name.substring(index + 1, minOf(index + 3, name.length))
            if (hex.length != 2 || hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
            bytes += hex.toInt(16).toByte()
            index += 3
        } else {
            if (char !in 'a'..'z' && char !in '0'..'9' && char != '_' && char != '-') return null
            bytes += char.code.toByte()
            index++
        }
    }
    return bytes.toByteArray().decodeToString().takeIf { encodeFileName(it) == name }
}
